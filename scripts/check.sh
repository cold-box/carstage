#!/usr/bin/env bash
# Runs every check a change needs before review and merge, in one command. Exits 1 if any fails.
#
# Usage: scripts/check.sh [--device]
#   (default)  unit tests, debug build and lint for every module. Any lint warning fails; checks
#              that do not apply are disabled, with the reason, in the module's lint block. Also
#              compiles the app's release code, which has no Lab (src/debug), so product code that
#              reaches into the Lab fails here rather than at submission.
#   --device   then also runs scripts/doctor.sh --device on the running emulator, which installs
#              the new debug APK and runs the smoke probe. Vehicle and audio changes need this.
set -uo pipefail
# shellcheck source=lib/env.sh
source "$(dirname "$0")/lib/env.sh"

device=false
case "${1:-}" in
  "") ;;
  --device) device=true ;;
  *) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 2 ;;
esac

# JUnit XML results of every module; pass extra find tests such as "-newer <file>".
test_results() {
  find "$REPO_ROOT/app" "$REPO_ROOT/tools" -path '*/build/test-results/testDebugUnitTest/TEST-*.xml' "$@"
}

# Prints "<tests> tests, <failed> failed" from the JUnit XML results.
test_summary() {
  test_results -print0 |
    xargs -0 cat 2>/dev/null |
    awk 'match($0, /<testsuite [^>]*>/) {
           s = substr($0, RSTART, RLENGTH)
           for (k = 1; k <= 3; k++) {
             key = (k == 1) ? "tests" : (k == 2) ? "failures" : "errors"
             if (match(s, key "=\"[0-9]+\"")) { v = substr(s, RSTART + length(key) + 2, RLENGTH - length(key) - 3); n[key] += v }
           }
         }
         END { printf "%d tests, %d failed\n", n["tests"], n["failures"] + n["errors"] }'
}

# Results older than this file are from an earlier run.
started="$(mktemp)"
trap 'rm -f "$started"' EXIT

# --continue runs the other tasks after a failure, so one run shows every problem.
# The release build's lint too: it declares fewer vehicle permissions than the debug one (it does
# not drive the cabin, app/build.gradle.kts), so only it finds code that needs one it dropped.
"$REPO_ROOT/scripts/gradle.sh" --continue testDebugUnitTest assembleDebug lintDebug :app:compileReleaseKotlin :app:lintRelease
gradle_status=$?

echo
if [[ -n "$(test_results -newer "$started" -print -quit)" ]]; then
  echo "Unit tests: $(test_summary)"
elif [[ $gradle_status -ne 0 ]]; then
  # Gradle keeps old results when the test task does not run, for example after a compile error.
  echo "Unit tests: not run this time (a task they need failed; see the Gradle output above)"
else
  echo "Unit tests: unchanged since the previous run, $(test_summary)"
fi
if [[ $gradle_status -ne 0 ]]; then
  echo "FAIL  Gradle checks failed. Reports:"
  echo "      tests  app/build/reports/tests/testDebugUnitTest/index.html"
  echo "      lint   app/build/reports/lint-results-debug.html, lint-results-release.html (and tools/mediatester/build/reports/)"
  exit 1
fi
echo "OK    unit tests, debug build, lint, release compile and lint"

if $device; then
  echo
  "$REPO_ROOT/scripts/doctor.sh" --device || { echo "FAIL  scripts/doctor.sh --device"; exit 1; }
fi

echo
echo "scripts/check.sh: all checks passed."
