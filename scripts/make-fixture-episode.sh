#!/usr/bin/env bash
# Builds a 20-second synthetic episode in watchparty's on-disk layout, for the
# instrumented player tests. Real episodes are hundreds of MB and not ours to
# publish, so CI plays this instead; `pnpm test:player-real` plays a real one.
#
# The flags are copied from a real ingest's processing_meta.json
# (hadoku-watchparty, schema v3-fragmented-audio), because the point of the
# test is that ExoPlayer accepts bytes muxed exactly the way watchparty muxes
# them for MSE. Keep them in step if that schema changes:
#   video: libx264 crf 24, yuv420p, keyint=48 no B-frames,
#          +faststart+frag_keyframe+empty_moov+default_base_moof, 2 s fragments
#   audio: one AAC file per language, +empty_moov+default_base_moof, 2 s fragments
# Two quirks of real ingests are reproduced on purpose:
#   - the languages differ in sample rate (eng 44.1 kHz, jpn 48 kHz);
#   - every audio file carries a QuickTime chapter track (hdlr 'text',
#     tref 'chap'), inherited from the source MKV's chapters.
set -euo pipefail

out=${1:-"$(dirname "$0")/../android/app/src/androidTest/assets/episodes/fixture-s01e01"}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
rm -rf "$out"
mkdir -p "$out/audio" "$out/subs"

frag=(-frag_duration 2000000 -min_frag_duration 2000000)

cat > "$work/chapters.txt" <<'EOF'
;FFMETADATA1
[CHAPTER]
TIMEBASE=1/1000
START=0
END=10000
title=Opening
[CHAPTER]
TIMEBASE=1/1000
START=10000
END=20000
title=Part A
EOF

ffmpeg -v error -f lavfi -i "testsrc2=size=320x180:rate=24000/1001:duration=20" \
  -c:v libx264 -preset medium -crf 24 -pix_fmt yuv420p \
  -x264-params keyint=48:min-keyint=48:scenecut=0:bframes=0 \
  -movflags +faststart+frag_keyframe+empty_moov+default_base_moof "${frag[@]}" \
  -metadata:s:v:0 language=und -map_metadata -1 -fflags +bitexact \
  "$out/output_frag.mp4"

# lang, sample rate, tone: distinct tones so a human listening can tell them apart.
for spec in "eng 1 44100 440" "jpn 2 48000 660"; do
  read -r lang n rate tone <<< "$spec"
  ffmpeg -v error -f lavfi -i "sine=frequency=$tone:sample_rate=$rate:duration=20" \
    -i "$work/chapters.txt" -map 0:a -map_chapters 1 -map_metadata -1 \
    -c:a aac -b:a 192k -ac 2 -metadata:s:a:0 language="$lang" \
    -movflags +empty_moov+default_base_moof "${frag[@]}" -fflags +bitexact \
    "$out/audio/audio_${lang}_${n}.m4a"
done

# Cues across the whole timeline, so a seek always lands near one. The stray
# </b> mirrors real ingests, which carry tag debris from ASS conversion.
for lang in eng ger; do
  {
    echo "WEBVTT"
    echo
    for s in 0 4 8 12 16; do
      printf '00:%02d.500 --> 00:%02d.500 line:85%% position:50%% align:center\n' "$s" "$((s + 3))"
      echo "</b>$lang cue at ${s}s"
      echo
    done
  } > "$out/subs/sub_${lang}.vtt"
done

du -sh "$out"
