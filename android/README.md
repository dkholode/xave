# Building Xave

How to build, install, and test the Android app. For what the app does and how
to use it, see the [main README](../README.md).

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

## Tests

For renderer and storage checks, install Python 3 and Android Studio, then run:

```bash
python3 tests/run_host_tests.py
```

The script uses Android Studio's bundled JDK and JSON library on macOS. On other
systems, set `JAVA_HOME` to a JDK and `XAVE_JSON_JAR` to a JAR containing
`org.json`, such as
[json-20240303.jar](https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar)
from Maven Central.

For capture checks:

```bash
python3 tests/build_test_page.py
```

Open `app/build/capture-tests.html` in a browser to see the results. CI runs the
same page in headless Chrome.

For release builds, see [RELEASING.md](../RELEASING.md).
