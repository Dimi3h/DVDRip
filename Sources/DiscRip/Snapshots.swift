import AppKit
import DiscRipCore
import SwiftUI

/// `DiscRip --snapshot <folder> [--open <iso>]` renders the main screens offscreen to PNG files.
/// Used to check layout, light and dark mode without screen-recording permission.
@MainActor
enum Snapshots {
    static var requested: (folder: URL, iso: URL?)? {
        let args = CommandLine.arguments
        guard let i = args.firstIndex(of: "--snapshot"), i + 1 < args.count else { return nil }
        let iso = args.firstIndex(of: "--open").flatMap { $0 + 1 < args.count ? URL(fileURLWithPath: args[$0 + 1]) : nil }
        return (URL(fileURLWithPath: args[i + 1]), iso)
    }

    static func run(model: AppModel, folder: URL, iso: URL?) async {
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        if let iso {
            model.openFile(iso)
            for _ in 0..<100 where model.info == nil { try? await Task.sleep(nanoseconds: 100_000_000) }
        }
        let screens: [(String, AnyView, CGSize)] = [
            ("home", AnyView(NavigationStack { HomeView() }), CGSize(width: 760, height: 640)),
            ("disc", AnyView(NavigationStack { DiscView() }), CGSize(width: 760, height: 1500)),
            ("metadata", AnyView(NavigationStack { MetadataView() }), CGSize(width: 760, height: 900)),
            ("settings", AnyView(SettingsView()), CGSize(width: 560, height: 560)),
        ]
        for appearance in [NSAppearance.Name.aqua, .darkAqua] {
            for (name, view, size) in screens {
                let suffix = appearance == .darkAqua ? "-dark" : ""
                render(view.environment(model), size: size, appearance: appearance,
                       to: folder.appendingPathComponent("\(name)\(suffix).png"))
                try? await Task.sleep(nanoseconds: 300_000_000)
            }
        }
        // Rip screen with a simulated progress state.
        model.metadata = MediaMetadata(title: "Test Film", year: 2024, tmdbId: 4242)
        var state = RipState()
        state.phase = .muxing
        state.progress = RipProgress(phase: .reading, bytesDone: 2_400_000_000, bytesTotal: 5_600_000_000, elapsed: 600,
                                     mediaDone: 2600, mediaTotal: 6200, outputBytes: 2_300_000_000)
        model.rip = state
        render(AnyView(NavigationStack { RipView() }).environment(model), size: CGSize(width: 760, height: 700),
               appearance: .aqua, to: folder.appendingPathComponent("rip.png"))
        try? await Task.sleep(nanoseconds: 300_000_000)
        model.rip = nil
        NSApp.terminate(nil)
    }

    static func render<V: View>(_ view: V, size: CGSize, appearance: NSAppearance.Name, to url: URL) {
        let host = NSHostingView(rootView: view.frame(width: size.width, height: size.height))
        host.frame = NSRect(origin: .zero, size: size)
        let window = NSWindow(contentRect: host.frame, styleMask: [.titled], backing: .buffered, defer: false)
        window.appearance = NSAppearance(named: appearance)
        window.contentView = host
        window.layoutIfNeeded()
        host.layoutSubtreeIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.4))
        guard let rep = host.bitmapImageRepForCachingDisplay(in: host.bounds) else { return }
        host.cacheDisplay(in: host.bounds, to: rep)
        try? rep.representation(using: .png, properties: [:])?.write(to: url)
    }
}
