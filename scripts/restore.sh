#!/bin/bash
# Undo everything this project changed on the TV:
#  - re-enables every package listed in backup/disabled-by-us.txt
#  - lets background-restricted apps run in the background again, turns Wi-Fi scanning back on
#  - turns Private DNS back to automatic
#  - makes the stock Android TV launcher the home screen again (and optionally removes Apple-style launcher)
# Usage: scripts/restore.sh [--remove-launcher]
source "$(dirname "$0")/env.sh"
LOG="$(dirname "$0")/../backup/disabled-by-us.txt"
adb connect "$TV" >/dev/null
if [ -f "$LOG" ]; then
  while read -r p; do
    [ -z "$p" ] && continue
    echo "$p -> $(A shell pm enable --user 0 "$p" 2>&1 | tr -d '\r')"
  done < "$LOG"
fi
# Speed tweaks: background restrictions and Wi-Fi scanning.
BG="$(dirname "$0")/../backup/bg-restricted.txt"
if [ -f "$BG" ]; then
  while read -r p; do
    [ -z "$p" ] && continue
    A shell cmd appops set "$p" RUN_ANY_IN_BACKGROUND allow
    A shell cmd appops set "$p" RUN_IN_BACKGROUND allow
    echo "$p -> background allowed"
  done < "$BG"
fi
A shell settings put global wifi_scan_always_enabled 1
# Phone remote: drop our accessibility service, keep the others (Button Mapper).
CUR="$(A shell settings get secure enabled_accessibility_services | tr -d '\r')"
NEW="$(echo "$CUR" | tr ':' '\n' | grep -v 'my.rafhan.app.tvhome/' | paste -sd: -)"
if [ "$NEW" != "$CUR" ]; then
  if [ -n "$NEW" ]; then A shell settings put secure enabled_accessibility_services "$NEW"
  else A shell settings delete secure enabled_accessibility_services; fi
fi
A shell settings put global private_dns_mode opportunistic
A shell settings delete global private_dns_specifier >/dev/null 2>&1
A shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity
if [ "$1" = "--remove-launcher" ]; then A shell pm uninstall my.rafhan.app.tvhome; fi
echo "Done. Press Home on the remote (or reboot) to return to the stock launcher."
