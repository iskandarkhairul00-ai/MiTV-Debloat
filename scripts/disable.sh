#!/bin/bash
# Usage: scripts/disable.sh pkg [pkg...]
# Disables each package for user 0 (never uninstalls) and logs it to backup/disabled-by-us.txt
# so restore.sh can re-enable it. One pm call per adb shell call: chained pm commands can stop early.
source "$(dirname "$0")/env.sh"
LOG="$(dirname "$0")/../backup/disabled-by-us.txt"
mkdir -p "$(dirname "$LOG")"
for p in "$@"; do
  if ! A shell pm list packages "$p" | tr -d '\r' | grep -qx "package:$p"; then echo "skip (not installed): $p"; continue; fi
  # Already disabled before we touched it (the TV's own choice, or an earlier run): leave it, and
  # don't log it, so restore.sh doesn't turn on something this project never turned off.
  if A shell pm list packages -d "$p" | tr -d '\r' | grep -qx "package:$p"; then
    echo "skip (already disabled): $p"
    continue
  fi
  out=$(A shell pm disable-user --user 0 "$p" 2>&1 | tr -d '\r')
  echo "$p -> $out"
  case "$out" in *disabled-user*) grep -qx "$p" "$LOG" 2>/dev/null || echo "$p" >> "$LOG";; esac
done
