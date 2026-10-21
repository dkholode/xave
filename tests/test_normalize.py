import base64

import httpx

from app.fetchers.base import Author, Document, ImageBlock, TextBlock
from app.normalize import embed_media

# 1x1 PNG
PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
)


def _doc(avatar: str) -> Document:
    return Document(
        source_url="u",
        retrieved_at="2026-01-01",
        doc_type="tweet",
        author=Author(name="A", handle="a", avatar_url=avatar),
        blocks=[ImageBlock(url="https://pbs.twimg.com/x.jpg"), TextBlock(text="hi")],
    )


def test_embed_media_sets_data_uri():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=PNG, headers={"content-type": "image/png"})

    client = httpx.Client(transport=httpx.MockTransport(handler))
    doc = _doc("https://pbs.twimg.com/a_normal.jpg")
    embed_media(doc, client=client)

    assert doc.author.avatar_url.startswith("data:image/png;base64,")
    assert doc.blocks[0].data_uri.startswith("data:image/png;base64,")


def test_embed_media_handles_failure_gracefully():
    client = httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(500)))
    doc = _doc("https://pbs.twimg.com/a_normal.jpg")
    warnings = []
    embed_media(doc, client=client, warnings=warnings)  # must not raise

    assert doc.blocks[0].data_uri is None
    assert doc.author.avatar_url is None
    assert len(warnings) == 2
    assert "avatar" in warnings[0]
    assert "image" in warnings[1]


def test_embed_media_enforces_count_and_byte_limits():
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(str(request.url))
        return httpx.Response(
            200,
            content=PNG,
            headers={"content-type": "image/png", "content-length": str(len(PNG))},
        )

    client = httpx.Client(transport=httpx.MockTransport(handler))
    doc = _doc("")
    doc.blocks.append(ImageBlock(url="https://pbs.twimg.com/y.jpg"))
    warnings = []

    embed_media(
        doc,
        client=client,
        warnings=warnings,
        max_media_count=1,
        max_total_bytes=len(PNG),
    )

    assert doc.blocks[0].data_uri
    assert doc.blocks[2].data_uri is None
    assert len(calls) == 1
    assert any("media-count limit" in warning for warning in warnings)


def test_embed_media_does_not_read_body_over_byte_limit():
    client = httpx.Client(
        transport=httpx.MockTransport(
            lambda request: httpx.Response(
                200,
                content=PNG,
                headers={"content-length": str(len(PNG))},
            )
        )
    )
    doc = _doc("")
    warnings = []

    embed_media(
        doc,
        client=client,
        warnings=warnings,
        max_total_bytes=len(PNG) - 1,
    )

    assert doc.blocks[0].data_uri is None
    assert any("Could not archive image" in warning for warning in warnings)
