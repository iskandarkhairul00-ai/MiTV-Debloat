# Development

Notes for changing and rebuilding the launcher. To install it, see the [install guides](../README.md#install); for how each feature works, see [features.md](features.md).

## Folder structure

```
MiTV-Debloat/
├── README.md
├── PROMPT.md                    paste into an AI coding agent to repeat the whole setup (debloat + launcher) on another TV
├── docs/                        install guides (install-*.md), features.md, xiaomi-debloat.md, development.md, screenshots
├── backup/                      state captured BEFORE any change (local only, not in the repo: it identifies the TV)
│   ├── disabled-by-us.txt       every package this project disabled (restore.sh reads it)
│   ├── packages-*.txt           full package lists (all, system, third-party, disabled)
│   ├── getprop.txt, settings-{global,secure,system}.txt
│   ├── stock-home.txt           the TV's own launcher, recorded by build-and-install.sh (restore.sh returns to it)
│   ├── home-default.txt, home-candidates.txt, accessibility.txt, accessibility-enabled-before.txt, private-dns-before.txt
│   └── dumpsys/                 activities/services of every manufacturer package that was checked
├── launcher/                    the launcher source (Java, AndroidX RecyclerView, minSdk 29, targetSdk 36), incl. the phone remote
│   ├── app/src/main/            the launcher, the phone remote page (assets/remote.html) and its resources
│   └── app/src/test/            unit tests for the pure logic (no TV or emulator needed)
├── .github/workflows/ci.yml     GitHub Actions: lint, unit tests and the release build on every push
├── scripts/
│   ├── build-and-install.sh     check the computer and TV, build the launcher (R8 release, ~0.6 MB APK), install it, make it the home screen
│   ├── benchmark.sh             measure a launcher's memory, CPU, start-up and smoothness
│   ├── restore.sh               undo everything (back to the TV's own launcher)
│   ├── disable.sh               disable packages (and log them so restore.sh can undo)
│   ├── key.sh / shot.sh         send remote keys / pull a screenshot (for testing)
│   └── env.sh                   TV address: TV=<ip> once, then remembered in .tv (not in the repo)
└── shots/                       screenshots taken during the work
```

The scripts need ADB debugging switched on; the [install guides](../README.md#install) cover connecting.

## Rebuild and reinstall

```bash
scripts/build-and-install.sh                 # build, install, set as home
scripts/build-and-install.sh --disable-stock # same, and disable the TV's own launcher (needed on Google TV)
```
Notes on the install:
- It uses `pm install` over the shell, not `adb install`.
- It grants location permission so the Wi-Fi pill can show the network name. Android 10 requires that.
- It uses `cmd package set-home-activity`, because several apps claim HOME on this TV: the factory test, tvusage and FallbackHome.
- While the stock launcher is enabled, it always wins (its priority is 2). That's why it has to be disabled.
- **New TV IP:** run `TV=<new-ip> scripts/build-and-install.sh` once; the new address is saved in `.tv` for every script.
- **Adding a permission:** adding a permission like INTERNET to an already-installed app doesn't reach the network firewall on Android 10, so the app gets `EPERM` on sockets. If you ever add one, reinstall cleanly: `adb shell pm uninstall my.rafhan.app.tvhome && scripts/build-and-install.sh`.
- **Signing:** release builds are signed with the project's release key when it's available (CI secrets, or `launcher/keystore.properties` on your computer; see [Release signing and GitHub Releases](#release-signing-and-github-releases)), so any build upgrades any other in place. Without the key, they're signed with this computer's debug key, which only upgrades an install built on the same computer. Switching between the two needs one uninstall (`adb shell pm uninstall my.rafhan.app.tvhome`), which loses the launcher's settings and the paired phones.

## Release signing and GitHub Releases

Every CI build is signed with one fixed **release key**, kept in the repository's secrets. So an APK from GitHub installs over the previous one and the TV keeps the launcher's settings. A version tag publishes the APK as a **GitHub Release**, which anyone can download without signing in.

### One-time setup (the repository owner)

You need the JDK's `keytool` (from step 2 of the install guide) and the GitHub CLI (`gh`, signed in with `gh auth login`).

1. **Create the key** (it asks for a password; pick a strong one and store it in your password manager):
   ```bash
   keytool -genkeypair -v -keystore tvhome-release.jks -alias tvhome \
     -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=TV Home"
   ```
   **Back up `tvhome-release.jks` and its password** somewhere safe and private (not in the repository). If you lose them, no future build can update the installed launcher without an uninstall.
2. **Give it to CI** as four repository secrets (run in the repository folder):
   ```bash
   base64 < tvhome-release.jks | tr -d '\n' | gh secret set SIGNING_KEYSTORE_BASE64
   gh secret set SIGNING_STORE_PASSWORD      # paste the password, then Enter
   gh secret set SIGNING_KEY_PASSWORD        # the same password (keytool uses one for both)
   gh secret set SIGNING_KEY_ALIAS --body tvhome
   ```
   Or add them on GitHub: the repository → Settings → Secrets and variables → Actions → New repository secret. For `SIGNING_KEYSTORE_BASE64`, paste the output of `base64 < tvhome-release.jks | tr -d '\n'`.
3. **Optional, to sign your own builds with it too** (so `scripts/build-and-install.sh` and GitHub Releases can update each other): create `launcher/keystore.properties` (git ignores it):
   ```properties
   storeFile=/full/path/to/tvhome-release.jks
   storePassword=your-password
   keyAlias=tvhome
   keyPassword=your-password
   ```

Check it worked: the next CI run's *Release signing key* step runs instead of being skipped. On your computer, `apksigner verify --print-certs launcher/app/build/outputs/apk/release/app-release.apk` shows `CN=TV Home`.

### Publishing a version

1. Bump `versionCode` (by 1) and `versionName` in `launcher/app/build.gradle`, and commit.
2. Tag the commit with `v` + the versionName and push the tag:
   ```bash
   git tag v1.9
   git push origin master v1.9
   ```
3. CI builds and tests it, then creates the release **TV Home 1.9** with `tvhome-1.9.apk` and notes generated from the commits.

The release job refuses to publish if the tag doesn't match `versionName`, or if the signing secrets are missing (a debug-signed release couldn't be updated later without losing settings).

## Tests and CI

The launcher's pure logic is unit tested on the computer, so a change doesn't need the TV to catch a regression:

```bash
cd launcher
./gradlew testDebugUnitTest     # just the tests (37 of them, a few seconds)
./gradlew lint                  # Android lint; errors fail the build, warnings don't
./gradlew assembleRelease       # the APK scripts/build-and-install.sh installs
```

Gradle needs Java 17+: `export JAVA_HOME="$(/usr/libexec/java_home -v 17+)"` on macOS, or `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` on Linux. The tests read a stubbed `android.jar`, so they can't touch the TV's APIs — that's why the logic they cover is kept free of `Context`:

| Test | Covers |
|---|---|
| `FileApiTest` | Files tab: name cleaning, unique names, `Range` parsing, keeping paths inside a root |
| `HidKeysTest` | Phone typing → USB-HID reports (letters, digits, punctuation) |
| `DockConfigTest` | `dock.txt` parsing and cleaning (comments, blanks, duplicates, the 5-app cap) |
| `UsageScoresTest` | "Most used": the week window, the per-day merge, per-app totals, exclusions |
| `RemotePairingTest` | Pairing: PIN lifetime, tries, lockout and its exponential backoff |

`.github/workflows/ci.yml` runs the same three tasks on every push and pull request, validates the Gradle wrapper and uploads the release APK as a build artifact.

## Benchmark

Measured on 2026-10-25 with `scripts/benchmark.sh <home component> <label>`, using the optimised R8 release build of launcher 1.8. Conditions:
- Nothing was playing, and USB debugging was on.
- The stock launcher ran without its recommendation services, which this setup disables. It was enabled only for its own run (with it enabled, Home keeps going to it), then disabled again before this launcher's run.

| | This launcher (1.8) | Stock Android TV launcher |
|---|---|---|
| Memory, idle / after navigating (PSS) | 88 / **83 MB** | 87 / 122 MB |
| CPU, idle, 60 s | 0.15 s | 0.11 s |
| Cold start (process killed → first frame) | **0.51–0.57 s** | 1.49–1.59 s |
| Back to home from an app (wait time) | 22–24 ms | 17–20 ms |
| Janky frames while navigating | **5.8 % (33/567)** | 22.4 % (74/331) |
| Frame time p50 / p90 / p99 | 7 / **8** / 61 ms | 5 / 22 / 40 ms |
| APK size | **0.65 MB** | 12.7 MB |

**Notes:**
- **Idle memory depends on the graphics buffers.** About 45 MB of this launcher's PSS is GPU memory (`GL mtrack`: the window's buffers, the wallpaper and the tile textures). Whether they're all allocated when the idle sample is taken changes the idle figure a lot: earlier runs measured 44–51 MB idle, this one 88 MB, settling at 82 MB a few minutes later. The after-navigating figure, when everything is drawn, is steady across runs (75–83 MB) and is the fairer comparison.
- **Extra processes only while used:** the launcher is a single process at idle. The Now Playing helper (~23 MB) runs only while something plays, and the virtual keypad (~21 MB) only for 5 minutes after a phone-remote key press inside an app.
- **Screensaver:** under the screensaver the launcher uses no CPU (0 ticks in 60 s). The System card refreshes every 5 s, and only while home is on screen and in focus.
- **Other apps in front:** after 30 s with another full-screen app in front, the app-list tile images are freed and redrawn on return. Hardware bitmaps live in graphics memory, so this saving doesn't show up in PSS.
- **Before these optimisations** the same benchmark measured 104 / 133 MB, 0.54 s idle CPU, 2.0–2.1 s cold start and 11.4 % janky frames.
