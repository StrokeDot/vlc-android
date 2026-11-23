#!/usr/bin/env bash
# JVM checks for embedded subtitles and streaming conversion (HLS/fMP4), against real files.
# Needs on PATH: kotlinc (or $KOTLINC), java, ffmpeg, ffprobe, mkvmerge.
# The Android APIs CastHlsSession touches are stubbed in buildsystem/tests/jvm-stubs; VLC is stood
# in for by a stub that writes an ffmpeg-made AAC transport stream progressively.
set -euo pipefail
cd "$(dirname "$0")/.."
KOTLINC="${KOTLINC:-kotlinc}"
for tool in "$KOTLINC" java ffmpeg ffprobe mkvmerge; do command -v "$tool" >/dev/null || { echo "missing $tool"; exit 2; }; done
STDLIB="$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")/../lib/kotlin-stdlib.jar"
C=application/vlc-android/src/org/videolan/vlc/cast
OUT="$(mktemp -d)"; FX="$OUT/fx"; mkdir -p "$FX"
trap 'rm -rf "$OUT"' EXIT

# Fixtures: ffmpeg (no subtitle cues) and mkvmerge (zlib-compressed tracks, subtitle cues).
printf '1\n00:00:01,000 --> 00:00:02,500\nI <3 you & me\n\n2\n00:00:03,000 --> 00:00:04,000\n{\\an8}Top text\n\n3\n00:00:05,000 --> 00:00:06,000\n<font color="red">Red</font> and <i>italic</i>\n\n4\n00:00:07,000 --> 00:00:08,000\na --> b\nsecond line\n' > "$FX/tricky.srt"
cat > "$FX/tricky.ass" <<'ASS'
[Script Info]
ScriptType: v4.00+

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\b1}Bold{\b0}, with comma
Dialogue: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,{\p1}m 0 0 l 100 0 100 100 0 100{\p0}
Dialogue: 0,0:00:05.00,0:00:06.00,Default,,0,0,0,,Line1\N\NLine3
Dialogue: 0,0:00:07.00,0:00:08.00,Default,,0,0,0,,Sign {\p1}m 0 0 l 5 5{\p0}text
ASS
ff() { ffmpeg -hide_banner -loglevel error -y "$@"; }
ff -f lavfi -i testsrc2=size=320x180:rate=24 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 10 -c:v libx264 -g 48 -bf 2 -c:a ac3 -ac 2 "$FX/base.mkv"
ff -i "$FX/base.mkv" -i "$FX/tricky.srt" -i "$FX/tricky.ass" -map 0 -map 1 -map 2 -c copy "$FX/ff.mkv"
mkvmerge -q -o "$FX/mm.mkv" --compression 0:zlib --compression 1:zlib "$FX/base.mkv" --compression 0:zlib --track-name 0:"English SDH" --language 0:eng "$FX/tricky.srt" --compression 0:zlib --forced-display-flag 0:1 "$FX/tricky.ass"
ff -f lavfi -i testsrc2=size=640x360:rate=24000/1001 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 40 -c:v libx264 -preset veryfast -g 48 -bf 3 -c:a aac -ac 2 "$FX/h264aac.mkv"
ff -f lavfi -i testsrc2=size=640x360:rate=25 -f lavfi -i sine=frequency=330:sample_rate=44100 -t 30 -c:v libx265 -preset ultrafast -x265-params keyint=50:bframes=4:log-level=error -c:a aac -ac 2 "$FX/hevc.mkv"
mkvmerge -q -o "$FX/hevc_mm.mkv" "$FX/hevc.mkv"
ff -f lavfi -i testsrc2=size=640x360:rate=24 -f lavfi -i sine=frequency=500:sample_rate=48000 -t 40 -c:v libx264 -preset veryfast -g 48 -bf 3 -c:a eac3 -ac 6 "$FX/eac3.mkv"
ff -i "$FX/eac3.mkv" -map 0:a -c:a aac -ac 2 -ar 48000 -f mpegts "$FX/eac3_audio.ts"

echo "== subtitles"
"$KOTLINC" -nowarn $C/CastMkvReader.kt $C/CastMkvSubtitles.kt $C/CastCueText.kt $C/CastVttText.java buildsystem/tests/CastMatroskaCheck.kt -d "$OUT/sub" 2>&1 | grep -E "error" && exit 1 || true
javac -nowarn -d "$OUT/sub" $C/CastVttText.java
java -cp "$OUT/sub:$STDLIB" org.videolan.vlc.cast.CastMatroskaCheckKt "$FX"

echo "== streaming conversion"
"$KOTLINC" -nowarn buildsystem/tests/jvm-stubs/*.kt $C/CastHlsSession.kt $C/CastMkvHls.kt $C/CastMkvReader.kt $C/CastFmp4.kt $C/CastTsAac.kt \
  $C/CastDownmix.java $C/CastGrowingFile.java $C/CastFileServer.java buildsystem/tests/CastHlsServeCheck.kt -d "$OUT/hls" 2>&1 | grep -E "error" && exit 1 || true
javac -nowarn -cp "$OUT/hls" -d "$OUT/hls" $C/CastDownmix.java $C/CastGrowingFile.java $C/CastFileServer.java
for movie in h264aac hevc_mm eac3; do
  echo "-- $movie"
  java -DfakeTs="$FX/eac3_audio.ts" -cp "$OUT/hls:$STDLIB" org.videolan.vlc.cast.CastHlsServeCheckKt "$FX/$movie.mkv" "$OUT/serve-$movie"
done
echo "Cast JVM checks passed"
