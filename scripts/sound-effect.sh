#!/usr/bin/env bash
# Converts a CC0 recording into one of the app's sound effects (README "효과음 출처"): mono,
# two-pass EBU R128 loudness normalization (linear) to -19 LUFS with peaks under -1.5 dBTP, then
# Ogg Opus 48 kHz 96 kb/s in app/src/main/res/raw/. A recording with sharp peaks (applause) stops a
# little under the target rather than being compressed. Prints the measured result.
#
# Usage: scripts/sound-effect.sh <recording> <name>    e.g. scripts/sound-effect.sh well-done.ogg sfx_applause
#   <name> is the resource name: lowercase letters, digits and '_'. Needs ffmpeg with libopus.
set -euo pipefail
# shellcheck source=lib/env.sh
source "$(dirname "$0")/lib/env.sh"

[[ $# -eq 2 ]] || { awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 2; }
src="$1"
name="$2"
[[ "$name" =~ ^[a-z][a-z0-9_]*$ ]] || { echo "sound-effect.sh: bad resource name: $name" >&2; exit 2; }
command -v ffmpeg >/dev/null || { echo "sound-effect.sh: ffmpeg is needed (brew install ffmpeg)" >&2; exit 1; }
out="$REPO_ROOT/app/src/main/res/raw/$name.ogg"

# Downmix first, so the loudness is measured on what is kept.
TARGET="aformat=channel_layouts=mono,loudnorm=I=-19:TP=-1.5:LRA=11"
json=$(ffmpeg -hide_banner -i "$src" -af "$TARGET:print_format=json" -f null - 2>&1 | awk '/^\{/{f=1} f{print} /^\}/{f=0}')
get() { echo "$json" | python3 -c "import sys, json; print(json.load(sys.stdin)['$1'])"; }
ffmpeg -v error -y -i "$src" \
  -af "$TARGET:measured_I=$(get input_i):measured_TP=$(get input_tp):measured_LRA=$(get input_lra):measured_thresh=$(get input_thresh):offset=$(get target_offset):linear=true,aresample=48000" \
  -c:a libopus -b:a 96k "$out"
summary=$(ffmpeg -hide_banner -i "$out" -af ebur128=peak=true -f null - 2>&1 | grep -A14 Summary | grep -E '^ +(I|Peak):' | tr -s ' ' | tr '\n' ' ')
echo "$name: $(ffprobe -v error -show_entries format=duration -of csv=p=0 "$out") s,$summary"
