#!/usr/bin/env python3
"""End-to-end tests of the rip engine on the build machine.

Runs discrip_host against the image made by native/scripts/make-test-dvd.sh and checks the
Matroska files with ffprobe and ffmpeg. css_tool, built next to discrip_host, makes CSS-scrambled
copies of the image.

    DISCRIP_HOST=native/build/host/discrip_host TEST_ISO=test.iso TEST_FOLDER=folder \
        python3 native/tests/test_engine.py
"""
import array
import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest

HOST = os.environ.get("DISCRIP_HOST", "native/build/host/discrip_host")
CSS_TOOL = os.environ.get("CSS_TOOL", os.path.join(os.path.dirname(HOST), "css_tool"))
ISO = os.environ["TEST_ISO"]
FOLDER = os.environ["TEST_FOLDER"]
TITLE_KEY = "0123456789"


def run_host(*args, check=True):
    result = subprocess.run([HOST, *args], capture_output=True, text=True, timeout=300)
    if check and result.returncode != 0:
        raise AssertionError(f"discrip_host {' '.join(args)} failed:\n{result.stdout}\n{result.stderr}")
    return json.loads(result.stdout), result.stderr


def host(*args, check=True):
    return run_host(*args, check=check)[0]


def extent(iso, name):
    """First block of a file in the image, from isoinfo's listing."""
    return file_blocks(iso, name)[0]


def file_blocks(iso, name):
    """First block and length in blocks of a file in the image, from isoinfo's listing."""
    listing = subprocess.run(["isoinfo", "-l", "-i", iso], capture_output=True, text=True, check=True).stdout
    for line in listing.splitlines():
        match = re.search(r"(\d+) \w{3} [ \d]\d \d{4} \[\s*(\d+)\s+\d+\]\s+(\S+?);1", line)
        if match and match.group(3) == name:
            return int(match.group(2)), (int(match.group(1)) + 2047) // 2048
    raise AssertionError(f"{name} not in {iso}")


def attackable_blocks(iso, name, at_least=4):
    """
    Blocks of a VOB, counted from its start, that libdvdcss's title key attack (AttackPattern in
    css.c) tries once they are scrambled: their clear bytes end in a pattern that repeats at least
    twice. It guesses the scrambled bytes continue it; the longer the pattern, the safer the guess.
    """
    first, blocks = file_blocks(iso, name)
    found = []
    with open(iso, "rb") as image:
        image.seek(first * 2048)
        for block in range(blocks):
            p = image.read(2048)
            if p[:4] != b"\x00\x00\x01\xba" or p[0x0e:0x11] != b"\x00\x00\x01" or p[0x11] in (0xbb, 0xbe, 0xbf):
                continue
            best_length = best_cycle = 0
            for cycle in range(2, 0x30):
                j = cycle + 1
                while j < 0x80 and p[0x7f - (j % cycle)] == p[0x7f - j]:
                    if j > best_length:
                        best_length, best_cycle = j, cycle
                    j += 1
            if best_length > 3 and best_length // best_cycle >= 2 and best_length >= at_least:
                found.append(block)
    return found


def frames(path):
    """A checksum line per packet of every stream, to tell whether two rips hold the same data."""
    return subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-map", "0", "-c", "copy", "-f", "framemd5", "-"],
                          capture_output=True, text=True, check=True).stdout


def probe(path, entries):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", entries, "-of", "json", path],
        capture_output=True, text=True, check=True,
    ).stdout
    return json.loads(out)


def codec_private(path, stream):
    """The CodecPrivate of a stream, from ffprobe's hex dump of its extradata."""
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", stream, "-show_streams", "-show_data", path],
        capture_output=True, text=True, check=True,
    ).stdout
    dump = out.split("extradata=", 1)[1].split("extradata_size", 1)[0]
    hex_digits = "".join("".join(line[10:50].split()) for line in dump.strip().splitlines())
    return bytes.fromhex(hex_digits).decode("ascii")


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

    def scrambled(self, ranges, *options):
        """A copy of the image with the packs in ranges of VTS_01_1.VOB (from its start) scrambled."""
        path = self.output("css.iso")
        shutil.copyfile(ISO, path)
        vob = extent(ISO, "VTS_01_1.VOB")
        blocks = [f"{vob + first}:{count}" for first, count in ranges]
        result = subprocess.run([CSS_TOOL, "scramble", *options, path, TITLE_KEY, *blocks],
                                capture_output=True, text=True, check=True)
        self.assertGreater(int(result.stdout.split()[0]), 0)
        return path

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
        self.assertEqual([(0x20, "nl"), (0x21, "en")], [(s["streamId"], s["language"]) for s in main["subtitles"]])
        self.assertEqual([], image[1]["subtitles"])
        # The cells in playback order, for the main feature detection in the app.
        self.assertEqual([3000, 3000, 6000], [c["durationMs"] for c in main["playback"]])
        self.assertEqual(0, main["playback"][0]["first"])
        self.assertEqual(main["sectors"], sum(c["last"] - c["first"] + 1 for c in main["playback"]))

    def test_lossless_rip_lines_up_the_timeline(self):
        path = self.output("main.mkv")
        report = host("rip", ISO, "1", path, "--audio", "0x80:eng:Stereo,0xa0:dut",
                      "--movie-title", "Testfilm", "--date", "2024", "--tag", "IMDB=tt0133093", "--tag", "TMDB=movie/603")
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
        self.assertEqual("tt0133093", info["format"]["tags"]["IMDB"])
        self.assertEqual("movie/603", info["format"]["tags"]["TMDB"])

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

    def test_writing_and_reading_through_callbacks_matches_a_descriptor(self):
        # The app writes to the NAS through callbacks, and reads the file back through them.
        direct = self.output("descriptor.mkv")
        callbacks = self.output("callbacks.mkv")
        arguments = ["--audio", "0x80:eng,0xa0:dut", "--subs", "0x20:dut", "--movie-title", "Testfilm"]
        host("rip", ISO, "1", direct, *arguments)
        report = host("rip", ISO, "1", callbacks, *arguments, "--callbacks")
        self.assertEqual(os.path.getsize(callbacks), report["outputBytes"])
        entries = "packet=stream_index,pts_time,size"
        self.assertEqual(probe(direct, entries), probe(callbacks, entries))

        through_descriptor = host("probe", callbacks)
        self.assertTrue(through_descriptor["ok"])
        self.assertEqual("Testfilm", through_descriptor["tags"]["title"])
        # Short reads, as SMB may return, change nothing.
        self.assertEqual(through_descriptor, host("probe", callbacks, "--callbacks"))

    def test_a_failing_write_callback_stops_the_rip(self):
        size = host("rip", ISO, "1", self.output("whole.mkv"), "--audio", "0x80")["outputBytes"]
        halfway = str(size // 2)
        report = host("rip", ISO, "1", self.output("broken.mkv"), "--audio", "0x80", "--fail-write-at", halfway, check=False)
        self.assertEqual("write_failed", report["error"])
        report = host("rip", ISO, "1", self.output("full.mkv"), "--audio", "0x80", "--fail-write-at", f"{halfway}:nospace",
                      check=False)
        self.assertEqual("no_space", report["error"])

    def test_subtitles_become_vobsub_tracks_on_the_same_timeline(self):
        path = self.output("subs.mkv")
        report = host("rip", ISO, "1", path, "--audio", "0x80:eng", "--subs", "0x20:dut,0x21:eng:Forced", "--forced-sub", "0x21")
        self.assertEqual(2, report["subtitleTracks"])

        streams = probe(path, "stream=codec_name:stream_tags=language,title:stream_disposition=default,forced")["streams"]
        subtitles = streams[2:]
        self.assertEqual(["dvd_subtitle", "dvd_subtitle"], [s["codec_name"] for s in subtitles])
        self.assertEqual(["dut", "eng"], [s["tags"]["language"] for s in subtitles])
        self.assertEqual("Forced", subtitles[1]["tags"]["title"])
        self.assertEqual([0, 1], [s["disposition"]["forced"] for s in subtitles])
        self.assertEqual([0, 0], [s["disposition"]["default"] for s in subtitles])

        # The program chain palette (YCrCb in the IFO) comes back as the RGB it was authored with.
        header = codec_private(path, "s:0")
        self.assertTrue(header.startswith("size: 720x576\npalette: 000000, ffffff, ffff00, 0000ff, 808080"), header)

        packets = probe(path, "packet=stream_index,pts_time,duration_time")["packets"]
        dutch = [(float(p["pts_time"]), float(p["duration_time"])) for p in packets if p["stream_index"] == 2]
        english = [(float(p["pts_time"]), float(p["duration_time"])) for p in packets if p["stream_index"] == 3]
        # One subtitle per part; the second part's comes after the clock jump, at 6 s + 1 s.
        self.assertEqual(2, len(dutch))
        self.assertAlmostEqual(1.0, dutch[0][0], delta=0.05)
        self.assertAlmostEqual(7.0, dutch[1][0], delta=0.05)
        self.assertAlmostEqual(1.5, dutch[0][1], delta=0.05)
        self.assertAlmostEqual(1.2, english[0][0], delta=0.05)
        self.assertAlmostEqual(0.8, english[0][1], delta=0.05)

        # Decoding needs the size and palette from the header; re-encoding makes ffmpeg decode it all.
        decode = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-map", "0:s", "-c:s", "dvdsub", "-f", "matroska", "-"],
                                capture_output=True)
        self.assertEqual(b"", decode.stderr)

    def test_a_subtitle_stream_the_title_lacks_is_skipped(self):
        report = host("rip", ISO, "2", self.output("nosubs.mkv"), "--subs", "0x20:dut")
        self.assertEqual(0, report["subtitleTracks"])
        self.assertEqual(["Subtitle stream 0x20 is not in this title; skipped"], report["warnings"])

    def test_a_css_scrambled_image_rips_like_the_clear_one(self):
        _, blocks = file_blocks(ISO, "VTS_01_1.VOB")
        image = self.scrambled([(0, blocks)])
        arguments = ["--audio", "0x80:eng,0xa0:dut", "--subs", "0x20:dut"]
        clear = self.output("clear.mkv")
        host("rip", ISO, "1", clear, *arguments)
        decrypted = self.output("decrypted.mkv")
        report, log = run_host("rip", image, "1", decrypted, *arguments)
        self.assertTrue(report["ok"])
        self.assertIn("CSS title key found", log)
        self.assertEqual(frames(clear), frames(decrypted))

    def test_scrambling_that_starts_inside_the_title_is_decrypted_from_there(self):
        _, blocks = file_blocks(ISO, "VTS_01_1.VOB")
        later = [b for b in attackable_blocks(ISO, "VTS_01_1.VOB", at_least=20) if b > blocks // 2]
        self.assertTrue(later, "the test DVD has no block in its second half that the title key attack can use")
        start = later[0] - 100
        image = self.scrambled([(start, blocks - start)])
        clear = self.output("clear.mkv")
        host("rip", ISO, "1", clear, "--audio", "0x80,0xa0")
        decrypted = self.output("decrypted.mkv")
        report, log = run_host("rip", image, "1", decrypted, "--audio", "0x80,0xa0")
        self.assertTrue(report["ok"])
        # The blocks before were read as they are; the search starts at the first scrambled one.
        searches = [int(b) for b in re.findall(r"Recovering the CSS title key from block (\d+) on", log)]
        vob = extent(ISO, "VTS_01_1.VOB")
        self.assertEqual(1, len(searches))
        self.assertTrue(vob + start <= searches[0] < vob + start + 4, searches)
        self.assertEqual(frames(clear), frames(decrypted))

    def test_a_title_whose_key_cannot_be_recovered_is_refused(self):
        _, blocks = file_blocks(ISO, "VTS_01_1.VOB")
        image = self.scrambled([(0, blocks)], "--no-pattern")
        report, log = run_host("rip", image, "1", self.output("refused.mkv"), "--audio", "0x80", check=False)
        self.assertEqual("scrambled", report["error"])
        self.assertIn("No CSS title key found", log)

    def test_the_search_for_a_key_goes_on_past_unreadable_blocks(self):
        _, blocks = file_blocks(ISO, "VTS_01_1.VOB")
        attackable = attackable_blocks(ISO, "VTS_01_1.VOB")
        self.assertTrue(attackable and attackable[0] > 10, attackable)
        image = self.scrambled([(0, blocks)])
        vob = extent(ISO, "VTS_01_1.VOB")
        # Before the first block the attack can use, so the search runs into them.
        bad = attackable[0] // 2
        report, log = run_host("rip", image, "1", self.output("bad.mkv"), "--audio", "0x80", "--bad-blocks", f"{vob + bad}:2")
        self.assertTrue(report["ok"])
        self.assertEqual([[bad, 2]], report["badSectors"])
        self.assertIn(f"Recovering the CSS title key from block {vob + bad + 2} on", log)
        self.assertIn("CSS title key found", log)
        # Twice by the search (libdvdcss tries once more), then once in bulk and three times alone by the rip.
        self.assertEqual(["6", "6"], re.findall(r"bad block \d+ read (\d+) times", log))

    def test_the_search_for_a_key_can_be_cancelled(self):
        _, blocks = file_blocks(ISO, "VTS_01_1.VOB")
        image = self.scrambled([(0, blocks)], "--no-pattern")
        report = host("rip", image, "1", self.output("cancel.mkv"), "--audio", "0x80", "--cancel-after", str(blocks // 2), check=False)
        self.assertEqual("cancelled", report["error"])

    def test_missing_title(self):
        report = host("rip", ISO, "9", self.output("missing.mkv"), check=False)
        self.assertEqual("no_title", report["error"])

    # The lossy presets, with FFmpeg's MPEG-4 encoder standing in for MediaCodec.

    def test_a_lossy_rip_reencodes_the_video_and_keeps_the_rest(self):
        arguments = ["--audio", "0x80:eng,0xa0:dut", "--subs", "0x20:dut,0x21:eng", "--movie-title", "Testfilm"]
        lossless = self.output("lossless.mkv")
        kept = host("rip", ISO, "1", lossless, *arguments)
        path = self.output("lossy.mkv")
        report, log = run_host("rip", ISO, "1", path, *arguments, "--encoder", "mpeg4", "--bitrate", "2500000")
        self.assertTrue(report["ok"])
        # 12 s at 25 frames a second, none of them interlaced.
        self.assertEqual(300, report["framesEncoded"])
        self.assertEqual(0, report["interlacedFrames"])
        self.assertIn("300 frames encoded", log.strip().splitlines()[-1])
        self.assertEqual(kept["chapterStartsMs"], report["chapterStartsMs"])
        self.assertEqual(kept["durationMs"], report["durationMs"])

        # Same size, same frame rate, and the 16:9 picture still shown at 16:9 (64:45 pixels).
        video = probe(path, "stream=codec_name,width,height,sample_aspect_ratio,display_aspect_ratio,avg_frame_rate")["streams"][0]
        self.assertEqual({"codec_name": "mpeg4", "width": 720, "height": 576, "sample_aspect_ratio": "64:45",
                          "display_aspect_ratio": "16:9", "avg_frame_rate": "25/1"}, video)
        # The engine reads it back the same way, as the app checks it.
        self.assertEqual("16:9", host("probe", path)["streams"][0]["displayAspect"])
        self.assertEqual("16:9", host("probe", lossless)["streams"][0]["displayAspect"])
        times = [float(p["pts_time"]) for p in probe(path, "packet=pts_time")["packets"]]
        self.assertGreater(min(times), -0.001)
        self.assertLess(max(times), 12.1)

        # Audio and subtitles are the very packets of the lossless rip.
        def other_streams(file):
            return [line for line in frames(file).splitlines() if line and not line.startswith(("#", "0,"))]
        self.assertEqual(other_streams(lossless), other_streams(path))
        self.assertEqual(probe(lossless, "chapter=start_time,end_time:chapter_tags=title"),
                         probe(path, "chapter=start_time,end_time:chapter_tags=title"))

        decode = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-map", "0:v", "-map", "0:a", "-f", "null", "-"],
                                capture_output=True, text=True)
        self.assertEqual("", decode.stderr)

    def test_the_bit_rate_steers_the_video_size(self):
        sizes = []
        for bit_rate in (1000000, 4000000):
            path = self.output(f"{bit_rate}.mkv")
            host("rip", ISO, "1", path, "--audio", "0x80", "--encoder", "mpeg4", "--bitrate", str(bit_rate))
            size = sum(int(p["size"]) for p in probe(path, "packet=size,stream_index")["packets"] if p["stream_index"] == 0)
            # Never much over the target; under it where the picture needs no more (still bars).
            self.assertLess(size, bit_rate * 12 / 8 * 1.2, bit_rate)
            sizes.append(size)
        self.assertGreater(sizes[1], sizes[0] * 2)

    def test_interlaced_frames_are_deinterlaced_and_progressive_ones_left_alone(self):
        def detected(path):
            """idet's verdict per frame, from the fields' likeness: (top field first, progressive)."""
            result = subprocess.run(["ffmpeg", "-hide_banner", "-i", path, "-map", "0:v", "-vf", "idet", "-f", "null", "-"],
                                    capture_output=True, text=True, check=True)
            line = [l for l in result.stderr.splitlines() if "Single frame detection" in l][-1]
            return int(re.search(r"TFF:\s*(\d+)", line).group(1)), int(re.search(r"Progressive:\s*(\d+)", line).group(1))

        lossless = self.output("interlaced.mkv")
        host("rip", ISO, "2", lossless, "--audio", "0x80")
        tff, _ = detected(lossless)
        self.assertGreater(tff, 70)

        path = self.output("deinterlaced.mkv")
        report = host("rip", ISO, "2", path, "--audio", "0x80", "--encoder", "mpeg4")
        self.assertEqual(75, report["interlacedFrames"])
        self.assertEqual(75, report["framesEncoded"])
        tff, progressive = detected(path)
        self.assertLess(tff, 5)
        self.assertGreater(progressive, 70)
        self.assertEqual("25/1", probe(path, "stream=avg_frame_rate")["streams"][0]["avg_frame_rate"])

    def test_an_encoder_the_build_lacks_is_unavailable_before_anything_is_written(self):
        path = self.output("unavailable.mkv")
        report = host("rip", ISO, "1", path, "--audio", "0x80", "--encoder", "hevc_mediacodec", check=False)
        self.assertEqual("encoder_unavailable", report["error"])
        self.assertIn("hevc_mediacodec", report["message"])
        self.assertEqual(0, os.path.getsize(path))

    def test_a_lossy_rip_that_cannot_write_names_the_cause(self):
        size = host("rip", ISO, "1", self.output("whole.mkv"), "--audio", "0x80", "--encoder", "mpeg4")["outputBytes"]
        halfway = str(size // 2)
        report = host("rip", ISO, "1", self.output("full.mkv"), "--audio", "0x80", "--encoder", "mpeg4",
                      "--fail-write-at", f"{halfway}:nospace", check=False)
        self.assertEqual("no_space", report["error"])
        report = host("rip", ISO, "1", self.output("broken.mkv"), "--audio", "0x80", "--encoder", "mpeg4",
                      "--fail-write-at", halfway, check=False)
        self.assertEqual("write_failed", report["error"])

    def test_a_lossy_rip_can_be_cancelled(self):
        report = host("rip", ISO, "1", self.output("cancel.mkv"), "--audio", "0x80", "--encoder", "mpeg4",
                      "--cancel-after", "600", check=False)
        self.assertEqual("cancelled", report["error"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
