# Mi TV debloat + Apple TV style launcher

TV: **Xiaomi Mi TV MOOQ0** ("croods", MediaTek, Android TV 10 / SDK 29, 1080p), at `192.168.0.44`.
Done on 2026-10-20. Every step was checked with screenshots pulled from the TV (`shots/`).

```
MiTV-Debloat/
├── README.md
├── backup/                      state captured BEFORE any change
│   ├── disabled-by-us.txt       every package this project disabled (restore.sh reads it)
│   ├── packages-*.txt           full package lists (all, system, third-party, disabled)
│   ├── getprop.txt, settings-{global,secure,system}.txt
│   ├── home-default.txt, home-candidates.txt, accessibility.txt, private-dns-before.txt
│   └── dumpsys/                 activities/services of every manufacturer package that was checked
├── launcher/                    the launcher source (Java, AndroidX RecyclerView, minSdk 29, targetSdk 36), incl. the phone remote
├── scripts/
│   ├── build-and-install.sh     build the launcher, install it, make it the home screen
│   ├── restore.sh               undo everything
│   ├── disable.sh               disable packages (and log them so restore.sh can undo)
│   ├── key.sh / shot.sh         send remote keys / pull a screenshot (for testing)
│   └── env.sh                   TV address (override with TV=ip:5555)
└── shots/                       screenshots taken during the work
```

The scripts need ADB debugging switched on (see [Reconnecting](#reconnecting-adb)).

## What was disabled

Everything was disabled with `pm disable-user --user 0`. Nothing was uninstalled.

| Why | Packages |
|---|---|
| Telemetry | `com.miui.tv.analytics`, `com.xiaomi.statistic`, `com.google.android.feedback`, `com.google.android.tv.bugreportsender` |
| Content / ads / recommendation rows | `com.mitv.tvhome.michannel` (Mi Channel rows), `com.xm.webcontent`, `android.autoinstalls.config.xiaomi.amelie` (pushes app installs), `fusion.android.tv.demo` (store demo), `com.google.android.tvrecommendations`, `com.google.android.leanbacklauncher.recommendations` |
| Background services | `com.android.printspooler`, `com.google.android.leanbacklauncher` (old, unused launcher), `com.mitv.dream` and `com.google.android.backdrop` (unused screensavers) |
| You don't use | `com.amazon.amazonvideo.livingroom` (Prime Video), `com.google.android.videos` (Play Movies), `com.google.android.play.games`, `com.google.android.marvin.talkback`, `com.google.android.katniss` + `com.google.android.tv.assistant` (Google Assistant), `com.xiaomi.mitv.mediaexplorer` |
| Replaced | `com.google.android.tvlauncher` (stock home screen) |

**Re-enabled later:** `com.xiaomi.mitv.tvmanager` (**TV Manager**: Optimize, Memory boost, Cleaner, Deep clean, Uninstall apps). It's in the app grid. It starts at boot and uses Firebase, which is why it was first disabled.

**Kept on purpose:**
- **PatchWall** (`com.mitv.tvhome.atv`): it hosts the Input/Source popup.
- `com.mitv.videoplayer`: it holds PatchWall's data store.
- **Live TV and inputs:** `com.mediatek.wwtv.tvcenter`, `com.mediatek.tvinput`, `com.mediatek.tv.service`, `mitv.service`.
- **Remote and Bluetooth pairing:** `com.mediatek.hotkey.dispatcher` and `com.xiaomi.android.tvsetup.partnercustomizer`.
- **Keyboard, Play Store and Play Services:** Gboard, Play Store, Play Services.
- **Apps and features you ticked:** YouTube, YouTube Music, Netflix, Mi TV+, Mi Music, Chromecast, screen mirroring (MiPlay/Miracast), Xiaomi system updates, Gallery and Clock.
- **Your own installs:** every app you installed yourself was left alone.

**Side effects:**
- The remote's **Assistant/mic button** does nothing now, because Assistant is disabled.
- If your remote has a **Prime Video button**, it won't open anything.

## Re-enabling an app

**One app**, from the computer:
```bash
adb connect 192.168.0.44:5555
adb shell pm enable --user 0 com.google.android.katniss     # e.g. bring Google Assistant back
```
**One app, on the TV:** Settings → Apps → See all apps → Show system apps → pick it → Enable.

**Everything** (all disabled packages, stock launcher, DNS back to automatic):
```bash
scripts/restore.sh                    # keeps the new launcher installed but not used
scripts/restore.sh --remove-launcher  # also uninstalls it
```
Then press Home.

## Speed tweaks

- **Background-restricted apps:** tonton, iQIYI, Viu, iflix and M3U IPTV can't run services or boot tasks while you're not using them. They work normally when you open them, but the first launch is a cold start. The list is in `backup/bg-restricted.txt`, and `restore.sh` lifts the restriction.
  ```bash
  adb shell cmd appops set my.com.tonton.screen RUN_ANY_IN_BACKGROUND ignore   # restrict one more app
  adb shell cmd appops set my.com.tonton.screen RUN_ANY_IN_BACKGROUND allow    # undo for one app
  ```
- **Wi-Fi background scanning is off** (`wifi_scan_always_enabled 0`). Wi-Fi works normally. To undo: Settings → Network & Internet → Scanning always available.
- **Other suggestions, not applied:**
  - **Animations 0.5×:** Developer options → Window/Transition/Animator scale. Makes menus and app launches feel snappier.
  - **Weekly reboot:** after a few days the TV collects lost RAM and swap.
  - **Turn off Play Store auto-update.**
  - **Disable Android TV Remote Service** (`com.google.android.tv.remote.service`) if you never control the TV from your phone.
  - **Time NetSpeed** (`visnkmr.apps.timenetspeed`) has since been uninstalled.
  - **Task killer** "Background Apps & Process List" (`io.github.visnkmr.bapl`) only runs a moment at boot and when opened. It's optional to remove. Using it to kill apps tends to make the TV slower, because killed apps restart from scratch; TV Manager's Memory boost has the same effect.

## Ad-blocking DNS

Private DNS is set to AdGuard (`dns.adguard-dns.com`). It blocks ad and tracker domains in most apps. YouTube still plays and played fine after the change, but **YouTube ads are not blocked**: they come from the same servers as the videos.

To undo it on the TV, go to Settings → Network & Internet → Private DNS → Automatic. From the computer:
```bash
adb shell settings put global private_dns_mode opportunistic
```

## The launcher

- **Dock:** Netflix · RadioStream · YouTube · MovieBox · RTMKlik. The focused app's name appears large above the dock, with a glow in a colour taken from its banner.
- **Top bar:**
  - **Inputs** opens a dropdown with HDMI 1–3, AV and Live TV. It switches through the TV input framework.
  - **Wi-Fi** shows the network name and opens Wi-Fi settings.
  - **Settings** opens the TV's Settings.
- **Remote:**
  - Down from the dock slides up to all other apps (5 per row). Up/Down always move exactly one row.
  - Up from the dock goes to the top bar.
  - Ends of the top bar and rows don't wrap.
  - Back returns to the dock.
  - Home while already home jumps back to the dock.
- **Focus memory:** the last focused tile is remembered, even if the TV kills the launcher.
- **Resource use:** about 90 MB of RAM and 0% CPU when idle. There is no blur, video or network polling; the clock updates from the system's minute tick.
- **New apps:** they appear automatically, sorted A–Z.

### Date, Hijri date and prayer times

The top left shows the big clock with the date and Hijri date stacked beside it, level with the pills. Below it, the prayer card's top lines up with the system card's. It shows today's Fajr, Dhuhr, Asr, Maghrib and Isya from JAKIM e-solat. The next prayer is in gold, with a countdown in the card's header. After Isya it shows tomorrow's Fajr.

- **Fetching:** the whole month is fetched once from `esolatApi/takwimsolat&period=month&zone=SGR03` and cached on the TV. It's fetched again only when the month changes. If there's no network, it retries when the network comes back, at most once a minute. The display updates on the clock's minute tick.
- **Zone:** to use another zone, edit `prayer_zone` in `launcher/app/src/main/res/values/strings.xml` (e.g. `WLY01`, `JHR02`, see e-solat.gov.my), then run `scripts/build-and-install.sh`.
- **Permission quirk:** adding a permission like INTERNET to an already-installed app doesn't reach the network firewall on Android 10, so the app gets `EPERM` on sockets. If you ever add one, reinstall cleanly: `adb shell pm uninstall my.rafhan.app.tvhome && scripts/build-and-install.sh`.

### Now Playing

Under the System card, whenever an app has a media session (RadioStream, Spotify, music apps):
- **Shows:** cover art, title, artist, the app and its state (Playing / Paused).
- **Progress:** a progress bar with time when the track has a length; **LIVE** for streams.
- **Buttons:** **Play/Pause**, **Stop** and **Open app**. Press **Up** from the dock to reach them, Left/Right to move between them, Up again for the top bar.
- **How it reads the session:** this low-RAM TV refuses the permission apps normally use to read other apps' media sessions (notification-listener access). So:
  - **USB debugging on:** a small helper (`MediaHelper`) runs as the shell user over the launcher's loopback ADB connection and reports the session once a second. That includes cover art, track length and position. It costs about 26 MB and around 1% CPU, and only runs while the home screen is up.
  - **USB debugging off:** the card reads `dumpsys media_session` every 4 s (the `DUMP` permission, granted by `build-and-install.sh`). That gives the app, title, artist, state and elapsed time, but **no cover art or progress bar**, so the app icon stands in.
- **Apps that pause when hidden** (YouTube, Netflix) don't show up while you're on the home screen, because their session ends.

### Most used apps (last 7 days)

- **The card:** under the prayer card, it shows your top 5 apps over a rolling 7 days, each with its time and a bar, plus the total.
- **The grid:** sorted the same way, most used first. Apps you haven't used stay A–Z after them. Both refresh every time you come back to the home screen.
- **How time is counted, per day:**
  - **Visible time.** This counts watching HDMI or tuner through Live TV, which Android barely counts as "foreground".
  - **Background playback (e.g. RadioStream audio)**, but only on days the app was also on screen. On this TV a group of always-on services, such as the NetSpeedTime overlay, MiLink and Live TV's service, log identical whole-day service times that aren't real use.
- **Excluded apps:** Live TV (`com.mediatek.wwtv.tvcenter`) is left out of the ranking. It's only the screen for HDMI, AV and the antenna, so its time is input-watching time, not app use. It isn't on the card or in the total, and it stays in A–Z order in the grid. To exclude more apps, edit `usage_excluded` in `launcher/app/src/main/res/values/apps.xml` and rebuild.
- **Usage access:** granted by `scripts/build-and-install.sh` with `adb shell appops set my.rafhan.app.tvhome GET_USAGE_STATS allow`. Without it the card shows that command and the grid stays A–Z.
- **History:** Android keeps daily usage detail for about a week, which is exactly what the card needs.

### System panel

The card sits under the pills, starting at the Inputs pill. It shows:

| Row | Cells |
|---|---|
| 1 | CPU clock (average of the cores), RAM used/total, storage used/total (`/data`), uptime |
| 2 | IP address, Wi-Fi signal (dBm and link speed), Private DNS, USB debugging |

- **Bars:** RAM and storage turn amber over 80% and red over 92%.
- **USB debugging:** shows amber **ON :5555** while network ADB is open, and green **OFF** once you switch it off. It updates the moment the setting changes.
- **CPU:** this firmware blocks apps from reading true CPU load, and the TV has no temperature sensor service. `/proc/stat`, `/proc/loadavg` and the thermal zone are all denied by SELinux, and the thermal HAL is absent. So the card shows the cores' current clock instead. The `ondemand` governor raises it under load. For real CPU % use `adb shell dumpsys cpuinfo`.
- **Refresh:** CPU, RAM and uptime refresh every 2 s, only while the home screen is on screen; the card stops sampling when the app grid or another app is open. Storage refreshes every 30 s. Network and USB values only change on events.
- **Code:** the card lives in `launcher/app/src/main/java/my/rafhan/app/tvhome/SystemPanel.java`.

### Phone remote

Open **http://192.168.0.44:8486** on your phone, on the same Wi-Fi. Use **Add to Home Screen** to get an app icon.

**Pairing (once per phone):**
1. Tap **Show PIN on TV**. A 4-digit PIN appears in the middle of the TV for 60 seconds.
2. Type it on the phone. The phone keeps a token.
3. After 5 wrong tries the PIN is voided and you ask for a new one.

**Unpairing:** "Unpair this phone" at the bottom of the page.

| Tab | Does | Works |
|---|---|---|
| Remote | Swipe = D-pad, double-tap = OK | Home screen always. **In any app while USB debugging is on**; otherwise the pad dims in apps |
| Remote | Keyboard, Delete, Enter (only shown while USB debugging is on) | Type into any text field on the TV, e.g. YouTube or Netflix search (letters, numbers, symbols) |
| Remote | Back, Home | Everywhere, through the launcher's accessibility service |
| Remote | Play/Pause | Whatever is playing (Netflix, YouTube, RadioStream…) |
| Remote | Vol −, Mute, Vol + | Everywhere; shows the TV's volume bar |
| Apps | Dock, then all apps most-used first; tap to open | Everywhere |
| Inputs | HDMI 1–3, AV, Live TV | Everywhere |
| Files | Browse internal storage and USB drives. Upload from the phone (with progress), download to the phone, open a video, photo or song on the TV | Everywhere |

**How it works:**
- **No ADB, so USB debugging can be switched off.**
- **Server:** the launcher runs a small foreground service (`RemoteService`) with an HTTP server on port 8486 (`RemoteServer`). It serves `assets/remote.html`.
- **What drives each action:**
  - D-pad keys go to the launcher's own focus logic.
  - Back and Home use `RemoteAccessibilityService`, which only performs those two actions and doesn't read the screen.
  - Media keys and volume go through `AudioManager`.
  - Apps and inputs are normal intents.
- **What it can't do:** without ADB it can't press D-pad keys or type inside other apps. Use the physical remote there.

**D-pad and typing in any app (USB debugging on):**
- **How it works:** Android doesn't let an app press keys in other apps, and on this TV the ADB shell can't write to the remote's `/dev/input` devices either (SELinux). It can create a virtual USB keyboard through `/dev/uhid` with Android's own `hid` command, so the launcher (`AdbKeys`):
  1. Opens an ADB connection to itself (`127.0.0.1:5555`, via the dadb library).
  2. Registers a virtual **keypad** with arrows, Enter, Esc and Delete.
  3. Sends key reports to it, about 50 ms per press.
- **Typing:** uses a second virtual **keyboard**, which is removed after a minute idle. While it exists, Android treats the TV as having a physical keyboard and hides the on-screen keyboard. The keypad has no letter keys, so it doesn't do this.
- **First use:** the TV asks **"Allow USB debugging?"** for the launcher's own key. Tick *Always allow* and accept. The fingerprint is the launcher's key in its private storage.
- **It connects** when a paired phone opens the remote, and drops when USB debugging is turned off. Then the remote falls back to home-screen-only, and the page says so.
- **After a reboot** network ADB (port 5555) may be off again. Turn it back on to get in-app D-pad.
- **Cost:** each virtual device is a small `hid` process, about 20 MB. The keypad runs while ADB is on; the keyboard only while typing.
- **Security:** while debugging is on the launcher has ADB shell access, the same power network ADB already gives anyone on your Wi-Fi. Only paired phones can use it.

**Files tab:**
- **Uploads** stream straight to disk, so the size limit is the TV's free space. A 50 MB file takes about 4 s on Wi-Fi.
- **Duplicate names** get " (1)".
- **USB drives:** uploads may be refused, because Android 10 limits apps writing to removable drives. Browsing and downloading still work.
- **Open on TV** uses your "Always" choice if you've set one. Otherwise it picks MX Player for video and audio and X-plore for photos, so Android's "Open with" chooser never appears.
- **Access is limited** to internal storage and USB drives. Paths outside them (`/data`, `../`) are refused.

**Set up by `scripts/build-and-install.sh`:**
- Overlay permission (`appops … SYSTEM_ALERT_WINDOW allow`). It shows the PIN, and Android 10 needs it before the remote can open apps while another app is on screen.
- Storage permissions for the Files tab (`READ`/`WRITE_EXTERNAL_STORAGE`). These are granted right after install.
- The accessibility service, appended next to Button Mapper's. If the Back button says it needs the accessibility service, re-run the script or enable "Home phone remote" in Settings → Device Preferences → Accessibility.

**Security:**
- It's plain HTTP on your LAN. Only paired phones can control it.
- Requests addressed by hostname instead of IP are refused, which blocks DNS-rebinding tricks from web pages.
- If the TV's IP changes, the bookmark breaks. Reserve 192.168.0.44 for the TV in the router.

**Undo:** `scripts/restore.sh` removes the accessibility service and keeps Button Mapper's.

### Change the dock order

**No rebuild:** push a `dock.txt` with one package name per line, left to right (5 max):
```bash
cat > dock.txt <<'EOF'
com.google.android.youtube.tv
com.netflix.ninja
my.rafhan.app.radiostream
com.community.mbox.tv
my.gov.rtm.tv
EOF
adb push dock.txt /sdcard/Android/data/my.rafhan.app.tvhome/files/dock.txt
adb shell am force-stop my.rafhan.app.tvhome && adb shell input keyevent KEYCODE_HOME
```
Delete that file to go back to the built-in order.

To find a package name, run `adb shell pm list packages | grep -i <name>`. Apps you know:
- tonton: `my.com.tonton.screen`
- M3U IPTV: `de.herber_edevelopment.m3uiptv`
- Live TV: `com.mediatek.wwtv.tvcenter`
- YouTube Music: `com.google.android.youtube.tvmusic`

**Permanent:** edit `launcher/app/src/main/res/values/dock.xml`, then run `scripts/build-and-install.sh`.
The dock size and grid columns are `DOCK_SIZE` and `COLS` at the top of `MainActivity.java` (tile sizes `TILE_W`/`TILE_H`/`COL_W` next to them must fit `COLS × COL_W` within 960 dp).

### Change the wallpaper

The background is pitch black by default. To use a picture (ideally 1920×1080, JPG/PNG/WebP):
```bash
adb push mypicture.jpg /sdcard/Android/data/my.rafhan.app.tvhome/files/wallpaper.jpg
```
Open any app and press Home: the picture shows up, dimmed so text stays readable. To go back to black:
```bash
adb shell rm /sdcard/Android/data/my.rafhan.app.tvhome/files/wallpaper.jpg
```
(These files are deleted if the launcher is uninstalled.)

### Hidden system apps

Some system apps only have a phone-style launcher entry. On this TV those are setup leftovers (a Netflix helper and a "Disclaimer Customization" screen), so the grid hides them. Clock is the exception. To show another one, add its package to `launcher/app/src/main/res/values/apps.xml` and rebuild.

### Rebuild / reinstall

```bash
scripts/build-and-install.sh                 # build, install, set as home
scripts/build-and-install.sh --disable-stock # same, and disable the stock launcher again (e.g. after restore)
```
Notes on the install:
- It uses `pm install` over the shell, not `adb install`.
- It grants location permission so the Wi-Fi pill can show the network name. Android 10 requires that.
- It uses `cmd package set-home-activity`, because several apps claim HOME on this TV: the factory test, tvusage and FallbackHome.
- While the stock launcher is enabled, it always wins (its priority is 2). That's why it has to be disabled.

## Reconnecting ADB

All scripts talk to the TV over network ADB. To use them again:
1. Settings → Device Preferences → About → press OK on **Build** 7 times.
2. Settings → Device Preferences → Developer options → **USB debugging** on.
3. On the computer: `adb connect 192.168.0.44:5555`, then accept the prompt on the TV.

If the TV gets a new IP, run `TV=<new-ip>:5555 scripts/restore.sh` (works for any script).

**When you're done, switch USB debugging off again.** While it's on, anyone on your network can control the TV.
