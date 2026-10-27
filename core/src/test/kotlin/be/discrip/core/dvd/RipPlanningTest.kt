package be.discrip.core.dvd

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RipPlanningTest {

    private val video = DvdVideo(2, "PAL", "16:9", 720, 576, letterboxed = false, film = false)

    private fun title(number: Int, durationMs: Long, chapters: Int = 1, audio: List<DvdAudioStream> = emptyList()) =
        DvdTitle(number, 1, 1, chapters, chapters, 1000, durationMs, video, audio, emptyList(), emptyList())

    private fun audio(index: Int, language: String?, codec: AudioCodec = AudioCodec.AC3, purpose: AudioPurpose = AudioPurpose.NORMAL) =
        DvdAudioStream(index, 0x80 + index, codec, language, 6, 48000, 0, purpose)

    @Test
    fun `the longest title is the main feature`() {
        val titles = listOf(title(1, 30_000), title(2, 6_000_000), title(3, 120_000))

        assertEquals(2, RipPlanning.mainTitle(titles)?.number)
    }

    @Test
    fun `equal lengths go to more chapters, then the lower number`() {
        assertEquals(3, RipPlanning.mainTitle(listOf(title(2, 1000, chapters = 4), title(3, 1000, chapters = 9)))?.number)
        assertEquals(2, RipPlanning.mainTitle(listOf(title(3, 1000, chapters = 4), title(2, 1000, chapters = 4)))?.number)
        assertNull(RipPlanning.mainTitle(emptyList()))
    }

    @Test
    fun `Dutch and English go first, others keep the disc order`() {
        val t = title(1, 1000, audio = listOf(audio(0, "fr"), audio(1, "en"), audio(2, "de"), audio(3, "nl")))

        val tracks = RipPlanning.audioTracks(t)

        assertEquals(listOf("dut", "eng", "fre", "ger"), tracks.map { it.language })
        assertEquals(listOf(true, false, false, false), tracks.map { it.isDefault })
    }

    @Test
    fun `commentary comes after the main track of its language and is never the default`() {
        val t = title(
            1, 1000,
            audio = listOf(
                audio(0, "en", purpose = AudioPurpose.COMMENTARY),
                audio(1, "en"),
                audio(2, "en", purpose = AudioPurpose.VISUALLY_IMPAIRED),
            ),
        )

        val tracks = RipPlanning.audioTracks(t)

        assertEquals(listOf(0x81, 0x80, 0x82), tracks.map { it.streamId })
        assertEquals(listOf(true, false, false), tracks.map { it.isDefault })
    }

    @Test
    fun `tracks without a language stay in, as undetermined`() {
        val tracks = RipPlanning.audioTracks(title(1, 1000, audio = listOf(audio(0, null), audio(1, "en"))))

        assertEquals(listOf("eng", null), tracks.map { it.language })
    }

    @Test
    fun `streams of an unknown format are left out`() {
        val tracks = RipPlanning.audioTracks(title(1, 1000, audio = listOf(audio(0, "en", AudioCodec.UNKNOWN), audio(1, "en", AudioCodec.DTS))))

        assertEquals(listOf(0x81), tracks.map { it.streamId })
        assertEquals(true, tracks.single().isDefault)
    }

    @Test
    fun `with only commentary the first track is still the default`() {
        val tracks = RipPlanning.audioTracks(title(1, 1000, audio = listOf(audio(0, "en", purpose = AudioPurpose.COMMENTARY))))

        assertEquals(listOf(true), tracks.map { it.isDefault })
    }
}
