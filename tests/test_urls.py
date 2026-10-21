import pytest

from app.fetchers.base import FetchError
from app.urls import parse_x_url


@pytest.mark.parametrize(
    "url,user,tid",
    [
        ("https://x.com/jack/status/20", "jack", "20"),
        ("https://twitter.com/BarackObama/status/266031293945503744", "BarackObama", "266031293945503744"),
        ("https://mobile.twitter.com/foo/status/123", "foo", "123"),
        ("https://x.com/i/web/status/999", None, "999"),
        ("x.com/bar/status/55", "bar", "55"),
        ("https://x.com/foo/status/77?s=20&t=abc", "foo", "77"),
    ],
)
def test_status_urls(url, user, tid):
    parsed = parse_x_url(url)
    assert parsed.doc_type == "status"
    assert parsed.tweet_id == tid
    assert parsed.user == user
    assert parsed.canonical_url.startswith("https://x.com/")


def test_article_url():
    parsed = parse_x_url("https://x.com/someone/article/abc-123")
    assert parsed.doc_type == "article"
    assert parsed.tweet_id == "abc-123"


def test_i_article_url_has_no_fake_user():
    parsed = parse_x_url("https://x.com/i/article/abc-123")
    assert parsed.user is None


@pytest.mark.parametrize(
    "url",
    ["https://youtube.com/watch?v=1", "https://x.com/jack", "", "https://x.com/"],
)
def test_invalid_urls_raise(url):
    with pytest.raises(FetchError):
        parse_x_url(url)
