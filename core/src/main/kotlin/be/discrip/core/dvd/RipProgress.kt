package be.discrip.core.dvd

/** Where a running rip stands, with speed and time left derived from recent progress. */
data class RipProgress(
    val sectorsDone: Long,
    val sectorsTotal: Long,
    val outputBytes: Long,
    /** How far into the title the written video has come. */
    val positionMs: Long,
    val elapsedMs: Long,
    /** Disc data read per second over the last seconds; null until it can be measured. */
    val readBytesPerSecond: Double?,
    /** Estimated time left; null until the speed is known. */
    val remainingMs: Long?,
) {
    val fraction: Float
        get() = if (sectorsTotal <= 0) 0f else (sectorsDone.toDouble() / sectorsTotal).coerceIn(0.0, 1.0).toFloat()
}

/**
 * Turns the engine's progress reports into [RipProgress]. Speed is measured over a sliding
 * window, so a slow patch on the disc shows up quickly and is forgotten again.
 */
class ProgressTracker(private val clock: () -> Long) {

    private val started = clock()
    private val samples = ArrayDeque<Pair<Long, Long>>()

    fun update(sectorsDone: Long, sectorsTotal: Long, outputBytes: Long, positionMs: Long): RipProgress {
        val now = clock()
        samples.addLast(now to sectorsDone)
        while (samples.size > 2 && now - samples[1].first >= WINDOW_MILLIS) samples.removeFirst()

        val (firstTime, firstSectors) = samples.first()
        val span = now - firstTime
        val rate = if (span >= MIN_SPAN_MILLIS && sectorsDone > firstSectors) {
            (sectorsDone - firstSectors) * DvdTitle.SECTOR_SIZE * 1000.0 / span
        } else {
            null
        }
        val remaining = rate?.let { ((sectorsTotal - sectorsDone).coerceAtLeast(0) * DvdTitle.SECTOR_SIZE * 1000.0 / it).toLong() }
        return RipProgress(
            sectorsDone = sectorsDone,
            sectorsTotal = sectorsTotal,
            outputBytes = outputBytes,
            positionMs = positionMs,
            elapsedMs = now - started,
            readBytesPerSecond = rate,
            remainingMs = remaining,
        )
    }

    private companion object {
        const val WINDOW_MILLIS = 8_000L
        const val MIN_SPAN_MILLIS = 1_500L
    }
}
