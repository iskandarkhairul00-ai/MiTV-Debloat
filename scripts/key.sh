#!/bin/bash
# Usage: scripts/key.sh DPAD_DOWN DPAD_DOWN ENTER ...   (one `input` call; each call costs ~4 s on this TV)
source "$(dirname "$0")/env.sh"
A shell input keyevent $(printf 'KEYCODE_%s ' "$@")
