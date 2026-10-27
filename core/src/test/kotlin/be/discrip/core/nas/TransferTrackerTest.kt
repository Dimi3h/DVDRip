package be.discrip.core.nas

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TransferTrackerTest {

    @Test
    fun `speed and time left come from the last seconds`() {
        var now = 0L
        val tracker = TransferTracker { now }

        val first = tracker.update(0, 100_000_000)
        assertNull(first.bytesPerSecond)
        assertNull(first.remainingMs)

        now = 2_000
        val second = tracker.update(20_000_000, 100_000_000)
        assertEquals(10_000_000.0, second.bytesPerSecond)
        assertEquals(8_000L, second.remainingMs)
        assertEquals(0.2f, second.fraction)

        // A slow patch shows once the window has moved past the fast start.
        now = 12_000
        tracker.update(30_000_000, 100_000_000)
        now = 20_000
        val slow = tracker.update(34_000_000, 100_000_000)
        assertEquals(500_000.0, slow.bytesPerSecond)
        assertEquals(132_000L, slow.remainingMs)
    }
}
