#!/usr/bin/env bash
# Library v1's acceptance test against production (LibraryDownloadTest):
# browse to an episode, download it, verify it, then watch it with the
# network off. Needs a signed-in emulator (`pnpm emulator:sign-in`); moves
# the whole episode (~560 MB for the default) through hadoku.me.
#
#   scripts/test-library-live.sh [catalog path under output/fmp4]
#
# Installs with -r, which keeps the app's data and so the session.
set -euo pipefail
export PATH="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools:$PATH"

path=${1:-anime/Frieren/frieren-s01e01}
(cd "$(dirname "$0")/../android" && ./gradlew -q :app:installDebug :app:installDebugAndroidTest)

adb logcat -c
status=0
adb shell am instrument -w -e class me.hadoku.app.library.LibraryDownloadTest -e path "$path" \
  me.hadoku.app.test/androidx.test.runner.AndroidJUnitRunner | tee /dev/stderr | grep -q '^OK ' || status=1
adb logcat -d -s HadokuLibraryTest:I
exit $status
