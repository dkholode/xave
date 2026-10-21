# Xave for Android

Capture a blog post or X page in the built-in browser, preview it, and save it
for offline reading. The reader runs on the device and does not need the Python
app. It supports Android 8 or later and is designed for the Boox Palma 2.

## Build and install

1. Install Android Studio and open this `android` folder as the project.
2. In SDK Manager, install Android SDK Platform 35 and accept the SDK licenses.
3. Let Gradle sync, then select your device and click Run.

For a command-line build, set `JAVA_HOME` to a JDK 17 or later and
`ANDROID_HOME` to your Android SDK folder, then run from this directory:

```bash
./gradlew assembleDebug
```

On Windows, use `gradlew.bat assembleDebug`. The first build downloads Gradle
and the Android build plugin.

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Copy it to your Android
device and open it to install, allowing installation from that source when
prompted. Or enable USB debugging, connect the device, approve the computer,
and use the Android SDK's `adb` command:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Builds use the standard local debug signing key. If `.local/debug.keystore`
already exists, Gradle uses it so updates can retain an existing installation.
Keep that key private. Installing a build signed with a different key requires
uninstalling the previous app, which removes its saved library.

## Read offline

1. Paste an HTTPS blog-post or X link, or use another app's Share action to send
   a link to Xave.
2. Let the page load. Sign in to X in the built-in browser if required. Expand
   truncated posts and scroll to load the content you want to save.
3. Tap **Preview capture** and review the text, post count, and warnings.
4. Tap **Save offline**. Reopen saved items from **Library**. Unfinished previews
   are kept under **Unfinished captures**.
5. Use **More** for text size and **Prev / Next** to move through the article.
   Reading position is saved. **Export** saves a self-contained HTML file or
   opens Android's Print / Save as PDF dialog, if a print service is installed.

The library and browser sessions stay on the device. Uninstalling the app or
clearing its data deletes them. Export important articles for a separate copy.

## Capture limits

- X threads contain loaded posts by the linked post's author. Xave does not
  verify reply relationships or guarantee a complete thread. Review the preview.
- Open the full reading view before capturing an X Article. Blog extraction and
  unusual page layouts may be incomplete.
- Loaded images are saved within size and time limits. Missing images are
  marked. Video, audio, and interactive widgets are not downloaded.
- Capturing requires internet access. Saved reading blocks scripts and network
  loads. Opening source links takes you back online.

## Tests

For renderer and storage checks, install Python 3 and Android Studio, then run:

```bash
python3 tests/run_host_tests.py
```

The script uses Android Studio's bundled JDK and JSON library on macOS. On other
systems, set `JAVA_HOME` and `XAVE_JSON_JAR` to a JDK and a JAR containing
`org.json`.

For capture checks:

```bash
python3 tests/build_test_page.py
```

Open `app/build/capture-tests.html` in a browser to see the results.
