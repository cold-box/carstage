#!/usr/bin/env bash
# Builds the CarStage development environment from the versions pinned in toolchain/:
# Android SDK packages and the Pleos Connect system image (toolchain/android-sdk.lock),
# the Pleos_Connect_v2.1 AVD (toolchain/avd/config.ini) and local.properties.
# Re-running is safe: packages that already match the lock are skipped. Check the result with scripts/doctor.sh.
#
# Usage: scripts/bootstrap.sh [--accept-licenses] [--recreate-avd]
#   ANDROID_HOME      SDK root (default: ~/Library/Android/sdk)
#   ANDROID_AVD_HOME  AVD folder (default: ~/.android/avd)
#   CARSTAGE_CACHE    download folder (default: ~/Library/Caches/carstage); interrupted downloads resume
#   KEEP_DOWNLOADS=1  keep zips after installing (default: delete them, the Pleos image zip is 4.7 GB)
set -euo pipefail
# shellcheck source=lib/env.sh
source "$(dirname "$0")/lib/env.sh"

accept_licenses=false
recreate_avd=false
for arg in "$@"; do
  case "$arg" in
    --accept-licenses) accept_licenses=true ;;
    --recreate-avd) recreate_avd=true ;;
    *) echo "unknown option: $arg"; exit 2 ;;
  esac
done

CACHE="${CARSTAGE_CACHE:-$HOME/Library/Caches/carstage}"

[[ "$(uname -s)/$(uname -m)" == "Darwin/arm64" ]] || {
  echo "Only macOS on Apple Silicon is pinned and verified (toolchain/android-sdk.lock)."
  exit 1
}

echo "Installing these packages means accepting their licenses:"
echo "  - Android SDK License (Google): platform-tools, emulator, build-tools, platforms"
echo "  - Pleos Connect SDK License (42dot): Pleos Connect system image"
echo "  Full texts: <license> in toolchain/package-xml/*.xml"
if ! $accept_licenses; then
  [[ -t 0 ]] || { echo "Non-interactive run: pass --accept-licenses."; exit 1; }
  read -r -p "Accept? [y/N] " answer
  [[ "$answer" == [yY]* ]] || exit 1
fi

install_package() {
  local path="$1" version="$2" zip_sha1="$3" url="$4" marker="$5" marker_sha1="$6"
  local dir state
  dir="$(package_dir "$path")"
  state="$(package_state "$path" "$marker" "$marker_sha1")"
  if [[ "$state" == match ]]; then
    [[ -f "$dir/package.xml" ]] || cp "$(package_xml_template "$path")" "$dir/package.xml"
    echo "==> $path $version: already installed"
    return
  fi
  [[ "$state" == mismatch ]] && echo "==> $path: installed version differs from the lock, replacing with $version"

  mkdir -p "$CACHE"
  local zip
  zip="$CACHE/$(basename "$url")"
  if [[ ! -f "$zip" || "$(sha1_of "$zip")" != "$zip_sha1" ]]; then
    echo "==> Downloading $path $version"
    curl -fL --retry 3 -C - -o "$zip" "$url" || curl -fL --retry 3 -o "$zip" "$url"
    local actual
    actual="$(sha1_of "$zip")"
    if [[ "$actual" != "$zip_sha1" ]]; then
      rm -f "$zip"
      echo "checksum mismatch for $url: got $actual, lock has $zip_sha1."
      echo "The publisher may have replaced the file. Update toolchain/ only after verifying the new version."
      exit 1
    fi
  fi

  echo "==> Extracting $path"
  local work="$ANDROID_HOME/.carstage-extract"
  rm -rf "$work"
  mkdir -p "$work"
  unzip -q "$zip" -d "$work"
  local inner
  inner="$(find "$work" -mindepth 1 -maxdepth 1 -type d)"
  [[ "$(echo "$inner" | wc -l | tr -d ' ')" == 1 ]] || { echo "unexpected layout in $zip"; exit 1; }
  rm -rf "$dir"
  mkdir -p "$(dirname "$dir")"
  mv "$inner" "$dir"
  rm -rf "$work"
  cp "$(package_xml_template "$path")" "$dir/package.xml"
  [[ "$(package_state "$path" "$marker" "$marker_sha1")" == match ]] || { echo "$path did not install as pinned"; exit 1; }
  [[ "${KEEP_DOWNLOADS:-0}" == 1 ]] || rm -f "$zip"
}

for_each_locked_package install_package

create_avd() {
  local image_dir
  image_dir="$ANDROID_HOME/$(avd_config_value image.sysdir.1 "$REPO_ROOT/toolchain/avd/config.ini")"
  rm -rf "$AVD_DIR"
  mkdir -p "$AVD_DIR"
  cp "$REPO_ROOT/toolchain/avd/config.ini" "$AVD_DIR/config.ini"
  # avdmanager seeds the AVD with the image's initial user data the same way.
  cp "$image_dir/userdata.img" "$AVD_DIR/userdata.img"
  cat > "$ANDROID_AVD_HOME/$AVD_NAME.ini" <<EOF
avd.ini.encoding=UTF-8
path=$AVD_DIR
path.rel=avd/$AVD_NAME.avd
target=android-34
EOF
}

avd_matches() {
  local key
  [[ -f "$AVD_DIR/config.ini" && -f "$ANDROID_AVD_HOME/$AVD_NAME.ini" ]] || return 1
  for key in "${AVD_MANAGED_KEYS[@]}"; do
    [[ "$(avd_config_value "$key" "$AVD_DIR/config.ini")" == "$(avd_config_value "$key" "$REPO_ROOT/toolchain/avd/config.ini")" ]] || return 1
  done
}

# The emulator holds multiinstance.lock open while this AVD folder is in use.
avd_running() { [[ -f "$AVD_DIR/multiinstance.lock" ]] && lsof -t "$AVD_DIR/multiinstance.lock" >/dev/null 2>&1; }

if avd_running && { $recreate_avd || ! avd_matches; }; then
  echo "$AVD_NAME is running. Close the emulator and run this again."
  exit 1
fi
if $recreate_avd || [[ ! -d "$AVD_DIR" ]]; then
  echo "==> Creating AVD $AVD_NAME"
  create_avd
elif avd_matches; then
  echo "==> AVD $AVD_NAME: already matches toolchain/avd/config.ini"
else
  echo "AVD $AVD_NAME exists but differs from toolchain/avd/config.ini."
  echo "Run again with --recreate-avd (this wipes the emulator's app data)."
  exit 1
fi

LOCAL_PROPERTIES="$REPO_ROOT/local.properties"
if [[ "$(sed -n 's/^sdk\.dir=//p' "$LOCAL_PROPERTIES" 2>/dev/null)" != "$ANDROID_HOME" ]]; then
  echo "==> Writing local.properties (sdk.dir=$ANDROID_HOME)"
  echo "sdk.dir=$ANDROID_HOME" > "$LOCAL_PROPERTIES"
fi

echo "==> Done. Next: scripts/run-emulator.sh, then scripts/doctor.sh --device"
