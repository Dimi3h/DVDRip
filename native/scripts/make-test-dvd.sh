#!/usr/bin/env bash
# Authors a small unencrypted DVD-Video image for the rip tests:
#   title 1: two VOBs (6 s each, the second with its own clock, so the timestamps jump back at
#            the cell boundary), chapters at 0, 3 and 6 s
#   title 2: one 3 s VOB
# Every VOB has an AC-3 track (English) and an LPCM track (Dutch), each with its own tone per part
# so tests can tell the parts apart.
#
#   make-test-dvd.sh <output.iso> [<VIDEO_TS parent folder to keep>]
#
# Needs ffmpeg, dvdauthor and genisoimage (Debian/Ubuntu: apt install ffmpeg dvdauthor genisoimage).
set -euo pipefail

output="${1:?usage: make-test-dvd.sh <output.iso> [folder]}"
keep_folder="${2:-}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

part() { # name seconds video-source tone-ac3 tone-lpcm
    ffmpeg -hide_banner -loglevel error \
        -f lavfi -i "$3=size=720x576:rate=25:duration=$2" \
        -f lavfi -i "sine=frequency=$4:sample_rate=48000:duration=$2" \
        -f lavfi -i "sine=frequency=$5:sample_rate=48000:duration=$2" \
        -map 0:v -map 1:a -map 2:a -target pal-dvd -aspect 16:9 \
        -b:v 1500k -maxrate 2500k -bufsize 1835008 \
        -c:a:0 ac3 -b:a:0 192k -ac:a:0 2 -c:a:1 pcm_dvd -ac:a:1 2 \
        "$work/$1.mpg"
}

part part1 6 testsrc2 440 660
part part2 6 smptebars 880 990
part extra 3 rgbtestsrc 330 440

cat > "$work/dvd.xml" <<XML
<dvdauthor dest="$work/dvd">
  <vmgm />
  <titleset>
    <titles>
      <video format="pal" aspect="16:9" widescreen="nopanscan" />
      <audio lang="en" format="ac3" />
      <audio lang="nl" format="pcm" />
      <pgc>
        <vob file="$work/part1.mpg" chapters="0,0:03" />
        <vob file="$work/part2.mpg" chapters="0" />
      </pgc>
      <pgc>
        <vob file="$work/extra.mpg" />
      </pgc>
    </titles>
  </titleset>
</dvdauthor>
XML
VIDEO_FORMAT=PAL dvdauthor -x "$work/dvd.xml" > "$work/dvdauthor.log" 2>&1 || { tail -20 "$work/dvdauthor.log" >&2; exit 1; }
genisoimage -quiet -dvd-video -udf -V DISCRIP_TEST_DVD -o "$output" "$work/dvd"
if [ -n "$keep_folder" ]; then
    rm -rf "$keep_folder"
    mkdir -p "$keep_folder"
    cp -r "$work/dvd/VIDEO_TS" "$keep_folder/"
fi
echo "Test DVD written to $output"
