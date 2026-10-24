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
- **Signing:** the release APK is signed with the computer's debug key, so it upgrades an install in place only when built on the same machine. Built elsewhere, uninstall once (`adb shell pm uninstall my.rafhan.app.tvhome`) and install again; the launcher's settings and the paired phones are lost.

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

Measured with `scripts/benchmark.sh <home component> <label>`, using the optimised R8 release build. Conditions:
- Nothing was playing, and USB debugging was on.
- The stock launcher ran without its recommendation services, which this setup disables.

| | This launcher | Stock Android TV launcher |
|---|---|---|
| Memory, idle, settled / after navigating (PSS) | **44 / 75 MB** | 94 / 124 MB |
| CPU, idle, 60 s | 0.19 s | 0.14 s |
| Cold start (process killed → first frame) | 0.60–1.05 s | 0.56–0.81 s |
| Back to home from an app (wait time) | 233–308 ms | 214–284 ms |
| Janky frames while navigating | **7.6 % (43/566)** | 18.9 % (62/328) |
| Frame time p50 / p90 / p99 | 8 / **10** / 65 ms | 6 / 27 / 69 ms |
| APK size | 0.6 MB | 12.7 MB |

**Notes:**
- **Extra processes only while used:** the launcher is a single process at idle. The Now Playing helper (~23 MB) runs only while something plays, and the virtual keypad (~21 MB) only for 5 minutes after a phone-remote key press inside an app.
- **Screensaver:** under the screensaver the launcher uses no CPU (0 ticks in 60 s). The System card refreshes every 5 s, and only while home is on screen and in focus.
- **Other apps in front:** after 30 s with another full-screen app in front, the app-list tile images are freed and redrawn on return. Hardware bitmaps live in graphics memory, so this saving doesn't show up in PSS.
- **Before these optimisations** the same benchmark measured 104 / 133 MB, 0.54 s idle CPU, 2.0–2.1 s cold start and 11.4 % janky frames.
