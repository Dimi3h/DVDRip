import AppKit
import DiscRipCore
import Foundation
import Observation
import UserNotifications

enum Route: Hashable {
    case disc
    case metadata
    case rip
}

enum SidebarItem: Hashable {
    case disc
    case history
}

/// Where the current DVD-Video comes from.
struct DiscSource: Equatable {
    enum Kind: Equatable { case drive(OpticalMedia), file(URL) }
    var kind: Kind
    var displayName: String

    var media: OpticalMedia? {
        if case let .drive(m) = kind { return m }
        return nil
    }
}

struct RipState {
    var phase: JobPhase = .preparing
    var progress = RipProgress()
    var uploadDone: Int64 = 0
    var uploadTotal: Int64 = 0
    var uploadElapsed: TimeInterval = 0
    var startedAt = Date()
    var result: JobResult?
    var error: DiscRipError?
    var isRunning = true
}

@MainActor
@Observable
final class AppModel {
    // Persistence
    let store = SettingsStore()
    let historyStore = HistoryStore()
    var settings: AppSettings { didSet { store.save(settings) } }
    var history: [HistoryEntry] = []

    // Navigation
    var sidebar: SidebarItem? = .disc
    var path: [Route] = []

    // Hardware
    let monitor = DriveMonitor()
    var drives: [OpticalDrive] = []
    var discs: [OpticalMedia] = []

    // Current disc
    var source: DiscSource?
    var disc: NativeDisc?
    var info: DiscInfo?
    var isLoadingDisc = false
    var loadError: DiscRipError?
    var regionStatus: Region.Compatibility = .regionFree
    var driveRegion: Region.DriveState?

    // Selection
    var candidates: [TitleScoring.Candidate] = []
    var selectedTitle: Int?
    var selectedAudio: Set<Int> = []
    var selectedSubtitles: Set<Int> = []
    var preset: QualityPreset = .lossless
    var storage: StorageTarget = .local
    var lpcmToFlac = false

    // Metadata
    var metadataMode: MetadataMode = .automatic
    var metadata = MediaMetadata(title: "")
    var searchQuery = ""
    var searchYear = ""
    var searchResults: [TMDBClient.Movie] = []
    var isSearching = false
    var searchError: String?
    var chosenMovieId: Int?

    // Rip
    var rip: RipState?
    private var ripTask: Task<Void, Never>?
    private var cancelToken: CancelToken?
    private var activity: NSObjectProtocol?

    // Batch flow
    var nextDiscPrompt = false
    private var ejectedAfterRip = false
    var banner: String?

    let encoders = NativeDisc.hardwareEncoders

    init() {
        settings = store.load()
        history = historyStore.load()
        preset = settings.defaultPreset
        storage = settings.storageTarget
        lpcmToFlac = settings.lpcmToFlac
        metadataMode = settings.metadataMode
        monitor.onChange = { [weak self] in self?.hardwareChanged() }
        monitor.start()
        hardwareChanged()
        if Bundle.main.bundleIdentifier != nil {
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
        }
    }

    // MARK: Display names

    /// "PLOP_EN_DE_KABOUTERKERMIS" -> "Plop En De Kabouterkermis".
    static func prettyTitle(volumeLabel: String) -> String {
        TitleNormalizer.searchTitle(fromVolumeLabel: volumeLabel)
    }

    /// Readable name of the open source: the disc label for a drive, the file name for an image.
    var sourceTitle: String? {
        guard let s = source else { return nil }
        switch s.kind {
        case let .drive(m):
            // The volume name may arrive after the disc itself (mounting); use the latest known one.
            let current = discs.first { $0.bsdName == m.bsdName }?.volumeName ?? m.volumeName
            guard let label = info?.volumeLabel.nonEmpty ?? current else { return nil }
            return Self.prettyTitle(volumeLabel: label)
        case let .file(url): return Self.prettyTitle(volumeLabel: info?.volumeLabel.nonEmpty ?? url.deletingPathExtension().lastPathComponent)
        }
    }

    // MARK: Hardware

    var primaryDrive: OpticalDrive? { drives.first }
    var dvdInDrive: OpticalMedia? { discs.first { $0.kind == .dvd } }
    var isRipping: Bool { rip?.isRunning == true }

    private func hardwareChanged() {
        let previous = Set(discs.map(\.bsdName))
        drives = monitor.drives
        discs = monitor.media
        // The disc being shown was ejected or the drive was unplugged. While a rip result is on
        // screen (the disc was just ejected by DiscRip) the result stays until "Klaar".
        if let m = source?.media, !discs.contains(where: { $0.bsdName == m.bsdName }), !isRipping, rip == nil {
            closeDisc()
            banner = String(localized: "De disc werd verwijderd.")
        }
        // A new DVD appeared: open it straight away (batch ripping).
        if let dvd = dvdInDrive, !previous.contains(dvd.bsdName), source == nil, !isRipping {
            nextDiscPrompt = false
            openDrive(dvd)
        }
    }

    // MARK: Opening

    func openDrive(_ media: OpticalMedia) {
        guard media.kind == .dvd else {
            loadError = .notDVDVideo(detail: media.kind.rawValue)
            return
        }
        let name = media.volumeName ?? media.bsdName
        load(DiscSource(kind: .drive(media), displayName: name), paths: [media.rawDevicePath] + [media.volumePath?.path].compactMap { $0 })
    }

    func openFile(_ url: URL) {
        var target = url
        // A VIDEO_TS.IFO or a VIDEO_TS folder: libdvdread wants the folder (or its parent).
        if url.pathExtension.lowercased() == "ifo" { target = url.deletingLastPathComponent() }
        load(DiscSource(kind: .file(url), displayName: url.lastPathComponent), paths: [target.path])
    }

    func presentOpenPanel() {
        let panel = NSOpenPanel()
        panel.title = String(localized: "ISO-bestand of VIDEO_TS-map kiezen")
        panel.canChooseFiles = true
        panel.canChooseDirectories = true
        panel.allowsMultipleSelection = false
        panel.allowedContentTypes = [.diskImage, .folder, .data]
        if panel.runModal() == .OK, let url = panel.url { openFile(url) }
    }

    private func load(_ src: DiscSource, paths: [String]) {
        guard !isRipping else { return }
        closeDisc()
        source = src
        isLoadingDisc = true
        loadError = nil
        banner = nil
        sidebar = .disc
        let cache = AppPaths.cssCacheDirectory
        // A drive that has spun down needs a few seconds before reads succeed: retry before
        // concluding that the disc is not a DVD-Video. Files and folders are tried once.
        let attempts = src.media != nil ? 3 : 1
        Task {
            let result: Result<(NativeDisc, DiscInfo), DiscRipError> = await Task.detached(priority: .userInitiated) {
                var lastError: DiscRipError = .notDVDVideo(detail: "")
                for attempt in 1...attempts {
                    if attempt > 1 {
                        AppLog.write("Retrying in 4 s (attempt \(attempt) of \(attempts))")
                        try? await Task.sleep(nanoseconds: 4_000_000_000)
                    }
                    for p in paths {
                        AppLog.write("Opening \(p)")
                        do {
                            let d = try NativeDisc(path: p, cssCacheDirectory: cache)
                            let i = try d.info()
                            if i.titles.isEmpty { throw DiscRipError.notDVDVideo(detail: "no titles in \(p)") }
                            AppLog.write("Opened \(p): \(i.volumeLabel), \(i.titles.count) titles")
                            return .success((d, i))
                        } catch let e as DiscRipError {
                            if case let .notDVDVideo(detail) = e { AppLog.write("Failed: \(detail)") }
                            lastError = e
                        } catch {
                            AppLog.write("Failed: \(error)")
                            lastError = .notDVDVideo(detail: error.localizedDescription)
                        }
                    }
                }
                return .failure(lastError)
            }.value
            guard self.source == src else { return }
            self.isLoadingDisc = false
            switch result {
            case let .success((d, i)):
                self.disc = d
                self.info = i
                self.prepareSelection()
                if let m = src.media {
                    self.driveRegion = NativeDisc.driveRegionState(rawDevicePath: m.rawDevicePath)
                    self.regionStatus = Region.compatibility(drive: self.driveRegion, discDisabledMask: i.discRegionMask)
                } else {
                    self.regionStatus = .regionFree
                }
                self.path = [.disc]
                Haptics.tap()
            case let .failure(e):
                self.loadError = e
            }
        }
    }

    func closeDisc() {
        source = nil
        disc = nil
        info = nil
        candidates = []
        selectedTitle = nil
        path = []
        loadError = nil
        regionStatus = .regionFree
        driveRegion = nil
    }

    // MARK: Selection

    var currentTitle: TitleInfo? {
        guard let info, let selectedTitle else { return nil }
        return info.titles.first { $0.index == selectedTitle }
    }

    var orderedAudio: [AudioTrack] {
        Languages.ordered(currentTitle?.audio ?? [], preferred: settings.preferredLanguages) { $0.lang }
    }

    var orderedSubtitles: [SubtitleTrack] {
        Languages.ordered(currentTitle?.subtitles ?? [], preferred: settings.preferredLanguages) { $0.lang }
    }

    var chosenAudio: [AudioTrack] { orderedAudio.filter { selectedAudio.contains($0.streamId) } }
    var chosenSubtitles: [SubtitleTrack] { orderedSubtitles.filter { selectedSubtitles.contains($0.streamId) } }

    private func prepareSelection() {
        guard let info else { return }
        candidates = TitleScoring.rank(info.titles)
        selectTitle(candidates.first?.title.index)
        preset = settings.defaultPreset
        storage = settings.storageTarget
        lpcmToFlac = settings.lpcmToFlac
        let guess = TitleNormalizer.searchTitle(fromVolumeLabel: info.volumeLabel.isEmpty ? (source?.displayName ?? "") : info.volumeLabel)
        searchQuery = guess
        searchYear = ""
        searchResults = []
        searchError = nil
        chosenMovieId = nil
        metadata = MediaMetadata(title: guess)
        metadataMode = (settings.metadataMode == .automatic && hasTMDBKey) ? .automatic : .manual
    }

    func selectTitle(_ index: Int?) {
        selectedTitle = index
        guard let t = currentTitle else { return }
        selectedAudio = Set(t.audio.map(\.streamId))
        let prefs = Set(settings.preferredLanguages.map { $0.lowercased() })
        switch settings.subtitleDefault {
        case .none: selectedSubtitles = []
        case .all: selectedSubtitles = Set(t.subtitles.map(\.streamId))
        case .preferred: selectedSubtitles = Set(t.subtitles.filter { prefs.contains($0.lang.lowercased()) }.map(\.streamId))
        }
    }

    func estimate(for p: QualityPreset) -> Int64? {
        guard let t = currentTitle else { return nil }
        return SizeEstimator.estimate(title: t, preset: p, bitrateKbps: settings.bitrateKbps(for: p), audio: chosenAudio,
                                      subtitleCount: chosenSubtitles.count, lpcmToFlac: lpcmToFlac)
    }

    /// The preset actually used: HEVC presets fall back to H.264 when there is no HEVC hardware encoder.
    func encoderNote(for p: QualityPreset) -> String? {
        switch p.videoCodec {
        case .copy: return nil
        case .hevc where !encoders.hevc && encoders.h264:
            return String(localized: "Geen HEVC-hardware-encoder op deze Mac: H.264 wordt gebruikt.")
        case .hevc, .h264:
            return (encoders.hevc || encoders.h264) ? nil : String(localized: "Geen hardware-videoencoder beschikbaar.")
        }
    }

    var canProceedFromDisc: Bool {
        guard let t = currentTitle else { return false }
        return !t.cssKeyMissing && !(p_isLossyWithoutEncoder)
    }

    private var p_isLossyWithoutEncoder: Bool { preset.isLossy && !encoders.hevc && !encoders.h264 }

    // MARK: Metadata

    var hasTMDBKey: Bool { !store.secret(.tmdbKey).isEmpty }

    func searchTMDB() {
        let query = searchQuery.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else { return }
        let key = store.secret(.tmdbKey)
        guard !key.isEmpty else {
            searchError = DiscRipError.tmdbNoKey.localizedDescription
            metadataMode = .manual
            return
        }
        isSearching = true
        searchError = nil
        let year = Int(searchYear)
        Task {
            do {
                let results = try await TMDBClient(credential: key).search(query, year: year)
                self.searchResults = results
                if results.isEmpty { self.searchError = String(localized: "Geen resultaten op TMDB voor “\(query)”.") }
                if let best = TMDBClient.bestMatch(results, query: query, year: year) { self.choose(best) }
            } catch {
                self.searchResults = []
                self.searchError = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
                if let e = error as? DiscRipError, e == .tmdbUnreachable || e == .tmdbInvalidKey { self.metadataMode = .manual }
            }
            self.isSearching = false
        }
    }

    func choose(_ movie: TMDBClient.Movie) {
        chosenMovieId = movie.id
        metadata = movie.metadata()
        Haptics.tap()
    }

    var targetPreview: String {
        let s = settings
        switch storage {
        case .local, .both:
            return PlexNaming.relativePath(metadata, moviesBase: s.localMoviesSubfolder, homeVideosBase: s.localHomeVideosSubfolder)
        case .nas:
            return PlexNaming.relativePath(metadata, moviesBase: s.nasMoviesFolder, homeVideosBase: s.nasHomeVideosFolder)
        }
    }

    // MARK: Rip

    func startRip() {
        guard let disc, let info, let t = currentTitle, metadata.isValid, !isRipping else { return }
        var meta = metadata
        meta.title = meta.title.trimmingCharacters(in: .whitespacesAndNewlines)
        if meta.kind.usesHomeVideoFolder {
            meta.tmdbId = nil
            meta.imdbId = nil
        }
        let plan = RipPlan(sourcePath: disc.path, volumeLabel: info.volumeLabel, title: t, audio: chosenAudio,
                           subtitles: chosenSubtitles, preset: preset, bitrateKbps: settings.bitrateKbps(for: preset),
                           lpcmToFlac: lpcmToFlac && chosenAudio.contains(where: \.isLPCM), metadata: meta, storage: storage)
        let job = RipJob(plan: plan, settings: settings, nasPassword: store.secret(.nasPassword),
                         plexToken: store.secret(.plexToken), disc: disc)
        let token = CancelToken()
        cancelToken = token
        rip = RipState()
        path = [.disc, .metadata, .rip]
        activity = ProcessInfo.processInfo.beginActivity(options: [.userInitiated, .idleSystemSleepDisabled],
                                                         reason: "DiscRip is ripping a DVD")
        ripTask = Task {
            do {
                let result = try await job.run(cancel: token) { update in
                    Task { @MainActor in self.apply(update) }
                }
                self.finish(plan: plan, result: result, error: nil)
            } catch {
                let e = (error as? DiscRipError) ?? .internalError(error.localizedDescription)
                self.finish(plan: plan, result: nil, error: e)
            }
        }
    }

    private var lastDockUpdate = Date.distantPast

    private func apply(_ u: JobUpdate) {
        guard var r = rip, r.isRunning else { return }
        r.phase = u.phase
        if let p = u.rip { r.progress = p }
        if u.uploadTotal > 0 {
            r.uploadDone = u.uploadDone
            r.uploadTotal = u.uploadTotal
            r.uploadElapsed = u.uploadElapsed
        }
        rip = r
        if Date().timeIntervalSince(lastDockUpdate) > 1 {
            lastDockUpdate = Date()
            let fraction = u.phase == .uploading && u.uploadTotal > 0 ? Double(u.uploadDone) / Double(u.uploadTotal) : r.progress.fraction
            NSApp.dockTile.badgeLabel = "\(Int(fraction * 100))%"
        }
    }

    func cancelRip() {
        cancelToken?.cancel()
        ripTask?.cancel()
    }

    private func finish(plan: RipPlan, result: JobResult?, error: DiscRipError?) {
        if let activity { ProcessInfo.processInfo.endActivity(activity) }
        activity = nil
        NSApp.dockTile.badgeLabel = nil
        var r = rip ?? RipState()
        r.isRunning = false
        r.result = result
        r.error = error
        if result != nil { r.phase = .done }
        rip = r

        let entry = HistoryEntry(
            metadata: plan.metadata, volumeLabel: plan.volumeLabel, titleIndex: plan.title.index, preset: plan.preset,
            videoCodec: result.map { $0.outcome.videoCodec.rawValue } ?? plan.preset.videoCodec.rawValue,
            storage: plan.storage, localPath: result?.localURL?.path, nasPath: result?.nasURL?.path,
            sizeBytes: result?.outcome.outputBytes ?? 0, durationSeconds: plan.title.duration,
            ripSeconds: result?.ripSeconds ?? Date().timeIntervalSince(r.startedAt),
            result: error == .cancelled ? .cancelled : error != nil ? .failed : (result?.warnings.isEmpty ?? true) ? .success : .warning,
            message: error?.localizedDescription, warnings: result?.warnings ?? [],
            plexURL: result?.plexURL?.absoluteString)
        history = historyStore.append(entry)

        if result != nil {
            let meta = plan.metadata
            Task { _ = await PosterCache.load(for: meta) }
            Haptics.success()
            if settings.playSounds { Sounds.success.play() }
            notify(title: String(localized: "Rip klaar"), body: plan.metadata.title)
            if settings.ejectAfterRip, let m = source?.media {
                // The raw device must be closed before macOS will eject it.
                disc = nil
                Task {
                    let ok = await monitor.eject(m)
                    if ok {
                        self.ejectedAfterRip = true
                    } else {
                        self.banner = String(localized: "De disc kon niet automatisch uitgeworpen worden. Gebruik Disc › Disc uitwerpen.")
                    }
                }
            }
        } else if error != .cancelled {
            if settings.playSounds { Sounds.failure.play() }
            notify(title: String(localized: "Rip mislukt"), body: error?.localizedDescription ?? "")
        }
    }

    /// After a finished rip: back to the start, ready for the next disc.
    func doneWithRip() {
        let succeeded = rip?.result != nil
        let wasDrive = source?.media != nil
        rip = nil
        if wasDrive && (ejectedAfterRip || succeeded) {
            closeDisc()
            ejectedAfterRip = false
            if let dvd = dvdInDrive {
                openDrive(dvd) // a new disc was inserted while the result was on screen
            } else {
                nextDiscPrompt = true
            }
        } else if source != nil {
            path = [.disc]
        } else {
            path = []
        }
    }

    private func notify(title: String, body: String) {
        guard Bundle.main.bundleIdentifier != nil, !NSApp.isActive else { return }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
    }

    func ejectCurrent() {
        guard let m = source?.media ?? dvdInDrive, !isRipping else { return }
        closeDisc()
        Task { _ = await monitor.eject(m) }
    }

    func clearHistory() {
        history = []
        historyStore.save([])
    }
}

enum Sounds {
    case success, failure

    func play() {
        // System sounds; Glass for a finished rip, Basso for a failed one.
        let name = self == .success ? "Glass" : "Basso"
        NSSound(named: NSSound.Name(name))?.play()
    }
}

enum Haptics {
    static func tap() { NSHapticFeedbackManager.defaultPerformer.perform(.alignment, performanceTime: .now) }
    static func success() { NSHapticFeedbackManager.defaultPerformer.perform(.levelChange, performanceTime: .now) }
}

enum Format {
    static func duration(_ seconds: TimeInterval) -> String {
        let s = Int(seconds.rounded())
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
    }

    static func bytes(_ b: Int64) -> String { ByteCountFormatter.string(fromByteCount: b, countStyle: .file) }
}
