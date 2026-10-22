# Xave

Xave is an Android app for saving web articles and X posts to read offline.
Open a link in its built-in browser, capture what it loaded, and keep a clean,
script-free copy on your device. It runs on Android 8 or later, including phones,
tablets, and e-ink devices such as the Boox Palma.

<p>
  <img src="screenshots/library-light.png" width="240" alt="Library screen">
  <img src="screenshots/reader-light.png" width="240" alt="Reading a saved article in the light theme">
  <img src="screenshots/reader-dark.png" width="240" alt="Reading a saved article in the dark theme">
</p>

## Install

Download the latest APK from
[Releases](https://github.com/dkholode/xave/releases), open it on your device,
and allow installation from that source when prompted. To build it yourself,
see [android/README.md](android/README.md).

## Use

1. Paste an HTTPS link, or use another app's Share action to send a link to Xave.
2. Let the page load. Sign in if the site requires it, and scroll to load the
   content you want to keep. On X, expand long posts and scroll the thread.
3. Tap **Capture** once the top bar shows the posts you want as loaded. Xave
   saves it right away. Check the capture and the note at its top.
4. If something is missing, press Back to return to the loaded page, scroll
   further, and capture again. Delete the copy you don't need. **‹** returns to
   the library.
5. In the reader, the **⋮** menu has text size, theme, export, the post on X,
   and delete. **Prev / Next** page through it, and your position is saved.
   **Export** saves a self-contained HTML file or opens Android's
   Print / Save as PDF dialog, if a print service is installed.
6. To delete one or more items from the library, use **⋮ > Delete** there.

**Theme**, in the **⋮** menu, follows your system's dark mode by default. Choose
**Light** on e-ink screens for the sharpest text.

## Capture limits

- X threads contain the loaded posts by the linked post's author. Xave does not
  verify reply relationships or guarantee a complete thread. Check each capture.
- Open the full reading view before capturing an X Article. Article extraction
  is heuristic, and unusual page layouts may be incomplete.
- Loaded images are saved within size and time limits. Missing images are
  marked. Video, audio, and interactive widgets are not downloaded.
- Capturing needs internet access. Saved reading blocks scripts and network
  loads. Opening a source link takes you back online.

## Privacy

Your library and browser sessions stay on the device. Xave has no account,
server, analytics, or ads. Uninstalling the app or clearing its data deletes
the library, so export anything you want to keep separately.

Xave saves pages you can already open in your own browser, for your personal
reading. Respect content owners' rights and each site's terms.

## Contributing

Bug reports, site-capture fixes, and pull requests are welcome. See
[CONTRIBUTING.md](CONTRIBUTING.md). To report a security problem, see
[SECURITY.md](SECURITY.md).

## License

[Apache License 2.0](LICENSE)
