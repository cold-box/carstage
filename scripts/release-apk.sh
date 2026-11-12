#!/usr/bin/env bash
# Builds the release APK signed with the team key, and checks it before it is handed in.
#
# Usage: scripts/release-apk.sh [--out <path>]
#   --out  also copy the signed APK there, for example apk/<팀명>_CarStage_release.apk in the
#          repository that is submitted (the contest's rule: apk/팀명_앱이름_release.apk)
#
# The key is ~/.android/carstage-release.jks (CARSTAGE_RELEASE_STORE_FILE to put it elsewhere); it
# and its password are kept in 1Password, item "CarStage release signing key" in the Employee
# vault. When this Mac has no keystore yet, the script fetches it from there. `op run` resolves the
# password for one --no-daemon Gradle build, which forks a single-use daemon that exits with the
# build, and Kotlin compiles in that process, so no daemon keeps the password and it is not written
# to disk (Gradle's execution history keeps only a hash). Needs the 1Password CLI (`op`) signed in.
#
# The result must be signed by the team certificate alone (CERT_SHA256 below) and, while the
# release build does not drive the cabin (releaseDrivesCabin in app/build.gradle.kts), declare
# none of the car control permissions, which Pleos's review rejects without an agreement.
set -euo pipefail
# shellcheck source=lib/env.sh
source "$(dirname "$0")/lib/env.sh"

OP_ITEM="op://Employee/CarStage release signing key"
KEYSTORE="${CARSTAGE_RELEASE_STORE_FILE:-$HOME/.android/carstage-release.jks}"
# Gradle resolves a relative path against app/, so hand it an absolute one.
[[ "$KEYSTORE" == /* ]] || KEYSTORE="$PWD/$KEYSTORE"
GRADLE_FILE="$REPO_ROOT/app/build.gradle.kts"
# SHA-256 of the team certificate (CN=CarStage, O=CarStage, C=KR), as apksigner prints it.
CERT_SHA256="784f4fbb7e42972b187ff87057c2659e04dc946b2e2d56ac4668cb8e60b4b603"
BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
APK="$REPO_ROOT/app/build/outputs/apk/release/app-release.apk"

die() { echo "release-apk: $*" >&2; exit 1; }

out=""
while (( $# )); do
  case "$1" in
    --out)
      [[ $# -ge 2 ]] || die "--out needs a path"
      out="$2"; shift 2
      # The contest names the file apk/팀명_앱이름_release.apk.
      [[ "$out" == *_release.apk && ! -d "$out" ]] || die "--out must name a file ending in _release.apk, for example apk/<팀명>_CarStage_release.apk"
      ;;
    -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
    *) die "unknown argument: $1 (see --help)" ;;
  esac
done

command -v op >/dev/null || die "the 1Password CLI (op) is not installed: brew install 1password-cli"
[[ -x "$BUILD_TOOLS/apksigner" && -x "$BUILD_TOOLS/aapt2" ]] || die "build-tools 35.0.0 not found under $ANDROID_HOME (scripts/bootstrap.sh)"
# apksigner runs `java` from PATH; use the JDK that scripts/gradle.sh finds (Android Studio's JBR).
JDK="$(find_launcher_jdk)"
[[ -n "$JDK" ]] || die "no JDK found for apksigner: install Android Studio (it bundles one) or a JDK 17+"
export PATH="$JDK/bin:$PATH"

# Read the switch before building: true skips the permission check, anything unreadable stops.
cabin="$(sed -nE 's/^[[:space:]]*(private[[:space:]]+)?val releaseDrivesCabin([[:space:]]*:[[:space:]]*Boolean)?[[:space:]]*=[[:space:]]*(true|false)[[:space:]]*$/\3/p' "$GRADLE_FILE")"
[[ "$cabin" == true || "$cabin" == false ]] || die "cannot read releaseDrivesCabin from $GRADLE_FILE"

if [[ ! -f "$KEYSTORE" ]]; then
  echo "==> Fetching the keystore from 1Password to $KEYSTORE"
  mkdir -p "$(dirname "$KEYSTORE")"
  # Download beside it and move it in only when complete, so a broken download is not reused.
  tmp="$(mktemp "$KEYSTORE.XXXXXX")"
  trap 'rm -f "$tmp"' EXIT
  op read --force --out-file "$tmp" "$OP_ITEM/carstage-release.jks" >/dev/null \
    || die "could not read the keystore from 1Password (signed in? access to the Employee vault?)"
  [[ -s "$tmp" ]] || die "1Password returned an empty keystore"
  chmod 600 "$tmp"
  mv "$tmp" "$KEYSTORE"
fi

echo "==> Building the signed release APK (--no-daemon: a single-use daemon that exits with the build)"
rm -f "$APK"
CARSTAGE_RELEASE_STORE_FILE="$KEYSTORE" CARSTAGE_RELEASE_STORE_PASSWORD="$OP_ITEM/password" \
  op run -- "$REPO_ROOT/scripts/gradle.sh" --no-daemon -Pkotlin.compiler.execution.strategy=in-process :app:assembleRelease
[[ -f "$APK" ]] || die "Gradle did not produce $APK"

echo "==> Checking the signature"
certs="$("$BUILD_TOOLS/apksigner" verify --print-certs "$APK" 2>&1)" || die "$APK does not verify: $certs"
signers="$(grep -c 'certificate SHA-256 digest:' <<<"$certs" || true)"
[[ "$signers" == 1 ]] || die "expected one signer, found ${signers:-0}"
digest="$(awk '/certificate SHA-256 digest:/ { print $NF; exit }' <<<"$certs")"
[[ "$digest" == "$CERT_SHA256" ]] || die "signed by $digest, not by the team key ($CERT_SHA256)"

if [[ "$cabin" == false ]]; then
  echo "==> Checking that no car control permission is declared"
  perms="$("$BUILD_TOOLS/aapt2" dump permissions "$APK")"
  grep -q '^package: com.carstage.app$' <<<"$perms" || die "aapt2 did not list the permissions of $APK"
  if grep -E 'CONTROL_CAR_|CAR_LIGHTS' <<<"$perms"; then
    die "the release APK declares the car control permissions above, but releaseDrivesCabin is false"
  fi
else
  echo "==> releaseDrivesCabin is true: the car control permissions are expected, not checked"
fi

if [[ -n "$out" ]]; then
  mkdir -p "$(dirname "$out")"
  cp "$APK" "$out"
  echo "==> Copied to $out"
fi
echo "==> $APK: signed by the team key, $(du -h "$APK" | awk '{ print $1 }')"
