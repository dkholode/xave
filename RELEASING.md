# Releasing

Pushing a tag such as `v0.3.0` makes the
[Release workflow](.github/workflows/release.yml) build a signed APK and publish
it as a GitHub release.

## One-time setup

1. Create a release key. Keep the file and passwords private and backed up.
   If the key is lost, existing users cannot install updates without first
   uninstalling, which deletes their library.

   ```bash
   keytool -genkeypair -keystore xave-release.keystore -alias xave \
     -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Add the key to the repository's Actions secrets:

   ```bash
   base64 -i xave-release.keystore | gh secret set XAVE_RELEASE_KEYSTORE_BASE64
   gh secret set XAVE_RELEASE_KEYSTORE_PASSWORD
   gh secret set XAVE_RELEASE_KEY_ALIAS      # xave
   gh secret set XAVE_RELEASE_KEY_PASSWORD
   ```

## Each release

1. Increase `versionCode` and update `versionName` in
   `android/app/build.gradle`, then merge the change to `main`.
2. Tag the commit and push the tag:

   ```bash
   git tag v0.3.0
   git push origin v0.3.0
   ```

## Building a release locally

Set the same values as environment variables, with `XAVE_RELEASE_KEYSTORE`
pointing to the keystore file, and run `./gradlew assembleRelease` in `android`.
Without these variables, Gradle builds an unsigned release APK, which is what
F-Droid expects.

Release builds use a different key from local debug builds. To switch an
installed debug build to a release build, export any articles you want to keep,
then uninstall the debug build.
