from app.fetchers.base import (
    Author,
    DividerBlock,
    Document,
    ImageBlock,
    PlaceholderBlock,
    PostMetadata,
    TextBlock,
)
from app.render import build_footer, render_document


def make_doc() -> Document:
    return Document(
        source_url="https://x.com/a/status/1",
        retrieved_at="2026-06-22",
        doc_type="thread",
        author=Author(name="Alice", handle="alice", avatar_url="data:image/png;base64,AAAA"),
        blocks=[
            TextBlock(text="Hello @bob check https://example.com #cool"),
            ImageBlock(url="https://x/x.jpg", data_uri="data:image/png;base64,BBBB", alt="a cat"),
            PlaceholderBlock(media_kind="video", label="Video", link="https://x/v.mp4"),
            DividerBlock(),
            TextBlock(text="second"),
        ],
        root_id="1",
    )


def test_render_contains_expected_pieces():
    html = render_document(make_doc())
    assert "Alice" in html and "@alice" in html
    assert "data:image/png;base64,BBBB" in html  # embedded image, not remote
    assert 'href="https://example.com"' in html  # url linkified
    assert 'href="https://x.com/bob"' in html  # mention linkified
    assert "#cool" in html  # hashtag preserved
    assert "Video" in html and "v.mp4" in html  # placeholder + link
    assert "<hr" in html  # thread divider
    assert "a cat" in html  # alt text / caption


def test_render_escapes_html():
    doc = make_doc()
    doc.blocks = [TextBlock(text="<script>alert(1)</script>")]
    html = render_document(doc)
    assert "<script>alert" not in html
    assert "&lt;script&gt;" in html


def test_linkification_keeps_mentions_fragments_and_queries_inside_urls():
    from bs4 import BeautifulSoup
    from app.render import _linkify

    text = 'Read https://example.org/@alice#notes?x=1&y=2 then @bob #topic <script>'
    soup = BeautifulSoup(str(_linkify(text)), "html.parser")
    assert [link["href"] for link in soup.find_all("a")] == [
        "https://example.org/@alice#notes?x=1&y=2", "https://x.com/bob",
        "https://x.com/hashtag/topic",
    ]
    assert soup.get_text() == text
    assert soup.find("script") is None


def test_footer_has_date_and_page_counter():
    footer = build_footer(make_doc())
    assert "2026-06-22" in footer
    assert "x.com/a/status/1" in footer
    assert "pageNumber" in footer and "totalPages" in footer


def test_unembedded_image_is_reported_instead_of_loaded_remotely():
    doc = make_doc()
    doc.blocks = [ImageBlock(url="https://pbs.twimg.com/media/missing.jpg", alt="Missing")]

    html = render_document(doc)

    assert '<img src="https://pbs.twimg.com/media/missing.jpg"' not in html
    assert "Image unavailable" in html
    assert 'href="https://pbs.twimg.com/media/missing.jpg"' in html


def test_structured_posts_include_required_x_attribution_and_metadata():
    doc = make_doc()
    doc.posts = [
        PostMetadata(
            post_id="1",
            timestamp="2026-06-22T12:00:00Z",
            permalink="https://x.com/alice/status/1",
            author=doc.author,
            edit_history_post_ids=["0", "1"],
        ),
        PostMetadata(
            post_id="2",
            timestamp="2026-06-22T12:01:00Z",
            permalink="https://x.com/alice/status/2",
            author=doc.author,
        ),
    ]

    html = render_document(doc)

    assert html.count('class="x-post"') == 2
    assert html.count('class="x-logo"') == 2
    assert "https://x.com/alice" in html
    assert "@alice" in html
    assert "2026-06-22T12:00:00Z" in html
    assert "View on X" in html
    assert "Edited post" in html
    assert "post text is otherwise unmodified" in html
