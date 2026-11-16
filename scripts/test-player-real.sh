#!/usr/bin/env bash
# Plays a REAL watchparty episode through the instrumented player tests
# (EpisodePlaybackTest) instead of the bundled fixture.
#
#   scripts/test-player-real.sh [media id | local episode dir] [play seconds]
#
# A media id (the default is the episode the design doc measured) is fetched
# from production into a local cache by scripts/fetch-episode.mjs, which needs
# HADOKU_E2E_KEY: run this under dev-vault (`pnpm test:player-real` does). A
# local dir must hold the episode's files with its index.json and tracks.json.
#
# The episode is pushed to /data/local/tmp, not into the app's own dir: files
# the shell creates there are not readable by the app. The test copies them in
# itself and records the episode the way a finished download is recorded.
# Needs a device or emulator on adb with ~1.5 GB free (`pnpm emulator`).
# ONLY=<test method> runs one test of the four.
set -euo pipefail
export PATH="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools:$PATH"
here=$(cd "$(dirname "$0")" && pwd)

arg=${1:-output/fmp4/anime/Frieren/frieren-s01e01}
seconds=${2:-180}
if [ -d "$arg" ]; then src=$arg; else src=$(node "$here/fetch-episode.mjs" "$arg"); fi
for f in index.json tracks.json output_frag.mp4; do
  [ -f "$src/$f" ] || { echo "no $f in $src" >&2; exit 1; }
done
echo "episode source: $src"
stage=/data/local/tmp/hadoku-episodes
dest=$stage/$(basename "$src")

(cd "$here/../android" && ./gradlew -q :app:installDebug :app:installDebugAndroidTest)

adb shell rm -rf "$stage"
adb shell mkdir -p "$dest/audio" "$dest/subs"
adb push "$src/index.json" "$src/tracks.json" "$src/output_frag.mp4" "$dest/" > /dev/null
adb push "$src"/audio/*.m4a "$dest/audio/" > /dev/null
if compgen -G "$src/subs/*.vtt" > /dev/null; then adb push "$src"/subs/*.vtt "$dest/subs/" > /dev/null; fi
trap 'adb shell rm -rf "$stage"' EXIT

adb logcat -c
status=0
adb shell am instrument -w -e class "me.hadoku.app.player.EpisodePlaybackTest${ONLY:+#$ONLY}" \
  -e source "$dest" -e playSeconds "$seconds" \
  me.hadoku.app.test/androidx.test.runner.AndroidJUnitRunner | tee /dev/stderr | grep -q '^OK ' || status=1
adb logcat -d -s HadokuPlayerTest:I
exit $status
