import CDiscRip
import Foundation

public struct RipProgress: Sendable, Equatable {
    public enum Phase: Sendable { case reading, encoding, finishing }
    public var phase: Phase
    public var bytesDone: Int64
    public var bytesTotal: Int64
    public var elapsed: TimeInterval
    public var mediaDone: TimeInterval
    public var mediaTotal: TimeInterval
    public var encoderFPS: Double
    public var outputBytes: Int64
    public var badSectors: Int

    public init(phase: Phase = .reading, bytesDone: Int64 = 0, bytesTotal: Int64 = 0, elapsed: TimeInterval = 0,
                mediaDone: TimeInterval = 0, mediaTotal: TimeInterval = 0, encoderFPS: Double = 0,
                outputBytes: Int64 = 0, badSectors: Int = 0) {
        self.phase = phase
        self.bytesDone = bytesDone
        self.bytesTotal = bytesTotal
        self.elapsed = elapsed
        self.mediaDone = mediaDone
        self.mediaTotal = mediaTotal
        self.encoderFPS = encoderFPS
        self.outputBytes = outputBytes
        self.badSectors = badSectors
    }

    public var fraction: Double { bytesTotal > 0 ? min(Double(bytesDone) / Double(bytesTotal), 1) : 0 }
    public var megabytesPerSecond: Double { elapsed > 0 ? Double(bytesDone) / 1_000_000 / elapsed : 0 }

    public var eta: TimeInterval? {
        guard elapsed > 3, bytesDone > 0, bytesTotal > bytesDone else { return nil }
        return Double(bytesTotal - bytesDone) / (Double(bytesDone) / elapsed)
    }

    /// Encoding speed relative to real time (1.0 = real time).
    public var realtimeFactor: Double? {
        guard elapsed > 3, mediaDone > 0 else { return nil }
        return mediaDone / elapsed
    }
}

public struct RipRequest: Sendable {
    public var title: TitleInfo
    public var audio: [AudioTrack]
    public var subtitles: [SubtitleTrack]
    public var preset: QualityPreset
    public var bitrateKbps: Int
    public var lpcmToFlac: Bool
    public var metadata: MediaMetadata
    public var outputURL: URL

    public init(title: TitleInfo, audio: [AudioTrack], subtitles: [SubtitleTrack], preset: QualityPreset,
                bitrateKbps: Int, lpcmToFlac: Bool, metadata: MediaMetadata, outputURL: URL) {
        self.title = title
        self.audio = audio
        self.subtitles = subtitles
        self.preset = preset
        self.bitrateKbps = bitrateKbps
        self.lpcmToFlac = lpcmToFlac
        self.metadata = metadata
        self.outputURL = outputURL
    }
}

public struct RipOutcome: Sendable, Equatable {
    public var outputBytes: Int64
    public var videoCodec: QualityPreset.VideoCodec
    public var fellBackToH264: Bool
    public var badRanges: [ClosedRange<UInt32>]
    public var badSectors: Int
    public var mediaSeconds: Double
}

/// Thread-safe cancellation flag shared with the native pipeline.
public final class CancelToken: @unchecked Sendable {
    let flag: UnsafeMutablePointer<Int32>
    public init() {
        flag = .allocate(capacity: 1)
        flag.initialize(to: 0)
    }
    deinit { flag.deallocate() }
    public func cancel() { OSAtomicStore(flag, 1) }
    public var isCancelled: Bool { flag.pointee != 0 }
}

@inline(__always) private func OSAtomicStore(_ p: UnsafeMutablePointer<Int32>, _ v: Int32) {
    // Plain aligned 32-bit store; the native side only polls the value.
    p.pointee = v
}

public enum TrackNaming {
    public static func audioName(_ t: AudioTrack) -> String {
        var name = "\(Languages.displayName(t.lang)) · \(t.formatLabel) \(t.channelLabel)"
        if t.isCommentary { name += " · " + String(localized: "Commentaar") }
        return name
    }

    public static func subtitleName(_ t: SubtitleTrack) -> String {
        var name = Languages.displayName(t.lang)
        if t.isForced { name += " (" + String(localized: "geforceerd") + ")" }
        if (13...15).contains(t.codeExtension) { name += " · " + String(localized: "Commentaar") }
        return name
    }
}

private final class CallbackBox {
    let progress: (RipProgress) -> Void
    let log: (Int, String) -> Void
    init(progress: @escaping (RipProgress) -> Void, log: @escaping (Int, String) -> Void) {
        self.progress = progress
        self.log = log
    }
}

public final class Ripper {
    let disc: NativeDisc

    public init(disc: NativeDisc) { self.disc = disc }

    /// Blocking. Run on a background thread.
    public func rip(_ req: RipRequest, cancel: CancelToken,
                    log: @escaping (Int, String) -> Void = { _, _ in },
                    progress: @escaping (RipProgress) -> Void) throws -> RipOutcome {
        guard let handle = disc.rawHandle else { throw DiscRipError.notDVDVideo(detail: "") }
        let pool = CStringPool()
        let box = CallbackBox(progress: progress, log: log)
        let ctx = Unmanaged.passRetained(box).toOpaque()
        defer { Unmanaged<CallbackBox>.fromOpaque(ctx).release() }

        var o = dr_rip_options()
        o.title = Int32(req.title.index)
        o.angle = 1
        o.audio_stream_ids = UnsafePointer(pool.ints(req.audio.map(\.streamId)))
        o.audio_languages = UnsafePointer(pool.array(req.audio.map { Languages.iso639_2B($0.lang) }))
        o.audio_names = UnsafePointer(pool.array(req.audio.map(TrackNaming.audioName)))
        o.audio_count = Int32(req.audio.count)
        o.subtitle_stream_ids = UnsafePointer(pool.ints(req.subtitles.map(\.streamId)))
        o.subtitle_languages = UnsafePointer(pool.array(req.subtitles.map { Languages.iso639_2B($0.lang) }))
        o.subtitle_names = UnsafePointer(pool.array(req.subtitles.map(TrackNaming.subtitleName)))
        o.subtitle_count = Int32(req.subtitles.count)
        o.default_audio_index = req.audio.isEmpty ? -1 : 0
        switch req.preset.videoCodec {
        case .copy: o.video_mode = Int32(DR_VIDEO_COPY)
        case .hevc: o.video_mode = Int32(DR_VIDEO_HEVC)
        case .h264: o.video_mode = Int32(DR_VIDEO_H264)
        }
        o.video_bitrate = Int64(req.bitrateKbps) * 1000
        o.allow_h264_fallback = 1
        o.lpcm_to_flac = req.lpcmToFlac ? 1 : 0
        o.output_path = pool.string(req.outputURL.path)
        let tags = PlexNaming.tags(req.metadata).flatMap { [$0.0, $0.1] }
        o.global_tags = UnsafePointer(pool.array(tags))
        o.chapter_name_format = pool.string(String(localized: "Hoofdstuk %d"))
        o.cancel = UnsafeMutablePointer(cancel.flag)
        o.ctx = ctx
        o.progress = { ctx, p in
            guard let ctx, let p else { return }
            let box = Unmanaged<CallbackBox>.fromOpaque(ctx).takeUnretainedValue()
            let v = p.pointee
            box.progress(RipProgress(
                phase: v.phase == Int32(DR_PHASE_ENCODING) ? .encoding : v.phase == Int32(DR_PHASE_FINISHING) ? .finishing : .reading,
                bytesDone: v.bytes_done, bytesTotal: v.bytes_total, elapsed: v.elapsed_seconds,
                mediaDone: v.media_seconds_done, mediaTotal: v.media_seconds_total, encoderFPS: v.encoder_fps,
                outputBytes: v.output_bytes, badSectors: Int(v.bad_sectors)))
        }
        o.log = { ctx, level, msg in
            guard let ctx, let msg else { return }
            Unmanaged<CallbackBox>.fromOpaque(ctx).takeUnretainedValue().log(Int(level), String(cString: msg))
        }

        var res = dr_rip_result()
        let rc = dr_rip(handle, &o, &res)
        let message = withUnsafeBytes(of: res.message) { raw in
            String(cString: raw.bindMemory(to: CChar.self).baseAddress!)
        }
        let removed = res.partial_file_removed != 0
        switch Int(rc) {
        case Int(DR_OK):
            var ranges: [ClosedRange<UInt32>] = []
            withUnsafeBytes(of: res.bad_range_start) { s in
                withUnsafeBytes(of: res.bad_range_length) { l in
                    let starts = s.bindMemory(to: UInt32.self), lens = l.bindMemory(to: UInt32.self)
                    for i in 0..<Int(res.bad_range_count) where lens[i] > 0 {
                        ranges.append(starts[i]...(starts[i] + lens[i] - 1))
                    }
                }
            }
            let codec: QualityPreset.VideoCodec = res.video_mode_used == Int32(DR_VIDEO_HEVC) ? .hevc
                : res.video_mode_used == Int32(DR_VIDEO_H264) ? .h264 : .copy
            return RipOutcome(outputBytes: res.output_bytes, videoCodec: codec, fellBackToH264: res.fell_back_to_h264 != 0,
                              badRanges: ranges, badSectors: Int(res.bad_sectors), mediaSeconds: res.media_seconds)
        case Int(DR_CANCELLED): throw DiscRipError.cancelled
        case Int(DR_ERR_CSS_KEY): throw DiscRipError.cssKeyMissing(title: req.title.index)
        case Int(DR_ERR_READ_REFUSED): throw DiscRipError.readRefused(regionHint: true)
        case Int(DR_ERR_READ): throw DiscRipError.tooManyReadErrors
        case Int(DR_ERR_DEVICE_GONE): throw DiscRipError.deviceGone
        case Int(DR_ERR_ENCODER):
            throw message.contains("No hardware") ? DiscRipError.encoderUnavailable : DiscRipError.encoderFailed(partialRemoved: removed)
        case Int(DR_ERR_NO_SPACE):
            throw DiscRipError.insufficientSpace(location: req.outputURL.deletingLastPathComponent().path, needed: 0, available: 0)
        case Int(DR_ERR_OUTPUT): throw DiscRipError.output(message: message, partialRemoved: removed)
        case Int(DR_ERR_OPEN_TITLE), Int(DR_ERR_DEMUX): throw DiscRipError.notDVDVideo(detail: message)
        default: throw DiscRipError.internalError(message)
        }
    }
}
