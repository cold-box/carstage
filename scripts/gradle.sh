#!/usr/bin/env bash
# Runs ./gradlew with a JDK that can launch it, even when no JDK is on PATH (falls back to
# Android Studio's bundled JBR). The build itself runs on the Temurin 21 daemon pinned in
# gradle/gradle-daemon-jvm.properties; Gradle downloads it on first use.
#
# Usage: scripts/gradle.sh testDebugUnitTest assembleDebug
set -euo pipefail
# shellcheck source=lib/env.sh
source "$(dirname "$0")/lib/env.sh"

JAVA_HOME="$(find_launcher_jdk)"
[[ -n "$JAVA_HOME" ]] || { echo "No JDK found. Install Android Studio (it bundles one) or a JDK 17+."; exit 1; }
export JAVA_HOME

cd "$REPO_ROOT"
exec ./gradlew "$@"
