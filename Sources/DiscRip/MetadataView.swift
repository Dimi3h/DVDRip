import DiscRipCore
import SwiftUI

struct MetadataView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        Form {
            Section {
                PageHeader(title: "Metadata", subtitle: "Titel en jaar bepalen de map- en bestandsnaam voor Plex.")
            }
            .listRowBackground(Color.clear)

            Section {
                Picker("Bron", selection: $model.metadataMode) {
                    ForEach(MetadataMode.allCases) { Text($0.label).tag($0) }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                if model.metadataMode == .automatic && !model.hasTMDBKey {
                    Label("Stel een TMDB-API-sleutel in om automatisch te zoeken.", systemImage: "key")
                        .foregroundStyle(.orange)
                }
            }

            if model.metadataMode == .automatic {
                TMDBSearchSection()
            }

            ManualForm()

            Section("Wordt opgeslagen als") {
                Text(model.targetPreview)
                    .font(.callout.monospaced())
                    .textSelection(.enabled)
                    .foregroundStyle(.secondary)
                LabeledContent("Kwaliteit", value: model.preset.shortLabel)
                LabeledContent("Bewaren op", value: model.storage.label)
                if let bytes = model.estimate(for: model.preset) {
                    LabeledContent("Geschatte grootte", value: Format.bytes(bytes))
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Metadata")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    model.startRip()
                } label: {
                    Label("Rip starten", systemImage: "record.circle")
                        .labelStyle(.titleAndIcon)
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
                .disabled(!model.metadata.isValid || model.isRipping)
            }
        }
        .onChange(of: model.metadataMode) { _, mode in
            if mode == .automatic && model.searchResults.isEmpty && model.hasTMDBKey { model.searchTMDB() }
        }
    }
}

private struct TMDBSearchSection: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        Section {
            HStack {
                TextField("Zoek op TMDB", text: $model.searchQuery)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit { model.searchTMDB() }
                TextField("Jaar", text: $model.searchYear)
                    .textFieldStyle(.roundedBorder)
                    .frame(width: 70)
                    .onSubmit { model.searchTMDB() }
                Button("Zoeken") { model.searchTMDB() }
                    .disabled(model.isSearching || model.searchQuery.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            if model.isSearching {
                HStack { ProgressView().controlSize(.small); Text("Zoeken…").foregroundStyle(.secondary) }
            }
            if let e = model.searchError {
                VStack(alignment: .leading, spacing: 6) {
                    Label(e, systemImage: "exclamationmark.triangle").foregroundStyle(.orange)
                    Button("Zelf invullen") { model.metadataMode = .manual }
                        .buttonStyle(.link)
                }
            }
            ForEach(model.searchResults.prefix(8)) { movie in
                MovieRow(movie: movie, isChosen: model.chosenMovieId == movie.id) { model.choose(movie) }
            }
        } header: {
            Text("Automatisch (TMDB)")
        } footer: {
            Text("Gezocht in het Nederlands (België), met Engels als terugval. Kies een resultaat en controleer de velden hieronder.")
                .foregroundStyle(.secondary)
        }
    }
}

private struct MovieRow: View {
    let movie: TMDBClient.Movie
    let isChosen: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 12) {
                AsyncImage(url: movie.posterURL) { image in
                    image.resizable().aspectRatio(contentMode: .fill)
                } placeholder: {
                    ZStack {
                        Rectangle().fill(.quaternary)
                        Image(systemName: "film").foregroundStyle(.secondary)
                    }
                }
                .frame(width: 46, height: 69)
                .clipShape(RoundedRectangle(cornerRadius: 5))
                VStack(alignment: .leading, spacing: 3) {
                    HStack {
                        Text(movie.title).font(.headline)
                        if let y = movie.year { Text(String(y)).foregroundStyle(.secondary) }
                    }
                    if let o = movie.originalTitle, o != movie.title {
                        Text(o).font(.caption).foregroundStyle(.secondary)
                    }
                    Text(movie.overview?.nonEmpty ?? String(localized: "Geen beschrijving."))
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .lineLimit(3)
                }
                Spacer()
                Image(systemName: isChosen ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundStyle(isChosen ? AnyShapeStyle(.tint) : AnyShapeStyle(.tertiary))
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct ManualForm: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        Section {
            TextField("Titel", text: $model.metadata.title)
            TextField("Jaar", text: Binding(
                get: { model.metadata.year.map(String.init) ?? "" },
                set: { model.metadata.year = Int($0.filter(\.isNumber).prefix(4)) }))
            Picker("Soort", selection: $model.metadata.kind) {
                ForEach(MediaKind.allCases) { Text($0.label).tag($0) }
            }
            if model.metadata.kind == .movie {
                TextField("TMDB-id (optioneel)", text: Binding(
                    get: { model.metadata.tmdbId.map(String.init) ?? "" },
                    set: { model.metadata.tmdbId = Int($0.filter(\.isNumber)) }))
                TextField("IMDb-id (optioneel, bv. tt0133093)", text: Binding(
                    get: { model.metadata.imdbId ?? "" },
                    set: { model.metadata.imdbId = $0.isEmpty ? nil : $0 }))
            }
        } header: {
            Text(model.metadataMode == .automatic ? String(localized: "Controleren en aanpassen") : String(localized: "Zelf invullen"))
        } footer: {
            if model.metadata.kind.usesHomeVideoFolder {
                Text("Thuisvideo’s en andere video’s gaan naar hun eigen basismap en krijgen geen id in de naam.")
                    .foregroundStyle(.secondary)
            } else if !model.metadata.isValid {
                Text("Vul minstens een titel in (en een geldig jaar).").foregroundStyle(.orange)
            }
        }
    }
}
