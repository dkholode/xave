"""Parse and classify X/Twitter URLs."""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Literal, Optional
from urllib.parse import urlparse

from .fetchers.base import FetchError

_HOSTS = {
    "twitter.com",
    "www.twitter.com",
    "mobile.twitter.com",
    "x.com",
    "www.x.com",
    "mobile.x.com",
    "nitter.net",
}

# /<user>/status/<id>  and  /i/web/status/<id>  and  /statuses/<id>
_STATUS_RE = re.compile(r"/status(?:es)?/(\d+)")
# Long-form Articles. Exact path can vary; match an `article` segment with a
# trailing id-ish token. The auth-browser fetcher navigates the original URL,
# so precise parsing here matters less than correct classification.
_ARTICLE_RE = re.compile(r"/article/([A-Za-z0-9_-]+)")


@dataclass
class ParsedUrl:
    doc_type: Literal["status", "article"]
    user: Optional[str]
    tweet_id: str  # status id, or article id for articles
    canonical_url: str
    original_url: str


def parse_x_url(url: str) -> ParsedUrl:
    raw = (url or "").strip()
    if not raw:
        raise FetchError("Please provide an X/Twitter URL.")
    if "://" not in raw:
        raw = "https://" + raw

    parsed = urlparse(raw)
    host = (parsed.netloc or "").lower().split(":")[0]
    if host not in _HOSTS:
        raise FetchError(f"'{host or raw}' is not an x.com / twitter.com URL.")

    path = parsed.path.rstrip("/")

    art = _ARTICLE_RE.search(path)
    if art:
        user = _first_segment(path)
        if user == "i":
            user = None
        return ParsedUrl(
            doc_type="article",
            user=user,
            tweet_id=art.group(1),
            canonical_url=f"https://x.com{path}",
            original_url=raw,
        )

    status = _STATUS_RE.search(path)
    if status:
        seg = _first_segment(path)
        user = None if seg in {"i", "statuses", "status"} else seg
        return ParsedUrl(
            doc_type="status",
            user=user,
            tweet_id=status.group(1),
            canonical_url=f"https://x.com{path}",
            original_url=raw,
        )

    raise FetchError(
        "Could not find a tweet/thread or Article in that URL. Paste a link like "
        "https://x.com/<user>/status/<id>."
    )


def _first_segment(path: str) -> Optional[str]:
    parts = [p for p in path.split("/") if p]
    return parts[0] if parts else None
