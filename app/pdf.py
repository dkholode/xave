"""Render self-contained HTML to PDF using headless Chromium (Playwright).

Uses the sync Playwright API, which is safe here because FastAPI runs its
synchronous endpoints in a worker thread (no running event loop). Images are
already data: URIs, so no network wait is needed during printing.
"""
from __future__ import annotations

from typing import Optional

from playwright.sync_api import sync_playwright

from . import config


def html_to_pdf(html: str, footer_html: Optional[str] = None) -> bytes:
    with sync_playwright() as pw:
        browser = pw.chromium.launch(timeout=config.RENDER_TIMEOUT_MS)
        try:
            page = browser.new_page()
            page.set_content(
                html,
                wait_until="load",
                timeout=config.RENDER_TIMEOUT_MS,
            )
            pdf_bytes = page.pdf(
                format=config.PAGE_FORMAT,
                print_background=True,
                margin={"top": "16mm", "bottom": "20mm", "left": "14mm", "right": "14mm"},
                display_header_footer=bool(footer_html),
                header_template="<span></span>",
                footer_template=footer_html or "<span></span>",
            )
        finally:
            browser.close()
    return pdf_bytes
