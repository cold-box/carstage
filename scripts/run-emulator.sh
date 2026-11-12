#!/usr/bin/env bash
# Cold-boots the Pleos AVD with the host microphone routed into it.
# Starting the AVD from Android Studio instead leaves the microphone silent until you run
# `adb emu avd hostmicon` (or Extended Controls > Microphone > "Virtual microphone uses host audio input").
#
# Usage: scripts/run-emulator.sh [--silent]   (ANDROID_HOME, ANDROID_AVD_HOME as in scripts/bootstrap.sh)
#   --silent       no host microphone: the emulator's gRPC (token auth, localhost only) is on and
#                  scripts/virtual-cabin.sh feeds the microphone (the device's own output back
#                  through a virtual cabin, plus a voice file), so nothing needs the speakers.
#                  The daemon starts with road noise only; change it with `virtual-cabin.sh scene`
#   EMULATOR_PORT  console port (default: first free of 5554, 5556, ...); the adb serial is emulator-<port>
#   GRPC_PORT      gRPC port with --silent (default: 8554 + the console port - 5554)
#   HEADLESS=1     no window and a software GPU: with the Mac's display asleep or locked the
#                  windowed emulator stopped after its Metal setup and never reached adb (docs/05)
set -euo pipefail
# shellcheck source=lib/env.sh
source "$(dirname "$0")/lib/env.sh"

silent=false
case "${1:-}" in
  --silent) silent=true ;;
  "") ;;
  *) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 2 ;;
esac

port="${EMULATOR_PORT:-}"
if [[ -z "$port" ]]; then
  for candidate in $(seq 5554 2 5584); do
    lsof -nP -iTCP:"$candidate" -sTCP:LISTEN >/dev/null 2>&1 || { port="$candidate"; break; }
  done
fi
serial="emulator-$port"

if $silent; then
  # No -allow-host-audio: the host microphone stays zeroed, so only the virtual cabin is heard.
  audio_args=(-grpc "${GRPC_PORT:-$((8554 + port - 5554))}" -grpc-use-token)
else
  audio_args=(-allow-host-audio)
fi
window_args=()
[[ "${HEADLESS:-}" == "1" ]] && window_args=(-no-window -gpu swiftshader_indirect)
"$ANDROID_HOME/emulator/emulator" -avd "$AVD_NAME" -port "$port" \
  -no-snapshot-load -no-snapshot-save -no-boot-anim "${audio_args[@]}" ${window_args[@]+"${window_args[@]}"} &

# Address the device by serial so a phone or a second emulator on adb does not break these calls.
"$ADB" -s "$serial" wait-for-device
until [[ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do sleep 2; done

if $silent; then
  # Injecting right after boot_completed crashed emulator 37.2.12 (SIGSEGV in
  # audio_forwarder_enable, docs/05); 20 s later it did not.
  sleep 20
  ANDROID_SERIAL="$serial" "$REPO_ROOT/scripts/virtual-cabin.sh" start --reset-scene
  echo "==> $AVD_NAME booted as $serial, microphone fed by scripts/virtual-cabin.sh (no host audio)"
else
  # macOS asks once whether the emulator may use the microphone; allow it.
  "$ADB" -s "$serial" emu avd hostmicon
  echo "==> $AVD_NAME booted as $serial, host microphone enabled"
fi
wait
