# The prompt

Paste everything below the line into any AI coding agent that can run shell commands, on a computer on the **same Wi-Fi as the TV**. It debloats the TV and replaces the home screen with a fast, Apple TV style launcher like the one in this repo.

It was written for a **Xiaomi Mi TV (Android TV 10, MediaTek)**, but works on most Android TV and Google TV sets (Xiaomi, TCL, Sony, Philips, Hisense, Chromecast with Google TV…). It does **not** work on Samsung (Tizen), LG (webOS) or Fire TV.

The computer needs `adb` (Android platform-tools) and JDK 17. The assistant installs the Android SDK pieces it needs to build the launcher.

**Before you start, on the TV:**
1. Settings → Device Preferences → About → press OK on **Build** 7 times. This unlocks Developer options.
2. Settings → Device Preferences → Developer options → turn **USB debugging** on. On Google TV it may be called **Network debugging** or **Wireless debugging**.
3. Note the TV's IP address: Settings → Network & Internet → your Wi-Fi network.
4. Keep the remote nearby. The TV will ask **"Allow USB debugging?"** the first time the computer connects. Tick *Always allow* and accept.

Expect the assistant to ask you three or four questions before it changes anything: which apps you use, which apps go in the dock, and which optional features you want.

---

I want to debloat my Android TV and replace its home screen with a minimal, fast, Apple TV style launcher that I own the source of. The TV is a `<TV MODEL HERE>` at `<TV IP HERE>`. You have shell access on my computer; talk to the TV over network ADB. Work carefully: this is a TV my household uses every day, and every change must be undoable.

Before you start, ask me (one checklist, not one question at a time):
- Which installed apps I actually use, so you know what must never be touched. List the third-party and notable system apps you find and let me tick them.
- Which apps go in the dock, left to right (up to 5).
- Whether to keep: Google Assistant / voice search, Chromecast, screen mirroring (Miracast/MiPlay), manufacturer system updates, the manufacturer's own home/content app.
- Which optional launcher features I want: phone remote, Now Playing card, system card, most-used card, prayer times (and which zone), notifications bell, running-apps screen, built-in wallpapers, ad-blocking Private DNS.

Use the answers instead of guessing. Ask again whenever something depends on my preferences.

Connect
- `adb connect <TV IP>:5555`, then wait for me to accept the prompt on the TV. If it fails, check that USB debugging is on and that the TV and computer are on the same network. On macOS, the terminal app may need Local Network permission.
- Put the TV address in one place (`scripts/env.sh`, overridable with `TV=ip:5555`) and have every script source it.
- Run one `pm` command per `adb shell` call. Chained `pm` commands in one shell can stop early on some firmware.
- `adb install` waits for an on-screen confirmation on some sets. Push the APK to `/data/local/tmp` and run `pm install -r` instead.
- Each `adb shell input keyevent` call can take seconds on a slow TV. Send several keys in one `input keyevent A B C` call.

Back up first
- Before changing anything, save to `backup/`: `getprop`, `settings list global|secure|system`, every package list (`pm list packages -f`, `-s`, `-3`, `-d`, `-u`), the current HOME activity and all HOME candidates, preferred activities, enabled accessibility services, Private DNS mode, and a `dumpsys package` for every manufacturer package you consider touching.
- Keep a log, `backup/disabled-by-us.txt`, of every package you disable. Write `scripts/disable.sh` so it disables and logs in one step, and skips packages that aren't installed.
- Write `scripts/restore.sh` early and keep it up to date: it re-enables everything in the log, lifts any background restrictions, removes only our accessibility service (keeping others), sets Private DNS back to automatic, points HOME back at the stock launcher, and with `--remove-launcher` uninstalls ours.

Debloat
- Only ever `pm disable-user --user 0`. Never uninstall system packages, never touch `/system`.
- Good candidates: manufacturer telemetry and analytics, Google feedback and bug-report senders, ad and recommendation rows, store demo apps, auto-installers that push apps, unused screensavers, the old Leanback launcher, print spooler, and apps I said I don't use.
- Keep, no matter what: Live TV and the TV input framework (on MediaTek: `com.mediatek.wwtv.tvcenter`, `com.mediatek.tvinput`, `com.mediatek.tv.service`, `mitv.service`), the remote's hotkey dispatcher and Bluetooth pairing helpers, the keyboard, Play Store, Play Services, Settings, and anything I ticked.
- Check what a package does (`dumpsys package`, its activities and services) before disabling it. Some manufacturer apps host things you'd miss: on Xiaomi, PatchWall (`com.mitv.tvhome.atv`) hosts the Input/Source popup, and `com.mitv.videoplayer` holds PatchWall's data store.
- Disable in small batches. After each batch, press Home, open a couple of my apps, switch an HDMI input, and take a screenshot. After all batches, reboot and check again.
- Don't open factory, production-test or hidden engineering menus.
- If the firmware refuses `disable-user` for a package, note it and move on. Don't look for workarounds.
- Tell me about side effects, e.g. the remote's Assistant button doing nothing once Assistant is disabled.

Optional speed tweaks (ask first, log each one so restore.sh undoes it)
- Background-restrict streaming apps I don't want running when closed: `cmd appops set <pkg> RUN_ANY_IN_BACKGROUND ignore`, listed in `backup/bg-restricted.txt`.
- Turn off Wi-Fi background scanning: `settings put global wifi_scan_always_enabled 0`.
- Ad-blocking Private DNS: `private_dns_mode hostname`, `private_dns_specifier dns.adguard-dns.com`. Tell me it won't block YouTube ads.

Launcher
- First check the TV's SDK level (`getprop ro.build.version.sdk`) and that it accepts sideloaded APKs.
- Native Android, Java, no Kotlin, no Compose, no Leanback library. AndroidX RecyclerView only. `minSdk` = the TV's SDK, `targetSdk` 36 (some Google TV 14 sets refuse old targets). R8 release build with `shrinkResources`, signed with the debug key so reinstalls upgrade in place. Aim for an APK under about 0.6 MB.
- HOME + LEANBACK_LAUNCHER intent filters, `singleTask`, landscape. Add `<queries>` for LEANBACK_LAUNCHER and LAUNCHER intents or the app grid comes up empty on Android 11+.
- Look: dark background or a wallpaper (see below), the Outfit variable font (SIL OFL) bundled in assets, rounded banner tiles. One translucent "glass" focus highlight that springs to the focused tile, and only runs frame callbacks while it moves.
- Layout, top to bottom:
  - Top bar: big clock with the date beside it; round icon buttons for the **bell** (notifications), **Inputs** (dropdown: HDMI 1–3, AV, Live TV, via the TV input framework / `TvContract`), **Wi-Fi** (state by colour, opens Wi-Fi settings) and **Settings** (the launcher's own settings page).
  - Cards on the left under the clock, for whichever optional features I picked.
  - Dock near the bottom: the focused app's name large above it, with a soft glow in a vivid colour sampled from its banner. The first row of the app grid peeks under the dock, faded.
  - Down from the dock slides up to all other apps (5 per row), with a fade at the top edge as rows scroll under it.
  - Up from the top bar slides a screen down from above (the running-apps screen, if I picked it).
- Focus: don't rely on Android's focus search. Drive it from `dispatchKeyEvent` with a small state machine (section + index), so every key has exactly one outcome. Up/Down move exactly one row; Up from the dock goes to the top bar; row and bar ends don't wrap; Back returns to the dock; Home while home jumps to the dock. Android pauses the activity before delivering the Home intent, so detect "already home" as "not stopped since the last resume", not by checking whether it's resumed. Remember the last focused tile in SharedPreferences, so it survives the TV killing the launcher.
- Apps: LEANBACK_LAUNCHER apps, plus sideloaded apps that only have a LAUNCHER entry. Hide system apps with only a phone-style entry (setup leftovers), with an allowlist in `res/values/apps.xml`. New apps appear on their own (listen for package changes).
- Dock: default order from `res/values/dock.xml`; my edits are saved to a `dock.txt` (one package per line) in the app's external files dir. Edit it on the TV (hold OK or Menu on a tile: move, remove, add, replace) and from the phone remote. Skip apps that aren't installed instead of leaving a gap.
- Performance is the point. No blur, no video, no network polling. Render each tile once to an RGB_565 bitmap off the main thread. Update the clock from `ACTION_TIME_TICK`. Anything that refreshes runs only while home is resumed **and** has window focus (the screensaver covers home without pausing it). Free tile bitmaps after ~30 s with another app in front and redraw on return.
- Optional cards and features (only the ones I picked):
  - **System card:** CPU clock (average of cores), RAM (round the kernel's total up to the installed size, e.g. 1.76 → 2 GB; the firmware's video reserve counts as used), storage for the whole chip as Settings shows it (`StorageStatsManager.getTotalBytes`, decimal GB; not just `/data`), uptime, IP, Wi-Fi signal and link speed, Private DNS, USB debugging state (amber "ON :5555" / green "OFF", updated live). Many TVs block `/proc/stat`, `/proc/loadavg` and thermal zones via SELinux; detect what's readable and show the CPU clock if true load isn't. Sample every 2 s only while visible.
  - **Most used card:** top 5 apps over 7 days from `UsageStatsManager`, and the grid sorted the same way (unused apps A–Z after). Count visible time, not "foreground" time (Live TV showing HDMI barely counts as foreground). Count foreground-service time only on days the app was also visible, because always-on system services log fake whole-day times. Exclude Live TV. Grant with `appops set <pkg> GET_USAGE_STATS allow`.
  - **Now Playing card:** cover, title, artist, app, progress (or LIVE for streams), Play/Pause, Stop, Open app. Low-RAM TVs often refuse notification-listener access; if so, check `AudioManager.isMusicActive()` every few seconds and only then read `dumpsys media_session` (grant `android.permission.DUMP` over ADB). While USB debugging is on, a small helper run as the shell user via `app_process` over a loopback ADB connection can report cover art and position too.
  - **Prayer times card** (Malaysia: JAKIM e-solat `takwimsolat&period=month&zone=<ZONE>`): today's five prayers, the next one highlighted with a countdown, Hijri date beside the clock. Fetch a month once, cache it, refetch on month change or when the network returns (at most once a minute).
  - **Phone remote:** a foreground service with a tiny HTTP server (e.g. port 8486) serving one self-contained HTML page: D-pad by swipe, Back, Home, Play/Pause, volume, an app list, inputs, and a Files tab (browse/upload/download internal storage and USB drives, open a file on the TV). Pair with a 4-digit PIN shown on the TV through an overlay window (`SYSTEM_ALERT_WINDOW`), void it after 5 wrong tries, then use a token. Refuse requests whose Host isn't an IP (DNS rebinding). Canonicalise every file path and keep it inside the storage roots. Back/Home in other apps through an accessibility service that only calls `performGlobalAction` and doesn't read the screen; append it to `enabled_accessibility_services`, never replace what's there. D-pad and typing inside other apps are only possible with ADB: open a loopback ADB connection (`dadb`) to `127.0.0.1:5555` and create virtual HID devices with the `hid` command via `/dev/uhid`. Use a keypad without letter keys (so the on-screen keyboard still appears) and a separate keyboard only while typing.
  - **Notifications bell:** an amber count badge and a dropdown of the newest alerts, fed by a background service: Play Store "updates available" notifications read from `dumpsys notification --noredact` every 6 h (notification-listener access is refused on low-RAM TVs; needs DUMP and PACKAGE_USAGE_STATS), new HDMI-CEC devices (TvInputManager child inputs), Bluetooth devices connecting/disconnecting (ignore the TV remote and quick reconnects), and memory above a level. A small banner over any app for device/memory alerts and Wi-Fi changes (overlay window).
  - **Running-apps screen:** three borderless columns, the first listing launchable apps that have a process (parse `dumpsys activity lru`; only `TOP` counts as "on screen"), with memory (VmRSS from `/proc/<pid>/status` read over the loopback ADB shell) and a round ✕ to force-close (`am force-stop` over ADB; otherwise `killBackgroundProcesses`, and say it can't stop playing apps). Hold OK: hide from the list / force close / open. Never list apps with an enabled accessibility service (e.g. a button remapper).
  - **Settings page** (the gear): categories on the left, rows on the right (switches, choices that cycle on OK, pickers that open a list). One prefs-backed store with listeners so changes apply straight away: prayer zone (every JAKIM zone, from e-solat's zone list), which cards show (the column closes up), 12/24-hour clock, apps left out of most-used, each notification source and the memory level, connection banner, phone remote on/off and "forget all phones", hidden running apps, and About (version, APK size, a row that opens the TV's own Settings). Defaults must equal the old behaviour.
  - **Wallpapers:** a few photos from Wikimedia Commons *Featured pictures* (check each licence; show the credit in the list and the README), downloaded once at 1920 px and cached in app storage, not bundled in the APK. Plus every picture in a `Wallpapers` folder on internal storage (the phone remote's Files tab gets a shortcut to it). In the list, moving the focus previews a wallpaper behind Settings, OK sets it, Back restores the saved one. Decode at screen size in RGB_565, dim it (setting), and soften the dark shadows that were tuned for a black background.
- `scripts/build-and-install.sh`: build the release APK, push and `pm install -r`, then grant everything over ADB right after install (storage first: Android 10 fixes an app's storage access when its process starts; `ACCESS_FINE_LOCATION` so the Wi-Fi name is readable; usage stats; overlay; DUMP; the accessibility service), then `cmd package set-home-activity <pkg>/.MainActivity`. Several apps claim HOME on these TVs (factory test, FallbackHome), so set it explicitly. `--disable-stock` also disables the stock launcher through `disable.sh`, because while it's enabled its higher priority wins.
- Adding a permission like INTERNET to an already-installed app may not reach the network firewall on Android 10 (sockets fail with `EPERM`). If that happens, uninstall and reinstall cleanly.

Verify
- Write `scripts/key.sh` (send remote keys) and `scripts/shot.sh` (screencap to a file on the TV, pull it, shrink it). Don't screencap to stdout: vendor log noise corrupts the PNG.
- After every change: install, press keys, take a screenshot, look at it, fix, repeat. Check every remote direction from every section, an HDMI switch, opening and returning from my main apps, and a reboot.
- Write `scripts/benchmark.sh <home component> <label>` and compare against the stock launcher: PSS of all the launcher's processes idle and after navigating, CPU ticks over 60 s idle, cold start from the system's `Displayed` logcat line (3 runs), return-to-home wait time, and `dumpsys gfxinfo` janky frames and p50/p90/p99 over a fixed D-pad sequence. Put the table in the README.

Wrap up
- Deliver: `scripts/` (env, disable, restore, build-and-install, key, shot, benchmark), `backup/`, the launcher source in `launcher/`, a `shots/` folder, and a README that explains what was disabled and why, what was kept on purpose, side effects, how to re-enable one app or everything, how to change the dock, wallpaper and zone, how each feature works, and the benchmark.
- Remind me to turn USB debugging off when we're done: network ADB on port 5555 is unencrypted and anyone on my Wi-Fi can use it. Mention which launcher features need it on.
