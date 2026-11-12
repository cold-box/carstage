#!/usr/bin/env bash
# Checks that this machine matches the pinned CarStage environment. Exits 1 if anything differs.
#
# Usage: scripts/doctor.sh [--device]
#   --device  also checks the running emulator: Pleos image build, then installs the debug APK
#             and runs the smoke probe (microphone signal + vehicle gear read).
#             Set ANDROID_SERIAL (e.g. emulator-5556) when more than one emulator is running.
set -uo pipefail
# shellcheck source=lib/env.sh
source "$(dirname "$0")/lib/env.sh"

failures=0
ok() { echo "  OK    $*"; }
fail() { echo "  FAIL  $*"; failures=$((failures + 1)); }

echo "Host"
if [[ "$(uname -s)/$(uname -m)" == "Darwin/arm64" ]]; then ok "macOS on Apple Silicon"; else fail "$(uname -s)/$(uname -m): only macOS on Apple Silicon is pinned"; fi
launcher="$(find_launcher_jdk)"
if [[ -n "$launcher" ]]; then ok "Gradle launcher JDK: $launcher"; else fail "no JDK to launch Gradle: install Android Studio (bundled JBR) or a JDK 17+"; fi

echo "Android SDK ($ANDROID_HOME)"
check_package() {
  local path="$1" version="$2" marker="$5" marker_sha1="$6"
  case "$(package_state "$path" "$marker" "$marker_sha1")" in
    match)
      if [[ -f "$(package_dir "$path")/package.xml" ]]; then ok "$path $version"; else fail "$path: package.xml missing (scripts/bootstrap.sh)"; fi ;;
    missing) fail "$path: not installed (scripts/bootstrap.sh)" ;;
    mismatch) fail "$path: differs from $version in the lock (scripts/bootstrap.sh replaces it)" ;;
  esac
}
for_each_locked_package check_package

echo "AVD ($AVD_DIR)"
if [[ -f "$AVD_DIR/config.ini" && -f "$ANDROID_AVD_HOME/$AVD_NAME.ini" ]]; then
  for key in "${AVD_MANAGED_KEYS[@]}"; do
    want="$(avd_config_value "$key" "$REPO_ROOT/toolchain/avd/config.ini")"
    have="$(avd_config_value "$key" "$AVD_DIR/config.ini")"
    if [[ "$have" == "$want" ]]; then ok "$key=$have"; else fail "$key=$have, expected $want (scripts/bootstrap.sh --recreate-avd)"; fi
  done
else
  fail "$AVD_NAME not found (scripts/bootstrap.sh)"
fi

echo "Project"
sdk_dir="$(sed -n 's/^sdk\.dir=//p' "$REPO_ROOT/local.properties" 2>/dev/null)"
if [[ "$sdk_dir" == "$ANDROID_HOME" ]]; then ok "local.properties sdk.dir"; else fail "local.properties sdk.dir='$sdk_dir', expected $ANDROID_HOME (scripts/bootstrap.sh)"; fi

if [[ "${1:-}" == "--device" ]]; then
  echo "Emulator"
  # ANDROID_SERIAL picks the emulator when several are running; otherwise the first one adb lists.
  serial="${ANDROID_SERIAL:-$("$ADB" devices 2>/dev/null | awk '$1 ~ /^emulator-/ && $2 == "device" {print $1; exit}')}"
  if [[ -z "$serial" ]]; then
    fail "no running emulator (scripts/run-emulator.sh)"
  else
    adb_s() { "$ADB" -s "$serial" "$@"; }
    running_avd="$(adb_s emu avd name 2>/dev/null | head -1 | tr -d '\r')"
    if [[ "$running_avd" == "$AVD_NAME" ]]; then ok "$serial runs $AVD_NAME"; else fail "$serial runs '$running_avd', expected $AVD_NAME"; fi
    want_fp="$(sed -n 's/^ro\.system\.build\.fingerprint=//p' "$(package_dir 'system-images;android-34;pleos_car_emulator;arm64-v8a')/build.prop" 2>/dev/null)"
    have_fp="$(adb_s shell getprop ro.system.build.fingerprint | tr -d '\r')"
    if [[ -n "$want_fp" && "$have_fp" == "$want_fp" ]]; then ok "image $have_fp"; else fail "image '$have_fp', expected '$want_fp'"; fi

    apk="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
    if [[ ! -f "$apk" ]]; then
      fail "debug APK not built (scripts/gradle.sh assembleDebug)"
    elif ! adb_s install -r -g "$apk" >/dev/null; then
      fail "installing $apk failed"
    else
      ok "installed debug APK"
      adb_s logcat -c
      adb_s shell am start -n "$APP_ID/.MainActivity" --es probe smoke >/dev/null 2>&1
      # The probe takes about 2 s; give the app 30 s to start and finish.
      for _ in $(seq 30); do
        adb_s logcat -d -s CarStageLab:I -v raw | grep -q '^=== smoke end' && break
        sleep 1
      done
      smoke="$(adb_s logcat -d -s CarStageLab:I -v raw | grep -E '^\[(audio|vehicle)\]|^smoke:')"
      while IFS= read -r line; do echo "        $line"; done <<< "$smoke"
      if echo "$smoke" | grep -q '^smoke: PASS'; then
        ok "smoke probe (microphone signal, vehicle gear)"
      elif echo "$smoke" | grep -q 'mic=FAIL'; then
        fail "no host audio reaches the emulator: run 'adb emu avd hostmicon' or start with scripts/run-emulator.sh, and allow the emulator in System Settings > Privacy & Security > Microphone"
      else
        fail "smoke probe did not pass (log above)"
      fi
    fi
  fi
fi

echo
if ((failures == 0)); then echo "All checks passed."; else echo "$failures check(s) failed."; exit 1; fi
