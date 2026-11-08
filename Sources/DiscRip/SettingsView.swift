import AppKit
import DiscRipCore
import SwiftUI

struct SettingsView: View {
    var body: some View {
        TabView {
            GeneralSettings().tabItem { Label("Algemeen", systemImage: "gearshape") }
            QualitySettings().tabItem { Label("Kwaliteit", systemImage: "dial.medium") }
            StorageSettings().tabItem { Label("Opslag", systemImage: "folder") }
            NASSettings().tabItem { Label("NAS", systemImage: "externaldrive.connected.to.line.below") }
            PlexSettings().tabItem { Label("Plex", systemImage: "play.rectangle") }
            TMDBSettings().tabItem { Label("TMDB", systemImage: "film.stack") }
        }
        .frame(width: 560)
        .frame(minHeight: 420)
    }
}

private struct GeneralSettings: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        Form {
            Section {
                Picker("Metadata standaard", selection: $model.settings.metadataMode) {
                    ForEach(MetadataMode.allCases) { Text($0.label).tag($0) }
                }
                .pickerStyle(.segmented)
            } footer: {
                Text("Zonder TMDB-sleutel, of als TMDB onbereikbaar is, start DiscRip altijd met zelf invullen.")
                    .foregroundStyle(.secondary)
            }
            Section {
                TextField("Voorkeurstalen", text: Binding(
                    get: { model.settings.preferredLanguages.joined(separator: ", ") },
                    set: { model.settings.preferredLanguages = $0.split(whereSeparator: { ", ".contains($0) }).map { String($0).lowercased() } }))
                Picker("Ondertitels standaard", selection: $model.settings.subtitleDefault) {
                    ForEach(SubtitleDefault.allCases) { Text($0.label).tag($0) }
                }
            } header: {
                Text("Sporen")
            } footer: {
                Text("ISO 639-1-codes in volgorde, bv. nl, en. Audio in deze talen komt eerst; alle audiosporen worden standaard meegenomen.")
                    .foregroundStyle(.secondary)
            }
            Section("Na het rippen") {
                Toggle("Disc uitwerpen en wachten op de volgende", isOn: $model.settings.ejectAfterRip)
            }
        }
        .formStyle(.grouped)
    }
}

private struct QualitySettings: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        Form {
            Section {
                Picker("Standaardkwaliteit", selection: $model.settings.defaultPreset) {
                    ForEach(QualityPreset.allCases) { Text($0.label).tag($0) }
                }
                Toggle("LPCM-audio omzetten naar FLAC", isOn: $model.settings.lpcmToFlac)
            } footer: {
                Text("FLAC is verliesvrij en ongeveer 40 % kleiner dan LPCM. DTS en AC-3 worden altijd gekopieerd.")
                    .foregroundStyle(.secondary)
            }
            Section {
                ForEach(QualityPreset.allCases.filter(\.isLossy)) { p in
                    LabeledContent(p.shortLabel) {
                        HStack {
                            TextField("", value: Binding(
                                get: { model.settings.bitrateKbps(for: p) },
                                set: { model.settings.setBitrateKbps(min(max($0, 300), 20_000), for: p) }),
                                      format: .number)
                                .frame(width: 70)
                                .multilineTextAlignment(.trailing)
                            Text("kbit/s").foregroundStyle(.secondary)
                            Button("Standaard") { model.settings.setBitrateKbps(p.defaultBitrateKbps, for: p) }
                                .disabled(model.settings.bitrateKbps(for: p) == p.defaultBitrateKbps)
                        }
                    }
                }
            } header: {
                Text("Videobitrate (met kwaliteitsverlies)")
            } footer: {
                Text("Gemiddelde bitrate (VBR) voor de hardware-encoder. De geschatte bestandsgrootte volgt deze waarden.")
                    .foregroundStyle(.secondary)
            }
            Section("Hardware-encoders op deze Mac") {
                LabeledContent("HEVC (VideoToolbox)", value: model.encoders.hevc ? String(localized: "Beschikbaar") : String(localized: "Niet beschikbaar"))
                LabeledContent("H.264 (VideoToolbox)", value: model.encoders.h264 ? String(localized: "Beschikbaar") : String(localized: "Niet beschikbaar"))
            }
        }
        .formStyle(.grouped)
    }
}

private struct StorageSettings: View {
    @Environment(AppModel.self) private var model
    @ViewState private var folderError: String?

    var body: some View {
        @Bindable var model = model
        Form {
            Section {
                Picker("Standaard bewaren op", selection: $model.settings.storageTarget) {
                    ForEach(StorageTarget.allCases) { Text($0.label).tag($0) }
                }
                .pickerStyle(.segmented)
            } footer: {
                Text("Per rip kun je dit nog wijzigen op het disc-scherm.").foregroundStyle(.secondary)
            }
            Section {
                LabeledContent("Map") {
                    HStack {
                        Text(model.settings.localFolderPath ?? String(localized: "Niet gekozen"))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Button("Kies…") { chooseFolder() }
                    }
                }
                TextField("Submap films", text: $model.settings.localMoviesSubfolder, prompt: Text("(in de map zelf)"))
                TextField("Submap thuisvideo’s", text: $model.settings.localHomeVideosSubfolder)
                if let folderError { Text(folderError).foregroundStyle(.orange) }
            } header: {
                Text("Lokaal")
            } footer: {
                Text("Ook een USB-stick of externe schijf kan. DiscRip maakt per film een Plex-map aan.")
                    .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
    }

    private func chooseFolder() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.canCreateDirectories = true
        panel.prompt = String(localized: "Kies")
        guard panel.runModal() == .OK, let url = panel.url else { return }
        guard FileManager.default.isWritableFile(atPath: url.path) else {
            folderError = DiscRipError.folderNotWritable(path: url.path).localizedDescription
            return
        }
        folderError = nil
        model.settings.localFolderBookmark = try? LocalFolder.bookmark(for: url)
        model.settings.localFolderPath = url.path
    }
}

private struct SecretField: View {
    @Environment(AppModel.self) private var model
    let title: LocalizedStringKey
    let secret: SettingsStore.Secret
    @ViewState private var value = ""
    @ViewState private var loaded = false

    var body: some View {
        SecureField(title, text: $value)
            .onAppear {
                if !loaded {
                    value = model.store.secret(secret)
                    loaded = true
                }
            }
            .onChange(of: value) { _, v in
                if loaded { model.store.setSecret(secret, v.trimmingCharacters(in: .whitespacesAndNewlines)) }
            }
    }
}

private struct NASSettings: View {
    @Environment(AppModel.self) private var model
    @ViewState private var testing = false
    @ViewState private var testResult: (ok: Bool, text: String)?

    var body: some View {
        @Bindable var model = model
        Form {
            Section {
                TextField("Host", text: $model.settings.nasHost, prompt: Text("bv. diskstation.local of 192.168.1.20"))
                TextField("Share", text: $model.settings.nasShare, prompt: Text("bv. video"))
                TextField("Gebruikersnaam", text: $model.settings.nasUsername)
                SecretField(title: "Wachtwoord", secret: .nasPassword)
            } header: {
                Text("Synology NAS (SMB)")
            } footer: {
                Text("Het wachtwoord wordt in de sleutelhanger van macOS bewaard.").foregroundStyle(.secondary)
            }
            Section("Mappen op de share") {
                TextField("Films", text: $model.settings.nasMoviesFolder)
                TextField("Thuisvideo’s", text: $model.settings.nasHomeVideosFolder)
                Toggle("Rechtstreeks naar de NAS schrijven", isOn: $model.settings.nasDirectWrite)
                if model.settings.nasDirectWrite {
                    Text("Alleen bij Bewaren op: NAS. Er is dan geen lokale tussenkopie; een netwerkonderbreking breekt de rip af.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            Section {
                HStack {
                    Button("Verbinding testen") { test() }
                        .disabled(testing || !model.settings.nasConfigured)
                    if testing { ProgressView().controlSize(.small) }
                }
                if let r = testResult {
                    Label(r.text, systemImage: r.ok ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                        .foregroundStyle(r.ok ? .green : .orange)
                }
            }
        }
        .formStyle(.grouped)
    }

    private func test() {
        testing = true
        testResult = nil
        let s = model.settings
        let service = NASService(config: .init(host: s.nasHost, share: s.nasShare, username: s.nasUsername,
                                               password: model.store.secret(.nasPassword)))
        Task {
            do {
                let mount = try await service.mount()
                var text = String(localized: "Verbonden met \(mount.path)")
                if let free = NASService.availableCapacity(at: mount) {
                    text += " · " + String(localized: "\(Format.bytes(free)) vrij")
                }
                testResult = (true, text)
                await service.unmountIfNeeded()
            } catch {
                testResult = (false, error.localizedDescription)
            }
            testing = false
        }
    }
}

private struct PlexSettings: View {
    @Environment(AppModel.self) private var model
    @ViewState private var sections: [PlexClient.Section] = []
    @ViewState private var loading = false
    @ViewState private var message: (ok: Bool, text: String)?

    var body: some View {
        @Bindable var model = model
        Form {
            Section {
                TextField("Server-URL", text: $model.settings.plexURL, prompt: Text("bv. http://diskstation.local:32400"))
                SecretField(title: "Token", secret: .plexToken)
            } header: {
                Text("Plex Media Server")
            } footer: {
                Text("Het token vind je in Plex Web via ‘Info opvragen’ › ‘XML bekijken’ (X-Plex-Token in de URL).")
                    .foregroundStyle(.secondary)
            }
            Section("Bibliotheken") {
                HStack {
                    Button("Bibliotheken ophalen") { load() }
                        .disabled(loading || !model.settings.plexConfigured)
                    if loading { ProgressView().controlSize(.small) }
                }
                if let m = message {
                    Label(m.text, systemImage: m.ok ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                        .foregroundStyle(m.ok ? .green : .orange)
                }
                Picker("Films", selection: $model.settings.plexMoviesSectionId) {
                    Text("Geen").tag("")
                    ForEach(options(model.settings.plexMoviesSectionId)) { Text($0.title).tag($0.key) }
                }
                Picker("Thuisvideo’s", selection: $model.settings.plexHomeVideosSectionId) {
                    Text("Geen").tag("")
                    ForEach(options(model.settings.plexHomeVideosSectionId)) { Text($0.title).tag($0.key) }
                }
                ForEach(sections) { s in
                    LabeledContent(s.title, value: s.locations.joined(separator: ", "))
                        .font(.caption)
                }
            }
        }
        .formStyle(.grouped)
    }

    /// Fetched sections, plus a placeholder for a stored id that has not been fetched yet.
    private func options(_ current: String) -> [PlexClient.Section] {
        if current.isEmpty || sections.contains(where: { $0.key == current }) { return sections }
        return sections + [PlexClient.Section(key: current, title: String(localized: "Bibliotheek \(current)"), type: "movie", location: nil)]
    }

    private func load() {
        guard let plex = PlexClient(urlString: model.settings.plexURL, token: model.store.secret(.plexToken)) else { return }
        loading = true
        message = nil
        Task {
            do {
                sections = try await plex.sections().filter { $0.type == "movie" }
                message = (true, String(localized: "\(sections.count) filmbibliotheken gevonden"))
                if model.settings.plexMoviesSectionId.isEmpty, let first = sections.first {
                    model.settings.plexMoviesSectionId = first.key
                }
            } catch {
                message = (false, error.localizedDescription)
            }
            loading = false
        }
    }
}

private struct TMDBSettings: View {
    @Environment(AppModel.self) private var model
    @ViewState private var testing = false
    @ViewState private var result: (ok: Bool, text: String)?

    var body: some View {
        Form {
            Section {
                SecretField(title: "API-sleutel of leestoken", secret: .tmdbKey)
            } header: {
                Text("The Movie Database")
            } footer: {
                Text("Maak gratis een sleutel aan op themoviedb.org › Instellingen › API. Zowel de v3-sleutel als het v4-leestoken werkt.")
                    .foregroundStyle(.secondary)
            }
            Section {
                HStack {
                    Button("Sleutel testen") { test() }.disabled(testing)
                    if testing { ProgressView().controlSize(.small) }
                }
                if let r = result {
                    Label(r.text, systemImage: r.ok ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                        .foregroundStyle(r.ok ? .green : .orange)
                }
            } footer: {
                Text("Zoekresultaten in het Nederlands (België), met Engels (VS) als terugval.").foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
    }

    private func test() {
        testing = true
        result = nil
        let client = TMDBClient(credential: model.store.secret(.tmdbKey))
        Task {
            do {
                try await client.validate()
                result = (true, String(localized: "De sleutel werkt."))
            } catch {
                result = (false, error.localizedDescription)
            }
            testing = false
        }
    }
}
