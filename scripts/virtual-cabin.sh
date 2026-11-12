#!/usr/bin/env bash
# A virtual car cabin for the emulator's microphone, with no sound on this computer: what the
# device plays (another app's music) comes back into its microphone late, reflected and mixed
# with a voice file at set times, over the emulator's gRPC audio (streamAudio -> injectAudio).
# Needs an emulator started with scripts/run-emulator.sh --silent (gRPC with a token, no host mic),
# which also starts the daemon. Prepares a Python venv and the gRPC stubs in build/virtual-cabin/.
#
# Usage: scripts/virtual-cabin.sh <command> [options]   (--help after a command lists its options)
#   start                      start the daemon (quiet scene: road noise only); run-emulator.sh --silent does this
#   scene [--music on|off] [--music-gain-db 12] [--delay-ms 80] [--reflections on|off]
#         [--voice <file>|off] [--voice-gain-db 0] [--voice-at-s 5] [--voice-every-s 0]
#         [--noise-db -60|off] [--reset]
#                              change what the microphone hears; unspecified settings keep their value,
#                              the voice timing counts from this call
#   status [--json]            levels of the last second (device output, music and voice at the
#                              microphone, the microphone) and counters
#   stop [--force]             refused while the emulator runs: closing the microphone stream
#                              crashes emulator 37.2.12 once the device has recorded (docs/05)
#   voice <text> [file]        make a voice file with the macOS voice Yuna, silently
#                              (default build/virtual-cabin/voice.aiff)
#   ANDROID_SERIAL picks the emulator (default emulator-5554). The scene changes the device's
#   microphone: with other workers on the emulator, run it inside devlock.sh like other device commands.
set -euo pipefail
# shellcheck source=lib/env.sh
source "$(dirname "$0")/lib/env.sh"

DIR="$REPO_ROOT/build/virtual-cabin"
VENV="$DIR/venv"
STUBS="$DIR/gen"
PROTO_DIR="$ANDROID_HOME/emulator/lib"
# Pinned so every machine runs the same client; grpcio-tools generates the stubs.
REQUIREMENTS=(grpcio==1.85.1 grpcio-tools==1.85.1 numpy==2.5.4)

usage() {
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
  exit 2
}

[[ $# -ge 1 ]] || usage
[[ "$1" == "-h" || "$1" == "--help" ]] && usage

if [[ "$1" == "voice" ]]; then
  [[ $# -ge 2 ]] || usage
  out="${3:-$DIR/voice.aiff}"
  mkdir -p "$(dirname "$out")"
  # `say -o` writes the file and plays nothing.
  say -v Yuna -o "$out" "$2"
  echo "virtual-cabin: wrote $out ($(afinfo "$out" | awk '/estimated duration/ { print $3 " s" }'))"
  exit 0
fi

mkdir -p "$DIR"
want="${REQUIREMENTS[*]}"
if [[ ! -x "$VENV/bin/python" || "$(cat "$VENV/.requirements" 2>/dev/null)" != "$want" ]]; then
  echo "virtual-cabin: preparing $VENV" >&2
  python3 -m venv "$VENV"
  "$VENV/bin/pip" install --quiet --disable-pip-version-check "${REQUIREMENTS[@]}"
  echo "$want" > "$VENV/.requirements"
fi

# The stubs follow the emulator's own proto, regenerated whenever the emulator is updated.
proto="$PROTO_DIR/emulator_controller.proto"
[[ -f "$proto" ]] || { echo "virtual-cabin: no $proto (is the emulator installed?)" >&2; exit 1; }
stamp="$(md5_of "$proto")"
if [[ "$(cat "$STUBS/.proto" 2>/dev/null)" != "$stamp" ]]; then
  mkdir -p "$STUBS"
  "$VENV/bin/python" -m grpc_tools.protoc -I"$PROTO_DIR" --python_out="$STUBS" --grpc_python_out="$STUBS" "$proto"
  echo "$stamp" > "$STUBS/.proto"
fi

export VIRTUAL_CABIN_DIR="$DIR" VIRTUAL_CABIN_STUBS="$STUBS"
serial_args=()
[[ -n "${ANDROID_SERIAL:-}" ]] && serial_args=(--serial "$ANDROID_SERIAL")
exec "$VENV/bin/python" "$REPO_ROOT/scripts/lib/virtual_cabin.py" ${serial_args[@]+"${serial_args[@]}"} "$@"
