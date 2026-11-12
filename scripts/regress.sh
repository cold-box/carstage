#!/usr/bin/env bash
# Runs the emulator regression scenarios on the debug build and says PASS or FAIL for each, from
# the app's log. The app's sound effects are muted for the run (DevLab, `--es effects off`), so
# nothing plays through the computer's speakers; the call scenario rings the emulator's phone,
# so it runs only when asked for.
#
# Usage: scripts/regress.sh [--install] [--call] [scenario...]
#   gleo      Gleo opens the stage, starts a duel, ends the song; closing restores the car. Gleo only
#             beeps for the new stage and the duel started now, and the MC greets and calls the turn
#   drive     gear D closes the stage and restores; gear P offers to sing on
#   recovery  force-stop with the stage open; the next start restores what it left
#   probes    the Lab's stage probe restores the car and the simulator; the drive probe allows a stage
#   night     a night (made by the test: the emulator is always day) dims the quiet stage's light
#             below the day's floor of 20% and nothing goes above 40%
#   replay    recordings/regress.m4a plays to the end through scripts/replay.sh (skipped if absent)
#   mc        with the MC's switch off a stage asks for no line and starts no voice engine; turned on
#             mid-stage the engine starts, and it turns off again
#   trip      a stage opened as the screen's button does is greeted as usual, as the emulator reads
#             no trip; with a 95-minute trip set by the test the greeting names the drive
#   background  Settings covering the open stage closes it, and coming back offers to sing on
#   call      a call closes the stage; hanging up offers to sing on (rings: only with --call or by name)
#   With no scenario, runs all but call. --install first installs the debug APK that
#   scripts/check.sh built (app/build/outputs/apk/debug/app-debug.apk) with -g.
#
# Needs a running emulator (scripts/run-emulator.sh) and a debug build with the microphone
# permission. Puts the gear in P first. Leaves the gear in P, the stage closed, effects on, the
# night and the trip following the car, the MC on, the basic flow (the app's default) and no call. Set ANDROID_SERIAL when more than one emulator is running. Exits 1 if any scenario fails.
set -uo pipefail
# shellcheck source=lib/env.sh
source "$(dirname "$0")/lib/env.sh"

CALLER=01012345678
APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
RECORDING="$REPO_ROOT/recordings/regress.m4a"
ALL=" gleo drive recovery probes night replay mc trip background"
KNOWN=" gleo drive recovery probes night replay mc trip background call "

usage() {
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
  exit 2
}

die() { echo "regress.sh: $*" >&2; exit 1; }

# The app's product and Lab lines since the log was last cleared.
log_now() {
  "$ADB" logcat -d -v raw -s CarStage:I CarStageLab:I | awk '!/^--------- beginning of /'
}

# Later expectations look only at lines logged from now on, by the device's clock that stamps
# them (as replay.sh does), so lines dropped from a full log buffer cannot shift the mark.
mark() {
  MARK="$("$ADB" shell date +%s.%N | tr -d '\r' | sed -E 's/^([0-9]+\.[0-9]{3}).*/\1/')"
  [[ "$MARK" =~ ^[0-9]+\.[0-9]{3}$ ]] || { echo "  could not read the device clock: $MARK" >&2; return 1; }
}

since_mark() {
  "$ADB" logcat -v raw -t "$MARK" -s CarStage:I CarStageLab:I | awk '!/^--------- beginning of /'
}

# Waits up to $1 s for a line after the mark that matches the extended regex $2.
expect() {
  local deadline=$((SECONDS + $1))
  while ((SECONDS <= deadline)); do
    # grep reads all of its input (no -q), so pipefail never sees the writer cut off early.
    since_mark | grep -E -- "$2" >/dev/null && return 0
    sleep 0.5
  done
  echo "  no line matching '$2' within $1 s" >&2
  return 1
}

# Fails when a line logged since the scenario began matches the extended regex $1.
refute() {
  local hit
  hit="$(log_now | grep -E -- "$1" | head -1)" || true
  [[ -z "$hit" ]] && return 0
  echo "  unexpected line: $hit" >&2
  return 1
}

# Starts the app's activity with the given extras; am start exits 0 even when it fails.
app() {
  local out
  out="$("$ADB" shell am start -n "$APP_ID/.MainActivity" "$@" 2>&1)" || { echo "  am start failed: $out" >&2; return 1; }
  [[ "$out" != *Error* ]] || { echo "  am start failed: $out" >&2; return 1; }
}

gleo() {
  local out
  out="$("$ADB" shell am start -a "$1" -p "$APP_ID" --es ACTION_PARAM '{}' 2>&1)" || { echo "  Gleo $1 failed: $out" >&2; return 1; }
  [[ "$out" != *Error* ]] || { echo "  Gleo $1 failed: $out" >&2; return 1; }
}

gear() { "$REPO_ROOT/scripts/vconsole.sh" gear "$1" >/dev/null; }

open_stage() {
  mark
  gleo OPEN_STAGE
  # The car, not only the simulator: "driving [simulator]" means the car could not be reached.
  expect 15 'stage open, driving \[.*vehicle'
}

close_stage() {
  mark
  app --es stage close
  expect 15 '^test: stage closed'
}

# Every scenario starts parked, without a call, with effects muted, the night and the trip
# following the car, the MC on and every feature (both switches' settings outlive the app), the stage closed and a clear log.
prepare() {
  gear P || die "could not put the gear in P (scripts/vconsole.sh gear P)"
  "$ADB" logcat -c || die "adb logcat -c failed"
  mark || die "could not read the device clock"
  # The scenarios open the stage and duel by Gleo, which the basic flow refuses with no music playing.
  app --es effects off --es night car --es trip car --es mc on --es features all || die "could not reach the app"
  # A release build ignores this, and its effects would play out loud: stop instead.
  expect 15 '^test: effects muted' || die "the app did not mute its effects; is a debug build of $APP_ID installed?"
  expect 5 '^test: night follows the car' || die "the app did not take the night back to the car"
  expect 5 '^test: every feature' || die "the app did not turn every feature on"
  expect 5 '^test: MC on' || die "the app did not turn the MC on"
  expect 5 '^test: trip follows the car' || die "the app did not take the trip back to the car"
  close_stage || die "could not close the stage before the scenario"
  "$ADB" logcat -c
}

scenario_gleo() {
  open_stage
  # The MC greets the new stage and calls the duel's first turn with what the car knows, so Gleo
  # only beeps for those; it reads out the rest (docs/12 "Gleo와 MC").
  expect 10 'gleo OPEN_STAGE -> SUCCESS \(beep\)'
  expect 10 '^mc: "'
  mark
  gleo START_BATTLE
  expect 10 'duel turn: turn DRIVER'
  expect 10 'gleo START_BATTLE -> SUCCESS \(beep\)'
  expect 15 '^mc: "대결을 시작합니다'
  mark
  gleo FINISH_SONG
  expect 10 'song ended by request'
  expect 10 'gleo FINISH_SONG -> SUCCESS \(voice\)'
  refute 'mc: left out, Gleo said it'
  close_stage
  expect 10 'stage closed, restored all=true'
}

scenario_drive() {
  open_stage
  mark
  gear D
  expect 10 'closing stage: safety: NOT_PARKED'
  expect 10 'stage closed, restored all='
  mark
  gear P
  expect 15 'offering to sing on after the drive'
  # What the close did not put back (the car refuses reading lights while it drives) is retried
  # once parked, on a poll of its own: wait for that run before saying nothing failed.
  if log_now | grep -E 'stage closed, restored all=false' >/dev/null; then
    expect 15 'restored the values the last stage left|could not restore'
  fi
  refute 'could not restore'
}

scenario_recovery() {
  open_stage
  # Give the stage time to change the car, which journals the original values first.
  sleep 2
  "$ADB" shell am force-stop "$APP_ID"
  mark
  # The mute ended with the process; the new one starts muted too.
  app --es effects off
  expect 20 'restored the values the last stage left'
  refute 'could not restore'
}

scenario_probes() {
  mark
  app --es probe stage
  expect 60 '^=== stage end'
  expect 1 'stage restore: PASS'
  mark
  app --es probe drive
  expect 15 '^=== drive end'
  expect 1 'stageAllowed=true'
}

# The car's ambient brightness as the Virtual Console shows it: what the app last wrote.
brightness() {
  "$REPO_ROOT/scripts/vconsole.sh" state body | grep -oE '"brightness_pct": *[0-9]+' | grep -oE '[0-9]+$'
}

scenario_night() {
  mark
  app --es night on
  expect 10 '^night by a test'
  open_stage
  # Give the first frames time to set the light. Nobody sings, so it sits at the voices' floor:
  # 20% by day, 8% at night (a loud room lifts it, but never above the night's 40%).
  sleep 1
  local levels="" level low=false
  for _ in 1 2 3 4 5 6 7 8; do
    level="$(brightness || true)"
    [[ "$level" =~ ^[0-9]+$ ]] || { echo "  could not read the ambient brightness from the Virtual Console" >&2; return 1; }
    levels+=" $level"
    ((level <= 40)) || { echo "  ambient brightness $level% at night, above 40% (so far:$levels)" >&2; return 1; }
    ((level >= 20)) || low=true
    sleep 0.25
  done
  echo "  ambient brightness at night:$levels"
  [[ $low == true ]] || { echo "  never below the day's floor of 20%: the light does not look dimmed" >&2; return 1; }
  close_stage
  expect 10 'stage closed, restored all=true'
  mark
  app --es night car
  expect 5 '^test: night follows the car'
}

scenario_replay() {
  "$REPO_ROOT/scripts/replay.sh" "$RECORDING" >"$WORK/replay.log" 2>&1 || {
    tail -15 "$WORK/replay.log" | sed 's/^/  /' >&2
    return 1
  }
  grep -E 'off after' "$WORK/replay.log" | sed 's/^/  /'
}

scenario_mc() {
  mark
  app --es mc off
  expect 5 'voice turned off'
  open_stage
  # Off, the MC cannot greet, so Gleo reads its answer out instead of only beeping.
  expect 10 'gleo OPEN_STAGE -> SUCCESS \(voice\)'
  # Gleo opened it, so the greeting is left out anyway; only a switch that works keeps it from
  # being asked for at all.
  expect 5 'mc: left out, the MC is off'
  # The engine starts in about 0.5 s on the emulator (docs/05); off, it must not start at all.
  sleep 2
  refute 'voice ready'
  mark
  app --es mc on
  expect 10 'voice ready'
  mark
  app --es mc off
  expect 5 'voice turned off'
  close_stage
  expect 10 'stage closed, restored all=true'
}

scenario_trip() {
  # Opens the way the screen's button does (the gleo scenario opens by voice).
  mark
  app --es stage open
  expect 15 'stage open, driving \[.*vehicle'
  # The emulator v2.1.0 supports no trip read (docs/05): the usual greeting, said muted.
  expect 10 '^trip: the car reports none'
  expect 10 '^mc: "'
  refute '^mc: ".*달려 오셨'
  close_stage
  expect 10 'stage closed, restored all=true'
  mark
  app --es trip 95
  expect 5 '^test: trip of 95 min'
  mark
  app --es stage open
  expect 15 '^mc: "1시간 반 넘게 달려 오셨'
  close_stage
  expect 10 'stage closed, restored all=true'
}

scenario_background() {
  open_stage
  mark
  # Pleos' app review: an app reopened from the background restores its last state (docs/05).
  "$ADB" shell am start -a android.settings.SETTINGS >/dev/null
  expect 10 'closing stage: app moved to the background'
  expect 10 'stage closed, restored all='
  mark
  app
  expect 10 'offering to sing on after the background'
  # Opening anew answers the offer.
  mark
  app --es stage open
  expect 15 'stage open, driving \[.*vehicle'
  close_stage
  expect 10 'stage closed, restored all=true'
}

scenario_call() {
  open_stage
  mark
  "$ADB" emu gsm call "$CALLER" >/dev/null
  expect 15 'closing stage: call started'
  expect 10 'stage closed, restored all=true'
  # Answered, then hung up, as checked by hand (docs/05 "실행 제안과 이어서 부르기 확인").
  "$ADB" emu gsm accept "$CALLER" >/dev/null
  mark
  "$ADB" emu gsm cancel "$CALLER" >/dev/null
  expect 15 'offering to sing on after the call'
}

# Ends a call the call scenario may have left ringing; harmless without one.
hang_up() { "$ADB" emu gsm cancel "$CALLER" >/dev/null 2>&1 || true; }

cleanup() {
  # A second Ctrl-C must not cut this short and leave gear D, a ringing phone or an open stage.
  trap '' INT TERM
  [[ "${CALLING:-false}" == true ]] && hang_up
  if [[ "${STARTED:-false}" == true ]]; then
    gear P 2>/dev/null
    # Close first and unmute after, so a cue cannot play out loud while the stage closes.
    # The MC's setting outlives the app, so it is turned on either way; with effects still muted it
    # says nothing. A forced trip makes no sound either, so it goes back to the car either way too.
    app --es mc on --es trip car --es features basic 2>/dev/null
    if mark 2>/dev/null && app --es stage close 2>/dev/null && expect 15 '^test: stage closed' 2>/dev/null; then
      app --es effects on --es night car 2>/dev/null
    else
      echo "regress.sh: could not confirm the stage closed; effects stay muted (and a forced night stays) until the app restarts" >&2
    fi
  fi
  [[ -n "${WORK:-}" ]] && rm -rf "$WORK"
}

# Scenario lists are space-separated strings: bash 3.2 (macOS) calls an empty array unbound under set -u.
install=false
with_call=false
scenarios=""
for arg in "$@"; do
  case "$arg" in
    --install) install=true ;;
    --call) with_call=true ;;
    -*) usage ;;
    *) [[ "$KNOWN" == *" $arg "* ]] || die "unknown scenario '$arg' (expected:$KNOWN)"; scenarios+=" $arg" ;;
  esac
done
[[ -n "$scenarios" ]] || scenarios="$ALL"
[[ $with_call == false || "$scenarios " == *" call "* ]] || scenarios+=" call"

state="$("$ADB" get-state 2>&1)" || die "adb get-state: $state (start an emulator with scripts/run-emulator.sh; with several, set ANDROID_SERIAL)"
if $install; then
  [[ -f "$APK" ]] || die "no $APK; build it with scripts/check.sh"
  "$ADB" install -r -g "$APK" >/dev/null || die "adb install failed"
fi
WORK="$(mktemp -d)"
trap cleanup EXIT
# Ctrl-C ends the run (bash 3.2 would otherwise go on to the next scenario) and cleanup runs.
trap 'exit 130' INT TERM
STARTED=true
"$ADB" logcat -b crash -c

passed=0
failed=""
skipped=""
for name in $scenarios; do
  echo "== $name"
  if [[ "$name" == replay && ! -f "$RECORDING" ]]; then
    if [[ -L "$RECORDING" ]]; then
      # The link is committed where the recording is, so a broken one is a failure, not a skip.
      echo "FAIL  $name: ${RECORDING#"$REPO_ROOT"/} points at $(readlink "$RECORDING"), which is missing" >&2
      failed+=" $name"
    else
      echo "SKIP  $name: no ${RECORDING#"$REPO_ROOT"/} (internal recordings are not in every copy of the repo)"
      skipped+=" $name"
    fi
    continue
  fi
  prepare
  started=$SECONDS
  [[ "$name" != call ]] || CALLING=true
  # set -e in the subshell makes the first failed step end the scenario. On bash 3.2 a bare failing
  # [[ ]] does not; assert through a function (expect, refute) or with `[[ ... ]] || return 1`.
  (set -e; "scenario_$name")
  status=$?
  [[ "$name" != call ]] || { hang_up; CALLING=false; }
  crash="$("$ADB" logcat -d -b crash 2>/dev/null | grep -A3 "Process: $APP_ID" | head -8)" || true
  if [[ $status -eq 0 && -z "$crash" ]]; then
    echo "PASS  $name ($((SECONDS - started)) s)"
    passed=$((passed + 1))
  else
    [[ -z "$crash" ]] || printf '  the app crashed:\n%s\n' "$crash" | sed 's/^/  /' >&2
    echo "FAIL  $name; the app's log for it:" >&2
    log_now | tail -25 | sed 's/^/  | /' >&2
    failed+=" $name"
    "$ADB" logcat -b crash -c
  fi
done

echo
# The number of words in $1; splitting it is the point.
# shellcheck disable=SC2086
count() { set -- $1; echo $#; }
echo "regress.sh: $passed passed, $(count "$failed") failed${failed:+ (${failed# })}, $(count "$skipped") skipped${skipped:+ (${skipped# })}"
[[ -z "$failed" ]]
