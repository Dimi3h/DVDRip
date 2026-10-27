#!/usr/bin/env python3
"""Looks into the "Gebalanceerd" rip LossyEndToEndTest leaves on the emulator, with ffmpeg's own tools.

    check_lossy_output.py app/build/outputs/connected_android_test_additional_output

The app's engine reads its files back itself; this is a second opinion from a full FFmpeg build:
the codec, the size, every frame decodable, and the 16:9 aspect ratio both in Matroska and in the
HEVC or H.264 stream itself (its SPS), where players that ignore the container look for it.
"""
import glob
import json
import os
import subprocess
import sys
import tempfile


def probe(path, *arguments):
    out = subprocess.run(["ffprobe", "-v", "error", *arguments, "-of", "json", path], capture_output=True, text=True, check=True)
    return json.loads(out.stdout)


def check(condition, message):
    if not condition:
        print(f"FAIL {message}")
        sys.exit(1)
    print(f"ok   {message}")


def main(root):
    found = glob.glob(os.path.join(root, "**", "balanced.mkv"), recursive=True)
    check(len(found) == 1, f"one balanced.mkv under {root} (found {found})")
    path = found[0]
    note = os.path.join(os.path.dirname(path), "balanced.txt")
    if os.path.exists(note):
        print(f"     encoder: {open(note).read().strip()}")

    entries = "stream=codec_name,width,height,sample_aspect_ratio,display_aspect_ratio,nb_read_frames"
    video = probe(path, "-select_streams", "v:0", "-count_frames", "-show_entries", entries)["streams"][0]
    codec = video["codec_name"]
    check(codec in ("hevc", "h264"), f"the video is HEVC or H.264 ({codec})")
    check((video["width"], video["height"]) == (720, 576), f"720x576 as on the disc ({video['width']}x{video['height']})")
    check(video["display_aspect_ratio"] == "16:9", f"Matroska shows it at 16:9 ({video['display_aspect_ratio']})")
    check(video["nb_read_frames"] == "300", f"300 frames, 12 s at 25 per second ({video['nb_read_frames']})")

    decode = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-map", "0:v", "-f", "null", "-"], capture_output=True, text=True)
    check(decode.returncode == 0 and decode.stderr == "", f"every frame decodes cleanly {decode.stderr.strip()!r}")

    # The stream alone, out of Matroska: the aspect ratio must be in its parameter sets.
    with tempfile.TemporaryDirectory() as work:
        raw = os.path.join(work, f"video.{codec}")
        subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-map", "0:v", "-c", "copy", "-bsf:v", f"{codec}_mp4toannexb",
                        "-f", codec, raw], check=True)
        stream = probe(raw, "-show_entries", "stream=sample_aspect_ratio,display_aspect_ratio")["streams"][0]
        check(stream.get("sample_aspect_ratio") == "64:45", f"the {codec} stream itself has 64:45 pixels ({stream.get('sample_aspect_ratio')})")
        check(stream.get("display_aspect_ratio") == "16:9", f"and so shows at 16:9 without Matroska ({stream.get('display_aspect_ratio')})")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "app/build/outputs/connected_android_test_additional_output")
