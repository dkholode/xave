"""Shared document model and the Fetcher contract.

A `Document` is the source-agnostic representation every fetcher produces; the
renderer/PDF stages only ever see this, which is what lets us swap the
underlying data source (syndication, auth-browser, future scraper API).
"""
from __future__ import annotations

from typing import Annotated, Literal, Optional, Protocol, Union

from pydantic import BaseModel, Field


class FetchError(Exception):
    """Raised for an expected, user-presentable fetch failure."""

    def __init__(
        self,
        message: str,
        *,
        code: str = "fetch_failed",
        source_status: Optional[
            Literal[
                "available",
                "deleted",
                "protected",
                "age_restricted",
                "restricted",
                "not_found",
                "unsupported",
            ]
        ] = None,
    ):
        super().__init__(message)
        self.code = code
        self.source_status = source_status

    @property
    def classification(self) -> Optional[str]:
        """Stable alias used by API error serializers."""
        return self.source_status


class Author(BaseModel):
    name: str
    handle: str  # without the leading @
    avatar_url: Optional[str] = None  # remote URL, or a data: URI after embedding


class TextBlock(BaseModel):
    kind: Literal["text"] = "text"
    text: str


class HeadingBlock(BaseModel):
    kind: Literal["heading"] = "heading"
    level: int = 2
    text: str


class ImageBlock(BaseModel):
    kind: Literal["image"] = "image"
    url: str
    data_uri: Optional[str] = None  # filled by normalize.embed_media
    alt: str = ""


class PlaceholderBlock(BaseModel):
    kind: Literal["placeholder"] = "placeholder"
    media_kind: Literal["video", "quote", "card"]
    label: str
    link: Optional[str] = None


class DividerBlock(BaseModel):
    """Visual separator between tweets in a thread."""

    kind: Literal["divider"] = "divider"


Block = Annotated[
    Union[TextBlock, HeadingBlock, ImageBlock, PlaceholderBlock, DividerBlock],
    Field(discriminator="kind"),
]


class MediaVariant(BaseModel):
    """One downloadable rendition reported for video or GIF media."""

    url: str
    content_type: Optional[str] = None
    bit_rate: Optional[int] = None


class MediaMetadata(BaseModel):
    """Source metadata for one media resource attached to a post."""

    media_key: str
    media_type: Literal["photo", "video", "animated_gif", "unknown"] = "unknown"
    url: Optional[str] = None
    preview_image_url: Optional[str] = None
    alt_text: Optional[str] = None
    variants: list[MediaVariant] = Field(default_factory=list)


class QuotedPostMetadata(BaseModel):
    """Attribution metadata for a quoted post."""

    post_id: str
    timestamp: Optional[str] = None
    permalink: str
    author: Author
    text: Optional[str] = None


class PostMetadata(BaseModel):
    """Ordered, source-verifiable metadata for one captured post."""

    post_id: str
    timestamp: Optional[str] = None
    permalink: str
    author: Author
    parent_id: Optional[str] = None
    edit_history_post_ids: list[str] = Field(default_factory=list)
    quoted_post: Optional[QuotedPostMetadata] = None
    media: list[MediaMetadata] = Field(default_factory=list)
    source_status: Literal[
        "available",
        "deleted",
        "protected",
        "age_restricted",
        "restricted",
        "not_found",
    ] = "available"

    @property
    def edited(self) -> bool:
        return len(self.edit_history_post_ids) > 1


class CaptureMetadata(BaseModel):
    """Whether the fetcher could prove that it captured the supported source."""

    completeness: Literal["complete", "partial", "unsupported"] = "complete"
    missing_post_ids: list[str] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)

    @property
    def complete(self) -> bool:
        return self.completeness == "complete"


class Document(BaseModel):
    source_url: str
    retrieved_at: str  # ISO date
    doc_type: Literal["tweet", "thread", "article"]
    author: Author
    title: Optional[str] = None  # Articles only
    blocks: list[Block] = Field(default_factory=list)
    root_id: str = ""  # tweet id of the root, used for the filename
    posts: list[PostMetadata] = Field(default_factory=list)
    capture: CaptureMetadata = Field(default_factory=CaptureMetadata)
    source_status: Literal[
        "available",
        "deleted",
        "protected",
        "age_restricted",
        "restricted",
        "not_found",
        "unsupported",
    ] = "available"

    @property
    def capture_complete(self) -> bool:
        """Convenience accessor retained alongside structured capture metadata."""
        return self.capture.complete


class Fetcher(Protocol):
    """Implemented by the syndication and authenticated-browser fetchers."""

    def fetch(self, parsed) -> Document:  # noqa: ANN001 - ParsedUrl, avoid import cycle
        ...
