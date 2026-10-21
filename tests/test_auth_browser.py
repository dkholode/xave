"""Unit tests for the fragile GraphQL parsing, using synthetic TweetDetail-shaped
payloads (no live browser/login required)."""
import pytest

from app.fetchers.auth_browser import (
    AuthBrowserFetcher,
    _author_from_node,
    _collect_tweets,
    _gql_tweet_to_blocks,
    _scroll_until_stable,
    _self_reply_chain,
    _user_id,
)
from app.fetchers.base import FetchError
from app.urls import parse_x_url


def _tw(tid, text, parent=None, uid="1", handle="alice", name="Alice"):
    return {
        "rest_id": tid,
        "core": {
            "user_results": {
                "result": {
                    "rest_id": uid,
                    "legacy": {
                        "screen_name": handle,
                        "name": name,
                        "profile_image_url_https": "https://pbs.twimg.com/p_normal.jpg",
                    },
                }
            }
        },
        "legacy": {
            "full_text": text,
            "id_str": tid,
            "user_id_str": uid,
            "in_reply_to_status_id_str": parent,
            "entities": {},
            "extended_entities": {},
        },
    }


def _payload():
    tweets = [
        _tw("100", "First"),
        _tw("101", "Second", "100"),
        _tw("102", "Third", "101"),
        _tw("200", "bob reply", "100", uid="2", handle="bob", name="Bob"),
    ]
    entries = [{"content": {"itemContent": {"tweet_results": {"result": t}}}} for t in tweets]
    return {"data": {"thread": {"instructions": [{"entries": entries}]}}}


def test_collect_finds_all_tweets_deeply():
    tweets = {}
    _collect_tweets(_payload(), tweets)
    assert set(tweets) == {"100", "101", "102", "200"}


def test_self_reply_chain_excludes_other_authors():
    tweets = {}
    _collect_tweets(_payload(), tweets)
    chain = _self_reply_chain(tweets, "100", _user_id(tweets["100"]))
    assert [c["rest_id"] for c in chain] == ["100", "101", "102"]
    assert _author_from_node(chain[0]).handle == "alice"
    assert "_400x400" in _author_from_node(chain[0]).avatar_url


def test_gql_blocks_replace_url_entities():
    tweet = _tw("1", "hello https://t.co/x")
    tweet["legacy"]["entities"] = {
        "urls": [{"url": "https://t.co/x", "display_url": "example.com", "expanded_url": "https://example.com"}]
    }
    blocks = _gql_tweet_to_blocks(tweet)
    assert blocks[0].text == "hello example.com"


def test_self_reply_chain_does_not_include_unrelated_same_author_tweets():
    tweets = {
        "100": _tw("100", "Requested"),
        "999": _tw("999", "Unrelated"),
    }

    chain = _self_reply_chain(tweets, "100", "1")

    assert [tweet["rest_id"] for tweet in chain] == ["100"]


def test_self_reply_chain_fails_when_focal_tweet_is_missing():
    tweets = {"999": _tw("999", "Different tweet")}

    assert _self_reply_chain(tweets, "100", "1") == []


def test_self_reply_chain_keeps_all_connected_branches():
    tweets = {
        "100": _tw("100", "Root"),
        "101": _tw("101", "First branch", "100"),
        "102": _tw("102", "Second branch", "100"),
        "103": _tw("103", "First branch continuation", "101"),
        "999": _tw("999", "Unrelated"),
    }

    chain = _self_reply_chain(tweets, "100", "1")

    assert [tweet["rest_id"] for tweet in chain] == ["100", "101", "103", "102"]


def test_thread_uses_scoped_dom_fallback_when_graphql_is_missing():
    parsed = parse_x_url("https://x.com/alice/status/101")
    html = """
      <main>
        <article data-testid="tweet">
          <div data-testid="User-Name">Alice @alice</div>
          <a href="/alice/status/100">root</a>
          <div data-testid="tweetText">Root post</div>
        </article>
        <article data-testid="tweet">
          <div data-testid="User-Name">Alice @alice</div>
          <a href="/alice/status/101">reply</a>
          <div data-testid="tweetText">Requested reply</div>
        </article>
        <article data-testid="tweet">
          <div data-testid="User-Name">Bob @bob</div>
          <a href="/bob/status/200">other author</a>
          <div data-testid="tweetText">Interruption</div>
        </article>
        <article data-testid="tweet">
          <div data-testid="User-Name">Alice @alice</div>
          <a href="/alice/status/999">unrelated</a>
          <div data-testid="tweetText">Unrelated recommendation</div>
        </article>
      </main>
    """

    doc = AuthBrowserFetcher()._build_thread(parsed, [], html)

    assert doc.root_id == "100"
    assert doc.author.handle == "alice"
    assert [block.text for block in doc.blocks if block.kind == "text"] == [
        "Root post",
        "Requested reply",
    ]


class _FakeMouse:
    def __init__(self):
        self.scrolls = 0

    def wheel(self, x, y):
        self.scrolls += 1


class _FakePage:
    def __init__(self, heights):
        self.heights = iter(heights)
        self.mouse = _FakeMouse()

    def evaluate(self, script):
        return next(self.heights)

    def wait_for_timeout(self, milliseconds):
        return None


def test_scroll_until_stable_waits_for_repeated_stability():
    page = _FakePage([100, 200, 200, 200])

    _scroll_until_stable(page, [], max_scrolls=6, stable_rounds=2)

    assert page.mouse.scrolls == 3


def test_scroll_until_stable_fails_instead_of_silently_truncating():
    page = _FakePage([100, 200, 300, 400])

    with pytest.raises(FetchError, match="kept loading"):
        _scroll_until_stable(page, [], max_scrolls=3, stable_rounds=2)


def test_thread_scroll_stops_when_connected_chain_is_stable_despite_page_growth():
    captured = [_payload()]
    page = _FakePage([100, 200, 300, 400])

    _scroll_until_stable(
        page,
        captured,
        focal_id="100",
        max_scrolls=4,
        stable_rounds=2,
    )

    assert page.mouse.scrolls == 2
