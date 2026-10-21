"""Runtime configuration sourced from environment variables and ``.env``."""
from __future__ import annotations

import os
from pathlib import Path

from dotenv import load_dotenv

load_dotenv()

BASE_DIR = Path(__file__).resolve().parent.parent

# Persisted X login (created by `python -m app.auth.login`). Used by the
# auth-browser fallback for full threads and Articles.
STORAGE_STATE = Path(os.getenv("XAVE_STORAGE_STATE", str(BASE_DIR / "storage_state.json")))

# PDF page size understood by Chromium's page.pdf() (A4, Letter, ...).
PAGE_FORMAT = os.getenv("XAVE_PAGE_FORMAT", "A4")

# Milliseconds to allow for browser navigation in the auth fallback.
NAV_TIMEOUT_MS = int(os.getenv("XAVE_NAV_TIMEOUT_MS", "45000"))

# Send a desktop-browser UA so syndication/image hosts return full content.
USER_AGENT = os.getenv(
    "XAVE_USER_AGENT",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
)

# Maximum time for Chromium to load and render a PDF.
RENDER_TIMEOUT_MS = int(os.getenv("XAVE_RENDER_TIMEOUT_MS", "120000"))


def auth_available() -> bool:
    """True when a persisted X session exists for the auth-browser fallback."""
    return STORAGE_STATE.exists()
