from fastapi.responses import Response

from app.fetchers.base import Author, Document
from app.main import build_content_disposition, make_filename


def _article(title: str) -> Document:
    return Document(
        source_url="https://x.com/i/article/1",
        retrieved_at="2026-07-27",
        doc_type="article",
        author=Author(name="Alice", handle="alice"),
        title=title,
        root_id="1",
    )


def test_unicode_article_filename_produces_ascii_safe_header():
    filename = make_filename(_article("日本語の記事"))

    header = build_content_disposition(filename)
    response = Response(content=b"pdf", headers={"Content-Disposition": header})

    assert "filename*=UTF-8''" in response.headers["content-disposition"]
    assert "%E6%97%A5" in response.headers["content-disposition"]


def test_ascii_filename_remains_readable():
    header = build_content_disposition("alice-thread-100.pdf")

    assert 'filename="alice-thread-100.pdf"' in header
