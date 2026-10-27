package be.discrip.core.dvd

/**
 * What a title's cell list says about it. Discs protected with decoy titles (Disney's "X-Project",
 * Sony's ARccOS and others) carry dozens of titles as long as the film whose cells play in the
 * wrong order, repeat, or point at more data than could play in their time. The real film plays
 * its cells front to back.
 */
data class TitleAssessment(
    val title: DvdTitle,
    /** Cells that start before the cell played just before them. */
    val backwardJumps: Int,
    /** Cells played more than once. */
    val repeatedCells: Int,
    /** Cells holding more data than DVD-Video can play in their time (over 10.08 Mbit/s). */
    val overfullCells: Int,
    /** Cells shorter than a second. */
    val tinyCells: Int,
) {
    val anomalies: Int get() = backwardJumps + repeatedCells + overfullCells

    /** True when the cells show the signs of a decoy title. */
    val looksScrambled: Boolean get() = anomalies > 0
}

/** Picks the main feature of a disc. */
object MainFeature {

    /** Highest multiplex rate of DVD-Video, with room for rounding in the IFO times. */
    private const val MAX_BITS_PER_SECOND = 10_080_000 * 1.1
    private const val TINY_CELL_MS = 1_000L

    /** Titles whose score is this close to the best one count as equally good. */
    private const val TIE_SHARE = 0.99

    fun assess(title: DvdTitle): TitleAssessment {
        var backward = 0
        var overfull = 0
        var tiny = 0
        val seen = HashSet<Pair<Long, Long>>()
        var repeated = 0
        var previous: DvdCell? = null
        for (cell in title.playback) {
            if (previous != null && cell.first < previous.first) backward++
            if (!seen.add(cell.first to cell.last)) repeated++
            if (cell.durationMs < TINY_CELL_MS) {
                tiny++
            } else if (cell.sectors * DvdTitle.SECTOR_SIZE * 8.0 * 1000 / cell.durationMs > MAX_BITS_PER_SECOND) {
                overfull++
            }
            previous = cell
        }
        return TitleAssessment(title, backward, repeated, overfull, tiny)
    }

    /**
     * The main feature: the longest title, discounted by how many signs of a decoy it shows
     * (duration / (1 + anomalies)). Among titles that score within 1 % of the best, the one with
     * the fewest anomalies wins, then the fewest tiny cells, the longest, the most chapters and
     * the lowest number.
     */
    fun choose(titles: List<DvdTitle>): DvdTitle? {
        val assessed = titles.map(::assess)
        val best = assessed.maxOfOrNull(::score) ?: return null
        return assessed
            .filter { score(it) >= best * TIE_SHARE }
            .sortedWith(
                compareBy<TitleAssessment> { it.anomalies }
                    .thenBy { it.tinyCells }
                    .thenByDescending { it.title.durationMs }
                    .thenByDescending { it.title.chapters }
                    .thenBy { it.title.number },
            )
            .first()
            .title
    }

    private fun score(assessment: TitleAssessment): Double = assessment.title.durationMs.toDouble() / (1 + assessment.anomalies)

    /** Titles shorter than this are menus, logos, trailers and the like. */
    const val SHORT_TITLE_MS = 2 * 60 * 1000L

    /**
     * The titles worth listing: the [chosen] one and every title that is neither short nor a
     * decoy. A disc with 99 decoys then shows the film and its real extras.
     */
    fun listed(titles: List<DvdTitle>, chosen: Int?): List<DvdTitle> =
        titles.filter { it.number == chosen || (it.durationMs >= SHORT_TITLE_MS && !assess(it).looksScrambled) }
}
