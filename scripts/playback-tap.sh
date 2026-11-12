#!/usr/bin/env bash
# Starts or stops the development helper that hears what the other apps play and hands it to
# CarStage (tools/playbacktap), for an emulator without the MediaProjection consent dialog
# (docs/05 "재생 소리 캡처 확인"). The helper runs as the shell user, which may capture the
# playback; a development build of CarStage connects to it on 127.0.0.1 while the stage is open.
#
# Usage: scripts/playback-tap.sh start   (builds if needed, pushes and starts the helper)
#        scripts/playback-tap.sh stop
#        scripts/playback-tap.sh status  (whether it runs, and the end of its log)
# Set ANDROID_SERIAL when more than one device is up.
set -euo pipefail
# shellcheck source=lib/env.sh
source "$(dirname "$0")/lib/env.sh"

APK="$REPO_ROOT/tools/playbacktap/build/outputs/apk/debug/playbacktap-debug.apk"
DEVICE_APK=/data/local/tmp/playbacktap.apk
DEVICE_LOG=/data/local/tmp/playbacktap.log
MAIN=com.carstage.playbacktap.Main

fail() { echo "playback-tap.sh: $*" >&2; exit 1; }
helper_pids() { "$ADB" shell "pgrep -f $MAIN" 2>/dev/null | tr -d '\r' || true; }

case "${1:-}" in
  start)
    if [[ -n "$(helper_pids)" ]]; then
      echo "==> already running"
      exit 0
    fi
    [[ -f "$APK" ]] || "$REPO_ROOT/scripts/gradle.sh" :playbacktap:assembleDebug
    "$ADB" push "$APK" "$DEVICE_APK" >/dev/null
    # setsid so the helper outlives this adb shell.
    "$ADB" shell "CLASSPATH=$DEVICE_APK setsid app_process / $MAIN > $DEVICE_LOG 2>&1 < /dev/null &"
    for _ in 1 2 3 4 5 6 7 8 9 10; do
      if "$ADB" shell "grep -q listening $DEVICE_LOG" 2>/dev/null; then
        echo "==> started: $("$ADB" shell "grep listening $DEVICE_LOG" | tr -d '\r')"
        exit 0
      fi
      sleep 0.5
    done
    "$ADB" shell "cat $DEVICE_LOG" >&2 || true
    fail "the helper did not start"
    ;;
  stop)
    pids="$(helper_pids)"
    if [[ -z "$pids" ]]; then
      echo "==> not running"
      exit 0
    fi
    # shellcheck disable=SC2086
    "$ADB" shell "kill $pids"
    echo "==> stopped"
    ;;
  status)
    if [[ -n "$(helper_pids)" ]]; then echo "==> running"; else echo "==> not running"; fi
    "$ADB" shell "tail -5 $DEVICE_LOG" 2>/dev/null || true
    ;;
  *)
    fail "usage: playback-tap.sh start|stop|status"
    ;;
esac
