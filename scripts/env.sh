# Source me: shared settings for all scripts.
TV="${TV:-192.168.0.44:5555}"
A() { adb -s "$TV" "$@"; }
