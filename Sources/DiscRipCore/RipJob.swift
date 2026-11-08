import Foundation

public enum LocalFolder {
    public static func bookmark(for url: URL) throws -> Data {
        try url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
    }

    /// Resolves the chosen local folder and checks that it exists and is writable.
    public static func resolve(_ s: AppSettings) throws -> URL {
        guard let data = s.localFolderBookmark ?? s.localFolderPath.map({ Data($0.utf8) }) else {
            throw DiscRipError.folderNotChosen
        }
        var url: URL?
        if s.localFolderBookmark != nil {
            var stale = false
            url = try? URL(resolvingBookmarkData: data, options: [.withoutUI, .withoutMounting], relativeTo: nil,
                           bookmarkDataIsStale: &stale)
        }
        if url == nil, let p = s.localFolderPath { url = URL(fileURLWithPath: p, isDirectory: true) }
        guard let url else { throw DiscRipError.folderNotChosen }
        var isDir: ObjCBool = false
        guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir), isDir.boolValue else {
            throw DiscRipError.folderUnavailable(path: s.localFolderPath ?? url.path)
        }
        guard FileManager.default.isWritableFile(atPath: url.path) else {
            throw DiscRipError.folderNotWritable(path: url.path)
        }
        return url
    }

    public static func availableCapacity(at url: URL) -> Int64? {
        var probe = url
        while !FileManager.default.fileExists(atPath: probe.path) && probe.pathComponents.count > 1 {
            probe.deleteLastPathComponent()
        }
        return NASService.availableCapacity(at: probe)
    }
}

public struct RipPlan: Sendable {
    public var sourcePath: String
    public var volumeLabel: String
    public var title: TitleInfo
    public var audio: [AudioTrack]
    public var subtitles: [SubtitleTrack]
    public var preset: QualityPreset
    public var bitrateKbps: Int
    public var lpcmToFlac: Bool
    public var metadata: MediaMetadata
    public var storage: StorageTarget

    public init(sourcePath: String, volumeLabel: String, title: TitleInfo, audio: [AudioTrack],
                subtitles: [SubtitleTrack], preset: QualityPreset, bitrateKbps: Int, lpcmToFlac: Bool,
                metadata: MediaMetadata, storage: StorageTarget) {
        self.sourcePath = sourcePath
        self.volumeLabel = volumeLabel
        self.title = title
        self.audio = audio
        self.subtitles = subtitles
        self.preset = preset
        self.bitrateKbps = bitrateKbps
        self.lpcmToFlac = lpcmToFlac
        self.metadata = metadata
        self.storage = storage
    }

    public var estimatedBytes: Int64 {
        SizeEstimator.estimate(title: title, preset: preset, bitrateKbps: bitrateKbps, audio: audio,
                               subtitleCount: subtitles.count, lpcmToFlac: lpcmToFlac)
    }
}

public enum JobPhase: String, Sendable, CaseIterable {
    case preparing, reading, muxing, encoding, uploading, plex, done

    public var label: String {
        switch self {
        case .preparing: return String(localized: "Voorbereiden")
        case .reading: return String(localized: "Lezen")
        case .muxing: return String(localized: "Muxen")
        case .encoding: return String(localized: "Encoderen")
        case .uploading: return String(localized: "Uploaden")
        case .plex: return String(localized: "Plex")
        case .done: return String(localized: "Klaar")
        }
    }
}

public struct JobUpdate: Sendable {
    public var phase: JobPhase
    public var rip: RipProgress?
    public var uploadDone: Int64 = 0
    public var uploadTotal: Int64 = 0
    public var uploadElapsed: TimeInterval = 0
    public var note: String?
}

public struct JobResult: Sendable {
    public var localURL: URL?
    public var nasURL: URL?
    public var plexURL: URL?
    public var plexAdded: Bool
    public var warnings: [String]
    public var outcome: RipOutcome
    public var ripSeconds: TimeInterval
}

/// Runs one rip end to end: checks, rip, NAS upload with verification, Plex scan.
public final class RipJob: @unchecked Sendable {
    public let plan: RipPlan
    let settings: AppSettings
    let nasPassword: String
    let plexToken: String
    let disc: NativeDisc
    let logURL: URL
    private var logHandle: FileHandle?

    public init(plan: RipPlan, settings: AppSettings, nasPassword: String, plexToken: String, disc: NativeDisc,
                logDirectory: URL = AppPaths.logDirectory) {
        self.plan = plan
        self.settings = settings
        self.nasPassword = nasPassword
        self.plexToken = plexToken
        self.disc = disc
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd-HHmmss"
        try? FileManager.default.createDirectory(at: logDirectory, withIntermediateDirectories: true)
        logURL = logDirectory.appendingPathComponent("rip-\(f.string(from: Date())).log")
    }

    func log(_ s: String) {
        if logHandle == nil {
            FileManager.default.createFile(atPath: logURL.path, contents: nil)
            logHandle = try? FileHandle(forWritingTo: logURL)
        }
        logHandle?.write(Data((ISO8601DateFormatter().string(from: Date()) + " " + s + "\n").utf8))
    }

    public func run(cancel: CancelToken, update: @escaping @Sendable (JobUpdate) -> Void) async throws -> JobResult {
        defer { try? logHandle?.close() }
        do {
            let result = try await perform(cancel: cancel, update: update)
            for w in result.warnings { log("WARNING: \(w)") }
            log("Finished")
            return result
        } catch {
            log("FAILED: \((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)")
            throw error
        }
    }

    private func perform(cancel: CancelToken, update: @escaping @Sendable (JobUpdate) -> Void) async throws -> JobResult {
        update(JobUpdate(phase: .preparing))
        log("Rip \(plan.metadata.title) title \(plan.title.index) preset \(plan.preset.rawValue) storage \(plan.storage.rawValue)")
        var warnings: [String] = []
        let m = plan.metadata

        // Destinations
        var localRoot: URL?
        if plan.storage.usesLocal { localRoot = try LocalFolder.resolve(settings) }
        var nas: NASService?
        var nasRoot: URL?
        if plan.storage.usesNAS {
            guard settings.nasConfigured else { throw DiscRipError.nasNotConfigured }
            let service = NASService(config: .init(host: settings.nasHost, share: settings.nasShare,
                                                   username: settings.nasUsername, password: nasPassword))
            nasRoot = try await service.mount()
            nas = service
        }
        defer {
            if let nas { Task { await nas.unmountIfNeeded() } }
        }

        let localRel = PlexNaming.relativePath(m, moviesBase: settings.localMoviesSubfolder,
                                               homeVideosBase: settings.localHomeVideosSubfolder)
        let nasRel = PlexNaming.relativePath(m, moviesBase: settings.nasMoviesFolder,
                                             homeVideosBase: settings.nasHomeVideosFolder)
        let directToNAS = plan.storage == .nas && settings.nasDirectWrite
        let ripTarget: URL
        if let localRoot {
            ripTarget = localRoot.appendingPathComponent(localRel)
        } else if directToNAS, let nasRoot {
            ripTarget = nasRoot.appendingPathComponent(nasRel)
        } else {
            ripTarget = AppPaths.stagingDirectory.appendingPathComponent(URL(fileURLWithPath: nasRel).lastPathComponent)
        }
        let nasTarget = nasRoot?.appendingPathComponent(nasRel)

        // Free space
        let needed = Int64(Double(plan.estimatedBytes) * 1.05)
        func check(_ url: URL, _ name: String) throws {
            if let free = LocalFolder.availableCapacity(at: url), free < needed {
                throw DiscRipError.insufficientSpace(location: name, needed: needed, available: free)
            }
        }
        try check(ripTarget.deletingLastPathComponent(),
                  localRoot != nil ? ripTarget.deletingLastPathComponent().path
                  : directToNAS ? "NAS" : String(localized: "deze Mac (tijdelijke opslag)"))
        if let nasTarget, !directToNAS { try check(nasTarget.deletingLastPathComponent(), "NAS") }

        // Rip to a hidden partial file next to the target, rename when complete.
        let fm = FileManager.default
        try fm.createDirectory(at: ripTarget.deletingLastPathComponent(), withIntermediateDirectories: true)
        let partial = ripTarget.deletingLastPathComponent().appendingPathComponent("." + ripTarget.lastPathComponent + ".partial")
        try? fm.removeItem(at: partial)
        let request = RipRequest(title: plan.title, audio: plan.audio, subtitles: plan.subtitles, preset: plan.preset,
                                 bitrateKbps: plan.bitrateKbps, lpcmToFlac: plan.lpcmToFlac, metadata: m,
                                 outputURL: partial)
        update(JobUpdate(phase: .reading))
        let started = Date()
        let ripper = Ripper(disc: disc)
        let lossy = plan.preset.isLossy
        let outcome: RipOutcome = try await withTaskCancellationHandler {
            try await Task.detached(priority: .userInitiated) { [self] in
                try ripper.rip(request, cancel: cancel, log: { level, msg in self.log("[\(level)] \(msg)") }) { p in
                    let phase: JobPhase = p.bytesDone == 0 ? .reading : (lossy ? .encoding : .muxing)
                    update(JobUpdate(phase: phase, rip: p))
                }
            }.value
        } onCancel: {
            cancel.cancel()
        }
        let ripSeconds = Date().timeIntervalSince(started)
        try? fm.removeItem(at: ripTarget)
        try fm.moveItem(at: partial, to: ripTarget)
        log("Rip done: \(outcome.outputBytes) bytes in \(Int(ripSeconds)) s, codec \(outcome.videoCodec)")

        if outcome.fellBackToH264 {
            warnings.append(String(localized: "Deze Mac heeft geen HEVC-hardware-encoder; H.264 werd gebruikt."))
        }
        if outcome.badSectors > 0 {
            let ranges = outcome.badRanges.prefix(5).map { "\($0.lowerBound)–\($0.upperBound)" }.joined(separator: ", ")
            warnings.append(String(localized: "\(outcome.badSectors) onleesbare sectoren overgeslagen (sectoren \(ranges)). Op die plaatsen kan het beeld haperen."))
        }

        // NAS
        var nasFinal: URL?
        if let nasTarget {
            if directToNAS {
                nasFinal = ripTarget
            } else {
                if cancel.isCancelled { throw DiscRipError.cancelled }
                let uploadStart = Date()
                update(JobUpdate(phase: .uploading))
                try await Task.detached(priority: .userInitiated) {
                    try NASService.copyVerified(from: ripTarget, to: nasTarget, progress: { done, total in
                        update(JobUpdate(phase: .uploading, uploadDone: done, uploadTotal: total,
                                         uploadElapsed: Date().timeIntervalSince(uploadStart)))
                    }, isCancelled: { cancel.isCancelled })
                }.value
                nasFinal = nasTarget
                log("Uploaded and verified: \(nasTarget.path)")
                if plan.storage == .nas { try? fm.removeItem(at: ripTarget) }
            }
        }

        // Plex
        var plexURL: URL?
        var plexAdded = false
        if nasFinal != nil {
            update(JobUpdate(phase: .plex))
            do {
                let found = try await refreshPlex(folderName: PlexNaming.baseName(m))
                plexURL = found.url
                plexAdded = found.added
                if !found.added { warnings.append(DiscRipError.plexNotFound.localizedDescription) }
            } catch let e as DiscRipError where e == .plexNotConfigured {
                log("Plex not configured, scan skipped")
            } catch {
                warnings.append(String(localized: "Plex: \(error.localizedDescription)"))
            }
        }
        update(JobUpdate(phase: .done))
        return JobResult(localURL: plan.storage.usesLocal ? ripTarget : nil, nasURL: nasFinal, plexURL: plexURL,
                         plexAdded: plexAdded, warnings: warnings, outcome: outcome, ripSeconds: ripSeconds)
    }

    func refreshPlex(folderName: String) async throws -> (added: Bool, url: URL?) {
        let m = plan.metadata
        let sectionId = m.kind.usesHomeVideoFolder ? settings.plexHomeVideosSectionId : settings.plexMoviesSectionId
        guard settings.plexConfigured, !plexToken.isEmpty, !sectionId.isEmpty,
              let plex = PlexClient(urlString: settings.plexURL, token: plexToken) else {
            throw DiscRipError.plexNotConfigured
        }
        let sections = try await plex.sections()
        let section = sections.first { $0.key == sectionId }
        let base = m.kind.usesHomeVideoFolder ? settings.nasHomeVideosFolder : settings.nasMoviesFolder
        guard let path = PlexClient.serverFolderPath(locations: section?.locations ?? [], nasBaseFolder: base,
                                                     folderName: folderName) else {
            throw DiscRipError.plexNotFound
        }
        log("Plex refresh section \(sectionId) path \(path)")
        try await plex.refresh(sectionId: sectionId, path: path)
        guard let item = try await plex.waitForItem(sectionId: sectionId, metadata: m) else { return (false, nil) }
        let machine = try? await plex.machineIdentifier()
        return (true, machine.flatMap { PlexClient.webURL(machineIdentifier: $0, ratingKey: item.ratingKey) })
    }
}
