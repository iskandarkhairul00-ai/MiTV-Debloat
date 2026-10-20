#!/bin/bash
# Usage: scripts/shot.sh name   -> shots/name.png (screencap via file; stdout carries vendor noise)
source "$(dirname "$0")/env.sh"
out="$(dirname "$0")/../shots/${1:-shot}.png"
A shell screencap -p /sdcard/_s.png >/dev/null 2>&1
A pull /sdcard/_s.png "$out" >/dev/null && A shell rm /sdcard/_s.png
sips -Z 1280 "$out" >/dev/null 2>&1; echo "$out"
