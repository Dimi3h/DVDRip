import Foundation

public enum MediaKind: String, Codable, CaseIterable, Identifiable, Sendable {
    case movie
    case homeVideo
    case other

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .movie: return String(localized: "Film")
        case .homeVideo: return String(localized: "Thuisvideo")
        case .other: return String(localized: "Andere")
        }
    }

    /// Home videos and "other" go to their own base folder and never carry an id.
    public var usesHomeVideoFolder: Bool { self != .movie }
}

public struct MediaMetadata: Codable, Equatable, Sendable {
    public var title: String
    public var year: Int?
    public var tmdbId: Int?
    public var imdbId: String?
    public var kind: MediaKind
    public var overview: String?
    public var posterPath: String?
    public var originalTitle: String?

    public init(title: String, year: Int? = nil, tmdbId: Int? = nil, imdbId: String? = nil,
                kind: MediaKind = .movie, overview: String? = nil, posterPath: String? = nil,
                originalTitle: String? = nil) {
        self.title = title
        self.year = year
        self.tmdbId = tmdbId
        self.imdbId = imdbId
        self.kind = kind
        self.overview = overview
        self.posterPath = posterPath
        self.originalTitle = originalTitle
    }

    public var isValid: Bool {
        !title.trimmingCharacters(in: .whitespaces).isEmpty && (year.map { (1888...2100).contains($0) } ?? true)
    }

    /// Normalised IMDb id ("tt0133093") or nil.
    public var normalizedIMDbId: String? {
        guard let raw = imdbId?.trimmingCharacters(in: .whitespaces).lowercased(), !raw.isEmpty else { return nil }
        let digits = raw.hasPrefix("tt") ? String(raw.dropFirst(2)) : raw
        guard !digits.isEmpty, digits.allSatisfy(\.isNumber) else { return nil }
        return "tt" + digits
    }
}

/// Plex-style naming: `<base>/<Title> (<Year>) {tmdb-<id>}/<Title> (<Year>) {tmdb-<id>}.mkv`.
public enum PlexNaming {
    public static func sanitize(_ s: String) -> String {
        var out = s.replacingOccurrences(of: ": ", with: " - ")
            .replacingOccurrences(of: ":", with: "-")
        for ch in ["/", "\\", "*", "?", "\"", "<", ">", "|"] { out = out.replacingOccurrences(of: ch, with: "") }
        out = out.components(separatedBy: .whitespacesAndNewlines).filter { !$0.isEmpty }.joined(separator: " ")
        while out.hasSuffix(".") || out.hasSuffix(" ") { out.removeLast() }
        while out.hasPrefix(".") { out.removeFirst() }
        return out.isEmpty ? "DVD" : out
    }

    public static func baseName(_ m: MediaMetadata) -> String {
        var name = sanitize(m.title)
        if let year = m.year { name += " (\(year))" }
        if m.kind == .movie {
            if let id = m.tmdbId {
                name += " {tmdb-\(id)}"
            } else if let imdb = m.normalizedIMDbId {
                name += " {imdb-\(imdb)}"
            }
        }
        return name
    }

    /// Path relative to the destination root, e.g. "Films/The Matrix (1999) {tmdb-603}/The Matrix (1999) {tmdb-603}.mkv".
    public static func relativePath(_ m: MediaMetadata, moviesBase: String, homeVideosBase: String) -> String {
        let base = (m.kind.usesHomeVideoFolder ? homeVideosBase : moviesBase)
            .split(separator: "/").map(String.init).filter { !$0.isEmpty }.joined(separator: "/")
        let name = baseName(m)
        return (base.isEmpty ? "" : base + "/") + name + "/" + name + ".mkv"
    }

    /// Matroska global tags: TITLE (as a SimpleTag), DATE_RELEASED and the external ids.
    public static func tags(_ m: MediaMetadata) -> [(String, String)] {
        // libavformat writes "title" only into the segment info; the "-und" suffix makes it emit a
        // TITLE SimpleTag as well (the suffix becomes the tag language).
        var t: [(String, String)] = [("title", m.title), ("title-und", m.title)]
        if let y = m.year { t.append(("DATE_RELEASED", String(y))) }
        if m.kind == .movie {
            if let id = m.tmdbId { t.append(("TMDB", "movie/\(id)")) }
            if let imdb = m.normalizedIMDbId { t.append(("IMDB", imdb)) }
        }
        if let o = m.overview, !o.isEmpty { t.append(("SUMMARY", o)) }
        t.append(("ENCODER_SETTINGS", "DiscRip"))
        return t
    }
}

/// Guesses a search title from the disc volume label.
public enum TitleNormalizer {
    private static let dropWords: Set<String> = [
        "DISC", "DISK", "DVD", "DVD5", "DVD9", "CD", "SIDE", "EDITION", "SPECIAL", "COLLECTORS", "COLLECTOR",
        "LIMITED", "ULTIMATE", "ANNIVERSARY", "DELUXE", "DIRECTORS", "WIDESCREEN", "WS", "FULLSCREEN", "FS",
        "PAL", "NTSC", "R1", "R2", "R4", "REGION", "RETAIL", "MOVIE", "FEATURE", "MAIN", "VIDEO", "TS",
        "NL", "BE", "NLD", "UK", "US", "EU",
    ]
    private static let romanNumerals: Set<String> = ["II", "III", "IV", "VI", "VII", "VIII", "IX", "XI", "XII"]

    public static func searchTitle(fromVolumeLabel label: String) -> String {
        var s = label.replacingOccurrences(of: "_", with: " ")
            .replacingOccurrences(of: ".", with: " ")
            .replacingOccurrences(of: "-", with: " ")
        // "DISC1", "D1", "DISK2", "CD1", "SIDEA"
        s = s.replacingOccurrences(of: #"(?i)\b(disc|disk|dvd|cd|side)\s*[0-9a-b]{0,2}\b"#, with: " ",
                                   options: .regularExpression)
        s = s.replacingOccurrences(of: #"(?i)\bd[0-9]{1,2}\b"#, with: " ", options: .regularExpression)
        let words = s.split(whereSeparator: { $0 == " " }).map(String.init).filter { w in
            !dropWords.contains(w.uppercased())
        }
        let cased = words.map { w -> String in
            let upper = w.uppercased()
            if romanNumerals.contains(upper) { return upper }
            if w.count <= 1 && w.allSatisfy(\.isNumber) { return w }
            return w.prefix(1).uppercased() + w.dropFirst().lowercased()
        }
        let result = cased.joined(separator: " ")
        return result.isEmpty ? label : result
    }
}
