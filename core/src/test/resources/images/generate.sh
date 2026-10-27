#!/usr/bin/env bash
# Regenerates the disc image fixtures that VolumeReaderTest reads. They come from real tools so the
# parser is checked against something other than its own idea of the formats.
# Needs genisoimage and mkudffs (Debian/Ubuntu: apt install genisoimage udftools).
set -euo pipefail
cd "$(dirname "$0")"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export LC_ALL=C.UTF-8

# UDF 1.02 + ISO 9660 bridge, laid out like a DVD-Video disc.
mkdir -p "$work/bridge/VIDEO_TS" "$work/bridge/AUDIO_TS"
printf 'DVDVIDEO-VMG' > "$work/bridge/VIDEO_TS/VIDEO_TS.IFO"
genisoimage -quiet -udf -V DISCRIP_TEST -o "$work/bridge.iso" "$work/bridge"

# ISO 9660 only, no VIDEO_TS: a data disc.
mkdir -p "$work/data/DOCS"
printf 'readme' > "$work/data/DOCS/README.TXT"
genisoimage -quiet -V DATA_DISC -o "$work/data.iso" "$work/data"

# UDF 1.02 only, with a label that needs 16-bit compressed Unicode.
dd if=/dev/zero of="$work/udf-only.img" bs=2048 count=600 status=none
mkudffs --media-type=dvd --udfrev=0x0102 --label="Amélie 漢字" --blocksize=2048 "$work/udf-only.img" > /dev/null

for image in bridge.iso data.iso udf-only.img; do
    gzip -9 -n -c "$work/$image" > "$image.gz"
done
