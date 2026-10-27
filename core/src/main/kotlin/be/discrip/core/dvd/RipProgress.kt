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
    /** Video frames a lossy preset has encoded so far; 0 while the video is copied. */
    val framesEncoded: Long = 0,
    /** Frames encoded per second over the last seconds; null when nothing is encoded or it cannot be told yet. */
    val framesPerSecond: Double? = null,
    /**
     * Film ripped per second of ripping over the last seconds: 2.0 rips an hour of film in half an
     * hour, below 1.0 the rip takes longer than the film plays. Null until it can be measured.
     */
    val speed: Double? = null,
) {
    val fraction: Float
        get() = if (sectorsTotal <= 0) 0f else (sectorsDone.toDouble() / sectorsTotal).coerceIn(0.0, 1.0).toFloat()

    /** The rip runs slower than the film plays, as a lossy preset can on a slow device. */
    val slowerThanRealTime: Boolean get() = speed != null && speed < 1.0
}

/**
 * Turns the engine's progress reports into [RipProgress]. Speed is measured over a sliding
 * window, so a slow patch on the disc shows up quickly and is forgotten again.
 */
class ProgressTracker(private val clock: () -> Long) {

    private class Sample(val time: Long, val sectors: Long, val positionMs: Long, val frames: Long)

    private val started = clock()
    private val samples = ArrayDeque<Sample>()

    fun update(sectorsDone: Long, sectorsTotal: Long, outputBytes: Long, positionMs: Long, framesEncoded: Long = 0): RipProgress {
        val now = clock()
        samples.addLast(Sample(now, sectorsDone, positionMs, framesEncoded))
        while (samples.size > 2 && now - samples[1].time >= WINDOW_MILLIS) samples.removeFirst()

        val first = samples.first()
        val span = now - first.time
        val measurable = span >= MIN_SPAN_MILLIS
        val rate = if (measurable && sectorsDone > first.sectors) {
            (sectorsDone - first.sectors) * DvdTitle.SECTOR_SIZE * 1000.0 / span
        } else {
            null
        }
        val remaining = rate?.let { ((sectorsTotal - sectorsDone).coerceAtLeast(0) * DvdTitle.SECTOR_SIZE * 1000.0 / it).toLong() }
        val fps = if (measurable && framesEncoded > first.frames) (framesEncoded - first.frames) * 1000.0 / span else null
        val speed = if (measurable && positionMs > first.positionMs) (positionMs - first.positionMs).toDouble() / span else null
        return RipProgress(
            sectorsDone = sectorsDone,
            sectorsTotal = sectorsTotal,
            outputBytes = outputBytes,
            positionMs = positionMs,
            elapsedMs = now - started,
            readBytesPerSecond = rate,
            remainingMs = remaining,
            framesEncoded = framesEncoded,
            framesPerSecond = fps,
            speed = speed,
        )
    }

    private companion object {
        const val WINDOW_MILLIS = 8_000L
        const val MIN_SPAN_MILLIS = 1_500L
    }
}
