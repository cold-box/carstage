# Shared settings for scripts/*.sh. Source it; do not run it.
# shellcheck shell=bash disable=SC2034

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
export ANDROID_HOME ANDROID_AVD_HOME
AVD_NAME="Pleos_Connect_v2.1"
AVD_DIR="$ANDROID_AVD_HOME/$AVD_NAME.avd"
SDK_LOCK="$REPO_ROOT/toolchain/android-sdk.lock"
ADB="$ANDROID_HOME/platform-tools/adb"
APP_ID="com.carstage.app"

# AVD settings that must match toolchain/avd/config.ini. The emulator rewrites the rest of config.ini.
AVD_MANAGED_KEYS=(image.sysdir.1 tag.id hw.device.name hw.ramSize hw.gpu.mode hw.audioInput fastboot.forceColdBoot)

# Calls `fn path version zip_sha1 url marker marker_sha1` for each package in the lock.
for_each_locked_package() {
  local fn="$1" path version zip_sha1 url marker marker_sha1
  while IFS=$'\t' read -r path version zip_sha1 url marker marker_sha1; do
    [[ -z "$path" || "$path" == \#* ]] && continue
    "$fn" "$path" "$version" "$zip_sha1" "$url" "$marker" "$marker_sha1"
  done < "$SDK_LOCK"
}

package_dir() { echo "$ANDROID_HOME/${1//;//}"; }
package_xml_template() { echo "$REPO_ROOT/toolchain/package-xml/${1//;/_}.xml"; }
sha1_of() { shasum -a 1 "$1" | cut -d' ' -f1; }
md5_of() {
  if command -v md5 >/dev/null; then md5 -q "$1"; else md5sum "$1" | cut -d' ' -f1; fi
}

# Prints "match", "missing" or "mismatch" for an installed package.
package_state() {
  local file
  file="$(package_dir "$1")/$2"
  if [[ ! -f "$file" ]]; then echo missing
  elif [[ "$(sha1_of "$file")" == "$3" ]]; then echo match
  else echo mismatch
  fi
}

# Reads a key from an AVD config.ini, tolerating the emulator's "key = value" rewrite.
avd_config_value() {
  sed -nE "s/^$(printf '%s' "$1" | sed 's/\./\\./g')[[:space:]]*=[[:space:]]*(.*)$/\1/p" "$2" | head -1
}

# Prints a JDK home that can launch the Gradle wrapper (17+). The build itself runs on the
# Temurin 21 daemon from gradle/gradle-daemon-jvm.properties, which Gradle downloads if missing.
find_launcher_jdk() {
  local candidate
  for candidate in "${JAVA_HOME:-}" \
    "$(/usr/libexec/java_home -v 17+ 2>/dev/null || true)" \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
    /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home; do
    [[ -n "$candidate" && -x "$candidate/bin/java" ]] && { echo "$candidate"; return; }
  done
  # Prints nothing when none is found; succeed anyway, so a caller under set -e reaches its own
  # "no JDK" message instead of exiting silently.
  return 0
}
