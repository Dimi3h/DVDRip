import DiscRipCore
import SwiftUI

struct ContentView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        NavigationSplitView {
            List(selection: $model.sidebar) {
                Section("Rippen") {
                    NavigationLink(value: SidebarItem.disc) {
                        SidebarDiscRow()
                    }
                }
                Section("Archief") {
                    NavigationLink(value: SidebarItem.history) {
                        Label("Geschiedenis", systemImage: "clock")
                            .badge(model.history.count)
                    }
                }
            }
            .navigationSplitViewColumnWidth(min: 200, ideal: 230, max: 300)
        } detail: {
            switch model.sidebar ?? .disc {
            case .disc:
                NavigationStack(path: $model.path) {
                    HomeView()
                        .navigationDestination(for: Route.self) { route in
                            switch route {
                            case .disc: DiscView()
                            case .metadata: MetadataView()
                            case .rip: RipView()
                            }
                        }
                }
            case .history:
                HistoryView()
            }
        }
    }
}

private struct SidebarDiscRow: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        HStack {
            Label {
                VStack(alignment: .leading, spacing: 1) {
                    Text("Disc")
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            } icon: {
                Image(systemName: "opticaldiscdrive")
            }
            Spacer()
            if model.isRipping, let r = model.rip {
                ProgressView(value: r.phase == .uploading && r.uploadTotal > 0
                             ? Double(r.uploadDone) / Double(r.uploadTotal) : r.progress.fraction)
                    .progressViewStyle(.circular)
                    .controlSize(.small)
            }
        }
    }

    private var subtitle: String {
        if model.isRipping { return model.rip?.phase.label ?? "" }
        if model.source != nil, let title = model.sourceTitle { return title }
        if model.primaryDrive == nil { return String(localized: "Geen drive") }
        return String(localized: "Geen disc")
    }
}
