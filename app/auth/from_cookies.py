"""Build storage_state.json from cookies copied out of your normal browser.

X frequently rate-limits logins performed inside an automated browser ("We've
temporarily limited your login"). This avoids the login flow entirely by reusing
the session you already have. Grab the two cookie values from a browser where
you're logged in:

  DevTools (F12 / right-click > Inspect)
    > Application (Chrome) or Storage (Firefox)
    > Cookies > https://x.com
    > copy the VALUE of `auth_token` and of `ct0`

Then run `python -m app.auth.from_cookies`. Both values are requested through
hidden prompts so they do not enter shell history or appear on screen. The
XAVE_AUTH_TOKEN / XAVE_CT0 environment variables are also supported.
"""
from __future__ import annotations

import argparse
import getpass
import os
import sys

from .. import config
from .storage import write_storage_state


def _cookie(name: str, value: str, domain: str) -> dict:
    return {
        "name": name,
        "value": value,
        "domain": domain,
        "path": "/",
        "expires": -1,
        "httpOnly": name == "auth_token",
        "secure": True,
        "sameSite": "None" if name == "auth_token" else "Lax",
    }


def build_state(auth_token: str, ct0: str) -> dict:
    cookies = []
    for domain in (".x.com", ".twitter.com"):
        cookies.append(_cookie("auth_token", auth_token, domain))
        cookies.append(_cookie("ct0", ct0, domain))
    return {"cookies": cookies, "origins": []}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Write storage_state.json from browser cookies.")
    parser.parse_args(argv)

    auth_token = os.getenv("XAVE_AUTH_TOKEN") or getpass.getpass(
        "Paste your X auth_token cookie (hidden): "
    ).strip()
    ct0 = os.getenv("XAVE_CT0") or getpass.getpass("Paste your X ct0 cookie (hidden): ").strip()
    if not auth_token or not ct0:
        print("error: both auth_token and ct0 are required.", file=sys.stderr)
        return 1

    state = build_state(auth_token, ct0)
    write_storage_state(config.STORAGE_STATE, state)

    print(f"Wrote {config.STORAGE_STATE} ({len(state['cookies'])} cookies).")
    print('Test it:  python -m app.cli "<thread-url>" --full -o thread.pdf')
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
