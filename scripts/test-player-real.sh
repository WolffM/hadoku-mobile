#!/usr/bin/env bash
# Plays a REAL watchparty episode through the instrumented player tests: pushes
# its files into the app's episodes dir (where the Library will put downloads)
# and runs EpisodePlaybackTest against them instead of the bundled fixture.
#
#   scripts/test-player-real.sh [episode dir] [play seconds]
#
# The default episode is the one the design doc measured. Needs a device or
# emulator on adb (`pnpm emulator`). Installs the APKs itself: Gradle's
# connected tests uninstall the app afterwards, which deletes pushed episodes.
set -euo pipefail
export PATH="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools:$PATH"

# The tree production serves from: /home/hadoku/srv once watchparty's deploy
# moves there (hadoku_site moved its services in 2026-10), ~/repos until then.
# Confirm with the live server's MEDIA_ROOT, not by guessing; override with
# WATCHPARTY_MEDIA_ROOT.
media=${WATCHPARTY_MEDIA_ROOT:-/home/hadoku/srv/hadoku-watchparty/media}
[ -d "$media" ] || media=$HOME/repos/hadoku-watchparty/media
src=${1:-"$media/output/fmp4/anime/Frieren/frieren-s01e01"}
echo "episode source: $src"
seconds=${2:-180}
id=$(basename "$src")
dest=/sdcard/Android/data/me.hadoku.app/files/episodes/$id

(cd "$(dirname "$0")/../android" && ./gradlew -q :app:installDebug :app:installDebugAndroidTest)

# Only the files the player reads; index.json and the reports stay behind.
adb shell mkdir -p "$dest/audio" "$dest/subs"
adb push --sync "$src/output_frag.mp4" "$dest/"
adb push --sync "$src"/audio/*.m4a "$dest/audio/"
adb push --sync "$src"/subs/*.vtt "$dest/subs/"

adb logcat -c
status=0
adb shell am instrument -w -e class me.hadoku.app.player.EpisodePlaybackTest \
  -e mediaId "$id" -e playSeconds "$seconds" \
  me.hadoku.app.test/androidx.test.runner.AndroidJUnitRunner | tee /dev/stderr | grep -q '^OK ' || status=1
adb logcat -d -s HadokuPlayerTest:I
exit $status
