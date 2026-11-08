import Foundation
import Security

public enum StorageTarget: String, Codable, CaseIterable, Identifiable, Sendable {
    case local
    case nas
    case both

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .local: return String(localized: "Lokaal")
        case .nas: return String(localized: "NAS")
        case .both: return String(localized: "Beide")
        }
    }

    public var usesLocal: Bool { self != .nas }
    public var usesNAS: Bool { self != .local }
}

public enum MetadataMode: String, Codable, CaseIterable, Identifiable, Sendable {
    case automatic
    case manual
    public var id: String { rawValue }
    public var label: String {
        switch self {
        case .automatic: return String(localized: "Automatisch (TMDB)")
        case .manual: return String(localized: "Zelf invullen")
        }
    }
}

public enum SubtitleDefault: String, Codable, CaseIterable, Identifiable, Sendable {
    case none
    case preferred
    case all
    public var id: String { rawValue }
    public var label: String {
        switch self {
        case .none: return String(localized: "Geen")
        case .preferred: return String(localized: "Voorkeurstalen")
        case .all: return String(localized: "Alle")
        }
    }
}

public struct AppSettings: Codable, Equatable, Sendable {
    // Quality
    public var defaultPreset: QualityPreset = .lossless
    public var balancedKbps: Int = QualityPreset.balanced.defaultBitrateKbps
    public var compactKbps: Int = QualityPreset.compact.defaultBitrateKbps
    public var compatibleKbps: Int = QualityPreset.compatible.defaultBitrateKbps
    public var lpcmToFlac: Bool = false

    // Tracks
    public var preferredLanguages: [String] = ["nl", "en"]
    public var subtitleDefault: SubtitleDefault = .preferred

    // Metadata
    public var metadataMode: MetadataMode = .automatic

    // Storage
    public var storageTarget: StorageTarget = .local
    public var localFolderBookmark: Data?
    public var localFolderPath: String?
    public var localMoviesSubfolder: String = ""
    public var localHomeVideosSubfolder: String = "Home Videos"

    // NAS
    public var nasHost: String = ""
    public var nasShare: String = "video"
    public var nasMoviesFolder: String = "Films"
    public var nasHomeVideosFolder: String = "Home Videos"
    public var nasUsername: String = ""
    public var nasDirectWrite: Bool = false

    // Plex
    public var plexURL: String = ""
    public var plexMoviesSectionId: String = ""
    public var plexHomeVideosSectionId: String = ""

    // Behaviour
    public var ejectAfterRip: Bool = true

    public init() {}

    public func bitrateKbps(for preset: QualityPreset) -> Int {
        switch preset {
        case .lossless: return 0
        case .balanced: return balancedKbps
        case .compact: return compactKbps
        case .compatible: return compatibleKbps
        }
    }

    public mutating func setBitrateKbps(_ value: Int, for preset: QualityPreset) {
        switch preset {
        case .lossless: break
        case .balanced: balancedKbps = value
        case .compact: compactKbps = value
        case .compatible: compatibleKbps = value
        }
    }

    public var nasConfigured: Bool { !nasHost.trimmingCharacters(in: .whitespaces).isEmpty && !nasShare.isEmpty }
    public var plexConfigured: Bool { !plexURL.trimmingCharacters(in: .whitespaces).isEmpty }

    public init(from decoder: Decoder) throws {
        // Tolerant decoding: keys added later fall back to their defaults.
        self.init()
        let c = try decoder.container(keyedBy: CodingKeys.self)
        func v<T: Decodable>(_ k: CodingKeys, _ cur: T) -> T { (try? c.decodeIfPresent(T.self, forKey: k)) ?? cur }
        defaultPreset = v(.defaultPreset, defaultPreset)
        balancedKbps = v(.balancedKbps, balancedKbps)
        compactKbps = v(.compactKbps, compactKbps)
        compatibleKbps = v(.compatibleKbps, compatibleKbps)
        lpcmToFlac = v(.lpcmToFlac, lpcmToFlac)
        preferredLanguages = v(.preferredLanguages, preferredLanguages)
        subtitleDefault = v(.subtitleDefault, subtitleDefault)
        metadataMode = v(.metadataMode, metadataMode)
        storageTarget = v(.storageTarget, storageTarget)
        localFolderBookmark = v(.localFolderBookmark, localFolderBookmark)
        localFolderPath = v(.localFolderPath, localFolderPath)
        localMoviesSubfolder = v(.localMoviesSubfolder, localMoviesSubfolder)
        localHomeVideosSubfolder = v(.localHomeVideosSubfolder, localHomeVideosSubfolder)
        nasHost = v(.nasHost, nasHost)
        nasShare = v(.nasShare, nasShare)
        nasMoviesFolder = v(.nasMoviesFolder, nasMoviesFolder)
        nasHomeVideosFolder = v(.nasHomeVideosFolder, nasHomeVideosFolder)
        nasUsername = v(.nasUsername, nasUsername)
        nasDirectWrite = v(.nasDirectWrite, nasDirectWrite)
        plexURL = v(.plexURL, plexURL)
        plexMoviesSectionId = v(.plexMoviesSectionId, plexMoviesSectionId)
        plexHomeVideosSectionId = v(.plexHomeVideosSectionId, plexHomeVideosSectionId)
        ejectAfterRip = v(.ejectAfterRip, ejectAfterRip)
    }
}

/// Persists AppSettings in UserDefaults; secrets live in the Keychain.
public final class SettingsStore: @unchecked Sendable {
    private let defaults: UserDefaults
    private let key = "DiscRip.settings.v1"
    public let keychain: Keychain

    public init(defaults: UserDefaults = .standard, keychain: Keychain = Keychain(service: "be.discrip.DiscRip")) {
        self.defaults = defaults
        self.keychain = keychain
    }

    public func load() -> AppSettings {
        guard let data = defaults.data(forKey: key),
              let s = try? JSONDecoder().decode(AppSettings.self, from: data) else { return AppSettings() }
        return s
    }

    public func save(_ s: AppSettings) {
        if let data = try? JSONEncoder().encode(s) { defaults.set(data, forKey: key) }
    }

    public enum Secret: String, CaseIterable, Sendable {
        case nasPassword = "nas-password"
        case plexToken = "plex-token"
        case tmdbKey = "tmdb-key"
    }

    public func secret(_ s: Secret) -> String { keychain.get(s.rawValue) ?? "" }
    public func setSecret(_ s: Secret, _ value: String) {
        if value.isEmpty { keychain.delete(s.rawValue) } else { keychain.set(value, for: s.rawValue) }
    }
}

/// Minimal generic-password Keychain wrapper.
public struct Keychain: Sendable {
    public let service: String
    public init(service: String) { self.service = service }

    private func query(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    public func get(_ account: String) -> String? {
        var q = query(account)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    @discardableResult
    public func set(_ value: String, for account: String) -> Bool {
        let data = Data(value.utf8)
        let status = SecItemUpdate(query(account) as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var q = query(account)
            q[kSecValueData as String] = data
            q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            return SecItemAdd(q as CFDictionary, nil) == errSecSuccess
        }
        return status == errSecSuccess
    }

    public func delete(_ account: String) {
        SecItemDelete(query(account) as CFDictionary)
    }
}
