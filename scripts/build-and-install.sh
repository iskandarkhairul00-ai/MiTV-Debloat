#!/bin/bash
# Build the Apple TV style launcher, install it on the TV and make it the default home screen.
# Usage: scripts/build-and-install.sh [--disable-stock]
#   --disable-stock  also disables the stock Android TV launcher (logged, so restore.sh undoes it)
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
PKG=my.rafhan.app.tvhome

# Fall back to the system JDK if JAVA_HOME points somewhere stale.
[ -x "${JAVA_HOME:-}/bin/java" ] || export JAVA_HOME="$(/usr/libexec/java_home -v 17)"

adb connect "$TV" >/dev/null
(cd launcher && ./gradlew -q assembleDebug)
APK=launcher/app/build/outputs/apk/debug/app-debug.apk

# Push + pm install instead of `adb install`: on some sets adb install waits for an on-screen confirmation.
A push "$APK" /data/local/tmp/tvhome.apk >/dev/null
A shell pm install -r /data/local/tmp/tvhome.apk
A shell rm /data/local/tmp/tvhome.apk

# Phone remote Files tab. Granted straight after install, before anything restarts the launcher,
# because Android 10 sets an app's storage access when its process starts.
A shell pm grant $PKG android.permission.READ_EXTERNAL_STORAGE || true
A shell pm grant $PKG android.permission.WRITE_EXTERNAL_STORAGE || true

# Android 10 only reveals the Wi-Fi network name to apps with location permission.
A shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION || true

# Usage access, for the "Most used" card and the usage-sorted grid.
A shell appops set $PKG GET_USAGE_STATS allow

# Phone remote: the overlay permission shows the pairing PIN and lets the remote open apps while
# another app is on screen (Android 10 blocks background activity starts otherwise).
A shell appops set $PKG SYSTEM_ALERT_WINDOW allow

# Phone remote Back/Home in any app: enable our accessibility service next to whatever is
# already enabled (Button Mapper), never replacing it.
SVC="$PKG/$PKG.RemoteAccessibilityService"
CUR="$(A shell settings get secure enabled_accessibility_services | tr -d '\r')"
case "$CUR" in
  *"$SVC"*) ;;
  null|"") A shell settings put secure enabled_accessibility_services "$SVC" ;;
  *) A shell settings put secure enabled_accessibility_services "$CUR:$SVC" ;;
esac
A shell settings put secure accessibility_enabled 1

# Now Playing card: this low-RAM TV refuses notification-listener access, so without USB debugging
# the card reads `dumpsys media_session`, which needs DUMP (a development permission).
A shell pm grant $PKG android.permission.DUMP || true

# Several apps claim HOME on this TV (stock launcher, factory test, tvusage): pick ours explicitly.
A shell cmd package set-home-activity $PKG/.MainActivity

if [ "${1:-}" = "--disable-stock" ]; then
  scripts/disable.sh com.google.android.tvlauncher
fi

A shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
echo "Installed. HOME now resolves to:"
A shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1
