import AppKit
import DiscRipCore
import SwiftUI

@main
struct DiscRipApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @ViewState private var model = AppModel()

    var body: some Scene {
        Window("DiscRip", id: "main") {
            ContentView()
                .environment(model)
                .frame(minWidth: 860, minHeight: 600)
                .onAppear {
                    delegate.model = model
                    if let snap = Snapshots.requested {
                        Task { await Snapshots.run(model: model, folder: snap.folder, iso: snap.iso) }
                    }
                }
        }
        .windowToolbarStyle(.unified)
        .commands {
            CommandGroup(replacing: .newItem) {
                Button("ISO of VIDEO_TS openen…") { model.presentOpenPanel() }
                    .keyboardShortcut("o")
                    .disabled(model.isRipping)
            }
            CommandMenu("Disc") {
                Button("Disc uitwerpen") { model.ejectCurrent() }
                    .keyboardShortcut("e")
                    .disabled(model.isRipping || (model.source?.media == nil && model.dvdInDrive == nil))
                Button("Disc opnieuw lezen") {
                    if let m = model.dvdInDrive { model.openDrive(m) }
                }
                .keyboardShortcut("r")
                .disabled(model.isRipping || model.dvdInDrive == nil)
            }
        }

        Settings {
            SettingsView()
                .environment(model)
        }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    weak var model: AppModel?

    func applicationDidFinishLaunching(_ notification: Notification) {
        // Also when started with `swift run` (no app bundle): behave like a regular app.
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        // A rip keeps running when the window is closed.
        !(MainActor.assumeIsolated { model?.isRipping ?? false })
    }

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        let ripping = MainActor.assumeIsolated { model?.isRipping ?? false }
        guard ripping else { return .terminateNow }
        let alert = NSAlert()
        alert.messageText = String(localized: "Er loopt nog een rip")
        alert.informativeText = String(localized: "Als je DiscRip nu stopt, wordt de rip afgebroken en het onvolledige bestand verwijderd.")
        alert.addButton(withTitle: String(localized: "Doorgaan met rippen"))
        alert.addButton(withTitle: String(localized: "Stop en annuleer"))
        alert.alertStyle = .warning
        if alert.runModal() == .alertSecondButtonReturn {
            MainActor.assumeIsolated { model?.cancelRip() }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { NSApp.reply(toApplicationShouldTerminate: true) }
            return .terminateLater
        }
        return .terminateCancel
    }
}
