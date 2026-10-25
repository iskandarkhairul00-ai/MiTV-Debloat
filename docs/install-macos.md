# Install the launcher from a Mac

This guide builds the launcher on your Mac and installs it on your TV over Wi-Fi, using the scripts in this repository. It takes about 20 minutes the first time, most of it downloading tools.

Other guides: [Windows](install-windows.md) · [Linux](install-linux.md) · [with an AI agent](install-with-ai-agent.md)

## 1. Prepare the TV

The TV must run **Android TV or Google TV 10 or newer**. It was developed and tested on a Xiaomi Mi TV; other Android TV brands should work (see [TV brands](#tv-brands)).

1. **Check the Android version:** Settings → Device Preferences (or System) → About → *Android TV OS version*. It must be 10 or higher.
2. **Turn on Developer options:** on the same About screen, press OK on **Build** (Google TV: *Android TV OS build*) seven times, until it says you're a developer.
3. **Turn on debugging:** Settings → Device Preferences (or System) → Developer options → **USB debugging** on. Some TVs call it *Network debugging*.
4. **Note the TV's IP address:** Settings → Network & Internet → your Wi-Fi network (e.g. `192.168.1.50`).
5. **Keep the remote nearby.** The first time the Mac connects, the TV asks **"Allow USB debugging?"**. Tick *Always allow* and choose OK.

The Mac and the TV must be on the same network.

## 2. Install the tools

You need Git, adb, a JDK (17 or newer) and the Android SDK. With [Homebrew](https://brew.sh):

```bash
brew install git
brew install --cask android-platform-tools temurin@17 android-commandlinetools

# Tell the build where the Android SDK is (zsh is the default shell on macOS)
echo 'export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"' >> ~/.zshrc
source ~/.zshrc

# Accept the SDK licences and install the Android platform the launcher builds against
yes | sdkmanager --licenses
sdkmanager "platforms;android-36"
```

Check everything is there:

```bash
adb version && java -version && echo "$ANDROID_HOME"
```

## 3. Get the code

```bash
git clone https://github.com/nullifye/MiTV-Debloat.git
cd MiTV-Debloat
```

## 4. Connect to the TV

```bash
adb connect 192.168.1.50:5555      # use your TV's IP
```

Accept the prompt on the TV, then run `adb devices`. It should list the TV as `device` (not `unauthorized`).

> **macOS 15 or later:** if `adb connect` says *No route to host* although the TV is on, allow your terminal app in System Settings → Privacy & Security → **Local Network**, then try again.

## 5. Install the launcher

```bash
TV=192.168.1.50 scripts/build-and-install.sh
```

The script checks your tools and the TV, builds the launcher, installs it, gives it the permissions it needs and makes it the home screen. It also records the TV's own launcher so you can go back later. The IP is saved, so later runs are just `scripts/build-and-install.sh`.

Press **Home** on the remote. If the TV's own launcher still opens (Google TV does this), run:

```bash
scripts/build-and-install.sh --disable-stock
```

This disables the TV's own launcher. On Google TV it first disables Google TV's setup app too (`com.google.android.tungsten.setupwraith`), which also answers Home and would otherwise take it. Both are logged, so `restore.sh` turns them back on. The script then presses Home and tells you whether this launcher came up.

On Google TV, two things to know:
- **Wi-Fi:** if the setup app owns the saved Wi-Fi network, disabling it would delete that network, so the script leaves it on and says so. Forget the network in Settings and join it again (or use Ethernet), then run the script again.
- **Casting from your phone** may stop working after a reboot while the setup app is off. Run `scripts/restore.sh` before a factory reset.

To update the launcher later: `git pull`, then run `scripts/build-and-install.sh` again. Your settings are kept.

## 6. Undo

```bash
scripts/restore.sh                    # back to the TV's own launcher, re-enables anything disabled
scripts/restore.sh --remove-launcher  # ...and uninstalls this launcher
```

## 7. Turn off debugging when you're done

Network debugging on port 5555 isn't encrypted: anyone on your Wi-Fi could control the TV. When you're finished, turn **USB debugging** off in Developer options.

With debugging off, a few extras are limited: the phone remote can't move the cursor or type inside other apps, Now Playing shows no cover art, and the running-apps screen can't show memory or close apps that are playing. Everything else keeps working.

## TV brands

| TV | Notes |
|---|---|
| Xiaomi Mi TV / Mi Box | Developed and tested on a Mi TV. |
| Google TV (Chromecast with Google TV, newer Sony, TCL, Hisense) | Home keeps opening Google TV: use `--disable-stock`. Some only offer *Wireless debugging* (see below). |
| Other Android TV (Sony, Philips, TCL, Sharp, Nvidia Shield…) | Should work. If the TV refuses a permission, the script says so and only that feature is limited. |
| Samsung (Tizen), LG (webOS), Roku, Amazon Fire TV | Not supported: they don't run Android TV, or don't allow another home screen. |

**Wireless debugging** (some Android 11+ and Google TV sets): Developer options → Wireless debugging → *Pair device with pairing code*. On the Mac run `adb pair <ip>:<pairing port>` and type the code, then `adb connect <ip>:<port>` using the port shown on the Wireless debugging screen, and run the script with `TV=<ip>:<port>`. The port changes after the TV restarts.

## Troubleshooting

| Problem | Fix |
|---|---|
| `unauthorized` | Accept "Allow USB debugging?" on the TV. If it never appears, turn USB debugging off and on, run `adb kill-server`, and connect again. |
| `can't reach the TV` / *No route to host* | Check the IP and that both are on the same Wi-Fi. Run `adb kill-server` and try again. On macOS 15+, allow Local Network access (step 4). |
| `JDK 17 or newer is needed` | Install `temurin@17` (step 2), open a new terminal and try again. |
| `Android SDK not found` | Set `ANDROID_HOME` (step 2) and open a new terminal. |
| Build fails about licences or a missing platform | Run `yes \| sdkmanager --licenses` and `sdkmanager "platforms;android-36"`. |
| `needs Android 10` | The TV is too old for this launcher. |
| Home opens the old launcher | Run the script with `--disable-stock`. |
