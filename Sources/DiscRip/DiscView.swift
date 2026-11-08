import DiscRipCore
import SwiftUI

struct DiscView: View {
    @Environment(AppModel.self) private var model
    @ViewState private var showAllTitles = false

    var body: some View {
        @Bindable var model = model
        Form {
            Section {
                PageHeader(verbatim: model.source?.displayName ?? String(localized: "Disc"),
                           subtitle: model.info.map { String(localized: "\($0.titles.count) titels op de disc") })
            }
            .listRowBackground(Color.clear)

            RegionSection()

            Section {
                let top = model.candidates.filter(\.plausible).prefix(4)
                let shown = showAllTitles ? Array(model.candidates) : Array(top.isEmpty ? model.candidates.prefix(4) : top)
                ForEach(shown) { c in
                    TitleRow(candidate: c, isWinner: c.id == model.candidates.first?.id,
                             isSelected: model.selectedTitle == c.id) {
                        model.selectTitle(c.id)
                        Haptics.tap()
                    }
                }
                if model.candidates.count > shown.count || showAllTitles {
                    Button(showAllTitles ? String(localized: "Minder titels tonen") : String(localized: "Alle \(model.candidates.count) titels tonen")) {
                        withAnimation { showAllTitles.toggle() }
                    }
                    .buttonStyle(.link)
                }
            } header: {
                Text("Hoofdfilm")
            } footer: {
                Text("DiscRip koos de langste plausibele titel. Klopt dat, ga dan gewoon verder.")
                    .foregroundStyle(.secondary)
            }

            if let t = model.currentTitle {
                Section("Audio") {
                    if t.audio.isEmpty { Text("Deze titel heeft geen audiosporen.").foregroundStyle(.secondary) }
                    ForEach(model.orderedAudio) { a in
                        Toggle(isOn: binding(for: a.streamId, in: \.selectedAudio)) {
                            TrackLabel(title: Languages.displayName(a.lang),
                                       detail: "\(a.formatLabel) \(a.channelLabel)" + (a.isCommentary ? " · " + String(localized: "Commentaar") : ""),
                                       isFirst: a.streamId == model.chosenAudio.first?.streamId)
                        }
                        .toggleStyle(.switch)
                    }
                }

                Section {
                    if t.subtitles.isEmpty {
                        Text("Geen ondertitels op deze titel.").foregroundStyle(.secondary)
                    }
                    ForEach(model.orderedSubtitles) { s in
                        Toggle(isOn: binding(for: s.streamId, in: \.selectedSubtitles)) {
                            TrackLabel(title: Languages.displayName(s.lang),
                                       detail: s.isForced ? String(localized: "Geforceerd") : "VobSub", isFirst: false)
                        }
                        .toggleStyle(.switch)
                    }
                } header: {
                    Text("Ondertitels")
                } footer: {
                    if !t.subtitles.isEmpty {
                        Text("Ondertitels worden als VobSub-beeldsporen met taalcode meegenomen.").foregroundStyle(.secondary)
                    }
                }

                QualitySection()

                Section {
                    Picker("Bewaren op", selection: $model.storage) {
                        ForEach(StorageTarget.allCases) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                    StorageSummary()
                } header: {
                    Text("Bewaren op")
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle(model.source?.displayName ?? String(localized: "Disc"))
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    model.path.append(.metadata)
                    if model.metadataMode == .automatic && model.searchResults.isEmpty { model.searchTMDB() }
                } label: {
                    Label("Volgende", systemImage: "chevron.right")
                        .labelStyle(.titleAndIcon)
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
                .disabled(!model.canProceedFromDisc || model.isRipping)
            }
        }
    }

    private func binding(for id: Int, in key: ReferenceWritableKeyPath<AppModel, Set<Int>>) -> Binding<Bool> {
        Binding(get: { model[keyPath: key].contains(id) },
                set: { on in
                    if on { model[keyPath: key].insert(id) } else { model[keyPath: key].remove(id) }
                })
    }
}

private struct TitleRow: View {
    let candidate: TitleScoring.Candidate
    let isWinner: Bool
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        let t = candidate.title
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundStyle(isSelected ? AnyShapeStyle(.tint) : AnyShapeStyle(.tertiary))
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text("Titel \(t.index)").font(.headline)
                        if isWinner {
                            Text("Hoofdfilm")
                                .font(.caption.weight(.semibold))
                                .padding(.horizontal, 6).padding(.vertical, 1)
                                .background(Color.accentColor.opacity(0.15), in: Capsule())
                                .foregroundStyle(.tint)
                        }
                        if t.cssKeyMissing {
                            Text("CSS-sleutel ontbreekt")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.orange)
                        }
                    }
                    Text(verbatim: [Format.duration(t.duration),
                                   t.chapters == 1 ? String(localized: "1 hoofdstuk") : String(localized: "\(t.chapters) hoofdstukken"),
                                   String(localized: "\(t.audio.count) audio"),
                                   String(localized: "\(t.subtitles.count) ondertitels"),
                                   "\(t.video.standard) \(t.video.aspect)"].joined(separator: " · "))
                        .font(.callout)
                        .foregroundStyle(.secondary)
                    if candidate.duplicates >= 2 {
                        Text("\(candidate.duplicates) bijna identieke titels: waarschijnlijk geen hoofdfilm")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Text(Format.bytes(t.sizeBytes)).font(.callout).foregroundStyle(.secondary).monospacedDigit()
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(t.cssKeyMissing)
        .opacity(candidate.plausible || isSelected ? 1 : 0.6)
    }
}

private struct TrackLabel: View {
    let title: String
    let detail: String
    let isFirst: Bool

    var body: some View {
        HStack(spacing: 6) {
            Text(title)
            Text(detail).foregroundStyle(.secondary)
            if isFirst {
                Text("Standaard").font(.caption).foregroundStyle(.secondary)
                    .padding(.horizontal, 5).padding(.vertical, 1)
                    .overlay(Capsule().strokeBorder(.quaternary))
            }
        }
    }
}

private struct RegionSection: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        switch model.regionStatus {
        case .compatible, .regionFree:
            EmptyView()
        case let .mismatch(drive, disc):
            Section {
                Label(DiscRipError.regionMismatch(drive: drive, disc: disc).localizedDescription,
                      systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(.orange)
            }
        case .driveRegionNotSet:
            Section {
                Label(DiscRipError.driveRegionNotSet(disc: model.info?.allowedRegions ?? []).localizedDescription,
                      systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(.orange)
            }
        }
    }
}

private struct QualitySection: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        Section {
            ForEach(QualityPreset.allCases) { p in
                Button {
                    model.preset = p
                    Haptics.tap()
                } label: {
                    HStack(alignment: .top, spacing: 12) {
                        Image(systemName: model.preset == p ? "checkmark.circle.fill" : "circle")
                            .font(.title3)
                            .foregroundStyle(model.preset == p ? AnyShapeStyle(.tint) : AnyShapeStyle(.tertiary))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(p.label).font(.headline)
                            Text(p.summary).font(.callout).foregroundStyle(.secondary)
                            if p.isLossy {
                                Text("\((Double(model.settings.bitrateKbps(for: p)) / 1000).formatted(.number.precision(.fractionLength(1)))) Mbit/s video")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            if let note = model.encoderNote(for: p) {
                                Label(note, systemImage: "info.circle").font(.caption).foregroundStyle(.orange)
                            }
                        }
                        Spacer()
                        if let bytes = model.estimate(for: p) {
                            Text("≈ \(Format.bytes(bytes))").font(.callout).monospacedDigit().foregroundStyle(.secondary)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            if model.chosenAudio.contains(where: \.isLPCM) {
                Toggle("LPCM-audio omzetten naar FLAC (verliesvrij, kleiner)", isOn: $model.lpcmToFlac)
                    .toggleStyle(.switch)
            }
        } header: {
            Text("Kwaliteit")
        } footer: {
            Text("Hoofdstukken, beeldverhouding en taalcodes blijven in elke instelling behouden. DTS en AC-3 worden altijd gekopieerd.")
                .foregroundStyle(.secondary)
        }
    }
}

struct StorageSummary: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let s = model.settings
        VStack(alignment: .leading, spacing: 4) {
            if model.storage.usesLocal {
                Label(s.localFolderPath ?? String(localized: "Nog geen lokale map gekozen"), systemImage: "folder")
                    .foregroundStyle(s.localFolderPath == nil ? AnyShapeStyle(.orange) : AnyShapeStyle(.secondary))
            }
            if model.storage.usesNAS {
                Label(s.nasConfigured ? "smb://\(s.nasHost)/\(s.nasShare)" : String(localized: "NAS nog niet ingesteld"),
                      systemImage: "externaldrive.connected.to.line.below")
                    .foregroundStyle(s.nasConfigured ? AnyShapeStyle(.secondary) : AnyShapeStyle(.orange))
                Text(s.plexConfigured ? String(localized: "Na de upload scant Plex de nieuwe map.") : String(localized: "Plex is niet ingesteld; er volgt geen bibliotheekscan."))
                    .font(.caption).foregroundStyle(.secondary)
            }
            SettingsLink {
                Text("Instellingen wijzigen…")
            }
            .buttonStyle(.link)
            .font(.callout)
        }
        .font(.callout)
    }
}
