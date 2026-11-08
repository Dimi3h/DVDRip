// Diagnostics without the GUI.
//   discrip-cli info <source>                         disc description (JSON)
//   discrip-cli rip <source> <out.mkv> [preset] [seconds]
// <source> is /dev/rdiskN, a mount point, an ISO or a VIDEO_TS folder. DISCRIP_DEBUG=1 logs every packet.
import DiscRipCore
import Foundation

let args = CommandLine.arguments
guard args.count >= 3 else {
    print("usage: discrip-cli info <source> | rip <source> <out.mkv> [lossless|balanced|compact|compatible]")
    exit(2)
}
do {
    let disc = try NativeDisc(path: args[2], cssCacheDirectory: AppPaths.cssCacheDirectory)
    let info = try disc.info()
    if args[1] == "info" {
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        print(String(decoding: try enc.encode(info), as: UTF8.self))
        for c in TitleScoring.rank(info.titles).prefix(5) {
            print("title \(c.title.index): \(Int(c.title.duration))s chapters \(c.title.chapters) cells \(c.title.cells) score \(Int(c.score)) dups \(c.duplicates)")
        }
        exit(0)
    }
    guard args.count >= 4, let t = TitleScoring.mainFeature(info.titles) else { exit(2) }
    let preset = args.count > 4 ? QualityPreset(rawValue: args[4]) ?? .lossless : .lossless
    let req = RipRequest(title: t, audio: t.audio, subtitles: t.subtitles, preset: preset,
                         bitrateKbps: AppSettings().bitrateKbps(for: preset), lpcmToFlac: false,
                         metadata: MediaMetadata(title: info.volumeLabel), outputURL: URL(fileURLWithPath: args[3]))
    var last = Date.distantPast
    let out = try Ripper(disc: disc).rip(req, cancel: CancelToken(), log: { level, msg in
        FileHandle.standardError.write(Data("[\(level)] \(msg)\n".utf8))
    }) { p in
        if Date().timeIntervalSince(last) > 2 {
            last = Date()
            print(String(format: "%5.1f%%  %.1f MB/s", p.fraction * 100, p.megabytesPerSecond))
        }
    }
    print(out)
} catch {
    print("ERROR:", error.localizedDescription)
    exit(1)
}
