import AppKit
import DiscRipCore
import SwiftUI

/// Start screen: the drive and its disc as one large, state-driven hero, plus the latest rips.
struct HomeView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ScrollView {
            VStack(spacing: 36) {
                Hero()
                if let e = model.loadError, !model.isLoadingDisc {
                    ErrorCard(error: e)
                }
                RecentRips()
            }
            .frame(maxWidth: 680)
            .padding(.horizontal, 32)
            .padding(.top, 40)
            .padding(.bottom, 32)
            .frame(maxWidth: .infinity)
        }
        .background(HomeBackground())
        .navigationTitle("Disc")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    model.ejectCurrent()
                } label: {
                    Label("Uitwerpen", systemImage: "eject")
                }
                .disabled(model.isRipping || (model.source?.media == nil && model.dvdInDrive == nil))
                .help("Disc uitwerpen")
            }
        }
    }
}

// MARK: - Hero

private enum HomeState {
    case noDrive, empty, unsupported(String), insertNext, loading, ready, notOpened(OpticalMedia), failed
}

private struct Hero: View {
    @Environment(AppModel.self) private var model

    private var state: HomeState {
        if model.isLoadingDisc { return .loading }
        if model.info != nil, model.source != nil { return .ready }
        if model.loadError != nil, model.source != nil { return .failed }
        if let m = model.discs.first {
            switch m.kind {
            case .dvd: return .notOpened(m)
            case .cd: return .unsupported(String(localized: "Dit is een cd, geen DVD-Video."))
            case .bluray: return .unsupported(String(localized: "Blu-ray wordt niet ondersteund, alleen DVD-Video."))
            case .other: return .unsupported(String(localized: "Onbekende disc"))
            }
        }
        if model.nextDiscPrompt { return .insertNext }
        if model.primaryDrive == nil { return .noDrive }
        return .empty
    }

    var body: some View {
        VStack(spacing: 22) {
            DiscArtwork(style: artworkStyle)
                .frame(width: 210, height: 210)
                .padding(.bottom, 4)

            VStack(spacing: 8) {
                Text(verbatim: headline)
                    .font(.system(size: 30, weight: .bold))
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                Text(verbatim: subline)
                    .font(.title3)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }

            VStack(spacing: 12) {
                actions
            }
            .padding(.top, 4)

            if let drive = model.primaryDrive {
                DriveChip(drive: drive)
            }
        }
        .frame(maxWidth: .infinity)
        .animation(.smooth, value: headline)
    }

    private var artworkStyle: DiscArtwork.Style {
        switch state {
        case .loading: return .spinning
        case .ready, .notOpened: return .disc
        case .failed, .unsupported: return .problem
        case .noDrive, .empty, .insertNext: return .slot
        }
    }

    private var headline: String {
        switch state {
        case .noDrive: return String(localized: "Geen drive aangesloten")
        case .empty: return String(localized: "Steek een dvd in")
        case .insertNext: return String(localized: "Steek de volgende disc in")
        case .loading: return model.sourceTitle ?? String(localized: "Disc lezen…")
        case .ready, .failed: return model.sourceTitle ?? String(localized: "Disc")
        case let .notOpened(m): return AppModel.prettyTitle(volumeLabel: m.volumeName ?? m.bsdName)
        case .unsupported: return String(localized: "Deze disc kan niet geript worden")
        }
    }

    private var subline: String {
        switch state {
        case .noDrive: return String(localized: "Sluit een USB-dvd-drive aan. macOS herkent hem automatisch.")
        case .empty: return String(localized: "DiscRip opent de disc vanzelf zodra hij in de drive zit.")
        case .insertNext: return String(localized: "De vorige rip is klaar. De volgende disc opent vanzelf.")
        case .loading: return String(localized: "Titels en CSS-sleutels worden ingelezen. Bij sommige discs duurt dat een minuut.")
        case .ready:
            guard let info = model.info, let main = model.currentTitle ?? model.candidates.first?.title else { return "" }
            return String(localized: "Hoofdfilm \(Format.duration(main.duration)) · \(main.audio.count) audio · \(info.titles.count) titels op de disc")
        case .failed: return String(localized: "De disc kon niet gelezen worden.")
        case .notOpened: return String(localized: "Klaar om te openen")
        case let .unsupported(reason): return reason
        }
    }

    @ViewBuilder private var actions: some View {
        switch state {
        case .ready:
            Button {
                model.path = [.disc]
            } label: {
                Label("Verder naar rippen", systemImage: "arrow.right.circle.fill")
                    .frame(minWidth: 200)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .keyboardShortcut(.defaultAction)
        case let .notOpened(m):
            Button {
                model.openDrive(m)
            } label: {
                Label("Disc openen", systemImage: "opticaldisc")
                    .frame(minWidth: 200)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .keyboardShortcut(.defaultAction)
            .disabled(model.isRipping)
        case .failed:
            if let dvd = model.dvdInDrive {
                Button {
                    model.openDrive(dvd)
                } label: {
                    Label("Opnieuw proberen", systemImage: "arrow.clockwise")
                        .frame(minWidth: 200)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
            }
        case .loading:
            ProgressView().controlSize(.small)
        default:
            EmptyView()
        }
        if !model.isLoadingDisc {
            Button {
                model.presentOpenPanel()
            } label: {
                Label("ISO of VIDEO_TS openen…", systemImage: "folder")
            }
            .buttonStyle(.link)
            .disabled(model.isRipping)
        }
    }
}

private struct DriveChip: View {
    let drive: OpticalDrive

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "opticaldiscdrive.fill")
                .foregroundStyle(.secondary)
            Text(verbatim: drive.name.nonEmpty ?? String(localized: "Optische drive"))
                .foregroundStyle(.secondary)
            if !drive.interconnect.isEmpty {
                Text(verbatim: drive.interconnect)
                    .font(.caption2.weight(.semibold))
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(.quaternary, in: Capsule())
                    .foregroundStyle(.secondary)
            }
        }
        .font(.callout)
        .padding(.horizontal, 14).padding(.vertical, 7)
        .background(.regularMaterial, in: Capsule())
    }
}

/// A DVD drawn with gradients: silver data side with a soft rainbow sheen, or an empty tray slot.
struct DiscArtwork: View {
    enum Style: Equatable { case slot, disc, spinning, problem }
    let style: Style
    @ViewState private var angle: Double = 0

    var body: some View {
        GeometryReader { geo in
            let d = min(geo.size.width, geo.size.height)
            ZStack {
                if style == .slot {
                    Circle()
                        .strokeBorder(style: StrokeStyle(lineWidth: 2, dash: [6, 7]))
                        .foregroundStyle(.tertiary)
                    Image(systemName: "opticaldiscdrive")
                        .font(.system(size: d * 0.26, weight: .light))
                        .foregroundStyle(.tertiary)
                } else {
                    disc(d)
                        .rotationEffect(.degrees(angle))
                        .saturation(style == .problem ? 0 : 1)
                        .opacity(style == .problem ? 0.55 : 1)
                    if style == .problem {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .font(.system(size: d * 0.16))
                            .foregroundStyle(.orange)
                            .offset(x: d * 0.3, y: d * 0.3)
                    }
                }
            }
            .frame(width: d, height: d)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .onAppear { updateSpin() }
        .onChange(of: style) { _, _ in updateSpin() }
        .accessibilityHidden(true)
    }

    private func updateSpin() {
        if style == .spinning {
            angle = 0
            withAnimation(.linear(duration: 1.6).repeatForever(autoreverses: false)) { angle = 360 }
        } else {
            withAnimation(.smooth) { angle = 0 }
        }
    }

    @ViewBuilder private func disc(_ d: CGFloat) -> some View {
        let sheen = AngularGradient(colors: [
            Color(white: 0.93), Color(red: 0.80, green: 0.86, blue: 1.0), Color(white: 0.78),
            Color(red: 1.0, green: 0.86, blue: 0.92), Color(white: 0.95), Color(red: 0.84, green: 1.0, blue: 0.92),
            Color(white: 0.80), Color(red: 0.92, green: 0.88, blue: 1.0), Color(white: 0.93),
        ], center: .center)
        ZStack {
            Circle().fill(sheen)
                .shadow(color: .black.opacity(0.25), radius: d * 0.05, y: d * 0.025)
            // grooves
            ForEach([0.92, 0.80, 0.68, 0.56], id: \.self) { r in
                Circle()
                    .stroke(Color.white.opacity(0.35), lineWidth: 0.6)
                    .frame(width: d * r, height: d * r)
            }
            // clamping area and centre hole
            Circle().fill(Color(white: 0.88).opacity(0.9))
                .frame(width: d * 0.36, height: d * 0.36)
            Circle().stroke(Color.black.opacity(0.08), lineWidth: 1)
                .frame(width: d * 0.36, height: d * 0.36)
            Circle().fill(Color(nsColor: .windowBackgroundColor))
                .frame(width: d * 0.13, height: d * 0.13)
                .overlay(Circle().stroke(Color.black.opacity(0.12), lineWidth: 1))
            // soft reflection
            Circle()
                .trim(from: 0.06, to: 0.16)
                .stroke(Color.white.opacity(0.45), style: StrokeStyle(lineWidth: d * 0.12, lineCap: .round))
                .frame(width: d * 0.66, height: d * 0.66)
                .blur(radius: d * 0.045)
        }
    }
}

private struct HomeBackground: View {
    var body: some View {
        ZStack {
            Color(nsColor: .windowBackgroundColor)
            RadialGradient(colors: [Color.accentColor.opacity(0.16), .clear],
                           center: .top, startRadius: 0, endRadius: 520)
        }
        .ignoresSafeArea()
    }
}

private struct ErrorCard: View {
    let error: DiscRipError

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(error.localizedDescription, systemImage: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
            if case let .notDVDVideo(detail) = error, !detail.isEmpty {
                Text(verbatim: detail)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
    }
}

// MARK: - Recent rips

private struct RecentRips: View {
    @Environment(AppModel.self) private var model

    private var recent: [HistoryEntry] {
        Array(model.history.filter { $0.result == .success || $0.result == .warning }.prefix(4))
    }

    var body: some View {
        if !recent.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text("Laatst geript").font(.headline)
                    Spacer()
                    Button("Alles tonen") { model.sidebar = .history }
                        .buttonStyle(.link)
                }
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 140, maximum: 170), spacing: 16)],
                          alignment: .leading, spacing: 16) {
                    ForEach(recent) { e in
                        RecentCard(entry: e)
                    }
                }
            }
        }
    }
}

private struct RecentCard: View {
    @Environment(AppModel.self) private var model
    let entry: HistoryEntry

    var body: some View {
        Button {
            if let p = entry.localPath, FileManager.default.fileExists(atPath: p) {
                NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: p)])
            } else {
                model.sidebar = .history
            }
        } label: {
            VStack(alignment: .leading, spacing: 6) {
                Poster(metadata: entry.metadata)
                    .aspectRatio(2 / 3, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .shadow(color: .black.opacity(0.2), radius: 4, y: 2)
                Text(verbatim: entry.metadata.title)
                    .font(.callout.weight(.semibold))
                    .lineLimit(1)
                Text(verbatim: [entry.metadata.year.map(String.init), Format.bytes(entry.sizeBytes)]
                    .compactMap { $0 }.joined(separator: " · "))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .help(entry.localPath ?? entry.nasPath ?? "")
    }
}

private struct Poster: View {
    let metadata: MediaMetadata
    @ViewState private var image: NSImage?

    var body: some View {
        ZStack {
            LinearGradient(colors: [Color.accentColor.opacity(0.55), Color.accentColor.opacity(0.25)],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
            if let image {
                Image(nsImage: image).resizable().aspectRatio(contentMode: .fill)
            } else {
                Image(systemName: "film")
                    .font(.system(size: 28))
                    .foregroundStyle(.white.opacity(0.85))
            }
        }
        .task(id: metadata.posterPath) {
            image = PosterCache.cachedImage(for: metadata)
            if image == nil { image = await PosterCache.load(for: metadata) }
        }
    }
}

// MARK: - Shared

/// Large page title in the content area (macOS has no large navigation bar titles).
struct PageHeader: View {
    let title: Text
    let subtitle: Text?

    init(title: LocalizedStringKey, subtitle: LocalizedStringKey? = nil) {
        self.title = Text(title)
        self.subtitle = subtitle.map { Text($0) }
    }

    init(verbatim title: String, subtitle: String? = nil) {
        self.title = Text(verbatim: title)
        self.subtitle = subtitle.map { Text(verbatim: $0) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            title.font(.largeTitle.weight(.bold))
            if let subtitle { subtitle.font(.title3).foregroundStyle(.secondary) }
        }
        .padding(.vertical, 4)
    }
}

extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}
