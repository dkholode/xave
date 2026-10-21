"""Free path: X's public syndication endpoint (no login, no API key).

`cdn.syndication.twimg.com/tweet-result` returns a single tweet's text, media,
author, immediate parent and quoted tweet. It does NOT return downstream
replies, so this fetcher can reconstruct a thread only by walking *up* from a
later tweet via its parent chain (same author). Downward thread reconstruction
and Articles are handled by the auth-browser fetcher.
"""
from __future__ import annotations

import math
import re
from datetime import date
from typing import Optional

import httpx

from .. import config
from .base import (
    Author,
    DividerBlock,
    Document,
    FetchError,
    ImageBlock,
    PlaceholderBlock,
    TextBlock,
)

SYNDICATION_URL = "https://cdn.syndication.twimg.com/tweet-result"
_CHARS = "0123456789abcdefghijklmnopqrstuvwxyz"
_MAX_CHAIN = 50  # safety bound on parent-walking


# --- token -----------------------------------------------------------------
# X expects a `token` derived from the tweet id via JS `Number.toString(36)`.
# We port V8's DoubleToRadixCString so the value matches the browser exactly.
def _double_to_radix(value: float, radix: int) -> str:
    if value == 0:
        return "0"
    negative = value < 0
    if negative:
        value = -value

    integer = math.floor(value)
    fraction = value - integer
    delta = max(0.5 * (math.nextafter(value, math.inf) - value), 5e-324)

    frac = ""
    if fraction >= delta:
        frac = "."
        while True:
            fraction *= radix
            delta *= radix
            digit = int(math.floor(fraction))
            fraction -= digit
            if fraction > 0.5 or (fraction == 0.5 and (digit & 1) == 1):
                if fraction + delta > 1:
                    frac += _CHARS[digit]
                    chars = list(frac)
                    k = len(chars) - 1
                    while True:
                        if chars[k] == ".":
                            k -= 1
                            continue
                        idx = _CHARS.index(chars[k]) + 1
                        if idx == radix:
                            chars[k] = "0"
                            k -= 1
                            if k < 0:
                                integer += 1
                                break
                        else:
                            chars[k] = _CHARS[idx]
                            break
                    frac = "".join(chars)
                    break
            frac += _CHARS[digit]
            if fraction < delta:
                break

    if integer == 0:
        int_part = "0"
    else:
        int_part = ""
        i = int(integer)
        while i > 0:
            int_part = _CHARS[i % radix] + int_part
            i //= radix

    result = int_part + frac
    return "-" + result if negative else result


def get_token(tweet_id: str) -> str:
    raw = _double_to_radix((int(tweet_id) / 1e15) * math.pi, 36)
    return re.sub(r"(0+|\.)", "", raw)


# --- raw fetch -------------------------------------------------------------
def fetch_raw(client: httpx.Client, tweet_id: str) -> dict:
    """Fetch one tweet's syndication JSON. Public for use as a test seam."""
    resp = client.get(
        SYNDICATION_URL,
        params={"id": tweet_id, "token": get_token(tweet_id), "lang": "en"},
    )
    if resp.status_code == 404:
        raise FetchError(f"Tweet {tweet_id} was not found, is private, or was deleted.")
    resp.raise_for_status()
    data = resp.json()
    if data.get("__typename") == "TweetTombstone" or "tombstone" in data:
        raise FetchError("This tweet is unavailable (deleted, protected, or age-restricted).")
    if "user" not in data:
        raise FetchError("X returned an unexpected response for that tweet.")
    return data


# --- mapping helpers -------------------------------------------------------
def _author(user: dict) -> Author:
    avatar = user.get("profile_image_url_https")
    if avatar:  # bump 48px thumbnail to a crisper avatar
        avatar = avatar.replace("_normal.", "_400x400.")
    return Author(name=user.get("name", ""), handle=user.get("screen_name", ""), avatar_url=avatar)


def _clean_text(tweet: dict) -> str:
    text = tweet.get("text", "") or ""
    entities = tweet.get("entities", {}) or {}
    for media in entities.get("media", []) or []:
        if media.get("url"):
            text = text.replace(media["url"], "")
    for link in entities.get("urls", []) or []:
        if link.get("url"):
            text = text.replace(link["url"], link.get("display_url") or link.get("expanded_url") or "")
    return text.strip()


def _best_video_url(media: dict) -> Optional[str]:
    variants = [
        v
        for v in (media.get("video_info", {}) or {}).get("variants", [])
        if v.get("content_type") == "video/mp4" and v.get("url")
    ]
    if not variants:
        return None
    variants.sort(key=lambda v: v.get("bitrate", 0), reverse=True)
    return variants[0]["url"]


def _media_blocks(tweet: dict, permalink: str) -> list:
    blocks: list = []
    details = tweet.get("mediaDetails") or []
    for media in details:
        mtype = media.get("type")
        if mtype == "photo":
            blocks.append(ImageBlock(url=media.get("media_url_https", ""), alt=media.get("ext_alt_text") or ""))
        elif mtype in ("video", "animated_gif"):
            label = "GIF" if mtype == "animated_gif" else "Video"
            link = _best_video_url(media) or media.get("expanded_url") or permalink
            blocks.append(PlaceholderBlock(media_kind="video", label=label, link=link))
    if not details:  # fall back to the simplified photos[] list
        for photo in tweet.get("photos", []) or []:
            if photo.get("url"):
                blocks.append(ImageBlock(url=photo["url"], alt=""))
    return blocks


def _quote_block(quoted: dict) -> PlaceholderBlock:
    handle = (quoted.get("user", {}) or {}).get("screen_name", "")
    snippet = _clean_text(quoted)[:140]
    link = f"https://x.com/{handle}/status/{quoted.get('id_str', '')}"
    label = f"Quoted @{handle}: {snippet}" if snippet else f"Quoted @{handle}"
    return PlaceholderBlock(media_kind="quote", label=label, link=link)


def _card_block(card: dict) -> Optional[PlaceholderBlock]:
    try:
        raw = card.get("binding_values")
        values = {b["key"]: b["value"] for b in raw} if isinstance(raw, list) else (raw or {})
        title = (values.get("title", {}) or {}).get("string_value")
        url = card.get("url") or (values.get("card_url", {}) or {}).get("string_value")
        if title:
            return PlaceholderBlock(media_kind="card", label=f"Link: {title}", link=url)
    except Exception:  # pragma: no cover - cards are best-effort
        pass
    return None


def tweet_to_blocks(tweet: dict) -> list:
    handle = (tweet.get("user", {}) or {}).get("screen_name", "")
    permalink = f"https://x.com/{handle}/status/{tweet.get('id_str', '')}"
    blocks: list = []
    text = _clean_text(tweet)
    if text:
        blocks.append(TextBlock(text=text))
    blocks.extend(_media_blocks(tweet, permalink))
    if tweet.get("quoted_tweet"):
        blocks.append(_quote_block(tweet["quoted_tweet"]))
    if tweet.get("card"):
        card_block = _card_block(tweet["card"])
        if card_block:
            blocks.append(card_block)
    return blocks


def document_from_chain(chain: list[dict], parsed) -> Document:
    """Build a Document from an ordered (root-first) list of tweet dicts."""
    root = chain[0]
    blocks: list = []
    for index, tweet in enumerate(chain):
        if index > 0:
            blocks.append(DividerBlock())
        blocks.extend(tweet_to_blocks(tweet))
    return Document(
        source_url=parsed.canonical_url,
        retrieved_at=date.today().isoformat(),
        doc_type="thread" if len(chain) > 1 else "tweet",
        author=_author(root.get("user", {}) or {}),
        blocks=blocks,
        root_id=root.get("id_str", parsed.tweet_id),
    )


class SyndicationFetcher:
    def __init__(self, client: Optional[httpx.Client] = None):
        self._client = client

    def fetch(self, parsed) -> Document:
        client = self._client or httpx.Client(
            timeout=30, headers={"User-Agent": config.USER_AGENT}, follow_redirects=True
        )
        try:
            chain = self._collect_chain(client, parsed.tweet_id)
        finally:
            if self._client is None:
                client.close()
        return document_from_chain(chain, parsed)

    def _collect_chain(self, client: httpx.Client, tweet_id: str) -> list[dict]:
        """Fetch the tweet, then walk up same-author parents. Returns root-first."""
        items: list[dict] = []
        seen: set[str] = set()
        current: Optional[str] = tweet_id
        while current and current not in seen and len(items) < _MAX_CHAIN:
            seen.add(current)
            tweet = fetch_raw(client, current)
            items.append(tweet)
            parent = tweet.get("parent")
            same_author = (
                parent
                and (parent.get("user", {}) or {}).get("screen_name")
                == (tweet.get("user", {}) or {}).get("screen_name")
            )
            current = parent.get("id_str") if same_author else None
        items.reverse()
        return items
