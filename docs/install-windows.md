# Install the launcher from Windows

This guide builds the launcher on a Windows 10 or 11 PC and installs it on your TV over Wi-Fi, using the scripts in this repository. The scripts run in **Git Bash** (installed with Git for Windows). It takes about 20–30 minutes the first time, most of it downloading tools.

Other guides: [macOS](install-macos.md) · [Linux](install-linux.md) · [with an AI agent](install-with-ai-agent.md)

## 1. Prepare the TV

The TV must run **Android TV or Google TV 10 or newer**. It was developed and tested on a Xiaomi Mi TV; other Android TV brands should work (see [TV brands](#tv-brands)).

1. **Check the Android version:** Settings → Device Preferences (or System) → About → *Android TV OS version*. It must be 10 or higher.
2. **Turn on Developer options:** on the same About screen, press OK on **Build** (Google TV: *Android TV OS build*) seven times, until it says you're a developer.
3. **Turn on debugging:** Settings → Device Preferences (or System) → Developer options → **USB debugging** on. Some TVs call it *Network debugging*.
4. **Note the TV's IP address:** Settings → Network & Internet → your Wi-Fi network (e.g. `192.168.1.50`).
5. **Keep the remote nearby.** The first time the PC connects, the TV asks **"Allow USB debugging?"**. Tick *Always allow* and choose OK.

The PC and the TV must be on the same network.

## 2. Install the tools

You need Git (with Git Bash), adb, a JDK (17 or newer) and the Android SDK.

**a. Git, Java and adb.** Open **PowerShell** and run:

```powershell
winget install --id Git.Git -e
winget install --id EclipseAdoptium.Temurin.17.JDK -e
winget install --id Google.PlatformTools -e
```

Close PowerShell and open a new one, so it picks up the new programs.

**b. Android SDK.** Download *Command line tools only* for Windows from [developer.android.com/studio](https://developer.android.com/studio#command-line-tools-only) into your Downloads folder. Then, in PowerShell:

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
New-Item -ItemType Directory -Force "$sdk\cmdline-tools" | Out-Null
Get-ChildItem "$env:USERPROFILE\Downloads\commandlinetools-win-*_latest.zip" | Select-Object -First 1 |
    Expand-Archive -DestinationPath "$sdk\cmdline-tools" -Force
Rename-Item "$sdk\cmdline-tools\cmdline-tools" "latest"

# Tell the build where the SDK is
[Environment]::SetEnvironmentVariable("ANDROID_HOME", $sdk, "User")

# Accept the SDK licences (type y for each) and install the Android platform the launcher builds against
& "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
& "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" "platforms;android-36"
```

Prefer a graphical installer? Install Android Studio (`winget install --id Google.AndroidStudio -e`), open it once, let it set up the SDK, and set `ANDROID_HOME` to the SDK folder it shows (usually `%LOCALAPPDATA%\Android\Sdk`).

**c. Check.** Open **Git Bash** from the Start menu (a new window, so it sees `ANDROID_HOME`) and run:

```bash
adb version && java -version && echo "$ANDROID_HOME"
```

From here on, run every command in **Git Bash**, not PowerShell or Command Prompt.

## 3. Get the code

In Git Bash:

```bash
cd ~
git clone https://github.com/nullifye/MiTV-Debloat.git
cd MiTV-Debloat
```

## 4. Connect to the TV

```bash
adb connect 192.168.1.50:5555      # use your TV's IP
```

If Windows Firewall asks about adb, allow it on private networks. Accept the prompt on the TV, then run `adb devices`. It should list the TV as `device` (not `unauthorized`).

## 5. Install the launcher

```bash
TV=192.168.1.50 scripts/build-and-install.sh
```

The script checks your tools and the TV, builds the launcher, installs it, gives it the permissions it needs and makes it the home screen. It also records the TV's own launcher so you can go back later. The IP is saved, so later runs are just `scripts/build-and-install.sh`. The first build is slow (Gradle downloads its own tools, and antivirus scanning slows it further); later builds are quick.

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

**Wireless debugging** (some Android 11+ and Google TV sets): Developer options → Wireless debugging → *Pair device with pairing code*. Run `adb pair <ip>:<pairing port>` and type the code, then `adb connect <ip>:<port>` using the port shown on the Wireless debugging screen, and run the script with `TV=<ip>:<port>`. The port changes after the TV restarts.

## Troubleshooting

| Problem | Fix |
|---|---|
| `adb: command not found` (or `java`) in Git Bash | Close Git Bash and open a new window after installing. If it still fails, restart Windows. |
| `bad interpreter: /bin/bash^M` | Windows line endings got into the scripts. Run `git config --global core.autocrlf input`, then delete the folder and clone again. |
| `adb: error: failed to copy ... to 'C:/Program Files/Git/data/local/tmp/...'` | An older copy of the scripts: the shell turned the TV's path into a Windows one. Run `git pull` in the `MiTV-Debloat` folder and run the script again. Git Bash and MSYS2 (UCRT64/MINGW64) both work now. |
| `unauthorized` | Accept "Allow USB debugging?" on the TV. If it never appears, turn USB debugging off and on, run `adb kill-server`, and connect again. |
| `can't reach the TV` / *No route to host* | Check the IP and that both are on the same Wi-Fi. Run `adb kill-server` and try again. |
| `JDK 17 or newer is needed` | Install Temurin 17 (step 2a), then open a new Git Bash. |
| `Android SDK not found` | Set `ANDROID_HOME` (step 2b), then open a new Git Bash. |
| Build fails about licences or a missing platform | In PowerShell, run the two `sdkmanager.bat` lines from step 2b again. |
| `needs Android 10` | The TV is too old for this launcher. |
| Home opens the old launcher | Run the script with `--disable-stock`. |
