import Foundation
import Testing
@testable import DiscRipCore

/// End to end: rips testdata/DISCRIP_TEST_D1.iso (unencrypted DVD-Video made by scripts/make-test-iso.sh)
/// through the full RipJob into a local Plex-style folder, then inspects the MKV with ffprobe.
@Suite(.serialized) struct EndToEndRipTests {
    static let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    static let iso = root.appendingPathComponent("testdata/DISCRIP_TEST_D1.iso")
    static let ffprobe = root.appendingPathComponent("native/out/bin/ffprobe")

    struct Probe: Decodable {
        struct Stream: Decodable {
            var codec_name: String
            var codec_type: String
            var width: Int?
            var height: Int?
            var sample_aspect_ratio: String?
            var display_aspect_ratio: String?
            var tags: [String: String]?
            var disposition: [String: Int]?
        }
        struct Chapter: Decodable {
            var start_time: String
            var tags: [String: String]?
        }
        struct Format: Decodable {
            var duration: String
            var tags: [String: String]?
        }
        var streams: [Stream]
        var chapters: [Chapter]
        var format: Format
    }

    func probe(_ url: URL) throws -> Probe {
        let p = Process()
        p.executableURL = Self.ffprobe
        p.arguments = ["-v", "error", "-print_format", "json", "-show_streams", "-show_chapters", "-show_format", url.path]
        let pipe = Pipe()
        p.standardOutput = pipe
        try p.run()
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        return try JSONDecoder().decode(Probe.self, from: data)
    }

    func packetTimes(_ url: URL, stream: String) throws -> [Double] {
        let p = Process()
        p.executableURL = Self.ffprobe
        p.arguments = ["-v", "error", "-select_streams", stream, "-show_entries", "packet=pts_time", "-of", "csv=p=0", url.path]
        let pipe = Pipe()
        p.standardOutput = pipe
        try p.run()
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        return String(decoding: data, as: UTF8.self).split(separator: "\n").compactMap { Double($0) }.sorted()
    }

    func runJob(preset: QualityPreset, lpcmToFlac: Bool = false) async throws -> (URL, JobResult, URL) {
        let disc = try NativeDisc(path: Self.iso.path)
        let info = try disc.info()
        let main = try #require(TitleScoring.mainFeature(info.titles))
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("discrip-e2e-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        var settings = AppSettings()
        settings.localFolderPath = tmp.path
        settings.localMoviesSubfolder = "Films"
        let meta = MediaMetadata(title: "Test Film", year: 2024, tmdbId: 4242)
        let audio = Languages.ordered(main.audio, preferred: ["nl", "en"]) { $0.lang }
        let plan = RipPlan(sourcePath: Self.iso.path, volumeLabel: info.volumeLabel, title: main, audio: audio,
                           subtitles: main.subtitles, preset: preset, bitrateKbps: settings.bitrateKbps(for: preset),
                           lpcmToFlac: lpcmToFlac, metadata: meta, storage: .local)
        let job = RipJob(plan: plan, settings: settings, nasPassword: "", plexToken: "", disc: disc,
                         logDirectory: tmp.appendingPathComponent("logs"))
        let result = try await job.run(cancel: CancelToken()) { _ in }
        return (tmp, result, tmp.appendingPathComponent("Films/Test Film (2024) {tmdb-4242}/Test Film (2024) {tmdb-4242}.mkv"))
    }

    @Test func discInfo() throws {
        let info = try NativeDisc(path: Self.iso.path).info()
        #expect(info.volumeLabel == "DISCRIP_TEST_D1")
        #expect(info.titles.map(\.index) == [1, 2])
        let main = info.titles[1]
        #expect(main.chapters == 3)
        #expect(main.cells == 3)
        #expect((17_500...18_500).contains(main.durationMs))
        #expect(main.video.aspect == "16:9")
        #expect(main.audio.map(\.streamId) == [0x80, 0xa1])
    }

    @Test func losslessRip() async throws {
        let (tmp, result, expected) = try await runJob(preset: .lossless)
        defer { try? FileManager.default.removeItem(at: tmp) }
        #expect(result.localURL == expected)
        #expect(FileManager.default.fileExists(atPath: expected.path))
        #expect(result.outcome.videoCodec == .copy)
        #expect(result.outcome.badSectors == 0)
        let leftovers = try FileManager.default.contentsOfDirectory(atPath: expected.deletingLastPathComponent().path)
        #expect(leftovers == [expected.lastPathComponent])

        let p = try probe(expected)
        #expect(p.streams.map(\.codec_type) == ["video", "audio", "audio", "subtitle", "subtitle"])
        #expect(p.streams.map(\.codec_name) == ["mpeg2video", "ac3", "pcm_s16le", "dvd_subtitle", "dvd_subtitle"])
        #expect(p.streams[0].sample_aspect_ratio == "64:45")
        #expect(p.streams[0].display_aspect_ratio == "16:9")
        #expect(p.streams[1...].map { $0.tags?["language"] } == ["dut", "eng", "dut", "eng"])
        #expect(p.streams[1].disposition?["default"] == 1)
        #expect(p.chapters.count == 3)
        let ifoChapters = try NativeDisc(path: Self.iso.path).info().titles[1].chapterStartsMs.map { Double($0) / 1000 }
        #expect(p.chapters.map { Double($0.start_time)! } == ifoChapters)
        #expect(ifoChapters.count == 3 && ifoChapters[0] == 0 && ifoChapters[2] == 10)
        #expect(p.chapters[0].tags?["title"] == "Hoofdstuk 1")
        #expect(p.format.tags?["TITLE"] == "Test Film")
        #expect(p.format.tags?["DATE_RELEASED"] == "2024")
        #expect(p.format.tags?["TMDB"] == "movie/4242")
        #expect(abs(Double(p.format.duration)! - 18) < 0.2)

        // Every stream is complete and continuous across the timestamp break between the two VOBs.
        let video = try packetTimes(expected, stream: "v:0")
        #expect(video.count == 450)
        let ac3 = try packetTimes(expected, stream: "a:0")
        #expect(ac3.count == 562)
        #expect(zip(ac3, ac3.dropFirst()).allSatisfy { $1 - $0 < 0.05 })
        #expect(try packetTimes(expected, stream: "s:0") == [1.0, 6.0, 12.0])
        #expect(try packetTimes(expected, stream: "s:1") == [1.5, 6.5, 14.0])
    }

    @Test func balancedRip() async throws {
        let (tmp, result, expected) = try await runJob(preset: .balanced, lpcmToFlac: true)
        defer { try? FileManager.default.removeItem(at: tmp) }
        #expect(result.outcome.videoCodec == .hevc || (result.outcome.videoCodec == .h264 && result.outcome.fellBackToH264))
        let p = try probe(expected)
        #expect(["hevc", "h264"].contains(p.streams[0].codec_name))
        #expect(p.streams[0].width == 720 && p.streams[0].height == 576)
        #expect(p.streams[0].display_aspect_ratio == "16:9")
        #expect(p.streams.map(\.codec_name).dropFirst() == ["ac3", "flac", "dvd_subtitle", "dvd_subtitle"])
        #expect(p.chapters.count == 3)
        let video = try packetTimes(expected, stream: "v:0")
        #expect(video.count == 450)
        #expect(abs((video.last ?? 0) - 17.96) < 0.05)
        // 2.5 Mbit/s target over 18 s, allow for encoder rate control slack
        let size = (try FileManager.default.attributesOfItem(atPath: expected.path)[.size] as? NSNumber)?.int64Value ?? 0
        #expect(size > 3_000_000 && size < 12_000_000)
    }

    @Test func refusesWhenSpaceIsInsufficient() async throws {
        let disc = try NativeDisc(path: Self.iso.path)
        let main = try #require(TitleScoring.mainFeature(try disc.info().titles))
        var huge = main
        huge.sectors = 1 << 40 // pretend the title is 2 PB
        var settings = AppSettings()
        settings.localFolderPath = FileManager.default.temporaryDirectory.path
        let plan = RipPlan(sourcePath: Self.iso.path, volumeLabel: "X", title: huge, audio: main.audio, subtitles: [],
                           preset: .lossless, bitrateKbps: 0, lpcmToFlac: false,
                           metadata: MediaMetadata(title: "Huge"), storage: .local)
        await #expect {
            _ = try await RipJob(plan: plan, settings: settings, nasPassword: "", plexToken: "", disc: disc,
                                 logDirectory: FileManager.default.temporaryDirectory.appendingPathComponent("discrip-logs"))
                .run(cancel: CancelToken()) { _ in }
        } throws: { error in
            if case .insufficientSpace = error as? DiscRipError { return true }
            return false
        }
    }

    @Test func cancelRemovesPartialFile() async throws {
        let disc = try NativeDisc(path: Self.iso.path)
        let main = try #require(TitleScoring.mainFeature(try disc.info().titles))
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("discrip-cancel-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }
        let out = tmp.appendingPathComponent("x.mkv")
        let token = CancelToken()
        token.cancel()
        let req = RipRequest(title: main, audio: main.audio, subtitles: [], preset: .lossless, bitrateKbps: 0,
                             lpcmToFlac: false, metadata: MediaMetadata(title: "X"), outputURL: out)
        #expect(throws: DiscRipError.cancelled) { try Ripper(disc: disc).rip(req, cancel: token) { _ in } }
        #expect(!FileManager.default.fileExists(atPath: out.path))
    }
}
