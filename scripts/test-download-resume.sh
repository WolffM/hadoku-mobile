#!/usr/bin/env bash
# Runs the download spike: DownloadManager fetches one REAL episode file
# through https://hadoku.me with the emulator's session, is interrupted with
# airplane mode, and must finish byte-identical to the origin's copy here.
#
#   scripts/test-download-resume.sh [file in the episode] [interruptions] [episode dir]
#
# Defaults: Frieren s01e01's output_frag.mp4 (485 MB, under Cloudflare's 512 MB
# cache cap, which is where the cold-cache Range hazard lives), 2 interruptions.
# Needs a signed-in emulator (`pnpm emulator:sign-in`). Installs with -r, which
# keeps the app's data and so the session; never `pm clear` before this.
set -euo pipefail
export PATH="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools:$PATH"

file=${1:-output_frag.mp4}
interruptions=${2:-2}
src=${3:-"$HOME/repos/hadoku-watchparty/media/output/fmp4/anime/Frieren/frieren-s01e01"}
id=$(basename "$src")

size=$(stat -c %s "$src/$file")
sha=$(sha256sum "$src/$file" | cut -d' ' -f1)
echo "expecting $id/$file: $size bytes, sha256 $sha"

(cd "$(dirname "$0")/../android" && ./gradlew -q :app:installDebug :app:installDebugAndroidTest)

adb logcat -c
status=0
adb shell am instrument -w -e class me.hadoku.app.download.DownloadResumeTest \
  -e mediaId "$id" -e file "$file" -e size "$size" -e sha256 "$sha" -e interruptions "$interruptions" \
  me.hadoku.app.test/androidx.test.runner.AndroidJUnitRunner | tee /dev/stderr | grep -q '^OK ' || status=1
adb logcat -d -s HadokuDownloadTest:I
exit $status
