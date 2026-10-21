"""Render a Document to clean-reader HTML, and build the per-page PDF footer."""
from __future__ import annotations

import html
import re
from pathlib import Path

from jinja2 import Environment, FileSystemLoader, select_autoescape
from markupsafe import Markup

from .fetchers.base import Document

_TEMPLATES = Path(__file__).resolve().parent / "templates"
_LINK_TOKEN_RE = re.compile(r"https?://[^\s<]+|(?<![\w@])@\w{1,15}|(?<![\w&])#\w+")

READER_CSS = """
:root { --ink:#0f1419; --muted:#536471; --line:#eaecef; --accent:#1d9bf0; }
* { box-sizing: border-box; }
html, body { margin: 0; padding: 0; }
body {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
  color: var(--ink); font-size: 12pt; line-height: 1.55;
}
.doc-header { display: flex; align-items: center; gap: 12px; padding-bottom: 14px;
  border-bottom: 1px solid var(--line); margin-bottom: 22px; }
.doc-header .avatar { width: 52px; height: 52px; border-radius: 50%; object-fit: cover; }
.doc-header .name { font-weight: 700; font-size: 13pt; }
.doc-header .handle { color: var(--muted); font-size: 11pt; }
h1.title { font-size: 22pt; line-height: 1.2; margin: 0 0 20px; }
main h2 { font-size: 15pt; margin: 22px 0 8px; }
main h3 { font-size: 13pt; margin: 18px 0 6px; }
.text { margin: 0 0 14px; white-space: normal; }
figure { margin: 16px 0; page-break-inside: avoid; }
figure img { max-width: 100%; height: auto; border-radius: 12px; border: 1px solid var(--line); }
figcaption { color: var(--muted); font-size: 10pt; margin-top: 6px; }
a { color: var(--accent); text-decoration: none; word-break: break-word; }
.placeholder { color: var(--muted); font-size: 10.5pt; background: #f7f9fa;
  border: 1px solid var(--line); border-radius: 10px; padding: 8px 12px; margin: 12px 0;
  page-break-inside: avoid; }
.placeholder a { word-break: break-all; }
hr.tw-divider { border: none; border-top: 1px dashed var(--line); margin: 20px 0; }
.x-post { border: 1px solid var(--line); border-radius: 12px; padding: 14px;
  margin: 0 0 18px; page-break-inside: avoid; }
.x-post-header { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.x-post-header .avatar { width: 42px; height: 42px; border-radius: 50%; object-fit: cover; }
.x-post-header .identity { flex: 1; }
.x-post-header .name { font-weight: 700; }
.x-post-header .handle { color: var(--muted); }
.x-logo { width: 20px; height: 20px; fill: var(--ink); }
.x-post-meta { color: var(--muted); font-size: 9.5pt; margin-top: 12px; }
.x-post-meta a { color: var(--muted); text-decoration: underline; }
.format-disclosure { color: var(--muted); font-size: 8.5pt; margin-top: 16px; }
""".strip()

_env = Environment(
    loader=FileSystemLoader(str(_TEMPLATES)),
    autoescape=select_autoescape(["html"]),
)


def _linkify(text: str) -> Markup:
    parts = []
    end = 0
    for match in _LINK_TOKEN_RE.finditer(text):
        parts.append(html.escape(text[end:match.start()]))
        token = match.group()
        target = (
            "https://x.com/" + token[1:] if token.startswith("@")
            else "https://x.com/hashtag/" + token[1:] if token.startswith("#")
            else token
        )
        parts.append(f'<a href="{html.escape(target, quote=True)}">{html.escape(token)}</a>')
        end = match.end()
    parts.append(html.escape(text[end:]))
    return Markup("".join(parts).replace("\n", "<br>"))


_env.filters["linkify"] = _linkify


def render_document(doc: Document) -> str:
    template = _env.get_template("document.html")
    return template.render(doc=doc, css=READER_CSS, sections=_post_sections(doc))


def build_footer(doc: Document) -> str:
    """Chromium footerTemplate: 'Archived <date> · <source>' on the left,
    page numbers on the right. Inline styles are required (default font-size is 0)."""
    source = doc.source_url
    if len(source) > 90:
        source = source[:87] + "..."
    left = html.escape(f"Archived {doc.retrieved_at} · {source}")
    return (
        '<div style="font-size:8px;color:#8a8f95;width:100%;'
        'padding:0 14mm;display:flex;justify-content:space-between;font-family:sans-serif;">'
        f"<span>{left}</span>"
        '<span><span class="pageNumber"></span> / <span class="totalPages"></span></span>'
        "</div>"
    )


def _post_sections(doc: Document) -> list[dict]:
    if not doc.posts:
        return []
    groups: list[list] = [[]]
    for block in doc.blocks:
        if getattr(block, "kind", None) == "divider":
            groups.append([])
        else:
            groups[-1].append(block)
    if len(groups) != len(doc.posts):
        return []
    return [
        {"post": post, "blocks": blocks}
        for post, blocks in zip(doc.posts, groups)
    ]
