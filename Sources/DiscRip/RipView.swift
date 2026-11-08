import AppKit
import DiscRipCore
import SwiftUI

struct RipView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        Form {
            Section {
                PageHeader(verbatim: model.metadata.title.nonEmpty ?? String(localized: "Rip"),
                           subtitle: model.preset.shortLabel + " · " + model.storage.label)
            }
            .listRowBackground(Color.clear)

            if let r = model.rip {
                Section {
                    PhaseStrip(state: r, steps: steps)
                }
                if r.isRunning {
                    RunningSection(state: r)
                } else if let result = r.result {
                    SuccessSection(result: result)
                } else if let error = r.error {
                    FailureSection(error: error)
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Rip")
        .navigationBarBackButtonHidden(model.isRipping)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                if model.isRipping {
                    Button(role: .destructive) {
                        model.cancelRip()
                    } label: {
                        Label("Annuleren", systemImage: "xmark.circle")
                            .labelStyle(.titleAndIcon)
                    }
                } else {
                    Button {
                        model.doneWithRip()
                    } label: {
                        Text(model.rip?.result != nil ? String(localized: "Klaar") : String(localized: "Terug"))
                    }
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.defaultAction)
                }
            }
        }
    }

    private var steps: [JobPhase] {
        var s: [JobPhase] = [.reading, model.preset.isLossy ? .encoding : .muxing]
        if model.storage.usesNAS {
            if !(model.storage == .nas && model.settings.nasDirectWrite) { s.append(.uploading) }
            if model.settings.plexConfigured { s.append(.plex) }
        }
        return s
    }
}

private struct PhaseStrip: View {
    let state: RipState
    let steps: [JobPhase]

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Array(steps.enumerated()), id: \.offset) { i, step in
                let status = status(of: step)
                VStack(spacing: 6) {
                    ZStack {
                        Circle()
                            .fill(status == .done ? AnyShapeStyle(Color.accentColor) : AnyShapeStyle(.quaternary))
                            .frame(width: 28, height: 28)
                        if status == .done {
                            Image(systemName: "checkmark").font(.caption.weight(.bold)).foregroundStyle(.white)
                        } else if status == .active {
                            ProgressView().controlSize(.small)
                        } else if status == .failed {
                            Image(systemName: "xmark").font(.caption.weight(.bold)).foregroundStyle(.red)
                        } else {
                            Text("\(i + 1)").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                        }
                    }
                    Text(step.label)
                        .font(.callout.weight(status == .active ? .semibold : .regular))
                        .foregroundStyle(status == .pending ? .secondary : .primary)
                }
                .frame(maxWidth: .infinity)
                if i < steps.count - 1 {
                    Rectangle().fill(.quaternary).frame(height: 1).frame(maxWidth: 40).offset(y: -10)
                }
            }
        }
        .padding(.vertical, 6)
    }

    enum Status { case pending, active, done, failed }

    private func order(_ p: JobPhase) -> Int {
        switch p {
        case .preparing: return 0
        case .reading: return 1
        case .muxing, .encoding: return 2
        case .uploading: return 3
        case .plex: return 4
        case .done: return 5
        }
    }

    private func status(of step: JobPhase) -> Status {
        let current = order(state.phase)
        let mine = order(step)
        if state.result != nil { return .done }
        if !state.isRunning && state.error != nil {
            if mine < current { return .done }
            return mine == max(current, 1) ? .failed : .pending
        }
        // Reading and muxing/encoding run together: reading is done once data flows.
        if step == .reading && current >= 2 { return .done }
        if mine < current { return .done }
        if mine == current { return .active }
        return .pending
    }
}

private struct RunningSection: View {
    let state: RipState

    var body: some View {
        let p = state.progress
        Section {
            if state.phase == .uploading {
                let fraction = state.uploadTotal > 0 ? Double(state.uploadDone) / Double(state.uploadTotal) : 0
                ProgressView(value: fraction) {
                    Text("Uploaden naar de NAS")
                } currentValueLabel: {
                    Text("\(Format.bytes(state.uploadDone)) van \(Format.bytes(state.uploadTotal))").monospacedDigit()
                }
                let rate = state.uploadElapsed > 0 ? Double(state.uploadDone) / state.uploadElapsed : 0
                LabeledContent("Snelheid", value: "\((rate / 1_000_000).formatted(.number.precision(.fractionLength(1)))) MB/s")
                if rate > 0 {
                    LabeledContent("Nog", value: Format.duration(Double(state.uploadTotal - state.uploadDone) / rate))
                }
            } else if state.phase == .plex {
                HStack { ProgressView().controlSize(.small); Text("Plex scant de map en wacht op de film…") }
            } else {
                ProgressView(value: p.fraction) {
                    Text(state.phase == .encoding ? String(localized: "Lezen en encoderen") : state.phase == .muxing ? String(localized: "Lezen en muxen") : String(localized: "Disc lezen"))
                } currentValueLabel: {
                    Text("\(Format.bytes(p.bytesDone)) van \(Format.bytes(p.bytesTotal)) · \(Int(p.fraction * 100))%")
                        .monospacedDigit()
                }
                LabeledContent("Snelheid", value: "\(p.megabytesPerSecond.formatted(.number.precision(.fractionLength(1)))) MB/s")
                if let eta = p.eta {
                    LabeledContent("Nog", value: Format.duration(eta))
                } else {
                    LabeledContent("Nog", value: String(localized: "berekenen…"))
                }
                if p.encoderFPS > 0 {
                    LabeledContent("Encoder", value: "\(Int(p.encoderFPS.rounded())) fps")
                    if let rt = p.realtimeFactor, rt < 1 {
                        Label("Encoderen gaat trager dan realtime (\(rt.formatted(.number.precision(.fractionLength(2))))×); de resterende tijd houdt daar rekening mee.",
                              systemImage: "tortoise")
                            .font(.callout).foregroundStyle(.secondary)
                    }
                }
                LabeledContent("Geschreven", value: Format.bytes(p.outputBytes))
                LabeledContent("Verstreken", value: Format.duration(p.elapsed))
                if p.badSectors > 0 {
                    Label("\(p.badSectors) onleesbare sectoren overgeslagen", systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.orange)
                }
            }
        } footer: {
            Text("Je mag het venster sluiten; DiscRip houdt de Mac wakker tot de rip klaar is.")
                .foregroundStyle(.secondary)
        }
    }
}

private struct SuccessSection: View {
    @Environment(AppModel.self) private var model
    let result: JobResult

    var body: some View {
        Section {
            Label {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Rip voltooid").font(.headline)
                    Text("\(Format.bytes(result.outcome.outputBytes)) in \(Format.duration(result.ripSeconds))")
                        .foregroundStyle(.secondary)
                }
            } icon: {
                Image(systemName: "checkmark.seal.fill").foregroundStyle(.green).font(.title2)
            }
            if result.plexAdded {
                HStack {
                    Label("Toegevoegd aan Plex", systemImage: "play.rectangle.fill").foregroundStyle(.orange)
                    Spacer()
                    if let url = result.plexURL {
                        Link("Openen in Plex", destination: url)
                    }
                }
            }
            if let local = result.localURL {
                LabeledContent("Lokaal") {
                    Button("Toon in Finder") { NSWorkspace.shared.activateFileViewerSelecting([local]) }
                        .buttonStyle(.link)
                }
                Text(local.path).font(.caption.monospaced()).foregroundStyle(.secondary).textSelection(.enabled)
            }
            if let nas = result.nasURL {
                LabeledContent("NAS", value: nas.path)
                    .font(.callout)
            }
        }
        if !result.warnings.isEmpty {
            Section("Let op") {
                ForEach(result.warnings, id: \.self) { w in
                    Label(w, systemImage: "exclamationmark.triangle").foregroundStyle(.orange)
                }
            }
        }
        if model.source?.media != nil && model.settings.ejectAfterRip {
            Section {
                Label("De disc is uitgeworpen. Steek de volgende disc in en klik op Klaar om verder te gaan.", systemImage: "eject")
                    .foregroundStyle(.secondary)
                if let banner = model.banner {
                    Label(banner, systemImage: "exclamationmark.triangle").foregroundStyle(.orange)
                }
            }
        }
    }
}

private struct FailureSection: View {
    let error: DiscRipError

    var body: some View {
        Section {
            Label {
                VStack(alignment: .leading, spacing: 4) {
                    Text(error == .cancelled ? String(localized: "Rip geannuleerd") : String(localized: "Rip mislukt")).font(.headline)
                    Text(error.localizedDescription).foregroundStyle(.secondary)
                    if error == .cancelled {
                        Text("Het onvolledige bestand werd verwijderd.").foregroundStyle(.secondary)
                    }
                }
            } icon: {
                Image(systemName: error == .cancelled ? "xmark.circle.fill" : "exclamationmark.octagon.fill")
                    .foregroundStyle(error == .cancelled ? Color.secondary : Color.red)
                    .font(.title2)
            }
            Button("Logbestanden tonen") {
                NSWorkspace.shared.open(AppPaths.logDirectory)
            }
            .buttonStyle(.link)
        }
    }
}
