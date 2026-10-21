#!/bin/bash
# Benchmarks one launcher on the TV. Run for each launcher, then compare.
# Usage: scripts/benchmark.sh <home component> <label>
#   e.g. scripts/benchmark.sh my.rafhan.app.tvhome/.MainActivity custom
#        scripts/benchmark.sh com.google.android.tvlauncher/.MainActivity stock
# Prints one "key=value" per line. The given launcher must be enabled; this sets it as HOME.
set -u
source "$(dirname "$0")/env.sh"
COMP="$1"; LABEL="$2"; PKG="${COMP%%/*}"
sh_() { A shell "$@" 2>/dev/null | tr -d '\r'; }

# Every process belonging to the launcher, incl. helpers it starts through ADB (shell uid).
pids() {
  sh_ "ps -A -o PID,NAME,ARGS" | awk -v p="$PKG" '
    $2==p || index($2, p":")==1 { print $1; next }
    p=="my.rafhan.app.tvhome" && (index($0,"my.rafhan.app.tvhome.MediaHelper") || index($0,"com.android.commands.hid")) { print $1 }'
}
pss_kb() { local t=0; for p in $(pids); do v=$(sh_ dumpsys meminfo "$p" | awk '/TOTAL/ {print $2; exit}'); t=$((t + ${v:-0})); done; echo $t; }
ticks() { local t=0; for p in $(pids); do v=$(sh_ cat /proc/$p/stat | awk '{print $14+$15}'); t=$((t + ${v:-0})); done; echo $t; }

sh_ cmd package set-home-activity "$COMP" >/dev/null
sh_ am force-stop my.rafhan.app.tvhome; sh_ am force-stop com.google.android.tvlauncher
sh_ input keyevent KEYCODE_HOME; sleep 40

# Cold start (process killed), 3 runs. Read from the system's "Displayed <activity>: +Nms" line:
# the stock launcher is relaunched by the system the moment it's killed, so `am start -W` says 0.
cold=()
for i in 1 2 3; do
  A logcat -c
  sh_ am force-stop "$PKG"; sleep 1
  sh_ am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null; sleep 8
  t=$(A logcat -d | tr -d '\r' | grep "Displayed $PKG/" | tail -1 | sed -E 's/.*: \+//')
  cold+=("$t"); sleep 6
done
sleep 25

# Idle on the home screen: memory and 60 s of CPU.
echo "label=$LABEL"
echo "processes=$(pids | wc -l | tr -d ' ')"
echo "pss_kb=$(pss_kb)"
mem=$(sh_ dumpsys meminfo | awk '/Free RAM:/ {gsub(/[,K]/,"",$3); print $3; exit}')
echo "system_free_ram_kb=$mem"
t0=$(ticks); c0=$(sh_ head -1 /proc/stat); sleep 60; t1=$(ticks); c1=$(sh_ head -1 /proc/stat)
echo "idle_cpu_ticks_60s=$((t1 - t0))"
echo "$c0" "$c1" | awk '{u1=0; for(i=2;i<=11;i++) u1+=$i; i1=$5+$6; u2=0; for(i=13;i<=22;i++) u2+=$i; i2=$16+$17; printf "system_cpu_busy_pct=%.1f\n", 100*(1-(i2-i1)/(u2-u1))}'
echo "cold_start_ms=${cold[*]}"

# Return to home from another app, 3 runs.
warm=()
for i in 1 2 3; do
  sh_ am start -W -n com.android.tv.settings/.MainSettings >/dev/null; sleep 4
  t=$(sh_ am start -W -a android.intent.action.MAIN -c android.intent.category.HOME | awk -F': ' '/WaitTime/ {print $2}')
  warm+=("${t:-0}"); sleep 4
done
echo "home_return_wait_ms=${warm[*]}"

# Navigation smoothness: 16 D-pad presses on the home screen.
sleep 5
sh_ dumpsys gfxinfo "$PKG" reset >/dev/null
for k in RIGHT RIGHT RIGHT RIGHT LEFT LEFT LEFT LEFT DOWN RIGHT RIGHT DOWN UP LEFT LEFT UP; do
  sh_ input keyevent KEYCODE_DPAD_$k; sleep 0.6
done
sleep 2
sh_ dumpsys gfxinfo "$PKG" | awk '
  /Total frames rendered/ {print "frames=" $4}
  /Janky frames:/ {print "janky=" $3 " " $4}
  /50th percentile/ {print "p50_ms=" $3}
  /90th percentile/ {print "p90_ms=" $3}
  /99th percentile/ {print "p99_ms=" $3}' | head -5
sh_ input keyevent KEYCODE_BACK
echo "pss_after_nav_kb=$(pss_kb)"
apk=$(sh_ pm path "$PKG" | head -1 | sed 's/package://'); echo "apk_kb=$(sh_ ls -l "$apk" | awk '{print int($5/1024)}')"
