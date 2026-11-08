import Foundation

/// Main-feature detection.
public enum TitleScoring {
    public struct Candidate: Identifiable, Equatable, Sendable {
        public var title: TitleInfo
        public var score: Double
        public var duplicates: Int
        public var plausible: Bool
        public var id: Int { title.index }
    }

    public static let featureMinimum: TimeInterval = 60 * 60

    /// Titles whose duration is within 1 % (or 2 s) and whose chapter count matches.
    static func isNearDuplicate(_ a: TitleInfo, _ b: TitleInfo) -> Bool {
        let tolerance = max(2.0, max(a.duration, b.duration) * 0.01)
        return abs(a.duration - b.duration) <= tolerance && a.chapters == b.chapters
    }

    /// All titles ranked best first. The winner is the first element.
    public static func rank(_ titles: [TitleInfo]) -> [Candidate] {
        let usable = titles.filter { $0.durationMs > 0 }
        let hasFeature = usable.contains { !$0.cssKeyMissing && $0.duration >= featureMinimum }
        let candidates = usable.map { t -> Candidate in
            let dups = usable.filter { $0.index != t.index && isNearDuplicate($0, t) }.count
            let minutes = t.duration / 60
            var score = minutes
            score += Double(min(t.chapters, 50)) * 0.4
            score += Double(min(t.audio.count, 8)) * 1.5
            score += Double(min(t.subtitles.count, 16)) * 0.2
            // Discs that list dozens of almost identical titles (structure protection, "play all"
            // copies): every extra copy costs points, so a unique title of similar length wins.
            if dups >= 2 { score -= Double(dups) * 3 }
            if t.cssKeyMissing { score -= 10_000 }
            let plausible = !t.cssKeyMissing && (!hasFeature || t.duration >= featureMinimum)
            if !plausible { score -= 1_000 }
            return Candidate(title: t, score: score, duplicates: dups, plausible: plausible)
        }
        return candidates.sorted { a, b in
            a.score != b.score ? a.score > b.score : a.title.index < b.title.index
        }
    }

    public static func mainFeature(_ titles: [TitleInfo]) -> TitleInfo? { rank(titles).first?.title }
}
