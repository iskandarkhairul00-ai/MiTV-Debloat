#!/bin/bash
# Usage: scripts/shot.sh name   -> shots/name.png (screencap via file; stdout carries vendor noise)
source "$(dirname "$0")/env.sh"
out="$(dirname "$0")/../shots/${1:-shot}.png"
# Run by its full path on Windows, this is a /c/... path; adb.exe needs C:/... (A turns rewriting off).
command -v cygpath >/dev/null && out="$(cygpath -m "$out")"
A shell screencap -p /sdcard/_s.png >/dev/null 2>&1
A pull /sdcard/_s.png "$out" >/dev/null && A shell rm /sdcard/_s.png
# Shrink for the docs, where sips exists (macOS). Elsewhere keep the full-size PNG.
if command -v sips >/dev/null 2>&1; then sips -Z 1280 "$out" >/dev/null 2>&1; fi
echo "$out"
