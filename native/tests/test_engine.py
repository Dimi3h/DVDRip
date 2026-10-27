#!/usr/bin/env python3
"""End-to-end tests of the rip engine on the build machine.

Runs discrip_host against the image made by native/scripts/make-test-dvd.sh and checks the
Matroska files with ffprobe and ffmpeg.

    DISCRIP_HOST=native/build/host/discrip_host TEST_ISO=test.iso TEST_FOLDER=folder \
        python3 native/tests/test_engine.py
"""
import array
import json
import os
import re
import subprocess
import tempfile
import unittest

HOST = os.environ.get("DISCRIP_HOST", "native/build/host/discrip_host")
ISO = os.environ["TEST_ISO"]
FOLDER = os.environ["TEST_FOLDER"]


def run_host(*args, check=True):
    result = subprocess.run([HOST, *args], capture_output=True, text=True, timeout=300)
    if check and result.returncode != 0:
        raise AssertionError(f"discrip_host {' '.join(args)} failed:\n{result.stdout}\n{result.stderr}")
    return json.loads(result.stdout), result.stderr


def host(*args, check=True):
    return run_host(*args, check=check)[0]


def extent(iso, name):
    """First block of a file in the image, from isoinfo's listing."""
    listing = subprocess.run(["isoinfo", "-l", "-i", iso], capture_output=True, text=True, check=True).stdout
    for line in listing.splitlines():
        match = re.search(r"\[\s*(\d+)\s+\d+\]\s+(\S+?);1", line)
        if match and match.group(2) == name:
            return int(match.group(1))
    raise AssertionError(f"{name} not in {iso}")


def probe(path, entries):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", entries, "-of", "json", path],
        capture_output=True, text=True, check=True,
    ).stdout
    return json.loads(out)


def tone(path, track, start):
    """Dominant frequency (zero crossings) of 0.3 s of an audio track from start seconds on."""
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-ss", str(start), "-i", path, "-map", f"0:{track}", "-t", "0.3",
         "-ac", "1", "-ar", "48000", "-f", "s16le", "-"],
        capture_output=True, check=True,
    ).stdout
    samples = array.array("h")
    samples.frombytes(raw)
    crossings = sum(1 for i in range(1, len(samples)) if (samples[i - 1] < 0) != (samples[i] < 0))
    return crossings / 2 / (len(samples) / 48000.0)


class EngineTest(unittest.TestCase):

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)

    def output(self, name):
        return os.path.join(self.directory.name, name)

    def test_titles_from_image_and_folder_agree(self):
        image = host("titles", ISO)["titles"]
        folder = host("titles", "--dir", FOLDER)["titles"]
        self.assertEqual(image, folder)
        self.assertEqual([1, 2], [t["number"] for t in image])
        main = image[0]
        self.assertEqual(12000, main["durationMs"])
        self.assertEqual([0, 3000, 6000], main["chapterStartsMs"])
        self.assertEqual({"standard": "PAL", "aspect": "16:9", "width": 720, "height": 576},
                         {k: main["video"][k] for k in ("standard", "aspect", "width", "height")})
        self.assertEqual([(0x80, "ac3", "en"), (0xA0, "lpcm", "nl")],
                         [(a["streamId"], a["codec"], a["language"]) for a in main["audio"]])

    def test_lossless_rip_lines_up_the_timeline(self):
        path = self.output("main.mkv")
        report = host("rip", ISO, "1", path, "--audio", "0x80:eng:Stereo,0xa0:dut",
                      "--movie-title", "Testfilm", "--date", "2024")
        self.assertTrue(report["ok"])
        self.assertEqual([], report["badSectors"])
        self.assertAlmostEqual(12000, report["durationMs"], delta=50)
        self.assertEqual(3, len(report["chapterStartsMs"]))
        self.assertAlmostEqual(6000, report["chapterStartsMs"][2], delta=40)

        info = probe(path, "format=duration:format_tags:stream=codec_name,sample_aspect_ratio,display_aspect_ratio:"
                           "stream_tags=language,title:stream_disposition=default")
        streams = info["streams"]
        self.assertEqual(["mpeg2video", "ac3", "pcm_s16le"], [s["codec_name"] for s in streams])
        self.assertEqual("16:9", streams[0]["display_aspect_ratio"])
        self.assertEqual(["eng", "dut"], [s["tags"]["language"] for s in streams[1:]])
        self.assertEqual("Stereo", streams[1]["tags"]["title"])
        self.assertEqual([1, 1, 0], [s["disposition"]["default"] for s in streams])
        self.assertEqual("Testfilm", info["format"]["tags"]["title"])
        self.assertEqual("2024", info["format"]["tags"]["DATE_RELEASED"])

        chapters = probe(path, "chapter=start_time")["chapters"]
        self.assertEqual(3, len(chapters))

        packets = probe(path, "packet=pts_time")["packets"]
        times = [float(p["pts_time"]) for p in packets]
        self.assertGreater(min(times), -0.001)
        self.assertLess(max(times), 12.1)

        # The second VOB restarts its clock; after the fix its tones play after the first VOB's.
        self.assertAlmostEqual(440, tone(path, 1, 5.4), delta=20)
        self.assertAlmostEqual(880, tone(path, 1, 6.6), delta=20)
        self.assertAlmostEqual(660, tone(path, 2, 5.4), delta=20)
        self.assertAlmostEqual(990, tone(path, 2, 6.6), delta=20)

        decode = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-map", "0", "-f", "null", "-"],
                                capture_output=True, text=True)
        self.assertEqual("", decode.stderr)

    def test_lpcm_to_flac(self):
        path = self.output("flac.mkv")
        self.assertTrue(host("rip", ISO, "1", path, "--audio", "0xa0:dut", "--flac")["ok"])
        streams = probe(path, "stream=codec_name,sample_rate,channels")["streams"]
        self.assertEqual("flac", streams[1]["codec_name"])
        self.assertEqual("48000", streams[1]["sample_rate"])
        self.assertEqual(2, streams[1]["channels"])

    def test_folder_rip_matches_image_rip(self):
        image = self.output("image.mkv")
        folder = self.output("folder.mkv")
        host("rip", ISO, "1", image, "--audio", "0x80,0xa0")
        host("rip", "--dir", FOLDER, "1", folder, "--audio", "0x80,0xa0")
        entries = "packet=stream_index,pts_time,size"
        self.assertEqual(probe(image, entries), probe(folder, entries))

    def test_second_title(self):
        path = self.output("extra.mkv")
        report = host("rip", ISO, "2", path, "--audio", "0x80:eng")
        self.assertTrue(report["ok"])
        self.assertAlmostEqual(3000, report["durationMs"], delta=50)
        self.assertEqual([0], report["chapterStartsMs"])

    def test_cancel(self):
        report = host("rip", ISO, "1", self.output("cancel.mkv"), "--audio", "0x80", "--cancel-after", "600", check=False)
        self.assertEqual({"ok": False, "error": "cancelled", "message": "The rip was cancelled"}, report)

    def test_unknown_audio_stream_is_skipped_with_a_warning(self):
        report = host("rip", ISO, "1", self.output("unknown.mkv"), "--audio", "0x81:eng,0x80:eng")
        self.assertEqual(1, report["audioTracks"])
        self.assertEqual(["Audio stream 0x81 is not in this title; skipped"], report["warnings"])

    def test_unreadable_blocks_are_retried_skipped_and_reported(self):
        vob = extent(ISO, "VTS_01_1.VOB")
        path = self.output("bad.mkv")
        report, log = run_host("rip", ISO, "1", path, "--audio", "0x80", "--bad-blocks", f"{vob + 500}:4")
        self.assertTrue(report["ok"])
        self.assertEqual([[500, 4]], report["badSectors"])
        # One bulk read and three single-block attempts each.
        self.assertEqual(["4"] * 4, re.findall(r"bad block \d+ read (\d+) times", log))
        self.assertAlmostEqual(12000, report["durationMs"], delta=50)

    def test_a_full_disk_is_reported_as_such(self):
        report = host("rip", ISO, "1", "/dev/full", "--audio", "0x80", check=False)
        self.assertEqual("no_space", report["error"])

    def test_missing_title(self):
        report = host("rip", ISO, "9", self.output("missing.mkv"), check=False)
        self.assertEqual("no_title", report["error"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
