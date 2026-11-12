#!/usr/bin/env bash
# Plays recordings through the stage on the running emulator in place of the microphone, one
# after another, and prints each replay's log lines; the "off after" line sums up what the
# analysis heard. Stops with an error at the first recording that does not play to the end.
#
# Usage: scripts/replay.sh <recording>...
#   A .wav must be 16-bit PCM (mono or stereo). Anything else that afconvert reads (m4a from a
#   phone, for example) is converted to mono 16-bit 48 kHz WAV in build/recordings/ first.
#   File names may use only ASCII letters, digits, '.', '_' and '-', and must not start with '.'.
#
# The stage opens only as it would for a user: a debuggable build installed with the microphone
# permission (adb install -r -g), the gear in P (scripts/vconsole.sh gear P) and no call.
# Each WAV is uploaded to the app's cache/lab with adb push and checked by md5 before it plays.
# (Piping a file into `adb exec-in` cut WAVs of 8.0 and 2.9 MB to 6.8 and 2.1 MB without an
# error, 2026-10-23.) Set ANDROID_SERIAL when more than one emulator is running.
set -euo pipefail
# shellcheck source=lib/env.sh
source "$(dirname "$0")/lib/env.sh"

OUT="$REPO_ROOT/build/recordings"
DEVICE_TMP=/data/local/tmp
# perl's alarm ends the waiting logcat with SIGALRM, which the shell reports as 128 + 14.
ALARM_STATUS=142

usage() {
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
  exit 2
}

fail() { echo "replay.sh: $*" >&2; exit 1; }

# Prints the path of a 16-bit WAV for recording $1, converting it when it is not a WAV.
wav_for() {
  local src="$1" base
  base="$(basename "$src")"
  if [[ "$base" == *.wav ]]; then
    echo "$src"
    return
  fi
  command -v afconvert >/dev/null || fail "$base: afconvert (macOS) is needed to convert it; give a 16-bit WAV instead"
  mkdir -p "$OUT"
  afconvert -f WAVE -d LEI16@48000 -c 1 "$src" "$OUT/${base%.*}.wav" || fail "$base: afconvert could not convert it"
  echo "$OUT/${base%.*}.wav"
}

# Seconds of audio in WAV $1 as afinfo reports them (for example 30.378667); empty when unknown.
length_of() {
  command -v afinfo >/dev/null || return 0
  afinfo "$1" 2>/dev/null | awk '/estimated duration/ { print $3 }' || true
}

upload() {
  local wav="$1" name tmp out got want
  name="$(basename "$wav")"
  tmp="$DEVICE_TMP/carstage-$name"
  out="$("$ADB" push "$wav" "$tmp" 2>&1)" || fail "$name: adb push failed: $out"
  out="$("$ADB" shell "run-as $APP_ID --user 10 sh -c 'mkdir -p cache/lab && cp $tmp cache/lab/$name'" 2>&1)" || {
    "$ADB" shell rm -f "$tmp" >/dev/null 2>&1 || true
    fail "$name: could not copy it into the app's cache (is a debuggable build of $APP_ID installed?): $out"
  }
  "$ADB" shell rm -f "$tmp" || echo "replay.sh: could not remove $tmp from the device" >&2
  # adb shell, unlike exec-out, returns the device command's status, so a failed md5sum is caught.
  got="$("$ADB" shell run-as "$APP_ID" --user 10 md5sum "cache/lab/$name" 2>&1)" || fail "$name: could not checksum the uploaded copy: $got"
  got="${got%% *}"
  want="$(md5_of "$wav")"
  [[ "$got" =~ ^[0-9a-f]{32}$ && "$want" =~ ^[0-9a-f]{32}$ ]] || fail "$name: the md5 check did not run (device '$got', local '$want')"
  [[ "$got" == "$want" ]] || fail "$name: the uploaded copy differs (device $got, local $want)"
}

# Plays cache/lab/$1 and checks that it played to the end and the car was put back; $2 is its
# length in seconds, or empty when unknown.
play() {
  local name="$1" length="$2" wait=660 out status=0 first since run off
  [[ -z "$length" ]] || wait=$(( ${length%.*} + 60 ))
  "$ADB" logcat -c || fail "adb logcat -c failed"
  # am start exits 0 even when it fails, and warns when the activity is already on top.
  out="$("$ADB" shell am start -n "$APP_ID/.MainActivity" --es replay "$name" 2>&1)" || fail "$name: am start failed: $out"
  [[ "$out" != *Error* ]] || fail "$name: am start failed: $out"
  # The Lab answers a request with "=== replay start", or only "busy: ..." while another probe
  # runs. Waiting for this request's start first, and reading the log only from its time on,
  # keeps lines of an earlier replay or of other requests out of the result.
  first="$(perl -e 'alarm shift; exec @ARGV' 60 \
    "$ADB" logcat -s CarStageLab:I -v epoch -e '^(=== replay start|busy: )' -m 1)" || status=$?
  [[ "$status" -ne "$ALARM_STATUS" ]] || fail "$name: the app did not take the replay request within 60 s"
  [[ "$status" -eq 0 ]] || fail "adb logcat failed while waiting for the replay to start (status $status)"
  if [[ "$first" == *"busy: "* ]]; then
    echo "${first#*: }" >&2
    fail "$name: the Lab is running another probe; wait for its '=== <probe> end' line"
  fi
  # logcat may print a "--------- beginning of main" line first; the time is on the start line.
  since="$(printf '%s\n' "$first" | awk '/=== replay start/ { print $1; exit }')"
  [[ "$since" =~ ^[0-9]+\.[0-9]+$ ]] || fail "$name: could not read the start time from: $first"
  # "=== replay end" follows every accepted request, whatever the outcome.
  perl -e 'alarm shift; exec @ARGV' "$wait" \
    "$ADB" logcat -T "$since" -s CarStageLab:I -v raw -e '^=== replay end' -m 1 >/dev/null || status=$?
  run="$("$ADB" logcat -t "$since" -s CarStage:I CarStageLab:I -v raw)" || fail "adb logcat -t failed"
  run="$(printf '%s\n' "$run" | awk '!/^--------- beginning of /')"
  printf '%s\n' "$run"
  if [[ "$status" -ne 0 ]]; then
    "$ADB" logcat -d -b crash 2>/dev/null | tail -20 >&2 || true
    [[ "$status" -ne "$ALARM_STATUS" ]] || fail "$name: no '=== replay end' within $wait s; the replay may still be running in the app"
    fail "$name: adb logcat failed while waiting (status $status)"
  fi
  off="$(printf '%s\n' "$run" | sed -nE "s/^replay $name off after ([0-9.]+) s:.*/\1/p" | tail -1)"
  [[ -n "$off" && "$run" != *"closed before the recording ended"* && "$run" != *"stopped by itself"* ]] ||
    fail "$name: did not play to the end (see the lines above)"
  # The stage stays open for the length of the recording plus half a second.
  [[ -z "$length" ]] || awk -v off="$off" -v len="$length" 'BEGIN { exit !(off + 0 >= len + 0) }' ||
    fail "$name: the stage closed after $off s of $length s (see the lines above)"
  [[ "$run" != *"restored all=false"* ]] ||
    fail "$name: played to the end, but the car was not put back (see 'restore failures' above)"
}

[[ $# -ge 1 ]] || usage
state="$("$ADB" get-state 2>&1)" || fail "adb get-state: $state (start an emulator with scripts/run-emulator.sh; with several, set ANDROID_SERIAL)"
for recording in "$@"; do
  [[ -f "$recording" ]] || fail "$recording: no such file"
  [[ "$(basename "$recording")" =~ ^[A-Za-z0-9_-][A-Za-z0-9._-]*$ ]] ||
    fail "$recording: rename it with ASCII letters, digits, '.', '_' or '-', not starting with '.'"
done
for recording in "$@"; do
  wav="$(wav_for "$recording")"
  name="$(basename "$wav")"
  upload "$wav"
  length="$(length_of "$wav")"
  if [[ -n "$length" ]]; then
    printf '== %s (%.1f s)\n' "$name" "$length"
  else
    echo "== $name (length unknown, waiting up to 660 s)"
  fi
  play "$name" "$length"
done
