import Foundation

/// The Movie Database client. Accepts a v3 API key or a v4 read access token.
public struct TMDBClient: Sendable {
    public struct Movie: Codable, Identifiable, Equatable, Sendable {
        public var id: Int
        public var title: String
        public var originalTitle: String?
        public var releaseDate: String?
        public var overview: String?
        public var posterPath: String?
        public var popularity: Double?

        enum CodingKeys: String, CodingKey {
            case id, title, overview, popularity
            case originalTitle = "original_title"
            case releaseDate = "release_date"
            case posterPath = "poster_path"
        }

        public init(id: Int, title: String, originalTitle: String? = nil, releaseDate: String? = nil,
                    overview: String? = nil, posterPath: String? = nil, popularity: Double? = nil) {
            self.id = id
            self.title = title
            self.originalTitle = originalTitle
            self.releaseDate = releaseDate
            self.overview = overview
            self.posterPath = posterPath
            self.popularity = popularity
        }

        public var year: Int? {
            guard let d = releaseDate, d.count >= 4 else { return nil }
            return Int(d.prefix(4))
        }

        public var posterURL: URL? {
            posterPath.flatMap { URL(string: "https://image.tmdb.org/t/p/w185\($0)") }
        }

        public func metadata() -> MediaMetadata {
            MediaMetadata(title: title, year: year, tmdbId: id, kind: .movie, overview: overview,
                          posterPath: posterPath, originalTitle: originalTitle)
        }
    }

    struct SearchResponse: Codable { var results: [Movie] }

    public let credential: String
    public let languages: [String]
    let session: URLSession
    let baseURL: URL

    public init(credential: String, languages: [String] = ["nl-BE", "en-US"],
                session: URLSession = .shared, baseURL: URL = URL(string: "https://api.themoviedb.org/3")!) {
        self.credential = credential.trimmingCharacters(in: .whitespacesAndNewlines)
        self.languages = languages
        self.session = session
        self.baseURL = baseURL
    }

    var isBearerToken: Bool { credential.hasPrefix("eyJ") }

    func request(_ path: String, _ items: [URLQueryItem]) -> URLRequest {
        var comps = URLComponents(url: baseURL.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        var q = items
        if !isBearerToken { q.append(URLQueryItem(name: "api_key", value: credential)) }
        comps.queryItems = q
        var req = URLRequest(url: comps.url!, timeoutInterval: 15)
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if isBearerToken { req.setValue("Bearer \(credential)", forHTTPHeaderField: "Authorization") }
        return req
    }

    func fetch<T: Decodable>(_ req: URLRequest, as: T.Type) async throws -> T {
        guard !credential.isEmpty else { throw DiscRipError.tmdbNoKey }
        let data: Data, response: URLResponse
        do {
            (data, response) = try await session.data(for: req)
        } catch {
            throw DiscRipError.tmdbUnreachable
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 401 { throw DiscRipError.tmdbInvalidKey }
        guard (200..<300).contains(status) else { throw DiscRipError.tmdbUnreachable }
        return try JSONDecoder().decode(T.self, from: data)
    }

    /// Searches in the first language; empty overviews are filled from the next language.
    public func search(_ query: String, year: Int? = nil) async throws -> [Movie] {
        var items = [URLQueryItem(name: "query", value: query),
                     URLQueryItem(name: "include_adult", value: "false"),
                     URLQueryItem(name: "language", value: languages.first ?? "en-US")]
        if let year { items.append(URLQueryItem(name: "year", value: String(year))) }
        var movies = try await fetch(request("search/movie", items), as: SearchResponse.self).results
        if movies.isEmpty, year != nil { return try await search(query, year: nil) }
        if languages.count > 1, movies.contains(where: { ($0.overview ?? "").isEmpty }) {
            let fallback = try? await fetch(request("search/movie", [
                URLQueryItem(name: "query", value: query),
                URLQueryItem(name: "include_adult", value: "false"),
                URLQueryItem(name: "language", value: languages[1]),
            ]), as: SearchResponse.self).results
            let byId = Dictionary((fallback ?? []).map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
            movies = movies.map { m in
                var m = m
                if (m.overview ?? "").isEmpty, let o = byId[m.id]?.overview { m.overview = o }
                if m.posterPath == nil { m.posterPath = byId[m.id]?.posterPath }
                return m
            }
        }
        return movies
    }

    public func movie(id: Int) async throws -> Movie {
        var m = try await fetch(request("movie/\(id)", [URLQueryItem(name: "language", value: languages.first ?? "en-US")]),
                                as: Movie.self)
        if (m.overview ?? "").isEmpty, languages.count > 1,
           let en = try? await fetch(request("movie/\(id)", [URLQueryItem(name: "language", value: languages[1])]), as: Movie.self) {
            m.overview = en.overview
        }
        return m
    }

    /// Finds a movie by IMDb id.
    public func movie(imdbId: String) async throws -> Movie? {
        struct Find: Codable {
            var movie_results: [Movie]
        }
        let r = try await fetch(request("find/\(imdbId)", [URLQueryItem(name: "external_source", value: "imdb_id"),
                                                          URLQueryItem(name: "language", value: languages.first ?? "en-US")]),
                                as: Find.self)
        return r.movie_results.first
    }

    /// The result to preselect: an exact title match (ignoring case, accents and punctuation),
    /// preferring the requested year; otherwise TMDB's most relevant result (the first).
    public static func bestMatch(_ movies: [Movie], query: String, year: Int? = nil) -> Movie? {
        func norm(_ s: String) -> String {
            s.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
                .unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) }
                .map(String.init).joined()
        }
        let q = norm(query)
        let exact = movies.filter { norm($0.title) == q || norm($0.originalTitle ?? "") == q }
        if let year, let hit = exact.first(where: { $0.year == year }) ?? movies.prefix(3).first(where: { $0.year == year }) {
            return hit
        }
        return exact.first ?? movies.first
    }

    /// Checks the credential.
    public func validate() async throws {
        struct Config: Codable { var images: [String: AnyCodableIgnored]? }
        _ = try await fetch(request("configuration", []), as: Config.self)
    }
}

/// Decodes and discards any JSON value.
public struct AnyCodableIgnored: Codable, Sendable {
    public init(from decoder: Decoder) throws {}
    public func encode(to encoder: Encoder) throws {}
}
