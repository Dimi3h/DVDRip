package be.discrip.core.estimate

import be.discrip.core.model.QualityPreset
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SizeEstimatorTest {

    private val twoHours = 7_200.0

    @Test
    fun `size follows duration and bitrates`() {
        val bytes = SizeEstimator.estimateBytes(
            durationSeconds = twoHours,
            videoKbps = 2_500,
            audio = listOf(AudioStreamEstimate(448)),
            subtitleStreams = 0,
            lpcmToFlac = false,
        )

        // (2500 + 448) kbit/s * 7200 s / 8 * 1.01
        assertEquals(2_679_732_000, bytes)
    }

    @Test
    fun `subtitles add a little`() {
        val without = SizeEstimator.estimateBytes(twoHours, 2_500, emptyList(), 0, false)
        val with = SizeEstimator.estimateBytes(twoHours, 2_500, emptyList(), 2, false)

        assertEquals(18_180_000, with - without)
    }

    @Test
    fun `flac shrinks only lpcm tracks`() {
        val audio = listOf(AudioStreamEstimate(1_536, isLpcm = true), AudioStreamEstimate(448))
        val copy = SizeEstimator.estimateBytes(twoHours, 0, audio, 0, lpcmToFlac = false)
        val flac = SizeEstimator.estimateBytes(twoHours, 0, audio, 0, lpcmToFlac = true)

        val expectedSaving = 1_536 * 1000.0 / 8 * twoHours * 1.01 * 0.4
        assertTrue(abs((copy - flac) - expectedSaving) <= 1.0, "saved ${copy - flac}, expected $expectedSaving")
    }

    @Test
    fun `zero duration estimates nothing`() {
        assertEquals(0, SizeEstimator.estimateBytes(0.0, 2_500, emptyList(), 0, false))
    }

    @Test
    fun `reference estimates match the preset table`() {
        val gb = 1e9
        val lossless = SizeEstimator.referenceBytes(QualityPreset.LOSSLESS, null) / gb
        val balanced = SizeEstimator.referenceBytes(QualityPreset.BALANCED, null) / gb
        val smallest = SizeEstimator.referenceBytes(QualityPreset.MAX_COMPRESSION, null) / gb
        val compatible = SizeEstimator.referenceBytes(QualityPreset.COMPATIBLE, null) / gb

        assertTrue(lossless in 4.0..8.0, "lossless $lossless GB")
        assertTrue(balanced in 2.0..3.0, "balanced $balanced GB")
        assertTrue(smallest in 1.0..1.6, "max compression $smallest GB")
        assertTrue(compatible in 2.8..3.3, "compatible $compatible GB")
    }

    @Test
    fun `lossy presets use the configured bitrate, clamped`() {
        assertEquals(1_800, SizeEstimator.videoKbps(QualityPreset.BALANCED, 1_800, null))
        assertEquals(2_500, SizeEstimator.videoKbps(QualityPreset.BALANCED, null, null))
        assertEquals(QualityPreset.MAX_VIDEO_BITRATE_KBPS, SizeEstimator.videoKbps(QualityPreset.BALANCED, 99_000, null))
    }

    @Test
    fun `lossless uses the measured source bitrate`() {
        assertEquals(6_100, SizeEstimator.videoKbps(QualityPreset.LOSSLESS, 1_000, 6_100))
        assertEquals(SizeEstimator.TYPICAL_DVD_VIDEO_KBPS, SizeEstimator.videoKbps(QualityPreset.LOSSLESS, null, null))
    }

    @Test
    fun `source video bitrate subtracts audio and subtitles`() {
        // 5.0 GB over two hours is about 5.56 Mbit/s in total.
        val kbps = SizeEstimator.sourceVideoKbps(5_000_000_000, twoHours, listOf(448, 192), subtitleStreams = 3)

        val total = 5_000_000_000 * 8.0 / 1000.0 / twoHours / SizeEstimator.PROGRAM_STREAM_OVERHEAD
        assertEquals((total - 448 - 192 - 30).toInt(), kbps)
        assertNull(SizeEstimator.sourceVideoKbps(1_000, twoHours, listOf(448), 0))
    }

    @Test
    fun `free space check keeps a margin`() {
        assertEquals(1_100_000_000, SizeEstimator.requiredFreeBytes(1_000_000_000))
    }
}
