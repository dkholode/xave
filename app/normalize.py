"""Download remote images (avatar + photos) and inline them as data: URIs so
the rendered HTML is fully self-contained when Chromium prints it to PDF."""
from __future__ import annotations

import base64
from typing import Optional

import httpx

from . import config
from .fetchers.base import Document, ImageBlock


def embed_media(
    doc: Document,
    client: Optional[httpx.Client] = None,
    warnings: Optional[list[str]] = None,
    *,
    max_media_count: Optional[int] = None,
    max_total_bytes: Optional[int] = None,
) -> Document:
    if max_media_count is not None and max_media_count < 0:
        raise ValueError("max_media_count cannot be negative.")
    if max_total_bytes is not None and max_total_bytes < 0:
        raise ValueError("max_total_bytes cannot be negative.")
    own = client is None
    client = client or httpx.Client(
        timeout=30, headers={"User-Agent": config.USER_AGENT}, follow_redirects=True
    )
    downloaded_count = 0
    downloaded_bytes = 0
    cache: dict[str, tuple[Optional[str], int]] = {}

    def download(url: str) -> Optional[str]:
        nonlocal downloaded_count, downloaded_bytes
        if url in cache:
            return cache[url][0]
        if max_media_count is not None and downloaded_count >= max_media_count:
            if warnings is not None:
                warnings.append("Skipped media because the media-count limit was reached.")
            cache[url] = (None, 0)
            return None
        remaining = (
            None
            if max_total_bytes is None
            else max_total_bytes - downloaded_bytes
        )
        if remaining is not None and remaining <= 0:
            if warnings is not None:
                warnings.append("Skipped media because the source-media-size limit was reached.")
            cache[url] = (None, 0)
            return None
        data_uri, byte_count = _download_data_uri(client, url, remaining)
        if data_uri:
            downloaded_count += 1
            downloaded_bytes += byte_count
        cache[url] = (data_uri, byte_count)
        return data_uri

    try:
        authors = [doc.author, *(post.author for post in doc.posts)]
        for author in authors:
            if not author.avatar_url or not author.avatar_url.startswith("http"):
                continue
            avatar = download(author.avatar_url)
            if not avatar and warnings is not None:
                warnings.append("Could not archive the author avatar.")
            author.avatar_url = avatar or None
        for block in doc.blocks:
            if isinstance(block, ImageBlock) and not block.data_uri:
                block.data_uri = download(block.url)
                if not block.data_uri and warnings is not None:
                    warnings.append(f"Could not archive image: {block.url}")
    finally:
        if own:
            client.close()
    return doc


def _to_data_uri(client: httpx.Client, url: str) -> Optional[str]:
    return _download_data_uri(client, url, None)[0]


def _download_data_uri(
    client: httpx.Client, url: str, max_bytes: Optional[int]
) -> tuple[Optional[str], int]:
    try:
        with client.stream("GET", url) as resp:
            resp.raise_for_status()
            raw_length = resp.headers.get("content-length")
            if max_bytes is not None and raw_length and int(raw_length) > max_bytes:
                return None, 0
            chunks: list[bytes] = []
            byte_count = 0
            for chunk in resp.iter_bytes():
                byte_count += len(chunk)
                if max_bytes is not None and byte_count > max_bytes:
                    return None, 0
                chunks.append(chunk)
            content_type = (
                resp.headers.get("content-type", "image/jpeg").split(";")[0].strip()
            )
        encoded = base64.b64encode(b"".join(chunks)).decode("ascii")
        return f"data:{content_type};base64,{encoded}", byte_count
    except Exception:
        return None, 0  # caller warns; renderer shows an explicit unavailable-image placeholder
