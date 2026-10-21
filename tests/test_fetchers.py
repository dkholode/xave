from datetime import date

import pytest

from app.fetchers import fetch_document
from app.fetchers.auth_browser import AuthBrowserFetcher
from app.fetchers.base import Author, Document, FetchError, TextBlock
from app.fetchers.syndication import SyndicationFetcher


URL = "https://x.com/alice/status/100"


def _doc(text: str) -> Document:
    return Document(
        source_url=URL,
        retrieved_at=date.today().isoformat(),
        doc_type="tweet",
        author=Author(name="Alice", handle="alice"),
        blocks=[TextBlock(text=text)],
        root_id="100",
    )


def test_full_capture_without_login_fails_instead_of_returning_partial(monkeypatch):
    monkeypatch.setattr("app.fetchers.config.auth_available", lambda: False)
    monkeypatch.setattr(
        SyndicationFetcher,
        "fetch",
        lambda self, parsed: pytest.fail("public fetch should not run"),
    )

    with pytest.raises(FetchError, match="login"):
        fetch_document(URL, want_full=True)


def test_full_capture_uses_authenticated_fetch_before_public_fetch(monkeypatch):
    monkeypatch.setattr("app.fetchers.config.auth_available", lambda: True)
    monkeypatch.setattr(AuthBrowserFetcher, "fetch", lambda self, parsed: _doc("authenticated"))
    monkeypatch.setattr(
        SyndicationFetcher,
        "fetch",
        lambda self, parsed: pytest.fail("public fetch should not run"),
    )

    doc, warnings = fetch_document(URL, want_full=True)

    assert doc.blocks[0].text == "authenticated"
    assert warnings == []


def test_failed_authenticated_capture_falls_back_with_warning(monkeypatch):
    monkeypatch.setattr("app.fetchers.config.auth_available", lambda: True)

    def fail_auth(self, parsed):
        raise FetchError("GraphQL changed")

    monkeypatch.setattr(AuthBrowserFetcher, "fetch", fail_auth)
    monkeypatch.setattr(SyndicationFetcher, "fetch", lambda self, parsed: _doc("public fallback"))

    doc, warnings = fetch_document(URL, want_full=True)

    assert doc.blocks[0].text == "public fallback"
    assert warnings == [
        "Full-thread capture failed (GraphQL changed); returned what the free path could see."
    ]
