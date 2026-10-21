from app.fetchers.auth_browser import (
    AuthBrowserFetcher,
    _article_from_dom,
    _article_from_graphql,
    _linked_article_candidate,
)
from app.urls import parse_x_url


def _article(article_id: str, title: str) -> dict:
    return {
        "rest_id": article_id,
        "title": title,
        "author": {
            "name": "Alice Example",
            "screen_name": "alice",
            "profile_image_url_https": "https://pbs.twimg.com/profile_images/alice_normal.jpg",
        },
        "content_state": {
            "blocks": [
                {"type": "header-two", "text": "Introduction", "entityRanges": []},
                {"type": "unstyled", "text": "Article body", "entityRanges": []},
                {
                    "type": "atomic",
                    "text": " ",
                    "entityRanges": [{"key": 0, "offset": 0, "length": 1}],
                },
            ],
            "entityMap": {
                "0": {
                    "type": "IMAGE",
                    "data": {
                        "url": "https://pbs.twimg.com/media/article-image.jpg",
                        "alt": "Diagram",
                    },
                }
            },
        },
    }


def test_graphql_article_selects_requested_id_and_preserves_author_and_image():
    captured = [
        {
            "data": {
                "recommendation": _article("wrong", "Wrong article"),
                "requested": _article("abc-123", "Correct article"),
            }
        }
    ]

    title, author, blocks = _article_from_graphql(captured, "abc-123")

    assert title == "Correct article"
    assert author.name == "Alice Example"
    assert author.handle == "alice"
    assert [block.kind for block in blocks] == ["heading", "text", "image"]
    assert blocks[-1].url == "https://pbs.twimg.com/media/article-image.jpg"
    assert blocks[-1].alt == "Diagram"


def test_graphql_article_accepts_current_x_list_entity_map():
    article = _article("abc-123", "Current-schema article")
    article["content_state"]["blocks"][-1]["entityRanges"][0]["key"] = 7
    image_entity = article["content_state"]["entityMap"]["0"]
    article["content_state"]["entityMap"] = [
        {
            "key": 7,
            "value": {
                "type": image_entity["type"],
                "mutability": "IMMUTABLE",
                "data": image_entity["data"],
            },
        }
    ]

    title, author, blocks = _article_from_graphql(
        [{"article": article}], article_id="abc-123"
    )

    assert title == "Current-schema article"
    assert author.handle == "alice"
    assert [block.kind for block in blocks] == ["heading", "text", "image"]
    assert blocks[-1].url == "https://pbs.twimg.com/media/article-image.jpg"


def test_graphql_article_refuses_ambiguous_candidates_without_requested_id():
    captured = [{"items": [_article("one", "One"), _article("two", "Two")]}]

    assert _article_from_graphql(captured, "missing") == (None, None, [])


def test_graphql_article_refuses_single_candidate_with_wrong_id():
    captured = [{"article": _article("wrong", "Wrong article")}]

    assert _article_from_graphql(captured, "requested") == (None, None, [])


def test_dom_article_uses_scoped_container_and_preserves_author_and_images():
    html = """
      <html>
        <head><meta property="og:title" content="DOM article"></head>
        <body>
          <p>Navigation text that must not be captured</p>
          <article data-testid="article">
            <div data-testid="User-Name"><a href="/alice">Alice Example @alice</a></div>
            <h2>Heading</h2>
            <p>Body paragraph</p>
            <img src="https://pbs.twimg.com/media/dom-image.jpg" alt="Chart">
          </article>
          <p>Footer text that must not be captured</p>
        </body>
      </html>
    """

    title, author, blocks = _article_from_dom(html, None, None)

    assert title == "DOM article"
    assert author.handle == "alice"
    assert [getattr(block, "text", None) for block in blocks[:2]] == [
        "Heading",
        "Body paragraph",
    ]
    assert blocks[-1].kind == "image"
    assert blocks[-1].url == "https://pbs.twimg.com/media/dom-image.jpg"


def test_i_article_url_does_not_create_fake_i_author():
    parsed = parse_x_url("https://x.com/i/article/abc-123")

    assert parsed.user is None


def test_built_article_uses_extracted_author_for_i_article_url():
    parsed = parse_x_url("https://x.com/i/article/abc-123")
    captured = [{"data": {"article": _article("abc-123", "Correct article")}}]

    doc = AuthBrowserFetcher()._build_article(parsed, captured, "")

    assert doc.author.handle == "alice"
    assert doc.author.name == "Alice Example"


def test_status_share_url_selects_its_nested_article_instead_of_tweet_preview():
    status_id = "2037083922106875995"
    nested_article = _article("2037082334667624448", "Nested X Article")
    captured = [
        {
            "data": {
                "tweetResult": {
                    "result": {
                        "rest_id": status_id,
                        "article": {
                            "article_results": {
                                "result": nested_article,
                            }
                        },
                    }
                }
            }
        }
    ]

    assert _linked_article_candidate(captured, status_id) is nested_article
    title, author, blocks = _article_from_graphql(captured, status_id=status_id)

    assert title == "Nested X Article"
    assert [block.kind for block in blocks] == ["heading", "text", "image"]


def test_status_share_url_ignores_same_id_nodes_with_non_object_article_fields():
    status_id = "2037083922106875995"
    nested_article = _article("2037082334667624448", "Nested X Article")
    captured = [
        {"rest_id": status_id, "article": []},
        {
            "rest_id": status_id,
            "article": {"article_results": {"result": nested_article}},
        },
    ]

    assert _linked_article_candidate(captured, status_id) is nested_article


def test_status_share_url_builds_article_document():
    status_id = "2037083922106875995"
    parsed = parse_x_url(f"https://x.com/alice/status/{status_id}")
    captured = [
        {
            "tweet": {
                "rest_id": status_id,
                "article": {
                    "article_results": {
                        "result": _article("2037082334667624448", "Nested X Article"),
                    }
                },
            }
        }
    ]

    doc = AuthBrowserFetcher()._build_article(parsed, captured, "")

    assert doc.doc_type == "article"
    assert doc.title == "Nested X Article"
