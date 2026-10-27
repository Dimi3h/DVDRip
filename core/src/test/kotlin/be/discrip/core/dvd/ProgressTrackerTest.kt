package be.discrip.core.dvd

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressTrackerTest {

    private var now = 1_000_000L
    private val tracker = ProgressTracker { now }

    @Test
    fun `speed and time left are unknown at first`() {
        val progress = tracker.update(0, 10_000, 0, 0)

        assertNull(progress.readBytesPerSecond)
        assertNull(progress.remainingMs)
        assertEquals(0f, progress.fraction)
    }

    @Test
    fun `measures speed and estimates the rest`() {
        tracker.update(0, 10_000, 0, 0)
        now += 2_000
        // 1000 sectors (2 048 000 bytes) in two seconds.
        val progress = tracker.update(1_000, 10_000, 2_000_000, 40_000)

        assertEquals(1_024_000.0, assertNotNull(progress.readBytesPerSecond), 0.001)
        assertEquals(18_000, progress.remainingMs)
        assertEquals(2_000, progress.elapsedMs)
        assertEquals(0.1f, progress.fraction)
    }

    @Test
    fun `old samples drop out of the speed window`() {
        tracker.update(0, 100_000, 0, 0)
        // Fast for 10 seconds, then slow.
        repeat(10) {
            now += 1_000
            tracker.update((it + 1) * 1_000L, 100_000, 0, 0)
        }
        var progress = tracker.update(10_000, 100_000, 0, 0)
        repeat(10) {
            now += 1_000
            progress = tracker.update(10_000 + (it + 1) * 100L, 100_000, 0, 0)
        }

        // The window now only covers the slow part: about 100 sectors a second.
        assertEquals(100 * 2048.0, assertNotNull(progress.readBytesPerSecond), 100 * 2048.0 * 0.15)
    }

    @Test
    fun `a lossy rip shows how fast it encodes and whether it keeps up with the film`() {
        tracker.update(0, 10_000, 0, 0, 0)
        now += 4_000
        // 2 s of film at 25 frames a second in 4 s: half as fast as the film plays.
        val slow = tracker.update(500, 10_000, 1_000_000, 2_000, 50)

        assertEquals(12.5, assertNotNull(slow.framesPerSecond), 0.001)
        assertEquals(0.5, assertNotNull(slow.speed), 0.001)
        assertEquals(50, slow.framesEncoded)
        assertTrue(slow.slowerThanRealTime)

        now += 2_000
        val faster = tracker.update(1_500, 10_000, 3_000_000, 10_000, 250)
        assertFalse(faster.slowerThanRealTime)
    }

    @Test
    fun `a copy encodes nothing and has no frame rate`() {
        tracker.update(0, 10_000, 0, 0)
        now += 2_000
        val progress = tracker.update(1_000, 10_000, 2_000_000, 40_000)

        assertNull(progress.framesPerSecond)
        assertEquals(20.0, assertNotNull(progress.speed), 0.001)
    }

    @Test
    fun `fraction stays within 0 and 1`() {
        assertEquals(1f, tracker.update(12, 10, 0, 0).fraction)
        assertEquals(0f, tracker.update(5, 0, 0, 0).fraction)
    }
}
