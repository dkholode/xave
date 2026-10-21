"""Command-line entry, handy for testing the pipeline without the web UI.

    python -m app.cli <x-url> [-o out.pdf] [--full]
"""
from __future__ import annotations

import argparse
import sys

from .fetchers import fetch_document
from .fetchers.base import FetchError
from .main import make_filename
from .normalize import embed_media
from .pdf import html_to_pdf
from .render import build_footer, render_document


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Convert an X/Twitter URL to a clean PDF.")
    parser.add_argument("url")
    parser.add_argument("-o", "--out", help="Output path (default: derived filename).")
    parser.add_argument("--full", action="store_true", help="Capture full thread/Article via login.")
    args = parser.parse_args(argv)

    try:
        doc, warnings = fetch_document(args.url, want_full=args.full)
    except FetchError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    embed_media(doc, warnings=warnings)
    pdf_bytes = html_to_pdf(render_document(doc), footer_html=build_footer(doc))
    out = args.out or make_filename(doc)
    with open(out, "wb") as fh:
        fh.write(pdf_bytes)

    for warning in warnings:
        print(f"warning: {warning}", file=sys.stderr)
    print(f"wrote {out} ({len(pdf_bytes):,} bytes, {len(doc.blocks)} blocks)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
