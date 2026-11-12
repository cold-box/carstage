#!/usr/bin/env bash
# Copies songs into the test music app (tools/mediatester), which lists them through its
# MediaBrowserService the way Melon or the car's Bluetooth app lists theirs.
#
# Usage: scripts/media-library.sh [file...]   (default: every audio file in build/music)
#        scripts/media-library.sh --clear     (removes the copied songs; the test song stays)
#
# Needs a debug build of the test app on the device:
#   scripts/gradle.sh :mediatester:assembleDebug
#   adb install -r -g tools/mediatester/build/outputs/apk/debug/mediatester-debug.apk
# Pleos runs apps as user 10, whose storage adb cannot write, so each file goes to /data/local/tmp
# and run-as copies it into the app's files/music. Set ANDROID_SERIAL when more than one device is up.
set -euo pipefail
# shellcheck source=lib/env.sh
source "$(dirname "$0")/lib/env.sh"

TESTER_ID="com.carstage.mediatester"
DEVICE_TMP=/data/local/tmp

fail() { echo "media-library.sh: $*" >&2; exit 1; }
in_app() { "$ADB" shell "run-as $TESTER_ID --user 10 sh -c '$1'"; }

out="$(in_app 'mkdir -p files/music' 2>&1)" \
  || fail "cannot reach $TESTER_ID as user 10 (is its debug build installed?): $out"

if [[ "${1:-}" == "--clear" ]]; then
  in_app 'rm -f files/music/*'
  echo "==> cleared the test app's songs"
  exit 0
fi

files=("$@")
if [[ ${#files[@]} -eq 0 ]]; then
  shopt -s nullglob nocaseglob
  files=("$REPO_ROOT"/build/music/*.{mp3,ogg,oga,aac,m4a,wav,flac})
  shopt -u nullglob nocaseglob
  [[ ${#files[@]} -gt 0 ]] || fail "no songs in build/music; pass files or put some there"
fi

# Check every file before copying any, so a bad name does not leave half the songs copied.
for file in "${files[@]}"; do
  [[ -f "$file" ]] || fail "$file: no such file"
  name="$(basename "$file")"
  [[ "$name" =~ ^[A-Za-z0-9._-]+$ ]] || fail "$name: rename it to letters, digits, dot, dash or underscore"
done

for file in "${files[@]}"; do
  name="$(basename "$file")"
  tmp="$DEVICE_TMP/mediatester-$name"
  "$ADB" push -q "$file" "$tmp" >/dev/null || fail "$name: adb push failed"
  in_app "cp $tmp files/music/$name" || { "$ADB" shell rm -f "$tmp"; fail "$name: could not copy it into the app"; }
  "$ADB" shell rm -f "$tmp"
  # adb shell, unlike exec-out, returns the device command's status, so a failed md5sum is caught.
  got="$("$ADB" shell run-as "$TESTER_ID" --user 10 md5sum "files/music/$name" 2>&1)" || fail "$name: could not checksum the copy: $got"
  got="${got%% *}"
  want="$(md5_of "$file")"
  [[ "$got" =~ ^[0-9a-f]{32}$ && "$want" =~ ^[0-9a-f]{32}$ ]] || fail "$name: the md5 check did not run (device '$got', local '$want')"
  [[ "$got" == "$want" ]] || { in_app "rm -f files/music/$name"; fail "$name: the copy differs (device $got, local $want); removed it"; }
  echo "  $name"
done
echo "==> ${#files[@]} songs in the test app; browse it again to see them"
