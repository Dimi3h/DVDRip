import Foundation

public enum Languages {
    /// ISO 639-2/T codes whose bibliographic (/B) form differs; Matroska uses /B.
    private static let terminologyToBibliographic: [String: String] = [
        "sqi": "alb", "hye": "arm", "eus": "baq", "bod": "tib", "mya": "bur", "zho": "chi",
        "ces": "cze", "nld": "dut", "fra": "fre", "kat": "geo", "deu": "ger", "ell": "gre",
        "isl": "ice", "mkd": "mac", "mri": "mao", "msa": "may", "fas": "per", "ron": "rum",
        "slk": "slo", "cym": "wel",
    ]

    /// Common DVD languages, so the mapping never depends on the OS language database.
    private static let common: [String: String] = [
        "nl": "dut", "en": "eng", "fr": "fre", "de": "ger", "es": "spa", "it": "ita", "pt": "por",
        "sv": "swe", "da": "dan", "no": "nor", "nb": "nob", "fi": "fin", "pl": "pol", "cs": "cze",
        "hu": "hun", "el": "gre", "tr": "tur", "ru": "rus", "ja": "jpn", "zh": "chi", "ko": "kor",
        "he": "heb", "ar": "ara", "hi": "hin", "th": "tha", "is": "ice", "ro": "rum", "sk": "slo",
        "sl": "slv", "hr": "hrv", "sr": "srp", "bg": "bul", "uk": "ukr", "et": "est", "lv": "lav",
        "lt": "lit", "ca": "cat", "eu": "baq", "gl": "glg", "ga": "gle", "cy": "wel", "af": "afr",
        "id": "ind", "ms": "may", "vi": "vie", "fa": "per", "la": "lat",
    ]

    /// ISO 639-1 (from the IFO) to ISO 639-2/B for Matroska; "und" when unknown.
    public static func iso639_2B(_ code: String) -> String {
        let c = code.lowercased()
        guard c.count == 2 else { return c.count == 3 ? (terminologyToBibliographic[c] ?? c) : "und" }
        if let known = common[c] { return known }
        if let alpha3 = Locale.LanguageCode(c).identifier(.alpha3) {
            return terminologyToBibliographic[alpha3] ?? alpha3
        }
        return "und"
    }

    /// Display name in the UI language, e.g. "Nederlands" / "Engels".
    public static func displayName(_ code: String, locale: Locale = .current) -> String {
        guard !code.isEmpty else { return String(localized: "Onbekend") }
        let name = locale.localizedString(forLanguageCode: code) ?? code
        return name.prefix(1).uppercased() + name.dropFirst()
    }

    /// Orders tracks so that preferred languages (in order) come first; stable otherwise.
    public static func ordered<T>(_ items: [T], preferred: [String], language: (T) -> String) -> [T] {
        let prefs = preferred.map { $0.lowercased() }
        return items.enumerated().sorted { a, b in
            let ra = prefs.firstIndex(of: language(a.element).lowercased()) ?? prefs.count
            let rb = prefs.firstIndex(of: language(b.element).lowercased()) ?? prefs.count
            return ra != rb ? ra < rb : a.offset < b.offset
        }.map(\.element)
    }
}
