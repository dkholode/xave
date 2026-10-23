# Contributing to Xave

Thanks for helping. Bug reports, site-capture fixes, and focused pull requests
are all welcome.

## Reporting a capture problem

Most issues are pages that capture badly. Please include:

- The URL, if it is public
- What was missing or wrong in the capture
- Your device and Android version

Don't include links or screenshots that show private content or account
details.

## Making changes

1. Build and run the app as described in [android/README.md](android/README.md).
2. Keep changes small and focused on one problem.
3. Add or update a test when you change capture or rendering behavior:
   - Page extraction: add a DOM fixture to `android/tests/capture.test.js`.
   - Rendering and storage: add a test under
     `android/app/src/test/java/dev/xave/reader/`.
4. Run both test suites (see [android/README.md](android/README.md#tests)) and
   test on a device or emulator when you change the UI.
5. Open a pull request describing what changed and how you tested it. CI builds
   the APK and runs the tests.

The app has no third-party libraries; only the tests use JUnit and org.json.
Please discuss adding one in an issue first.

## Security-sensitive code

The capture script runs inside arbitrary websites, and the reader displays
content from them. Keep these properties intact:

- Websites never get a JavaScript bridge into the app.
- Saved reading runs without JavaScript or network access.
- Captured text and links are escaped, and only `https`/`http` links without
  credentials are kept.

Report vulnerabilities privately as described in [SECURITY.md](SECURITY.md).

## License

By contributing, you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE).
