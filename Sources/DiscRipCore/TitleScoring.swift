import Foundation

/// Main-feature detection.
public enum TitleScoring {
    public struct Candidate: Identifiable, Equatable, Sendable {
        public var title: TitleInfo
        public var score: Double
        /// Near-duplicates by duration and chapter count (structure-protected discs).
        public var duplicates: Int
        /// Index of an earlier title that plays exactly the same cells (menu paths to the same video).
        public var duplicateOf: Int?
        /// Titles this one plays back to back ("play all"), in playback order.
        public var playsAll: [Int]
        public var plausible: Bool
        public var id: Int { title.index }
    }

    public static let featureMinimum: TimeInterval = 60 * 60

    /// Titles whose duration is within 1 % (or 2 s) and whose chapter count matches.
    static func isNearDuplicate(_ a: TitleInfo, _ b: TitleInfo) -> Bool {
        let tolerance = max(2.0, max(a.duration, b.duration) * 0.01)
        return abs(a.duration - b.duration) <= tolerance && a.chapters == b.chapters
    }

    /// Cells as "vts:first-last" so that titles from different title sets never match.
    static func cellKeys(_ t: TitleInfo) -> Set<String>? {
        guard let ranges = t.cellRanges, !ranges.isEmpty else { return nil }
        return Set(ranges.compactMap { $0.count == 2 ? "\(t.vts):\($0[0])-\($0[1])" : nil })
    }

    /// Lowest title index among titles that play exactly the same cells.
    static func exactDuplicates(_ titles: [TitleInfo]) -> [Int: Int] {
        var firstByCells: [Set<String>: Int] = [:]
        var result: [Int: Int] = [:]
        for t in titles.sorted(by: { $0.index < $1.index }) {
            guard let keys = cellKeys(t) else { continue }
            if let first = firstByCells[keys] {
                result[t.index] = first
            } else {
                firstByCells[keys] = t.index
            }
        }
        return result
    }

    static func sectorCount(_ ranges: [[Int64]]?) -> Int64 {
        (ranges ?? []).reduce(0) { $0 + ($1.count == 2 ? $1[1] - $1[0] + 1 : 0) }
    }

    /// Titles whose cells are the union of two or more other distinct titles ("play all"). The
    /// parts may share small cells (episodes often end with the same short outro), up to 5 % of
    /// the whole.
    static func playAll(_ titles: [TitleInfo], duplicates: [Int: Int]) -> [Int: [Int]] {
        let distinct = titles.filter { duplicates[$0.index] == nil }
        var result: [Int: [Int]] = [:]
        for t in distinct {
            guard let whole = cellKeys(t) else { continue }
            let parts = distinct.filter { p in
                guard p.index != t.index, p.vts == t.vts, let k = cellKeys(p) else { return false }
                return k.isSubset(of: whole) && k.count < whole.count
            }
            guard parts.count >= 2 else { continue }
            var covered = Set<String>()
            var partSectors: Int64 = 0
            for p in parts {
                covered.formUnion(cellKeys(p)!)
                partSectors += sectorCount(p.cellRanges)
            }
            let wholeSectors = sectorCount(t.cellRanges)
            // Together the parts cover every cell, with at most a small shared overlap.
            guard covered == whole, wholeSectors > 0, partSectors - wholeSectors <= wholeSectors / 20 else { continue }
            // Playback order: by the first sector of each part.
            let ordered = parts.sorted { ($0.cellRanges?.first?.first ?? 0) < ($1.cellRanges?.first?.first ?? 0) }
            result[t.index] = ordered.map(\.index)
        }
        return result
    }

    /// All titles ranked best first. The winner is the first element.
    public static func rank(_ titles: [TitleInfo]) -> [Candidate] {
        let usable = titles.filter { $0.durationMs > 0 }
        let exact = exactDuplicates(usable)
        let playAllMap = playAll(usable, duplicates: exact)
        let partOfPlayAll = Set(playAllMap.values.flatMap { $0 })
        let hasFeature = usable.contains { !$0.cssKeyMissing && $0.duration >= featureMinimum && exact[$0.index] == nil }

        // A real programme next to a "play all" of bonus material: anything distinct that is not a
        // play-all itself, not one of its parts, and at least half as long as the longest play-all.
        let longestPlayAll = usable.filter { playAllMap[$0.index] != nil }.map(\.duration).max() ?? 0
        let hasDistinctProgramme = longestPlayAll > 0 && usable.contains {
            exact[$0.index] == nil && playAllMap[$0.index] == nil && !partOfPlayAll.contains($0.index)
                && !$0.cssKeyMissing && $0.duration >= longestPlayAll * 0.5
        }

        let candidates = usable.map { t -> Candidate in
            let dups = usable.filter { $0.index != t.index && exact[$0.index] == nil && isNearDuplicate($0, t) }.count
            let minutes = t.duration / 60
            var score = minutes
            score += Double(min(t.chapters, 50)) * 0.4
            score += Double(min(t.audio.count, 8)) * 1.5
            score += Double(min(t.subtitles.count, 16)) * 0.2
            // Discs that list dozens of almost identical titles (structure protection, "play all"
            // copies): every extra copy costs points, so a unique title of similar length wins.
            if dups >= 2 { score -= Double(dups) * 3 }
            // A play-all of bonus episodes loses from a real programme on the same disc; on a
            // disc that only holds episodes it still beats the single episodes.
            if playAllMap[t.index] != nil && hasDistinctProgramme { score -= 15 }
            if t.cssKeyMissing { score -= 10_000 }
            var plausible = !t.cssKeyMissing && (!hasFeature || t.duration >= featureMinimum)
            if exact[t.index] != nil {
                plausible = false
                score -= 5_000
            }
            if !plausible && exact[t.index] == nil { score -= 1_000 }
            return Candidate(title: t, score: score, duplicates: dups, duplicateOf: exact[t.index],
                             playsAll: playAllMap[t.index] ?? [], plausible: plausible)
        }
        return candidates.sorted { a, b in
            a.score != b.score ? a.score > b.score : a.title.index < b.title.index
        }
    }

    public static func mainFeature(_ titles: [TitleInfo]) -> TitleInfo? { rank(titles).first?.title }

    /// "8–15" or "3, 5, 9" for the UI.
    public static func rangeLabel(_ indices: [Int]) -> String {
        let s = indices.sorted()
        guard let f = s.first, let l = s.last else { return "" }
        if s.count >= 2 && l - f == s.count - 1 { return "\(f)–\(l)" }
        return s.map(String.init).joined(separator: ", ")
    }
}
