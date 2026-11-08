import Foundation

public enum QualityPreset: String, Codable, CaseIterable, Identifiable, Sendable {
    case lossless
    case balanced
    case compact
    case compatible

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .lossless: return String(localized: "Beste kwaliteit (verliesvrij)")
        case .balanced: return String(localized: "Gebalanceerd (met kwaliteitsverlies)")
        case .compact: return String(localized: "Meeste compressie (met kwaliteitsverlies)")
        case .compatible: return String(localized: "Breed compatibel (met kwaliteitsverlies)")
        }
    }

    public var shortLabel: String {
        switch self {
        case .lossless: return String(localized: "Beste kwaliteit")
        case .balanced: return String(localized: "Gebalanceerd")
        case .compact: return String(localized: "Meeste compressie")
        case .compatible: return String(localized: "Breed compatibel")
        }
    }

    public var summary: String {
        switch self {
        case .lossless: return String(localized: "MPEG-2-video, audio en ondertitels bit voor bit gekopieerd.")
        case .balanced: return String(localized: "HEVC via de hardware-encoder, originele resolutie, audio gekopieerd.")
        case .compact: return String(localized: "HEVC met lage bitrate voor de kleinste bestanden, audio gekopieerd.")
        case .compatible: return String(localized: "H.264 via de hardware-encoder, speelt op bijna elk toestel, audio gekopieerd.")
        }
    }

    public var isLossy: Bool { self != .lossless }

    /// Default video bitrates in kbit/s; adjustable in Settings.
    public var defaultBitrateKbps: Int {
        switch self {
        case .lossless: return 0
        case .balanced: return 2500
        case .compact: return 1200
        case .compatible: return 3000
        }
    }

    public enum VideoCodec: String, Sendable { case copy, hevc, h264 }

    public var videoCodec: VideoCodec {
        switch self {
        case .lossless: return .copy
        case .balanced, .compact: return .hevc
        case .compatible: return .h264
        }
    }
}

/// File size estimate shown next to every preset.
public enum SizeEstimator {
    /// Typical bitrate of a DVD audio track in bit/s.
    public static func audioBitrate(_ t: AudioTrack) -> Int64 {
        switch t.format {
        case "ac3": return t.channels >= 6 ? 448_000 : 192_000
        case "dts": return t.channels >= 5 ? 768_000 : 384_000
        case "lpcm": return Int64(t.channels) * Int64(t.sampleRate) * Int64(max(t.bits, 16))
        default: return 224_000
        }
    }

    static let subtitleBitrate: Int64 = 8_000
    static let containerOverhead = 1.01
    static let flacRatio = 0.6

    public static func audioBytes(_ tracks: [AudioTrack], duration: TimeInterval, lpcmToFlac: Bool) -> Int64 {
        tracks.reduce(0) { sum, t in
            var bits = Double(audioBitrate(t)) * duration
            if t.isLPCM && lpcmToFlac { bits *= flacRatio }
            return sum + Int64(bits / 8)
        }
    }

    /// Estimated MKV size in bytes.
    /// - Lossless: the title's VOB data minus the streams that are left out.
    /// - Lossy: duration × (video bitrate + kept audio + subtitles).
    public static func estimate(title: TitleInfo, preset: QualityPreset, bitrateKbps: Int,
                                audio: [AudioTrack], subtitleCount: Int, lpcmToFlac: Bool) -> Int64 {
        let d = title.duration
        let keptAudio = audioBytes(audio, duration: d, lpcmToFlac: lpcmToFlac)
        let subs = Int64(Double(subtitleBitrate * Int64(subtitleCount)) * d / 8)
        switch preset {
        case .lossless:
            let allAudio = audioBytes(title.audio, duration: d, lpcmToFlac: false)
            let allSubs = Int64(Double(subtitleBitrate * Int64(title.subtitles.count)) * d / 8)
            let vob = Double(title.sizeBytes) * 0.97 // pack headers and navigation packets are dropped
            let video = max(vob - Double(allAudio) - Double(allSubs), 0)
            return Int64((video + Double(keptAudio) + Double(subs)) * containerOverhead)
        default:
            let video = Double(bitrateKbps) * 1000 * d / 8
            return Int64((video + Double(keptAudio) + Double(subs)) * containerOverhead)
        }
    }
}
