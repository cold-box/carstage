#!/usr/bin/env bash
# Measures the echo canceller (app/src/main/.../audio/echo) on real songs, offline:
#   1. converts songs to 48 kHz mono WAV in build/echo/songs and makes a sung-like Korean voice
#      with macOS `say` in build/echo/voice.wav,
#   2. with --record, plays songs on the emulator through the test music app (Mac speaker -> Mac
#      mic -> emulator mic), records the emulator's microphone with the Lab's `sample` probe and
#      pulls the recordings to build/echo/real (the songs themselves are the reference),
#   3. runs EchoBenchTest, which prints one `echo bench` line per song and recording.
# Nothing under build/ is committed: the songs are only licensed for testing, the recordings hold
# the room's sound.
#
# Usage: scripts/echo-bench.sh [--record] [--seconds N] [music_dir]
#   music_dir: songs to use (default build/music); --record songs: RECORD_SONGS (default
#   "aegukga.ogg arirang.aac big-rock.mp3"), each recorded for N seconds (default 30).
# --record needs the emulator up with the host microphone on, the debug build and the test music
# app installed with the songs in its library (scripts/media-library.sh), and the Mac's volume up.
# On a shared emulator run it inside the device lock; it stops the test music app at the end.
set -euo pipefail
# shellcheck source=lib/env.sh
source "$(dirname "$0")/lib/env.sh"

fail() { echo "echo-bench.sh: $*" >&2; exit 1; }

record=0
seconds=30
music="$REPO_ROOT/build/music"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --record) record=1 ;;
    --seconds) shift; seconds="${1:?--seconds needs a number}" ;;
    -*) fail "unknown option $1" ;;
    *) music="$1" ;;
  esac
  shift
done
[[ -d "$music" ]] || fail "no music folder $music (give one, e.g. the main checkout's build/music)"
command -v afconvert >/dev/null || fail "afconvert (macOS) is needed"

out="$REPO_ROOT/build/echo"
mkdir -p "$out/songs" "$out/real"
for src in "$music"/*.{mp3,ogg,oga,aac,m4a,wav}; do
  [[ -f "$src" ]] || continue
  base="$(basename "${src%.*}")"
  [[ -f "$out/songs/$base.wav" ]] && continue
  afconvert -f WAVE -d LEI16@48000 -c 1 "$src" "$out/songs/$base.wav" || fail "afconvert could not read $src"
  echo "==> song $base"
done

if [[ ! -f "$out/voice.wav" ]]; then
  command -v say >/dev/null || fail "say (macOS) is needed for the voice"
  say -v Yuna -r 150 -o "$out/voice.aiff" \
    "동해 물과 백두산이 마르고 닳도록, 하느님이 보우하사 우리나라 만세. 아리랑 아리랑 아라리요, 아리랑 고개로 넘어간다. 나를 버리고 가시는 님은 십 리도 못 가서 발병 난다." \
    || fail "say could not make the voice"
  afconvert -f WAVE -d LEI16@48000 -c 1 "$out/voice.aiff" "$out/voice.wav"
  rm -f "$out/voice.aiff"
  echo "==> voice"
fi

if [[ "$record" == 1 ]]; then
  "$ADB" get-state >/dev/null 2>&1 || fail "no device"
  for song in ${RECORD_SONGS:-aegukga.ogg arirang.aac big-rock.mp3}; do
    base="${song%.*}"
    "$ADB" shell am start -n com.carstage.mediatester/.TesterActivity --es cmd play --es song "file:$song" >/dev/null
    sleep 3
    "$ADB" logcat -c
    "$ADB" shell am start -n com.carstage.app/.MainActivity --es probe sample --ei seconds "$seconds" >/dev/null
    "$ADB" logcat -s CarStageLab:I -e '=== sample end' -m 1 >/dev/null
    "$ADB" logcat -d -s CarStageLab:I -v raw | grep -E 'saved|RECORD_AUDIO|busy' || true
    "$ADB" exec-out run-as com.carstage.app --user 10 cat cache/lab/mic-sample.wav > "$out/real/$base.wav"
    "$ADB" shell am start -n com.carstage.mediatester/.TesterActivity --es cmd stop >/dev/null
    echo "==> recorded $base ($(wc -c < "$out/real/$base.wav") bytes)"
    sleep 2
  done
fi

"$REPO_ROOT/scripts/gradle.sh" :app:testDebugUnitTest --tests 'com.carstage.app.audio.echo.EchoBenchTest' -i \
  | grep -E 'echo bench|FAILED|skipped' || true
