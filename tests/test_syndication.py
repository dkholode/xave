import json
from pathlib import Path

import httpx
import pytest

from app.fetchers import syndication as syn
from app.fetchers.base import FetchError
from app.urls import parse_x_url

FIX = Path(__file__).parent / "fixtures"


def load(name: str) -> dict:
    return json.loads((FIX / name).read_text())


def test_token_known_values():
    # Values captured from a real browser session against the live endpoint.
    assert syn.get_token("20") == "6dq1a2xwd93"
    assert syn.get_token("266031293945503744") == "n7rfhxzwhkd"
    assert syn.get_token("440322224407314432") == "12fb9qdo78ad"


def test_document_from_photo_fixture():
    tweet = load("tweet_with_photo.json")
    parsed = parse_x_url("https://x.com/BarackObama/status/266031293945503744")
    doc = syn.document_from_chain([tweet], parsed)

    assert doc.doc_type == "tweet"
    assert doc.author.name == "Barack Obama"
    assert doc.author.handle == "BarackObama"
    assert doc.author.avatar_url and "_400x400" in doc.author.avatar_url

    kinds = [b.kind for b in doc.blocks]
    assert "text" in kinds and "image" in kinds

    text = next(b for b in doc.blocks if b.kind == "text").text
    assert "Four more years" in text
    assert "t.co" not in text  # trailing media URL stripped

    image = next(b for b in doc.blocks if b.kind == "image")
    assert image.url.startswith("https://pbs.twimg.com/")


def test_fetch_raw_success():
    tweet = load("tweet_text_only.json")

    def handler(request: httpx.Request) -> httpx.Response:
        assert "tweet-result" in str(request.url)
        assert "token=" in str(request.url)
        return httpx.Response(200, json=tweet)

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        data = syn.fetch_raw(client, "20")
    assert data["user"]["screen_name"] == "jack"


def test_fetch_raw_404_raises():
    with httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(404, text="x"))) as client:
        with pytest.raises(FetchError):
            syn.fetch_raw(client, "123")


def test_fetch_raw_tombstone_raises():
    tomb = load("tweet_tombstone.json")
    with httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(200, json=tomb))) as client:
        with pytest.raises(FetchError):
            syn.fetch_raw(client, "123")
