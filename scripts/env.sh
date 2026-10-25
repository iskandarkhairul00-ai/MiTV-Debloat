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
# On Windows the bash shells rewrite arguments that look like Unix paths into Windows ones, so a
# path on the TV such as /data/local/tmp/x.apk would reach adb as C:/Program Files/Git/data/...
# Switch that off for adb: MSYS_NO_PATHCONV for Git Bash, MSYS2_ARG_CONV_EXCL for MSYS2 (UCRT64,
# MINGW64). Local files given to adb are relative paths, which need no rewriting.
A() { MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' adb -s "$TV" "$@"; }
