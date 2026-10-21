from pathlib import Path

from fastapi.testclient import TestClient

from app.fetchers.base import Author, Document, TextBlock
from app.main import app


def _doc() -> Document:
    return Document(
        source_url="https://x.com/alice/status/100",
        retrieved_at="2026-07-27",
        doc_type="tweet",
        author=Author(name="Alice", handle="alice"),
        blocks=[TextBlock(text="Hello")],
        root_id="100",
    )


def test_unexpected_error_is_generic(monkeypatch):
    def fail(url, want_full=False):
        raise RuntimeError("/private/secret/path")

    monkeypatch.setattr("app.main.fetch_document", fail)
    client = TestClient(app)

    response = client.post("/api/convert", data={"url": "https://x.com/a/status/1"})

    assert response.status_code == 500
    assert response.json() == {"error": "Unexpected conversion error. Check the server log for details."}
    assert "secret" not in response.text


def test_convert_includes_media_warning_without_remote_image_fetch(monkeypatch):
    doc = _doc()
    warnings = []

    monkeypatch.setattr("app.main.fetch_document", lambda url, want_full=False: (doc, warnings))

    def warn_about_media(document, warnings=None):
        warnings.append("Could not archive image: https://example.com/image.jpg")
        return document

    monkeypatch.setattr("app.main.embed_media", warn_about_media)
    monkeypatch.setattr("app.main.html_to_pdf", lambda html, footer_html=None: b"%PDF-test")
    client = TestClient(app)

    response = client.post("/api/convert", data={"url": doc.source_url})

    assert response.status_code == 200
    assert response.content == b"%PDF-test"
    assert "Could not archive image" in response.headers["X-Xave-Warnings"]


def test_favicon_exists():
    response = TestClient(app).get("/favicon.ico")

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("image/svg+xml")


def test_homepage_serves_converter_and_has_no_commercial_routes():
    client = TestClient(app)

    response = client.get("/")

    assert response.status_code == 200
    assert "Download PDF" in response.text
    assert "/static/app.js" in response.text
    assert set(client.get("/openapi.json").json()["paths"]) == {"/", "/api/convert"}
    assert client.get("/admin").status_code == 404


def test_client_does_not_insert_server_messages_with_inner_html():
    static = Path(__file__).parents[1] / "app" / "static"
    javascript = (static / "app.js").read_text()

    assert "innerHTML" not in javascript
