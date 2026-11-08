import Foundation

public struct PlexClient: Sendable {
    public struct Section: Codable, Identifiable, Equatable, Hashable, Sendable {
        public struct PathLocation: Codable, Equatable, Hashable, Sendable {
            public var id: Int?
            public var path: String
        }
        public var key: String
        public var title: String
        public var type: String
        public var location: [PathLocation]?
        public var id: String { key }
        public var locations: [String] { (location ?? []).map { $0.path } }

        enum CodingKeys: String, CodingKey {
            case key, title, type
            case location = "Location"
        }

        public init(key: String, title: String, type: String, location: [PathLocation]?) {
            self.key = key
            self.title = title
            self.type = type
            self.location = location
        }
    }

    public struct Item: Codable, Equatable, Sendable {
        public struct GuidEntry: Codable, Equatable, Sendable { public var id: String }
        public var ratingKey: String
        public var title: String
        public var year: Int?
        public var guid: String?
        public var Guid: [GuidEntry]?
        public var addedAt: Int?
    }

    struct Container<T: Codable>: Codable { var MediaContainer: T }
    struct SectionList: Codable { var Directory: [Section]? }
    struct ItemList: Codable { var Metadata: [Item]? }
    struct Identity: Codable { var machineIdentifier: String }

    public let baseURL: URL
    public let token: String
    let session: URLSession

    public init?(urlString: String, token: String, session: URLSession = .shared) {
        var s = urlString.trimmingCharacters(in: .whitespacesAndNewlines)
        if !s.contains("://") { s = "http://" + s }
        while s.hasSuffix("/") { s.removeLast() }
        guard let url = URL(string: s) else { return nil }
        self.baseURL = url
        self.token = token.trimmingCharacters(in: .whitespacesAndNewlines)
        self.session = session
    }

    func url(_ path: String, _ items: [URLQueryItem] = []) -> URL {
        var comps = URLComponents(url: baseURL.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        comps.queryItems = items + [URLQueryItem(name: "X-Plex-Token", value: token)]
        // URLComponents leaves "/" unescaped in query values; Plex wants the path fully encoded.
        comps.percentEncodedQuery = comps.percentEncodedQuery?
            .replacingOccurrences(of: "/", with: "%2F")
            .replacingOccurrences(of: "+", with: "%2B")
        return comps.url!
    }

    func get(_ url: URL) async throws -> Data {
        var req = URLRequest(url: url, timeoutInterval: 20)
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.setValue("DiscRip", forHTTPHeaderField: "X-Plex-Product")
        req.setValue("discrip-mac", forHTTPHeaderField: "X-Plex-Client-Identifier")
        let data: Data, response: URLResponse
        do {
            (data, response) = try await session.data(for: req)
        } catch {
            throw DiscRipError.plexUnreachable(url: baseURL.absoluteString)
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 401 || status == 403 { throw DiscRipError.plexTokenInvalid }
        guard (200..<300).contains(status) else { throw DiscRipError.plexUnreachable(url: baseURL.absoluteString) }
        return data
    }

    public func sections() async throws -> [Section] {
        let data = try await get(url("library/sections"))
        return try JSONDecoder().decode(Container<SectionList>.self, from: data).MediaContainer.Directory ?? []
    }

    public func machineIdentifier() async throws -> String {
        let data = try await get(url("identity"))
        return try JSONDecoder().decode(Container<Identity>.self, from: data).MediaContainer.machineIdentifier
    }

    /// Asks Plex to scan one folder of a library section.
    public func refresh(sectionId: String, path: String) async throws {
        _ = try await get(url("library/sections/\(sectionId)/refresh", [URLQueryItem(name: "path", value: path)]))
    }

    public func items(sectionId: String, title: String) async throws -> [Item] {
        let data = try await get(url("library/sections/\(sectionId)/all", [
            URLQueryItem(name: "title", value: title),
            URLQueryItem(name: "includeGuids", value: "1"),
        ]))
        return try JSONDecoder().decode(Container<ItemList>.self, from: data).MediaContainer.Metadata ?? []
    }

    public static func matches(_ item: Item, metadata m: MediaMetadata) -> Bool {
        if let id = m.tmdbId, (item.Guid ?? []).contains(where: { $0.id == "tmdb://\(id)" }) { return true }
        if let imdb = m.normalizedIMDbId, (item.Guid ?? []).contains(where: { $0.id == "imdb://\(imdb)" }) { return true }
        let sameTitle = item.title.compare(m.title, options: [.caseInsensitive, .diacriticInsensitive]) == .orderedSame
        return sameTitle && (m.year == nil || item.year == nil || item.year == m.year)
    }

    /// Polls the section until the new item shows up. Returns the item or nil after the timeout.
    public func waitForItem(sectionId: String, metadata m: MediaMetadata, timeout: TimeInterval = 180,
                            interval: TimeInterval = 3) async throws -> Item? {
        let deadline = Date().addingTimeInterval(timeout)
        let searchTitle = String(m.title.prefix(40))
        repeat {
            try Task.checkCancellation()
            let found = try await items(sectionId: sectionId, title: searchTitle)
            if let hit = found.first(where: { Self.matches($0, metadata: m) }) { return hit }
            try await Task.sleep(nanoseconds: UInt64(interval * 1_000_000_000))
        } while Date() < deadline
        return nil
    }

    public static func webURL(machineIdentifier: String, ratingKey: String) -> URL? {
        URL(string: "https://app.plex.tv/desktop/#!/server/\(machineIdentifier)/details?key=%2Flibrary%2Fmetadata%2F\(ratingKey)")
    }

    /// The folder path as the Plex server sees it: the section location that corresponds to the
    /// NAS base folder, plus the movie folder name.
    public static func serverFolderPath(locations: [String], nasBaseFolder: String, folderName: String) -> String? {
        guard !locations.isEmpty else { return nil }
        let last = nasBaseFolder.split(separator: "/").last.map(String.init)?.lowercased()
        let chosen = locations.first { loc in
            loc.split(separator: "/").last.map { String($0).lowercased() } == last
        } ?? locations[0]
        var base = chosen
        while base.hasSuffix("/") { base.removeLast() }
        return base + "/" + folderName
    }
}
