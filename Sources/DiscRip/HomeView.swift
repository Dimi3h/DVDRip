import DiscRipCore
import SwiftUI

struct HomeView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        Form {
            Section {
                PageHeader(title: "DiscRip", subtitle: "DVD-Video naar MKV, klaar voor Plex.")
            }
            .listRowBackground(Color.clear)

            if model.nextDiscPrompt && model.source == nil && !model.isLoadingDisc {
                Section {
                    HStack(spacing: 14) {
                        Image(systemName: "opticaldisc")
                            .font(.system(size: 34, weight: .light))
                            .foregroundStyle(.tint)
                            .symbolEffect(.pulse, options: .repeating)
                        VStack(alignment: .leading, spacing: 3) {
                            Text("Steek de volgende disc in").font(.title3.weight(.semibold))
                            Text("De vorige rip is klaar en de disc werd uitgeworpen. DiscRip opent de volgende disc vanzelf.")
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.vertical, 6)
                }
            }

            Section("Drive") {
                DriveRow()
                if let banner = model.banner {
                    Label(banner, systemImage: "info.circle").foregroundStyle(.secondary)
                }
            }

            if model.isLoadingDisc {
                Section {
                    HStack(spacing: 12) {
                        ProgressView().controlSize(.small)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Disc lezen…")
                            Text("Titels worden ingelezen en CSS-sleutels bepaald. Dat kan bij sommige discs een minuut duren.")
                                .font(.callout).foregroundStyle(.secondary)
                        }
                    }
                }
            }

            if let e = model.loadError {
                Section {
                    Label(e.localizedDescription, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                    if case let .notDVDVideo(detail) = e, !detail.isEmpty {
                        Text(detail)
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                            .textSelection(.enabled)
                    }
                    if let dvd = model.dvdInDrive {
                        Button("Opnieuw proberen") { model.openDrive(dvd) }
                    }
                }
            }

            if let s = model.source, model.info != nil, !model.isLoadingDisc {
                Section("Geopend") {
                    LabeledContent {
                        Button("Verder") { model.path = [.disc] }
                            .buttonStyle(.borderedProminent)
                    } label: {
                        Label(s.displayName, systemImage: s.media == nil ? "doc" : "opticaldisc")
                    }
                }
            }

            Section {
                Button {
                    model.presentOpenPanel()
                } label: {
                    Label("ISO of VIDEO_TS openen…", systemImage: "folder")
                }
                .disabled(model.isRipping || model.isLoadingDisc)
            } footer: {
                Text("Een DVD-image (.iso) of een map met VIDEO_TS rippen zonder drive.")
                    .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
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

private struct DriveRow: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: model.primaryDrive == nil ? "opticaldiscdrive" : "opticaldiscdrive.fill")
                .font(.title2)
                .foregroundStyle(model.primaryDrive == nil ? AnyShapeStyle(.secondary) : AnyShapeStyle(.tint))
                .frame(width: 30)
            VStack(alignment: .leading, spacing: 2) {
                Text(model.primaryDrive?.name.nonEmpty ?? String(localized: "Geen optische drive gevonden"))
                    .font(.headline)
                Text(status).foregroundStyle(.secondary).font(.callout)
            }
            Spacer()
            if let drive = model.primaryDrive, !drive.interconnect.isEmpty {
                Text(drive.interconnect)
                    .font(.caption.weight(.medium))
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(.quaternary, in: Capsule())
            }
            if let dvd = model.dvdInDrive, model.source?.media != dvd, !model.isLoadingDisc {
                Button("Openen") { model.openDrive(dvd) }
                    .buttonStyle(.borderedProminent)
                    .disabled(model.isRipping)
            }
        }
        .padding(.vertical, 4)
    }

    private var status: String {
        if model.primaryDrive == nil && model.discs.isEmpty {
            return String(localized: "Sluit een USB-dvd-drive aan. macOS herkent hem automatisch.")
        }
        guard let m = model.discs.first else { return String(localized: "Geen disc in de drive") }
        switch m.kind {
        case .dvd: return String(localized: "DVD: \(m.volumeName ?? m.bsdName)")
        case .cd: return String(localized: "Dit is een cd, geen DVD-Video.")
        case .bluray: return String(localized: "Blu-ray wordt niet ondersteund, alleen DVD-Video.")
        case .other: return String(localized: "Onbekende disc")
        }
    }
}

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
