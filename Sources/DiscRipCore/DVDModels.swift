import Foundation

/// Disc description produced by the native layer (`dr_disc_info_json`).
public struct DiscInfo: Codable, Sendable, Equatable {
    public var volumeLabel: String
    public var discRegionMask: Int
    public var titles: [TitleInfo]

    public init(volumeLabel: String, discRegionMask: Int, titles: [TitleInfo]) {
        self.volumeLabel = volumeLabel
        self.discRegionMask = discRegionMask
        self.titles = titles
    }

    /// Regions (1...8) the disc may be played in.
    public var allowedRegions: [Int] { Region.regions(fromDisabledMask: discRegionMask) }
}

public struct TitleInfo: Codable, Sendable, Equatable, Identifiable, Hashable {
    public var index: Int
    public var vts: Int
    public var ttn: Int
    public var angles: Int
    public var chapters: Int
    public var cells: Int
    public var durationMs: Int64
    public var sectors: Int64
    public var cssKeyMissing: Bool
    public var video: VideoInfo
    public var chapterStartsMs: [Int64]
    public var audio: [AudioTrack]
    public var subtitles: [SubtitleTrack]
    public var palette: [String]

    public var id: Int { index }
    public var duration: TimeInterval { TimeInterval(durationMs) / 1000 }
    public var sizeBytes: Int64 { sectors * 2048 }

    public init(index: Int, vts: Int = 1, ttn: Int = 1, angles: Int = 1, chapters: Int, cells: Int = 1,
                durationMs: Int64, sectors: Int64 = 0, cssKeyMissing: Bool = false,
                video: VideoInfo = VideoInfo(), chapterStartsMs: [Int64] = [0],
                audio: [AudioTrack] = [], subtitles: [SubtitleTrack] = [], palette: [String] = []) {
        self.index = index
        self.vts = vts
        self.ttn = ttn
        self.angles = angles
        self.chapters = chapters
        self.cells = cells
        self.durationMs = durationMs
        self.sectors = sectors
        self.cssKeyMissing = cssKeyMissing
        self.video = video
        self.chapterStartsMs = chapterStartsMs
        self.audio = audio
        self.subtitles = subtitles
        self.palette = palette
    }
}

public struct VideoInfo: Codable, Sendable, Equatable, Hashable {
    public var width: Int
    public var height: Int
    public var standard: String   // PAL / NTSC
    public var aspect: String     // 16:9 / 4:3
    public var letterboxed: Bool
    public var filmMode: Bool
    public var fps: Double

    public init(width: Int = 720, height: Int = 576, standard: String = "PAL", aspect: String = "16:9",
                letterboxed: Bool = false, filmMode: Bool = false, fps: Double = 25) {
        self.width = width
        self.height = height
        self.standard = standard
        self.aspect = aspect
        self.letterboxed = letterboxed
        self.filmMode = filmMode
        self.fps = fps
    }

    /// Sample aspect ratio that makes the stored frame display at `aspect`.
    public var sampleAspectRatio: (num: Int, den: Int) {
        let (dn, dd) = aspect == "16:9" ? (16, 9) : (4, 3)
        return Fraction.reduce(dn * height, dd * width)
    }

    /// Width in square pixels when displayed at `aspect`.
    public var displayWidth: Int {
        let sar = sampleAspectRatio
        return Int((Double(width) * Double(sar.num) / Double(sar.den)).rounded())
    }
}

public struct AudioTrack: Codable, Sendable, Equatable, Hashable, Identifiable {
    public var streamId: Int
    public var logical: Int
    public var format: String     // ac3, dts, lpcm, mpeg1, mpeg2
    public var channels: Int
    public var sampleRate: Int
    public var bits: Int
    public var lang: String       // ISO 639-1 from the IFO, "" when unknown
    public var codeExtension: Int // 3/4 = director's comments

    public var id: Int { streamId }

    public init(streamId: Int, logical: Int = 0, format: String = "ac3", channels: Int = 2,
                sampleRate: Int = 48000, bits: Int = 0, lang: String = "", codeExtension: Int = 0) {
        self.streamId = streamId
        self.logical = logical
        self.format = format
        self.channels = channels
        self.sampleRate = sampleRate
        self.bits = bits
        self.lang = lang
        self.codeExtension = codeExtension
    }

    public var isCommentary: Bool { codeExtension == 3 || codeExtension == 4 }
    public var isLPCM: Bool { format == "lpcm" }

    public var formatLabel: String {
        switch format {
        case "ac3": return "Dolby Digital"
        case "dts": return "DTS"
        case "lpcm": return "LPCM"
        case "mpeg1", "mpeg2": return "MPEG"
        default: return format.uppercased()
        }
    }

    public var channelLabel: String {
        switch channels {
        case 1: return "1.0"
        case 2: return "2.0"
        case 6: return "5.1"
        case 7: return "6.1"
        case 8: return "7.1"
        default: return "\(channels) ch"
        }
    }
}

public struct SubtitleTrack: Codable, Sendable, Equatable, Hashable, Identifiable {
    public var streamId: Int
    public var logical: Int
    public var lang: String
    public var codeExtension: Int

    public var id: Int { streamId }

    public init(streamId: Int, logical: Int = 0, lang: String = "", codeExtension: Int = 0) {
        self.streamId = streamId
        self.logical = logical
        self.lang = lang
        self.codeExtension = codeExtension
    }

    /// 9 = forced captions, 13-15 = director's comments (DVD subpicture code extensions).
    public var isForced: Bool { codeExtension == 9 }
}

enum Fraction {
    static func gcd(_ a: Int, _ b: Int) -> Int { b == 0 ? abs(a) : gcd(b, a % b) }
    static func reduce(_ n: Int, _ d: Int) -> (num: Int, den: Int) {
        let g = gcd(n, d)
        return g == 0 ? (n, d) : (n / g, d / g)
    }
}
