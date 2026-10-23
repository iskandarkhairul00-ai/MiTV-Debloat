# Source me: shared settings for all scripts.
# The TV's address: TV=<ip> (or <ip>:<port>) on the command line, else the one build-and-install.sh
# saved in .tv once it reached the TV. Port 5555 is assumed when none is given.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -n "${TV:-}" ]; then
  case "$TV" in *:*) ;; *) TV="$TV:5555" ;; esac
elif [ -f "$ROOT/.tv" ]; then
  TV="$(tr -d '\r\n ' < "$ROOT/.tv")"
fi
if [ -z "${TV:-}" ]; then
  echo "Which TV? Run it with the TV's IP address the first time, for example:" >&2
  echo "  TV=192.168.1.50 $0 $*" >&2
  exit 1
fi
A() { adb -s "$TV" "$@"; }
