import Foundation

public struct HistoryEntry: Codable, Identifiable, Equatable, Sendable {
    public enum Result: String, Codable, Sendable { case success, warning, failed, cancelled }

    public var id: UUID
    public var date: Date
    public var metadata: MediaMetadata
    public var volumeLabel: String
    public var titleIndex: Int
    public var preset: QualityPreset
    public var videoCodec: String
    public var storage: StorageTarget
    public var localPath: String?
    public var nasPath: String?
    public var sizeBytes: Int64
    public var durationSeconds: Double
    public var ripSeconds: Double
    public var result: Result
    public var message: String?
    public var warnings: [String]
    public var plexURL: String?

    public init(id: UUID = UUID(), date: Date = Date(), metadata: MediaMetadata, volumeLabel: String, titleIndex: Int,
                preset: QualityPreset, videoCodec: String, storage: StorageTarget, localPath: String? = nil,
                nasPath: String? = nil, sizeBytes: Int64 = 0, durationSeconds: Double = 0, ripSeconds: Double = 0,
                result: Result, message: String? = nil, warnings: [String] = [], plexURL: String? = nil) {
        self.id = id
        self.date = date
        self.metadata = metadata
        self.volumeLabel = volumeLabel
        self.titleIndex = titleIndex
        self.preset = preset
        self.videoCodec = videoCodec
        self.storage = storage
        self.localPath = localPath
        self.nasPath = nasPath
        self.sizeBytes = sizeBytes
        self.durationSeconds = durationSeconds
        self.ripSeconds = ripSeconds
        self.result = result
        self.message = message
        self.warnings = warnings
        self.plexURL = plexURL
    }
}

/// Rip history, stored as JSON in Application Support.
public final class HistoryStore: @unchecked Sendable {
    public let url: URL
    private let lock = NSLock()

    public init(url: URL = AppPaths.supportDirectory.appendingPathComponent("history.json")) {
        self.url = url
    }

    public func load() -> [HistoryEntry] {
        lock.lock()
        defer { lock.unlock() }
        guard let data = try? Data(contentsOf: url) else { return [] }
        let dec = JSONDecoder()
        dec.dateDecodingStrategy = .iso8601
        return (try? dec.decode([HistoryEntry].self, from: data)) ?? []
    }

    public func save(_ entries: [HistoryEntry]) {
        lock.lock()
        defer { lock.unlock() }
        let enc = JSONEncoder()
        enc.dateEncodingStrategy = .iso8601
        enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        if let data = try? enc.encode(entries) { try? data.write(to: url, options: .atomic) }
    }

    @discardableResult
    public func append(_ e: HistoryEntry) -> [HistoryEntry] {
        var all = load()
        all.insert(e, at: 0)
        save(all)
        return all
    }
}

public enum AppPaths {
    public static var supportDirectory: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("DiscRip", isDirectory: true)
    }

    /// Private staging area for NAS-only rips.
    public static var stagingDirectory: URL { supportDirectory.appendingPathComponent("Staging", isDirectory: true) }

    public static var cssCacheDirectory: URL {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("DiscRip/dvdcss", isDirectory: true)
    }

    public static var logDirectory: URL {
        let base = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Logs/DiscRip", isDirectory: true)
    }
}

/// Application log (~/Library/Logs/DiscRip/DiscRip.log): disc events and open errors.
public enum AppLog {
    private static let lock = NSLock()

    public static var url: URL { AppPaths.logDirectory.appendingPathComponent("DiscRip.log") }

    public static func write(_ message: String) {
        lock.lock()
        defer { lock.unlock() }
        let fm = FileManager.default
        try? fm.createDirectory(at: AppPaths.logDirectory, withIntermediateDirectories: true)
        // Keep the log small: start over above 1 MB.
        if let size = (try? fm.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.intValue, size > 1_000_000 {
            try? fm.removeItem(at: url)
        }
        if !fm.fileExists(atPath: url.path) { fm.createFile(atPath: url.path, contents: nil) }
        guard let h = try? FileHandle(forWritingTo: url) else { return }
        defer { try? h.close() }
        _ = try? h.seekToEnd()
        h.write(Data((ISO8601DateFormatter().string(from: Date()) + " " + message + "\n").utf8))
    }
}
