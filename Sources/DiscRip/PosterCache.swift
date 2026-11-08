import AppKit
import DiscRipCore

/// TMDB posters of ripped titles, kept in Application Support so the start screen shows them
/// immediately and offline.
enum PosterCache {
    static var directory: URL { AppPaths.supportDirectory.appendingPathComponent("Posters", isDirectory: true) }

    static func fileURL(for posterPath: String) -> URL {
        directory.appendingPathComponent(posterPath.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            .replacingOccurrences(of: "/", with: "_"))
    }

    static func cachedImage(for m: MediaMetadata) -> NSImage? {
        guard let path = m.posterPath else { return nil }
        return NSImage(contentsOf: fileURL(for: path))
    }

    /// Downloads the poster if it is not cached yet. Returns the image or nil.
    static func load(for m: MediaMetadata) async -> NSImage? {
        if let image = cachedImage(for: m) { return image }
        guard let path = m.posterPath, let url = m.posterURL,
              let (data, response) = try? await URLSession.shared.data(from: url),
              (response as? HTTPURLResponse)?.statusCode == 200, let image = NSImage(data: data) else { return nil }
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try? data.write(to: fileURL(for: path), options: .atomic)
        return image
    }
}
