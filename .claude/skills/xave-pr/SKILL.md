---
name: xave-pr
description: Build a xave pull request's description, before/after device screenshots, and testing table. Use when opening a PR, or when a PR's description or screenshots need updating after new commits.
---

# xave PR

Scratch for one PR lives in `/tmp/xave-<slug>/`, where `<slug>` is the branch name with `/` replaced by `-`.

## 1. List the shots

A change needs screenshots when anything on the device screen looks or acts differently: layout, wording, a menu, a dialog, a flow, an error state. A bug fix with a visible symptom uses the broken state as its before shot. Docs, CI, and test-only changes need none; go to step 5.

If the change alters a screen in `screenshots/` (the README images), retake that file on the branch build at the size of the file it replaces, and commit it with the change.

Done when each shot has a screen name (`library`, `reader-menu`, `capture`) and is marked before+after, or after only for a screen `main` doesn't have.

## 2. Capture on the device

Use the connected Android device or emulator in portrait, and take every shot of one PR on that same device. If `adb devices` lists more than one, ask which to use and pass `-s <serial>` to each `adb` command. Write every device `rm` with literal paths.

1. Back up app data: `adb exec-out run-as dev.xave.reader tar cf - files shared_prefs app_webview > /tmp/xave-<slug>/device-backup.tar`
2. Load the same fixtures on both builds: synthetic `demo_*` accounts, plus a real public post when the change touches capture (`https://x.com/jack/status/20`).
3. Install a `main` build and take every before shot. Install the branch build and take every after shot: `adb exec-out screencap -p > /tmp/xave-<slug>/shots/<name>-before.png` (or `-after.png`).
4. Restore: `adb shell pm clear dev.xave.reader`, then `adb exec-in run-as dev.xave.reader tar xf - < /tmp/xave-<slug>/device-backup.tar`. Reinstall the build that was on the device before step 1.

Done when every shot from step 1 exists and the device has its own library back.

## 3. Compose

Build one image per screen with the sibling script, so every PR has the same layout:

`python3 -I .claude/skills/xave-pr/compose.py --before shots/library-before.png --after shots/library-after.png --out pr/library.png`

For a two-step flow (capture screen, then reader), pass two paths to each flag. Leave out `--before` for a new screen. Open each output and check that both sides show the same content.

## 4. Upload to `pr-assets`

Images live on the orphan `pr-assets` branch under `<slug>/`, written without a checkout:

```sh
git fetch -q origin pr-assets
export GIT_INDEX_FILE=/tmp/xave-<slug>/pr-assets.index
git read-tree origin/pr-assets
for f in /tmp/xave-<slug>/pr/*.png; do git update-index --add --cacheinfo "100644,$(git hash-object -w "$f"),<slug>/$(basename "$f")"; done
commit=$(git commit-tree "$(git write-tree)" -p origin/pr-assets -m "Add <slug> PR screenshots")
git push -q origin "$commit:refs/heads/pr-assets"
unset GIT_INDEX_FILE
```

Link each image as `https://raw.githubusercontent.com/dkholode/xave/<commit>/<slug>/<name>.png`. The commit pins the URL, so a retake keeps its file name and the body points at the new commit.

Done when `curl -sI` returns 200 for every URL.

## 5. Write the body

Load `unslop`, then write `/tmp/xave-<slug>/pr-body.md` in this order:

1. A lead of 1-3 sentences on what changes for someone using the app. For a bug, the link or steps that broke it and what the reader showed. When there are screenshots, add one sentence naming the device from `adb shell getprop ro.product.model` and `ro.build.version.release`: "Screenshots are from a Pixel 9a (Android 15), with the same `demo_*` fixtures on both builds."
2. One `##` section per screen or behavior: the image first, then one bullet per change.
3. `## Testing`: a table with columns Check, How, Result. Rows for both suites in `android/README.md#tests`, for `./gradlew assembleDebug lintDebug`, then one row per device check. A check you skipped reads "Not run".
4. `## Not in this PR`, only when something was left out on purpose.

Title in sentence case, naming what the user gets ("Save captures automatically").

Open with `gh pr create --base main --title "<title>" --body-file /tmp/xave-<slug>/pr-body.md`, or update with `gh pr edit <number> --body-file ...`.

Done when `gh pr view --json body` shows every image URL from step 4 and every section above.
