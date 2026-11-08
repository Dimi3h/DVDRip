import Foundation
import NetFS
import Network

/// SMB access to the NAS through the macOS SMB client (NetFS mount under /Volumes).
public final class NASService: @unchecked Sendable {
    public struct Config: Sendable, Equatable {
        public var host: String
        public var share: String
        public var username: String
        public var password: String

        public init(host: String, share: String, username: String, password: String) {
            self.host = host.trimmingCharacters(in: .whitespacesAndNewlines)
            self.share = share.trimmingCharacters(in: CharacterSet(charactersIn: "/ "))
            self.username = username
            self.password = password
        }

        public var shareURL: URL? {
            var comps = URLComponents()
            comps.scheme = "smb"
            comps.host = host
            comps.path = "/" + share
            return comps.url
        }
    }

    public let config: Config
    private(set) var mountedByUs = false
    public private(set) var mountPoint: URL?

    public init(config: Config) { self.config = config }

    /// TCP check on port 445 so an offline NAS gives a clear error quickly.
    public static func isReachable(host: String, timeout: TimeInterval = 5) async -> Bool {
        await withCheckedContinuation { cont in
            let conn = NWConnection(host: NWEndpoint.Host(host), port: 445, using: .tcp)
            let lock = NSLock()
            var done = false
            func finish(_ ok: Bool) {
                lock.lock()
                defer { lock.unlock() }
                guard !done else { return }
                done = true
                conn.cancel()
                cont.resume(returning: ok)
            }
            conn.stateUpdateHandler = { state in
                switch state {
                case .ready: finish(true)
                case .failed, .cancelled: finish(false)
                case .waiting: finish(false)
                default: break
                }
            }
            conn.start(queue: .global())
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) { finish(false) }
        }
    }

    /// Returns the existing mount of this share, if any.
    func existingMount() -> URL? {
        let keys: [URLResourceKey] = [.volumeURLForRemountingKey]
        let vols = FileManager.default.mountedVolumeURLs(includingResourceValuesForKeys: keys, options: [.skipHiddenVolumes]) ?? []
        for v in vols {
            guard let remount = (try? v.resourceValues(forKeys: Set(keys)))?.volumeURLForRemounting,
                  remount.scheme?.lowercased() == "smb" else { continue }
            let host = remount.host?.lowercased() ?? ""
            let share = remount.path.trimmingCharacters(in: CharacterSet(charactersIn: "/")).lowercased()
            if host == config.host.lowercased() && share == config.share.lowercased() { return v }
        }
        return nil
    }

    /// Mounts the share (or reuses an existing mount) and returns the mount point.
    public func mount() async throws -> URL {
        if let m = mountPoint { return m }
        guard !config.host.isEmpty, !config.share.isEmpty, let url = config.shareURL else {
            throw DiscRipError.nasNotConfigured
        }
        if let existing = existingMount() {
            mountPoint = existing
            return existing
        }
        guard await Self.isReachable(host: config.host) else { throw DiscRipError.nasUnreachable(host: config.host) }
        let cfg = config
        let result: (Int32, URL?) = await withCheckedContinuation { cont in
            DispatchQueue.global().async {
                let open = NSMutableDictionary()
                open[kNAUIOptionKey] = kNAUIOptionNoUI
                if cfg.username.isEmpty { open[kNetFSUseGuestKey] = true }
                var points: Unmanaged<CFArray>?
                let rc = NetFSMountURLSync(url as CFURL, nil,
                                           cfg.username.isEmpty ? nil : cfg.username as CFString,
                                           cfg.username.isEmpty ? nil : cfg.password as CFString,
                                           open as CFMutableDictionary, nil, &points)
                let arr = points?.takeRetainedValue() as? [String]
                cont.resume(returning: (rc, arr?.first.map { URL(fileURLWithPath: $0) }))
            }
        }
        switch result.0 {
        case 0:
            guard let point = result.1 else { throw DiscRipError.nasMountFailed(code: 0) }
            mountedByUs = true
            mountPoint = point
            return point
        case EAUTH, EACCES, EPERM, -6600, -5045:
            throw DiscRipError.nasAuthFailed(host: config.host)
        case EHOSTUNREACH, ENETUNREACH, ETIMEDOUT, EHOSTDOWN, ECONNREFUSED:
            throw DiscRipError.nasUnreachable(host: config.host)
        case EEXIST:
            if let existing = existingMount() {
                mountPoint = existing
                return existing
            }
            throw DiscRipError.nasMountFailed(code: result.0)
        default:
            throw DiscRipError.nasMountFailed(code: result.0)
        }
    }

    /// Unmounts the share if DiscRip mounted it.
    public func unmountIfNeeded() async {
        guard mountedByUs, let m = mountPoint else { return }
        mountedByUs = false
        mountPoint = nil
        try? await FileManager.default.unmountVolume(at: m, options: [])
    }

    public static func availableCapacity(at url: URL) -> Int64? {
        let v = try? url.resourceValues(forKeys: [.volumeAvailableCapacityKey, .volumeAvailableCapacityForImportantUsageKey])
        if let important = v?.volumeAvailableCapacityForImportantUsage, important > 0 { return important }
        return v?.volumeAvailableCapacity.map(Int64.init)
    }

    /// Copies a file with progress, then verifies the remote size.
    public static func copyVerified(from source: URL, to destination: URL,
                                    progress: @escaping @Sendable (Int64, Int64) -> Void,
                                    isCancelled: @escaping @Sendable () -> Bool) throws {
        let fm = FileManager.default
        let total = (try fm.attributesOfItem(atPath: source.path)[.size] as? NSNumber)?.int64Value ?? 0
        try fm.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
        let partial = destination.appendingPathExtension("part")
        try? fm.removeItem(at: partial)
        guard fm.createFile(atPath: partial.path, contents: nil) else {
            throw DiscRipError.output(message: partial.path, partialRemoved: false)
        }
        let input = try FileHandle(forReadingFrom: source)
        let output = try FileHandle(forWritingTo: partial)
        var copied: Int64 = 0
        do {
            defer {
                try? input.close()
                try? output.close()
            }
            while true {
                if isCancelled() { throw DiscRipError.cancelled }
                let chunk = try input.read(upToCount: 8 * 1024 * 1024) ?? Data()
                if chunk.isEmpty { break }
                try output.write(contentsOf: chunk)
                copied += Int64(chunk.count)
                progress(copied, total)
            }
            try output.synchronize()
        } catch {
            try? fm.removeItem(at: partial)
            if let e = error as? DiscRipError { throw e }
            let ns = error as NSError
            if ns.code == NSFileWriteOutOfSpaceError || (ns.domain == NSPOSIXErrorDomain && ns.code == Int(ENOSPC)) {
                throw DiscRipError.insufficientSpace(location: "NAS", needed: total, available: 0)
            }
            throw DiscRipError.output(message: error.localizedDescription, partialRemoved: true)
        }
        try? fm.removeItem(at: destination)
        try fm.moveItem(at: partial, to: destination)
        let remote = (try fm.attributesOfItem(atPath: destination.path)[.size] as? NSNumber)?.int64Value ?? -1
        guard remote == total else { throw DiscRipError.uploadVerifyFailed(local: total, remote: remote) }
    }
}
