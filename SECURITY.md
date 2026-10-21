# Security policy

## Reporting a vulnerability

Please report security problems privately through GitHub: open the repository's
**Security** tab and choose **Report a vulnerability**. Don't open a public
issue for them.

Include the affected version, steps to reproduce, and the impact you expect.
You should get a response within a week.

## Supported versions

Security fixes go into the latest release.

## Security model

Xave captures content from arbitrary websites, so it treats every page as
untrusted:

- The capture script runs in the page through `evaluateJavascript` and only
  returns data. Websites have no JavaScript bridge into the app.
- The online browser loads HTTPS pages only, and blocks mixed content, file
  access, and third-party cookies.
- Saved reading opens with JavaScript disabled, network loads blocked, and a
  Content Security Policy that permits only inline styles and embedded images.
- Captured text is escaped, and only `http`/`https` links without embedded
  credentials are kept.
- Images are downloaded without following redirects, so site cookies are never
  sent to another host. Cookies are never saved in articles or exports.
- The library and browser sessions are stored in the app's private storage and
  are excluded from Android backups.

Reports that bypass any of these are in scope.
