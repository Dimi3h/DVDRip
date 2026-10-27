package be.discrip.core.dvd

import be.discrip.core.estimate.SizeEstimator
import be.discrip.core.model.QualityPreset
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RipEstimateTest {

    private val video = DvdVideo(2, "PAL", "16:9", 720, 576, letterboxed = false, film = true)

    private fun stream(index: Int, codec: AudioCodec, channels: Int, bits: Int = 0) =
        DvdAudioStream(index, 0x80 + index, codec, "en", channels, 48_000, bits, AudioPurpose.NORMAL)

    /** A two-hour title at 6 Mbit/s video with one AC-3 5.1 track and two subtitle streams. */
    private val film: DvdTitle = run {
        val seconds = 7200
        val audio = listOf(stream(0, AudioCodec.AC3, 6))
        val kbps = (6000 + 448 + 2 * SizeEstimator.VOBSUB_KBPS) * SizeEstimator.PROGRAM_STREAM_OVERHEAD
        val sectors = (kbps * 1000 / 8 * seconds / 2048).toLong()
        DvdTitle(
            1, 1, 1, 20, 20, sectors, seconds * 1000L, video, audio,
            listOf(DvdSubtitleStream(0, 0x20, "nl"), DvdSubtitleStream(1, 0x21, "en")), emptyList(),
        )
    }

    @Test
    fun `audio rates DVDs use`() {
        assertEquals(192, RipEstimate.audioKbps(stream(0, AudioCodec.AC3, 2)))
        assertEquals(448, RipEstimate.audioKbps(stream(0, AudioCodec.AC3, 6)))
        assertEquals(768, RipEstimate.audioKbps(stream(0, AudioCodec.DTS, 6)))
        assertEquals(1536, RipEstimate.audioKbps(stream(0, AudioCodec.LPCM, 2, bits = 16)))
        assertEquals(2304, RipEstimate.audioKbps(stream(0, AudioCodec.LPCM, 2, bits = 24)))
    }

    @Test
    fun `everything kept comes to about the size of the title on disc`() {
        val bytes = RipEstimate.losslessBytes(film, RipPlanning.audioTracks(film), RipPlanning.subtitleTracks(film), lpcmToFlac = false)
        val disc = film.sizeBytes / SizeEstimator.PROGRAM_STREAM_OVERHEAD * SizeEstimator.CONTAINER_OVERHEAD

        assertEquals(disc, bytes.toDouble(), disc * 0.01)
    }

    @Test
    fun `a lossy preset takes its own video bit rate with the same audio and subtitles`() {
        val audio = RipPlanning.audioTracks(film)
        val subtitles = RipPlanning.subtitleTracks(film)

        val balanced = RipEstimate.bytes(film, QualityPreset.BALANCED, null, audio, subtitles, lpcmToFlac = false)
        val configured = RipEstimate.bytes(film, QualityPreset.BALANCED, 4_000, audio, subtitles, lpcmToFlac = false)

        // (2 500 + 448 + 20) kbit/s for two hours: about 2.7 GB; 1 500 kbit/s more is 1.35 GB more.
        assertEquals(2_671_200_000.0 * SizeEstimator.CONTAINER_OVERHEAD, balanced.toDouble(), 1_000_000.0)
        assertEquals(1_350_000_000.0 * SizeEstimator.CONTAINER_OVERHEAD, (configured - balanced).toDouble(), 1_000_000.0)
        assertEquals(
            RipEstimate.losslessBytes(film, audio, subtitles, lpcmToFlac = false),
            RipEstimate.bytes(film, QualityPreset.LOSSLESS, 4_000, audio, subtitles, lpcmToFlac = false),
        )
    }

    @Test
    fun `leaving tracks out makes it smaller`() {
        val all = RipEstimate.losslessBytes(film, RipPlanning.audioTracks(film), RipPlanning.subtitleTracks(film), lpcmToFlac = false)
        val videoOnly = RipEstimate.losslessBytes(film, emptyList(), emptyList(), lpcmToFlac = false)

        // 448 + 20 kbit/s for two hours: about 421 MB.
        assertEquals(421_200_000.0 * SizeEstimator.CONTAINER_OVERHEAD, (all - videoOnly).toDouble(), 2_000_000.0)
        assertTrue(videoOnly < all)
    }
}
