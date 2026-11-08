#!/usr/bin/env python3
"""Post-processes an MPEG-2 program stream written by `ffmpeg -f dvd -packetsize 2048` for the
DiscRip test disc:

* renumbers the LPCM substream id so that every DVD audio stream has its own number
  (ffmpeg numbers AC-3 and LPCM independently, DVD-Video uses one shared number space), and
* inserts simple VobSub subpicture units (a framed box) for one or more subtitle streams, and
* optionally removes the timestamps from some video PES packets (--strip-video-pts N: every Nth
  video PES that has one, starting with the second), as on real discs where not every frame carries a PTS.

Usage: inject_dvdsub.py IN OUT --lpcm-id 0xa1 --sub 0x20:1.0:2.5 --sub 0x21:4.0:2.0 ...
       (--sub STREAMID:START_SECONDS:DURATION_SECONDS, times relative to the first video PTS)
"""
import argparse
import struct
import sys

PACK = 2048


def read_pts(b, i):
    return (((b[i] >> 1) & 0x07) << 30) | (b[i + 1] << 22) | ((b[i + 2] >> 1) << 15) | (b[i + 3] << 7) | (b[i + 4] >> 1)


def write_pts(pts, marker=0x21):
    return bytes([
        marker | (((pts >> 30) & 0x07) << 1),
        (pts >> 22) & 0xFF,
        (((pts >> 15) & 0x7F) << 1) | 1,
        (pts >> 7) & 0xFF,
        ((pts & 0x7F) << 1) | 1,
    ])


def read_scr(b):
    # MPEG-2 pack header SCR base (33 bits)
    return (((b[4] >> 3) & 0x07) << 30) | ((b[4] & 0x03) << 28) | (b[5] << 20) | (((b[6] >> 3) & 0x1F) << 15) | \
        ((b[6] & 0x03) << 13) | (b[7] << 5) | (b[8] >> 3)


def rle_line(pixels):
    """DVD subpicture RLE for one line (2-bit pixels). Returns a list of nibbles."""
    nib = []
    i = 0
    n = len(pixels)
    while i < n:
        color = pixels[i]
        run = 1
        while i + run < n and pixels[i + run] == color and run < 255:
            run += 1
        if i + run == n and run > 63:
            nib += [0, 0, 0, color]  # fill to end of line
        else:
            v = (run << 2) | color
            if run < 4:
                nib += [v]
            elif run < 16:
                nib += [v >> 4, v & 0xF]
            elif run < 64:
                nib += [0, v >> 4, v & 0xF]
            else:
                nib += [0, v >> 8, (v >> 4) & 0xF, v & 0xF]
        i += run
    if len(nib) % 2:
        nib.append(0)
    return nib


def nibbles_to_bytes(nib):
    return bytes((nib[i] << 4) | nib[i + 1] for i in range(0, len(nib), 2))


def make_spu(width, height, x, y, duration_s, style):
    rows = []
    for row in range(height):
        if row < 3 or row >= height - 3:
            rows.append([2] * width)
        else:
            inner = [1 if (col // 24 + style) % 2 else 3 for col in range(width - 6)]
            rows.append([2, 2, 2] + inner + [2, 2, 2])
    top = b"".join(nibbles_to_bytes(rle_line(rows[r])) for r in range(0, height, 2))
    bottom = b"".join(nibbles_to_bytes(rle_line(rows[r])) for r in range(1, height, 2))
    top_off = 4
    bottom_off = top_off + len(top)
    dcsq1 = bottom_off + len(bottom)
    x2, y2 = x + width - 1, y + height - 1
    cmds1 = bytes([0x03, 0x32, 0x10, 0x04, 0xFF, 0xF0,
                   0x05, x >> 4, ((x & 0xF) << 4) | (x2 >> 8), x2 & 0xFF, y >> 4, ((y & 0xF) << 4) | (y2 >> 8), y2 & 0xFF,
                   0x06]) + struct.pack(">HH", top_off, bottom_off) + bytes([0x01, 0xFF])
    dcsq2 = dcsq1 + 4 + len(cmds1)
    seq1 = struct.pack(">HH", 0, dcsq2) + cmds1
    delay = int(duration_s * 90000 / 1024)
    seq2 = struct.pack(">HH", delay, dcsq2) + bytes([0x02, 0xFF])
    body = top + bottom + seq1 + seq2
    total = 4 + len(body)
    return struct.pack(">HH", total, dcsq1) + body


def make_sub_pack(template_pack, stream_id, pts, spu):
    """One 2048-byte pack: pack header copied from template, PES private_stream_1, padding."""
    header = bytearray(template_pack[:14])
    header[13] = (header[13] & 0xF8)  # no pack stuffing
    pes_payload = bytes([stream_id]) + spu
    pes_header_data = write_pts(pts)
    pes = b"\x00\x00\x01\xBD" + struct.pack(">H", 3 + len(pes_header_data) + len(pes_payload)) + \
        bytes([0x81, 0x80, len(pes_header_data)]) + pes_header_data + pes_payload
    pack = bytes(header) + pes
    if len(pack) > PACK - 6:
        sys.exit("SPU too large for one pack")
    pad = PACK - len(pack) - 6
    pack += b"\x00\x00\x01\xBE" + struct.pack(">H", pad) + b"\xFF" * pad
    assert len(pack) == PACK
    return pack


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("output")
    ap.add_argument("--lpcm-id", type=lambda s: int(s, 0), default=None)
    ap.add_argument("--sub", action="append", default=[])
    ap.add_argument("--strip-video-pts", type=int, default=0)
    args = ap.parse_args()

    data = open(args.input, "rb").read()
    if len(data) % PACK:
        sys.exit("input is not made of 2048-byte packs")
    packs = [bytearray(data[i:i + PACK]) for i in range(0, len(data), PACK)]
    first_video_pts = None
    video_pes = 0
    for p in packs:
        if p[:4] != b"\x00\x00\x01\xBA":
            sys.exit("unexpected pack header")
        i = 14 + (p[13] & 7)
        while i + 6 <= PACK:
            if p[i:i + 3] != b"\x00\x00\x01":
                break
            sid = p[i + 3]
            length = struct.unpack(">H", p[i + 4:i + 6])[0]
            if sid == 0xE0 and first_video_pts is None and (p[i + 7] & 0x80):
                first_video_pts = read_pts(p, i + 9)
            elif sid == 0xE0 and (p[i + 7] & 0x80) and args.strip_video_pts:
                video_pes += 1
                if video_pes % args.strip_video_pts == 1:  # includes the frame right after the first
                    # Clear PTS_DTS_flags; the timestamp bytes become header stuffing (0xFF).
                    hdr_len = p[i + 8]
                    p[i + 7] &= 0x3F
                    p[i + 9:i + 9 + hdr_len] = b"\xFF" * hdr_len
            if sid == 0xBD and args.lpcm_id is not None:
                hdr_len = p[i + 8]
                sub = i + 9 + hdr_len
                if 0xA0 <= p[sub] <= 0xA7:
                    p[sub] = args.lpcm_id
            i += 6 + length
    if first_video_pts is None:
        sys.exit("no video PTS found")

    events = []
    for k, spec in enumerate(args.sub):
        sid, start, dur = spec.split(":")
        sid = int(sid, 0)
        pts = first_video_pts + int(float(start) * 90000)
        events.append((pts, sid, make_spu(360, 60, 180, 470, float(dur), k % 2)))
    events.sort()

    out = []
    ev = 0
    for idx, p in enumerate(packs):
        scr = read_scr(p)
        # Subpicture packs go in shortly before their presentation time.
        while ev < len(events) and scr >= events[ev][0] - 45000:
            pts, sid, spu = events[ev]
            out.append(make_sub_pack(p, sid, pts, spu))
            ev += 1
        out.append(bytes(p))
    if ev != len(events):
        sys.exit("some subtitle events were after the end of the stream")
    open(args.output, "wb").write(b"".join(out))


if __name__ == "__main__":
    main()
