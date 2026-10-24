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

Xave captures content from X, so it treats every page as untrusted:

- The capture script runs in the page through `evaluateJavascript` and only
  returns data. Websites have no JavaScript bridge into the app.
- The online browser loads HTTPS pages only, and blocks mixed content, file
  access, and third-party cookies.
- Saved reading opens with JavaScript disabled, network loads blocked, and a
  Content Security Policy that permits only inline styles and the article's
  own stored images.
- Captured text is escaped, and only `http`/`https` links without embedded
  credentials are kept.
- Images are downloaded without following redirects, and site cookies are sent
  only to images on the captured page's own host. Cookies are never saved in
  articles or exports.
- The library and browser sessions are stored in the app's private storage.
  Cloud backup is off. Device-to-device transfer on Android 12 and later copies
  only the library and reading settings, never browser data such as cookies.
  On Android 11 and earlier, nothing is backed up or transferred.
- A library backup holds only saved articles and their images. Restoring one
  treats it as untrusted: only article and image entries with the expected
  names are read, sizes are capped, articles must parse as JSON, existing saves
  are never overwritten, and library rows are rebuilt from the articles.

Reports that bypass any of these are in scope.
