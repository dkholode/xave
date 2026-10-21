"""FastAPI app: a single page to paste an X URL and download a clean PDF."""
from __future__ import annotations

import logging
import re
import unicodedata
from pathlib import Path
from urllib.parse import quote

from fastapi import FastAPI, Form, Request
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates

from .fetchers import fetch_document
from .fetchers.base import Document, FetchError
from .normalize import embed_media
from .pdf import html_to_pdf
from .render import build_footer, render_document

_DIR = Path(__file__).resolve().parent
log = logging.getLogger("xave.web")
app = FastAPI(title="xave")
app.mount("/static", StaticFiles(directory=str(_DIR / "static")), name="static")
templates = Jinja2Templates(directory=str(_DIR / "templates"))


@app.get("/", response_class=HTMLResponse)
def index(request: Request) -> HTMLResponse:
    return templates.TemplateResponse(request, "index.html")


@app.get("/favicon.ico", include_in_schema=False)
def favicon() -> FileResponse:
    return FileResponse(_DIR / "static" / "favicon.svg", media_type="image/svg+xml")


@app.post("/api/convert")
def convert(url: str = Form(...), full: bool = Form(False)) -> Response:
    try:
        doc, warnings = fetch_document(url, want_full=full)
        embed_media(doc, warnings=warnings)
        pdf_bytes = html_to_pdf(render_document(doc), footer_html=build_footer(doc))

        headers = {"Content-Disposition": build_content_disposition(make_filename(doc))}
        if warnings:
            headers["X-Xave-Warnings"] = " | ".join(warnings)
        return Response(content=pdf_bytes, media_type="application/pdf", headers=headers)
    except FetchError as exc:
        return JSONResponse(status_code=400, content={"error": str(exc)})
    except Exception:
        log.exception("Unexpected conversion failure")
        return JSONResponse(
            status_code=500,
            content={"error": "Unexpected conversion error. Check the server log for details."},
        )


def make_filename(doc: Document) -> str:
    if doc.doc_type == "article" and doc.title:
        base = _slug(doc.title)
    elif doc.doc_type == "thread":
        base = f"{doc.author.handle}-thread-{doc.root_id}"
    else:
        base = f"{doc.author.handle}-tweet-{doc.root_id}"
    return f"{base or 'xave'}.pdf"


def _slug(text: str) -> str:
    slug = re.sub(r"[^\w\s-]", "", text).strip().lower()
    slug = re.sub(r"[\s_-]+", "-", slug)
    return slug[:80].strip("-")


def build_content_disposition(filename: str) -> str:
    """Return an ASCII-safe header plus an RFC 5987 UTF-8 filename."""
    ascii_name = (
        unicodedata.normalize("NFKD", filename).encode("ascii", "ignore").decode("ascii")
    )
    ascii_name = re.sub(r'[^A-Za-z0-9._ -]', "", ascii_name).strip() or "xave.pdf"
    encoded = quote(filename, safe="")
    return f"attachment; filename=\"{ascii_name}\"; filename*=UTF-8''{encoded}"
