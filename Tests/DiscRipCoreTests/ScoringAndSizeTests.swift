import Foundation
import Testing
@testable import DiscRipCore

private func title(_ i: Int, minutes: Double, chapters: Int = 10, audio: Int = 2, css: Bool = false) -> TitleInfo {
    TitleInfo(index: i, chapters: chapters, durationMs: Int64(minutes * 60_000), sectors: 1000, cssKeyMissing: css,
              audio: (0..<audio).map { AudioTrack(streamId: 0x80 + $0) })
}

@Suite struct TitleScoringTests {
    @Test func picksLongestFeature() {
        let titles = [title(1, minutes: 2), title(2, minutes: 104, chapters: 28), title(3, minutes: 12), title(4, minutes: 95)]
        #expect(TitleScoring.mainFeature(titles)?.index == 2)
    }

    @Test func fallsBackToLongestWhenNothingOverAnHour() {
        let titles = [title(1, minutes: 0.3), title(2, minutes: 45), title(3, minutes: 22)]
        #expect(TitleScoring.mainFeature(titles)?.index == 2)
        #expect(TitleScoring.rank(titles).allSatisfy { $0.plausible })
    }

    @Test func shortTitlesAreNotPlausibleWhenAFeatureExists() {
        let ranked = TitleScoring.rank([title(1, minutes: 50), title(2, minutes: 98)])
        #expect(ranked.first?.title.index == 2)
        #expect(ranked.first { $0.title.index == 1 }?.plausible == false)
    }

    @Test func penalisesNearDuplicates() {
        // Structure-protected disc: 20 near-identical 101 minute titles and one unique 100 minute title.
        var titles = (1...20).map { title($0, minutes: 101 + Double($0) * 0.01, chapters: 24) }
        titles.append(title(21, minutes: 95, chapters: 24))
        let winner = TitleScoring.mainFeature(titles)
        #expect(winner?.index == 21)
        let dup = TitleScoring.rank(titles).first { $0.title.index == 1 }
        #expect(dup?.duplicates == 19)
    }

    @Test func twoIdenticalTitlesAreNotPenalised() {
        // e.g. widescreen and full-screen version of the same film
        let titles = [title(1, minutes: 110, audio: 3), title(2, minutes: 110, audio: 1)]
        #expect(TitleScoring.mainFeature(titles)?.index == 1)
    }

    @Test func skipsTitlesWithoutCSSKey() {
        let titles = [title(1, minutes: 120, css: true), title(2, minutes: 118)]
        #expect(TitleScoring.mainFeature(titles)?.index == 2)
    }

    @Test func moreAudioAndChaptersBreakTies() {
        let titles = [title(1, minutes: 100, chapters: 1, audio: 1), title(2, minutes: 100, chapters: 30, audio: 4)]
        #expect(TitleScoring.mainFeature(titles)?.index == 2)
    }
}

@Suite struct SizeEstimatorTests {
    let film: TitleInfo = {
        TitleInfo(index: 1, chapters: 20, durationMs: 2 * 3600 * 1000,
                  sectors: 6_000_000_000 / 2048,
                  audio: [AudioTrack(streamId: 0x80, format: "ac3", channels: 6, lang: "nl"),
                          AudioTrack(streamId: 0x81, format: "ac3", channels: 6, lang: "en")],
                  subtitles: [SubtitleTrack(streamId: 0x20, lang: "nl")])
    }()

    @Test func lossyFollowsBitrateAndDuration() {
        let bytes = SizeEstimator.estimate(title: film, preset: .balanced, bitrateKbps: 2500, audio: film.audio,
                                           subtitleCount: 1, lpcmToFlac: false)
        // 2.5 Mbit/s video + 2 × 448 kbit/s audio over 2 h ≈ 3.06 GB
        #expect(abs(Double(bytes) - 3.06e9) / 3.06e9 < 0.03)
        let compact = SizeEstimator.estimate(title: film, preset: .compact, bitrateKbps: 1200, audio: [film.audio[0]],
                                             subtitleCount: 0, lpcmToFlac: false)
        #expect(compact < bytes / 2)
    }

    @Test func losslessIsVOBMinusDroppedStreams() {
        let all = SizeEstimator.estimate(title: film, preset: .lossless, bitrateKbps: 0, audio: film.audio,
                                         subtitleCount: 1, lpcmToFlac: false)
        #expect(abs(Double(all) - 6e9 * 0.97 * 1.01) / 6e9 < 0.01)
        let oneTrack = SizeEstimator.estimate(title: film, preset: .lossless, bitrateKbps: 0, audio: [film.audio[0]],
                                              subtitleCount: 0, lpcmToFlac: false)
        #expect(all - oneTrack > 390_000_000) // 448 kbit/s × 2 h ≈ 403 MB
    }

    @Test func flacShrinksLPCM() {
        let lpcm = AudioTrack(streamId: 0xa0, format: "lpcm", channels: 2, sampleRate: 48000, bits: 16)
        let pcm = SizeEstimator.audioBytes([lpcm], duration: 3600, lpcmToFlac: false)
        let flac = SizeEstimator.audioBytes([lpcm], duration: 3600, lpcmToFlac: true)
        #expect(pcm == 691_200_000)
        #expect(flac < pcm)
    }

    @Test func presetsHaveLabelsAndLossMarker() {
        #expect(QualityPreset.lossless.label.contains("verliesvrij"))
        for p in QualityPreset.allCases where p.isLossy {
            #expect(p.label.contains("met kwaliteitsverlies"))
        }
        #expect(AppSettings().defaultPreset == .lossless)
        #expect(QualityPreset.balanced.defaultBitrateKbps == 2500)
        #expect(QualityPreset.compact.defaultBitrateKbps == 1200)
        #expect(QualityPreset.compatible.videoCodec == .h264)
    }
}

@Suite struct LanguageAndRegionTests {
    @Test func iso639Mapping() {
        #expect(Languages.iso639_2B("nl") == "dut")
        #expect(Languages.iso639_2B("en") == "eng")
        #expect(Languages.iso639_2B("de") == "ger")
        #expect(Languages.iso639_2B("fr") == "fre")
        #expect(Languages.iso639_2B("") == "und")
        #expect(Languages.iso639_2B("xx") == "und" || Languages.iso639_2B("xx").count == 3)
    }

    @Test func orderingPutsPreferredFirst() {
        let tracks = [AudioTrack(streamId: 1, lang: "fr"), AudioTrack(streamId: 2, lang: "en"),
                      AudioTrack(streamId: 3, lang: "de"), AudioTrack(streamId: 4, lang: "nl"),
                      AudioTrack(streamId: 5, lang: "en", codeExtension: 3)]
        let ordered = Languages.ordered(tracks, preferred: ["nl", "en"]) { $0.lang }
        #expect(ordered.map(\.streamId) == [4, 2, 5, 1, 3])
    }

    @Test func regionCompatibility() {
        let region2 = Region.DriveState(disabledMask: 0xff & ~0x02, userChangesLeft: 4, rpcScheme: 1, typeCode: 1)
        #expect(region2.regions == [2])
        #expect(Region.compatibility(drive: region2, discDisabledMask: 0xfd) == .compatible)
        #expect(Region.compatibility(drive: region2, discDisabledMask: 0xfe) == .mismatch(drive: [2], disc: [1]))
        #expect(Region.compatibility(drive: region2, discDisabledMask: 0) == .regionFree)
        let unset = Region.DriveState(disabledMask: 0xff, userChangesLeft: 5, rpcScheme: 1, typeCode: 0)
        #expect(Region.compatibility(drive: unset, discDisabledMask: 0xfd) == .driveRegionNotSet)
        let rpc1 = Region.DriveState(disabledMask: 0, userChangesLeft: 0, rpcScheme: 0, typeCode: 0)
        #expect(Region.compatibility(drive: rpc1, discDisabledMask: 0xfe) == .regionFree)
        #expect(Region.compatibility(drive: nil, discDisabledMask: 0xfe) == .regionFree)
    }

    @Test func channelLabels() {
        #expect(AudioTrack(streamId: 0x89, format: "dts", channels: 5).channelLabel == "5.1")
        #expect(AudioTrack(streamId: 0x80, format: "ac3", channels: 6).channelLabel == "5.1")
        #expect(AudioTrack(streamId: 0x80, format: "ac3", channels: 2).channelLabel == "2.0")
    }

    @Test func sampleAspectRatios() {
        #expect(VideoInfo(width: 720, height: 576, aspect: "16:9").sampleAspectRatio == (64, 45))
        #expect(VideoInfo(width: 720, height: 480, aspect: "16:9").sampleAspectRatio == (32, 27))
        #expect(VideoInfo(width: 720, height: 576, aspect: "4:3").sampleAspectRatio == (16, 15))
        #expect(VideoInfo(width: 720, height: 480, aspect: "4:3").sampleAspectRatio == (8, 9))
        #expect(VideoInfo(width: 720, height: 576, aspect: "16:9").displayWidth == 1024)
    }
}
