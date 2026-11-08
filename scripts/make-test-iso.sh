#!/bin/bash
# Generates testdata/DISCRIP_TEST_D1.iso: a small, unencrypted PAL DVD-Video used by the
# integration tests (and handy for trying the app without a drive).
#   Title 1: 4 s "extra" (1 chapter)
#   Title 2: 18 s main feature built from two separately encoded VOBs (timestamp discontinuity),
#            3 chapters, 16:9 anamorphic, interlaced MPEG-2.
#   Audio:   #1 AC-3 2.0 Dutch, #2 LPCM 16-bit 2.0 English
#   Subs:    0x20 Dutch, 0x21 English (VobSub)
# Requires native/out/bin/{ffmpeg,dvdauthor} (native/scripts/build-native.sh and build-test-tools.sh).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN="$ROOT/native/out/bin"
OUT="$ROOT/testdata"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"

segment() { # name duration frequency
  "$BIN/ffmpeg" -hide_banner -loglevel error -y \
    -f lavfi -i "testsrc2=size=720x576:rate=25:duration=$2" \
    -f lavfi -i "sine=frequency=$3:sample_rate=48000:duration=$2" \
    -f lavfi -i "sine=frequency=$(($3 * 3 / 2)):sample_rate=48000:duration=$2" \
    -map 0:v -map 1:a -map 2:a \
    -c:v mpeg2video -b:v 1500k -maxrate 3000k -bufsize 1835k -g 12 -bf 2 -aspect 16:9 \
    -flags +ilme+ildct -pix_fmt yuv420p \
    -c:a:0 ac3 -b:a:0 192k -c:a:1 pcm_s16be -ac 2 \
    -f dvd -packetsize 2048 "$WORK/$1.raw.mpg"
}

segment extra 4 330
segment main1 10 440
segment main2 8 550

python3 "$ROOT/scripts/tools/inject_dvdsub.py" "$WORK/extra.raw.mpg" "$WORK/extra.mpg" --lpcm-id 0xa1 \
  --sub 0x20:1.0:1.5 --sub 0x21:1.2:1.5
python3 "$ROOT/scripts/tools/inject_dvdsub.py" "$WORK/main1.raw.mpg" "$WORK/main1.mpg" --lpcm-id 0xa1 \
  --sub 0x20:1.0:2.0 --sub 0x21:1.5:2.0 --sub 0x20:6.0:2.0 --sub 0x21:6.5:2.0
python3 "$ROOT/scripts/tools/inject_dvdsub.py" "$WORK/main2.raw.mpg" "$WORK/main2.mpg" --lpcm-id 0xa1 \
  --sub 0x20:2.0:2.0 --sub 0x21:4.0:2.0

cat > "$WORK/dvd.xml" <<EOF
<dvdauthor dest="$WORK/dvd" jumppad="0">
  <vmgm />
  <titleset>
    <titles>
      <video format="pal" aspect="16:9" widescreen="nopanscan" />
      <audio format="ac3" lang="nl" />
      <audio format="pcm" lang="en" />
      <subpicture lang="nl" />
      <subpicture lang="en" />
      <pgc>
        <vob file="$WORK/extra.mpg" />
      </pgc>
      <pgc>
        <vob file="$WORK/main1.mpg" chapters="0,0:05" />
        <vob file="$WORK/main2.mpg" chapters="0" />
      </pgc>
    </titles>
  </titleset>
</dvdauthor>
EOF
VIDEO_FORMAT=PAL "$BIN/dvdauthor" -x "$WORK/dvd.xml" > "$WORK/dvdauthor.log" 2>&1 || { cat "$WORK/dvdauthor.log"; exit 1; }

rm -f "$OUT/DISCRIP_TEST_D1.iso"
hdiutil makehybrid -quiet -udf -udf-version 1.02 -iso -udf-volume-name DISCRIP_TEST_D1 \
  -iso-volume-name DISCRIP_TEST_D1 -o "$OUT/DISCRIP_TEST_D1.iso" "$WORK/dvd"
ls -l "$OUT/DISCRIP_TEST_D1.iso"
