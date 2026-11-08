import Foundation

/// Every error the user can run into, with a clear Dutch message (English via en.lproj).
public enum DiscRipError: LocalizedError, Equatable, Sendable {
    case noDisc
    case notDVDVideo(detail: String)
    case regionMismatch(drive: [Int], disc: [Int])
    case driveRegionNotSet(disc: [Int])
    case readRefused(regionHint: Bool)
    case cssKeyMissing(title: Int)
    case tooManyReadErrors
    case deviceGone
    case insufficientSpace(location: String, needed: Int64, available: Int64)
    case folderNotChosen
    case folderUnavailable(path: String)
    case folderNotWritable(path: String)
    case nasNotConfigured
    case nasUnreachable(host: String)
    case nasAuthFailed(host: String)
    case nasMountFailed(code: Int32)
    case uploadVerifyFailed(local: Int64, remote: Int64)
    case plexNotConfigured
    case plexTokenInvalid
    case plexUnreachable(url: String)
    case plexNotFound
    case encoderUnavailable
    case encoderFailed(partialRemoved: Bool)
    case output(message: String, partialRemoved: Bool)
    case tmdbNoKey
    case tmdbInvalidKey
    case tmdbUnreachable
    case cancelled
    case internalError(String)

    private static func regions(_ r: [Int]) -> String {
        r.isEmpty ? "–" : r.map(String.init).joined(separator: ", ")
    }

    public var errorDescription: String? {
        switch self {
        case .noDisc:
            return String(localized: "Er zit geen disc in de drive.")
        case .notDVDVideo:
            return String(localized: "Dit is geen DVD-Video-disc (geen geldige VIDEO_TS gevonden).")
        case let .regionMismatch(drive, disc):
            return String(localized: "Regiocode past niet: de drive staat op regio \(Self.regions(drive)), de disc is voor regio \(Self.regions(disc)). Zet de drive eenmalig op regio 2 (bijvoorbeeld met een regio-instelprogramma op een pc).")
        case let .driveRegionNotSet(disc):
            return String(localized: "De drive heeft nog geen regiocode en weigert versleutelde discs (disc: regio \(Self.regions(disc))). Zet de drive eenmalig op regio 2 op een pc.")
        case let .readRefused(regionHint):
            return regionHint
                ? String(localized: "De drive weigert de disc te lezen. Dat gebeurt bij een RPC-2-drive met een andere regiocode: zet de drive op regio 2 op een pc.")
                : String(localized: "De drive weigert de disc te lezen. Controleer of de disc schoon en onbeschadigd is.")
        case let .cssKeyMissing(title):
            return String(localized: "De CSS-sleutel voor titel \(title) werd niet gevonden; deze titel wordt overgeslagen.")
        case .tooManyReadErrors:
            return String(localized: "Te veel onleesbare sectoren na elkaar. De disc is waarschijnlijk beschadigd.")
        case .deviceGone:
            return String(localized: "De drive werd losgekoppeld of de disc werd uitgeworpen tijdens het rippen.")
        case let .insufficientSpace(location, needed, available):
            let n = ByteCountFormatter.string(fromByteCount: needed, countStyle: .file)
            let a = ByteCountFormatter.string(fromByteCount: available, countStyle: .file)
            return String(localized: "Niet genoeg vrije ruimte op \(location): nodig ongeveer \(n), beschikbaar \(a).")
        case .folderNotChosen:
            return String(localized: "Kies eerst een lokale map in Instellingen › Opslag.")
        case let .folderUnavailable(path):
            return String(localized: "De lokale map is niet beschikbaar: \(path). Is de schijf aangesloten? Kies de map anders opnieuw in Instellingen.")
        case let .folderNotWritable(path):
            return String(localized: "DiscRip mag niet schrijven in \(path). Kies de map opnieuw in Instellingen of geef toegang in Systeeminstellingen › Privacy en beveiliging.")
        case .nasNotConfigured:
            return String(localized: "De NAS is nog niet ingesteld (host en share in Instellingen › NAS).")
        case let .nasUnreachable(host):
            return String(localized: "De NAS (\(host)) is niet bereikbaar. Controleer het netwerk en of SMB aan staat.")
        case let .nasAuthFailed(host):
            return String(localized: "Aanmelden op de NAS (\(host)) is mislukt. Controleer gebruikersnaam en wachtwoord.")
        case let .nasMountFailed(code):
            return String(localized: "De NAS-share kon niet gekoppeld worden (fout \(code)).")
        case let .uploadVerifyFailed(local, remote):
            return String(localized: "Controle na upload mislukt: lokaal \(local) bytes, op de NAS \(remote) bytes. De lokale kopie blijft bewaard.")
        case .plexNotConfigured:
            return String(localized: "Plex is nog niet ingesteld (server-URL, token en bibliotheek).")
        case .plexTokenInvalid:
            return String(localized: "Het Plex-token is ongeldig of verlopen.")
        case let .plexUnreachable(url):
            return String(localized: "De Plex-server (\(url)) is niet bereikbaar.")
        case .plexNotFound:
            return String(localized: "Plex heeft de film nog niet opgenomen na het scannen. Controleer of de bibliotheekmap overeenkomt met de NAS-map.")
        case .encoderUnavailable:
            return String(localized: "Er is geen hardware-videoencoder beschikbaar op deze Mac.")
        case let .encoderFailed(removed):
            return removed
                ? String(localized: "De hardware-encoder faalde tijdens het rippen. Het onvolledige bestand werd verwijderd.")
                : String(localized: "De hardware-encoder faalde tijdens het rippen. Er bleef een onvolledig bestand achter.")
        case let .output(message, removed):
            return removed
                ? String(localized: "Schrijven mislukt: \(message). Het onvolledige bestand werd verwijderd.")
                : String(localized: "Schrijven mislukt: \(message).")
        case .tmdbNoKey:
            return String(localized: "Er is geen TMDB-API-sleutel ingesteld.")
        case .tmdbInvalidKey:
            return String(localized: "De TMDB-API-sleutel werd geweigerd.")
        case .tmdbUnreachable:
            return String(localized: "TMDB is niet bereikbaar.")
        case .cancelled:
            return String(localized: "Geannuleerd.")
        case let .internalError(message):
            return String(localized: "Onverwachte fout: \(message)")
        }
    }
}
