package be.discrip.core.nas

/** How far an upload to the NAS has come, with speed and time left from recent progress. */
data class TransferProgress(
    val bytesDone: Long,
    val bytesTotal: Long,
    /** Bytes per second over the last seconds; null until it can be measured. */
    val bytesPerSecond: Double?,
    /** Estimated time left; null until the speed is known. */
    val remainingMs: Long?,
) {
    val fraction: Float
        get() = if (bytesTotal <= 0) 0f else (bytesDone.toDouble() / bytesTotal).coerceIn(0.0, 1.0).toFloat()
}

/** Turns byte counts into [TransferProgress], measuring speed over a sliding window. */
class TransferTracker(private val clock: () -> Long) {

    private val samples = ArrayDeque<Pair<Long, Long>>()

    fun update(bytesDone: Long, bytesTotal: Long): TransferProgress {
        val now = clock()
        samples.addLast(now to bytesDone)
        while (samples.size > 2 && now - samples[1].first >= WINDOW_MILLIS) samples.removeFirst()

        val (firstTime, firstBytes) = samples.first()
        val span = now - firstTime
        val rate = if (span >= MIN_SPAN_MILLIS && bytesDone > firstBytes) (bytesDone - firstBytes) * 1000.0 / span else null
        val remaining = rate?.let { ((bytesTotal - bytesDone).coerceAtLeast(0) * 1000.0 / it).toLong() }
        return TransferProgress(bytesDone, bytesTotal, rate, remaining)
    }

    private companion object {
        const val WINDOW_MILLIS = 8_000L
        const val MIN_SPAN_MILLIS = 1_500L
    }
}
