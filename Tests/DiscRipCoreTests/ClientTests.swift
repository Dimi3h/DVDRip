import Foundation
import Testing
@testable import DiscRipCore

/// URLProtocol stub keyed by test, so suites can run in parallel.
final class StubProtocol: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var handlers: [String: (URLRequest) -> (Int, Data)] = [:]
    nonisolated(unsafe) static var seen: [String: [URLRequest]] = [:]
    static let lock = NSLock()

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let key = request.value(forHTTPHeaderField: "X-Test") ?? request.url?.host ?? ""
        Self.lock.lock()
        let h = Self.handlers[key]
        Self.seen[key, default: []].append(request)
        Self.lock.unlock()
        let (status, data) = h?(request) ?? (404, Data())
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil,
                                                              headerFields: nil)!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}

    static func session(host: String, handler: @escaping (URLRequest) -> (Int, Data)) -> URLSession {
        lock.lock()
        handlers[host] = handler
        seen[host] = []
        lock.unlock()
        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [StubProtocol.self]
        return URLSession(configuration: cfg)
    }

    static func requests(_ host: String) -> [URLRequest] {
        lock.lock()
        defer { lock.unlock() }
        return seen[host] ?? []
    }
}

@Suite struct TMDBTests {
    let searchNL = #"{"results":[{"id":603,"title":"The Matrix","original_title":"The Matrix","release_date":"1999-03-31","overview":"","poster_path":"/p.jpg","popularity":80.1},{"id":604,"title":"The Matrix Reloaded","release_date":"2003-05-15","overview":"Neo...","poster_path":null}]}"#
    let searchEN = #"{"results":[{"id":603,"title":"The Matrix","release_date":"1999-03-31","overview":"A hacker learns...","poster_path":"/p.jpg"}]}"#

    @Test func searchFillsMissingOverviewFromEnglish() async throws {
        let host = "tmdb-search.test"
        let session = StubProtocol.session(host: host) { req in
            let lang = URLComponents(url: req.url!, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "language" }?.value
            return (200, Data((lang == "nl-BE" ? searchNL : searchEN).utf8))
        }
        let client = TMDBClient(credential: "abc123", session: session, baseURL: URL(string: "https://\(host)/3")!)
        let movies = try await client.search("The Matrix", year: nil)
        #expect(movies.count == 2)
        #expect(movies[0].year == 1999)
        #expect(movies[0].overview == "A hacker learns...")
        #expect(movies[0].posterURL?.absoluteString == "https://image.tmdb.org/t/p/w185/p.jpg")
        #expect(movies[0].metadata().tmdbId == 603)
        let first = try #require(StubProtocol.requests(host).first)
        let items = URLComponents(url: first.url!, resolvingAgainstBaseURL: false)!.queryItems!
        #expect(items.contains(URLQueryItem(name: "api_key", value: "abc123")))
        #expect(items.contains(URLQueryItem(name: "language", value: "nl-BE")))
        #expect(first.value(forHTTPHeaderField: "Authorization") == nil)
    }

    @Test func bearerTokenAndInvalidKey() async {
        let host = "tmdb-auth.test"
        let session = StubProtocol.session(host: host) { _ in (401, Data()) }
        let client = TMDBClient(credential: "eyJhbGciOi.token", session: session, baseURL: URL(string: "https://\(host)/3")!)
        await #expect(throws: DiscRipError.tmdbInvalidKey) { try await client.search("x") }
        #expect(StubProtocol.requests(host).first?.value(forHTTPHeaderField: "Authorization") == "Bearer eyJhbGciOi.token")
    }

    @Test func missingKey() async {
        let client = TMDBClient(credential: " ")
        await #expect(throws: DiscRipError.tmdbNoKey) { try await client.search("x") }
    }
}

@Suite struct PlexTests {
    @Test func sectionsAndRefreshURL() async throws {
        let host = "plex.test"
        let sections = #"{"MediaContainer":{"Directory":[{"key":"1","title":"Films","type":"movie","Location":[{"id":1,"path":"/volume1/video/Films"}]},{"key":"5","title":"Home Videos","type":"movie","Location":[{"id":2,"path":"/volume1/video/Home Videos"}]}]}}"#
        let session = StubProtocol.session(host: host) { req in
            if req.url!.path == "/library/sections" { return (200, Data(sections.utf8)) }
            return (200, Data("{}".utf8))
        }
        let plex = try #require(PlexClient(urlString: "http://plex.test:32400/", token: "tok", session: session))
        let s = try await plex.sections()
        #expect(s.map(\.title) == ["Films", "Home Videos"])
        #expect(s[1].locations == ["/volume1/video/Home Videos"])
        try await plex.refresh(sectionId: "1", path: "/volume1/video/Films/The Matrix (1999) {tmdb-603}")
        let refresh = try #require(StubProtocol.requests(host).last?.url?.absoluteString)
        #expect(refresh.hasPrefix("http://plex.test:32400/library/sections/1/refresh?path=%2Fvolume1%2Fvideo%2FFilms%2FThe%20Matrix%20(1999)%20%7Btmdb-603%7D"))
        #expect(refresh.hasSuffix("&X-Plex-Token=tok"))
    }

    @Test func invalidToken() async throws {
        let host = "plex-401.test"
        let session = StubProtocol.session(host: host) { _ in (401, Data()) }
        let plex = try #require(PlexClient(urlString: "plex-401.test", token: "bad", session: session))
        await #expect(throws: DiscRipError.plexTokenInvalid) { try await plex.sections() }
    }

    @Test func serverFolderPathPicksMatchingLocation() {
        let p = PlexClient.serverFolderPath(locations: ["/volume1/video/Series", "/volume1/video/Films/"],
                                            nasBaseFolder: "Films", folderName: "Heat (1995) {tmdb-949}")
        #expect(p == "/volume1/video/Films/Heat (1995) {tmdb-949}")
        #expect(PlexClient.serverFolderPath(locations: ["/data/movies"], nasBaseFolder: "Films", folderName: "X")
            == "/data/movies/X")
        #expect(PlexClient.serverFolderPath(locations: [], nasBaseFolder: "Films", folderName: "X") == nil)
    }

    @Test func itemMatching() {
        let m = MediaMetadata(title: "Amélie", year: 2001, tmdbId: 194)
        let byGuid = PlexClient.Item(ratingKey: "9", title: "Le Fabuleux Destin", year: 2001, guid: nil,
                                     Guid: [.init(id: "tmdb://194")], addedAt: nil)
        let byTitle = PlexClient.Item(ratingKey: "9", title: "amelie", year: 2001, guid: nil, Guid: nil, addedAt: nil)
        let wrongYear = PlexClient.Item(ratingKey: "9", title: "Amélie", year: 1999, guid: nil, Guid: nil, addedAt: nil)
        #expect(PlexClient.matches(byGuid, metadata: m))
        #expect(PlexClient.matches(byTitle, metadata: m))
        #expect(!PlexClient.matches(wrongYear, metadata: m))
        #expect(PlexClient.webURL(machineIdentifier: "abc", ratingKey: "42")?.absoluteString
            == "https://app.plex.tv/desktop/#!/server/abc/details?key=%2Flibrary%2Fmetadata%2F42")
    }
}

@Suite struct SettingsAndModelTests {
    @Test func settingsDecodeToleratesMissingKeys() throws {
        let json = #"{"defaultPreset":"compact","nasHost":"ds920"}"#
        let s = try JSONDecoder().decode(AppSettings.self, from: Data(json.utf8))
        #expect(s.defaultPreset == .compact)
        #expect(s.nasHost == "ds920")
        #expect(s.storageTarget == .local)
        #expect(s.balancedKbps == 2500)
    }

    @Test func settingsRoundTrip() {
        let defaults = UserDefaults(suiteName: "discrip-tests-\(UUID().uuidString)")!
        let store = SettingsStore(defaults: defaults, keychain: Keychain(service: "discrip-tests"))
        var s = AppSettings()
        s.storageTarget = .both
        s.setBitrateKbps(1800, for: .balanced)
        store.save(s)
        #expect(store.load() == s)
        #expect(store.load().bitrateKbps(for: .balanced) == 1800)
    }

    @Test func decodesNativeDiscInfo() throws {
        let url = try #require(Bundle.module.url(forResource: "test-disc-info", withExtension: "json", subdirectory: "Fixtures"))
        let info = try JSONDecoder().decode(DiscInfo.self, from: Data(contentsOf: url))
        #expect(info.volumeLabel == "DISCRIP_TEST_D1")
        #expect(info.titles.count == 2)
        let main = info.titles[1]
        #expect(main.chapters == 3)
        #expect(main.audio.map(\.format) == ["ac3", "lpcm"])
        #expect(main.audio.map(\.lang) == ["nl", "en"])
        #expect(main.subtitles.map(\.streamId) == [0x20, 0x21])
        #expect(info.allowedRegions == Array(1...8))
        #expect(TitleScoring.mainFeature(info.titles)?.index == 2)
        #expect(TrackNaming.audioName(main.audio[0]).contains("Dolby Digital 2.0"))
    }

    @Test func ripProgressMath() {
        let p = RipProgress(bytesDone: 50_000_000, bytesTotal: 100_000_000, elapsed: 10, mediaDone: 5, mediaTotal: 100)
        #expect(p.fraction == 0.5)
        #expect(p.megabytesPerSecond == 5)
        #expect(p.eta == 10)
        #expect(p.realtimeFactor == 0.5)
    }
}
