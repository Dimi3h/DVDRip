import AppKit
import DiscRipCore
import SwiftUI

struct HistoryView: View {
    @Environment(AppModel.self) private var model
    @ViewState private var selection: HistoryEntry.ID?
    @ViewState private var confirmClear = false

    var body: some View {
        Group {
            if model.history.isEmpty {
                ContentUnavailableView("Nog geen rips", systemImage: "clock",
                                       description: Text("Afgewerkte rips verschijnen hier, met hun locatie en eventuele waarschuwingen."))
            } else {
                HSplitView {
                    List(model.history, selection: $selection) { e in
                        HistoryRow(entry: e).tag(e.id)
                    }
                    .frame(minWidth: 280)
                    Group {
                        if let id = selection, let e = model.history.first(where: { $0.id == id }) {
                            HistoryDetail(entry: e)
                        } else {
                            ContentUnavailableView("Kies een rip", systemImage: "film")
                        }
                    }
                    .frame(minWidth: 320, maxWidth: .infinity, maxHeight: .infinity)
                }
            }
        }
        .navigationTitle("Geschiedenis")
        .toolbar {
            ToolbarItem {
                Button(role: .destructive) { confirmClear = true } label: {
                    Label("Geschiedenis wissen", systemImage: "trash")
                }
                .disabled(model.history.isEmpty)
                .help("Geschiedenis wissen (de bestanden blijven bestaan)")
            }
        }
        .confirmationDialog("Geschiedenis wissen?", isPresented: $confirmClear) {
            Button("Wissen", role: .destructive) { model.clearHistory() }
        } message: {
            Text("Alleen de lijst wordt gewist; de gerippte bestanden blijven staan.")
        }
    }
}

private struct HistoryRow: View {
    let entry: HistoryEntry

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: icon).foregroundStyle(color).font(.title3).frame(width: 22)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.metadata.year.map { "\(entry.metadata.title) (\($0))" } ?? entry.metadata.title)
                    .font(.headline).lineLimit(1)
                Text("\(entry.date.formatted(date: .abbreviated, time: .shortened)) · \(entry.preset.shortLabel)")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
    }

    private var icon: String {
        switch entry.result {
        case .success: return "checkmark.circle.fill"
        case .warning: return "exclamationmark.triangle.fill"
        case .failed: return "xmark.octagon.fill"
        case .cancelled: return "xmark.circle"
        }
    }

    private var color: Color {
        switch entry.result {
        case .success: return .green
        case .warning: return .orange
        case .failed: return .red
        case .cancelled: return .secondary
        }
    }
}

private struct HistoryDetail: View {
    let entry: HistoryEntry

    var body: some View {
        Form {
            Section {
                PageHeader(verbatim: entry.metadata.title, subtitle: entry.metadata.year.map { String($0) })
            }
            .listRowBackground(Color.clear)
            Section {
                LabeledContent("Datum", value: entry.date.formatted(date: .long, time: .shortened))
                LabeledContent("Disc", value: "\(entry.volumeLabel) · " + String(localized: "titel \(entry.titleIndex)"))
                LabeledContent("Kwaliteit", value: entry.preset.label)
                LabeledContent("Video", value: entry.videoCodec.uppercased())
                LabeledContent("Bewaard op", value: entry.storage.label)
                if entry.sizeBytes > 0 { LabeledContent("Grootte", value: Format.bytes(entry.sizeBytes)) }
                LabeledContent("Speelduur", value: Format.duration(entry.durationSeconds))
                LabeledContent("Ripduur", value: Format.duration(entry.ripSeconds))
                if let id = entry.metadata.tmdbId { LabeledContent("TMDB", value: String(id)) }
            }
            if let message = entry.message {
                Section("Fout") { Text(message).foregroundStyle(.red) }
            }
            if !entry.warnings.isEmpty {
                Section("Waarschuwingen") {
                    ForEach(entry.warnings, id: \.self) { Text($0).foregroundStyle(.orange) }
                }
            }
            Section("Bestanden") {
                if let p = entry.localPath {
                    HStack {
                        Text(p).font(.caption.monospaced()).textSelection(.enabled)
                        Spacer()
                        Button("Toon in Finder") {
                            NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: p)])
                        }
                        .disabled(!FileManager.default.fileExists(atPath: p))
                    }
                }
                if let p = entry.nasPath { Text(p).font(.caption.monospaced()).textSelection(.enabled) }
                if let s = entry.plexURL, let url = URL(string: s) { Link("Openen in Plex", destination: url) }
            }
        }
        .formStyle(.grouped)
    }
}
