"""One-time X login. Opens a real browser, you log in, the session is saved.

    python -m app.auth.login

The saved storage_state.json is then reused by the auth-browser fetcher for
full threads and Articles. Re-run this if the session ever expires.
"""
from __future__ import annotations

import sys

from playwright.sync_api import sync_playwright

from .. import config
from .storage import write_storage_state


def main() -> int:
    print("Opening a browser window. Log into X/Twitter as you normally would.")
    print("When your home timeline is visible, come back here and press Enter.\n")
    print(
        "If X temporarily limits this login, cancel with Ctrl+C and run "
        "`python -m app.auth.from_cookies` instead.\n"
    )

    with sync_playwright() as pw:
        launch_kwargs = {
            "headless": False,
            "args": ["--disable-blink-features=AutomationControlled"],
        }
        try:  # real Chrome is less likely to be flagged than bundled Chromium
            browser = pw.chromium.launch(channel="chrome", **launch_kwargs)
        except Exception:
            browser = pw.chromium.launch(**launch_kwargs)
        context = browser.new_context(
            user_agent=config.USER_AGENT, viewport={"width": 1280, "height": 900}, locale="en-US"
        )
        context.add_init_script("Object.defineProperty(navigator,'webdriver',{get:()=>undefined});")
        page = context.new_page()
        try:
            page.goto("https://x.com/login", wait_until="domcontentloaded")
        except Exception as exc:  # pragma: no cover - network/UI dependent
            print(f"(navigation warning: {exc})")

        if not _wait_for_confirmation():
            browser.close()
            return 1

        write_storage_state(config.STORAGE_STATE, context.storage_state())
        browser.close()

    print(f"\nSaved session to {config.STORAGE_STATE}")
    print("You can now capture full threads and Articles (tick the box in the UI, or pass --full).")
    return 0


def _wait_for_confirmation() -> bool:
    try:
        input("Press Enter once you are logged in to save the session... ")
        return True
    except (EOFError, KeyboardInterrupt):
        print(
            "\nLogin was not saved. If X limited the automated login, run "
            "`python -m app.auth.from_cookies`.",
            file=sys.stderr,
        )
        return False


if __name__ == "__main__":
    raise SystemExit(main())
