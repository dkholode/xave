# xave

xave has two apps:

- [Android reader](android/README.md): capture blog posts and X pages, save them
  on your device, and read offline. Designed for the Boox Palma 2.
- Python web app: save X/Twitter posts, threads, and Articles as reader-style
  PDFs with embedded images. Setup is below.

## Python web app

Requires Python 3.10 or later. From the project folder:

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
python -m playwright install chromium
python -m uvicorn app.main:app --reload
```

On Windows, activate the environment with `.venv\Scripts\activate` instead.
On Linux, Chromium may also need system packages installed with
`python -m playwright install --with-deps chromium`.

Open http://localhost:8000 and paste an X link. No API key is needed.
The default capture uses X's public endpoint. For a thread, paste its last post
to capture the available same-author parent chain.

## Full threads and Articles

Full-thread capture and Articles use your saved X login. To set it up:

```bash
python -m app.auth.login
```

Sign in to X in the browser that opens, then press Enter in the terminal.
Select **Capture full thread / Article** in the app when converting.

If X blocks the automated login, import an existing browser session:

1. In a browser signed into X, open developer tools.
2. Under Application or Storage, open the cookies for `https://x.com`.
3. Run `python -m app.auth.from_cookies` and enter the `auth_token` and `ct0`
   cookie values at the hidden prompts.

The session is saved in `storage_state.json`, which Git ignores. Keep that file
private. Repeat the login if your session expires. Run this app locally with
your own session; do not expose a server using your cookies to other users.

## Command line

With the virtual environment activated:

```bash
python -m app.cli "https://x.com/user/status/123" -o saved.pdf
python -m app.cli "https://x.com/user/status/123" --full
```

Optional settings for paper size, session location, and browser timeouts are in
[.env.example](.env.example). Copy it to `.env` to change them.

## Limits

X can change or restrict its endpoints. Full threads and Articles may be
incomplete; review capture warnings. Videos, quoted posts, and link cards are
represented by links rather than downloaded media. Respect content owners'
rights and X's terms when archiving.

## Tests

With Chromium installed:

```bash
python -m pip install pytest
python -m pytest
```

Tests use local fixtures and simulated responses. The PDF test runs Chromium.
