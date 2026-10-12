#!/usr/bin/env bash
#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

# Run the instrumented tests on one device and leave the app installed (the Gradle
# connected task uninstalls it, which removes a lab client's provisioning).
#   tools/instrumented-test.sh <serial> [test class or class#method]
set -euo pipefail
serial=${1:?usage: $0 <serial> [class]}
cd "$(dirname "$0")/.."
adb="$(command -v adb || echo "${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb")"
tools/gradle-docker.sh --console=plain -q :app:assembleDebug :app:assembleDebugAndroidTest
# MIUI refuses -g (granting runtime permissions) unless "USB debugging (Security settings)" is on;
# an update keeps the permissions already granted, so install without it there.
install() { "$adb" -s "$serial" install -r -t -g "$1" >/dev/null 2>&1 || "$adb" -s "$serial" install -r -t "$1" >/dev/null; }
install app/build/outputs/apk/debug/app-debug.apk
install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# The app's own screen in front makes Compose tests find no hierarchy: start from a stopped app.
"$adb" -s "$serial" shell am force-stop org.openmcx.client
args=()
[[ $# -ge 2 ]] && args=(-e class "$2")
out=$("$adb" -s "$serial" shell am instrument -w "${args[@]}" org.openmcx.client.test/androidx.test.runner.AndroidJUnitRunner)
echo "$out" | sed -n '/^Time:/,$p;/FAILURES/,$p;/^[0-9]*) /,/^$/p' | awk '!seen[$0]++'
grep -q '^OK (' <<<"$out"
