#!/usr/bin/env bash
# Drives the Pleos Connect Virtual Console (web server inside the emulator, port 8088) from the
# command line, so tests can change vehicle state without the browser.
#
# Usage: scripts/vconsole.sh <command>
#   gear P|R|N|D            set the actual gear
#   charging on|off         EV charge switch
#   soc <0-100>             EV battery state of charge
#   set <endpoint> k=v...   any Virtual Console write, e.g. set /vehicle/body ambient_brightness=40
#   state [key]             print the console state (optionally one top-level key, e.g. ev_battery)
#
# Writes are rejected with "emulator_inactive" unless a console client is connected, so every
# call first opens a short-lived client session (the browser page keeps one alive the same way).
# Set ANDROID_SERIAL when more than one emulator is running, and CONSOLE_PORT to change the
# host port forwarded to the console (default 18088).
set -euo pipefail
# shellcheck source=lib/env.sh
source "$(dirname "$0")/lib/env.sh"

CONSOLE_PORT="${CONSOLE_PORT:-18088}"
BASE="http://127.0.0.1:$CONSOLE_PORT"

usage() {
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
  exit 2
}

connect() {
  "$ADB" forward "tcp:$CONSOLE_PORT" tcp:8088 >/dev/null
  curl --fail-with-body -sS -m 5 -X POST "$BASE/browser/session?client=carstage-cli" >/dev/null
}

# post <endpoint> k=v...: values are URL-encoded, so '#', '&' and '+' are safe.
post() {
  local endpoint="$1"; shift
  [[ "$endpoint" == /* ]] || { echo "endpoint must start with /: $endpoint" >&2; exit 2; }
  local args=()
  for pair in "$@"; do args+=(--url-query "$pair"); done
  connect
  curl --fail-with-body -sS -m 10 -X POST "${args[@]}" "$BASE$endpoint"
  echo
}

case "${1:-}" in
  gear)
    case "${2:-}" in
      P) g=Park ;; R) g=Reverse ;; N) g=Neutral ;; D) g=Drive ;;
      *) echo "usage: $0 gear P|R|N|D" >&2; exit 2 ;;
    esac
    post /vehicle/gear "gear=$g" ;;
  charging)
    case "${2:-}" in on) v=true ;; off) v=false ;; *) echo "usage: $0 charging on|off" >&2; exit 2 ;; esac
    post /vehicle/ev-battery field=charge_switch "value=$v" ;;
  soc)
    [[ "${2:-}" =~ ^[0-9]+$ && "$2" -le 100 ]] || { echo "usage: $0 soc <0-100>" >&2; exit 2; }
    post /vehicle/ev-battery field=soc "value=$2" ;;
  set)
    [[ $# -ge 3 ]] || { echo "usage: $0 set <endpoint> k=v..." >&2; exit 2; }
    shift; post "$@" ;;
  state)
    connect
    json="$(curl --fail-with-body -sS -m 10 "$BASE/vehicle/state")"
    python3 -I -c '
import json, sys
state = json.loads(sys.argv[1])
key = sys.argv[2] if len(sys.argv) > 2 else None
if key is not None and key not in state:
    sys.exit("unknown key %r; valid keys: %s" % (key, " ".join(sorted(state))))
print(json.dumps(state[key] if key else state, indent=2, ensure_ascii=False))' "$json" ${2:+"$2"} ;;
  *)
    usage ;;
esac
