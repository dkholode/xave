"""Fetcher orchestration: pick the cheapest source that satisfies the request.

Free syndication handles ordinary tweets and parent-walkable threads. The
auth-browser fetcher (which uses your persisted X login) handles Articles and
requested full threads. A full capture fails clearly when no login is
configured; an authenticated parsing failure may fall back to the public
result with an explicit warning.
"""
from __future__ import annotations

from .. import config
from ..urls import parse_x_url
from .base import Document, FetchError
from .syndication import SyndicationFetcher


def fetch_document(url: str, *, want_full: bool = False) -> tuple[Document, list[str]]:
    parsed = parse_x_url(url)
    warnings: list[str] = []

    if parsed.doc_type == "article":
        if not config.auth_available():
            raise FetchError(
                "This looks like an X Article. Articles require a one-time X login. "
                "Run `python -m app.auth.login`, then try again."
            )
        from .auth_browser import AuthBrowserFetcher

        return AuthBrowserFetcher().fetch(parsed), warnings

    if want_full:
        if not config.auth_available():
            raise FetchError(
                "Full-thread capture requires a one-time X login. "
                "Run `python -m app.auth.login`, then try again."
            )

        try:
            from .auth_browser import AuthBrowserFetcher

            return AuthBrowserFetcher().fetch(parsed), warnings
        except FetchError as exc:
            warnings.append(f"Full-thread capture failed ({exc}); returned what the free path could see.")
        except Exception as exc:  # pragma: no cover - defensive
            warnings.append(f"Full-thread capture errored ({exc}); returned the free-path result.")

    return SyndicationFetcher().fetch(parsed), warnings
