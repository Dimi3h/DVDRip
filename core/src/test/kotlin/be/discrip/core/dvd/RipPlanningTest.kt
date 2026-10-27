package be.discrip.core.dvd

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RipPlanningTest {

    private val video = DvdVideo(2, "PAL", "16:9", 720, 576, letterboxed = false, film = false)

    private fun title(audio: List<DvdAudioStream> = emptyList(), subtitles: List<DvdSubtitleStream> = emptyList()) =
        DvdTitle(1, 1, 1, 1, 1, 1000, 1000, video, audio, subtitles, emptyList())

    private fun audio(index: Int, language: String?, codec: AudioCodec = AudioCodec.AC3, purpose: AudioPurpose = AudioPurpose.NORMAL) =
        DvdAudioStream(index, 0x80 + index, codec, language, 6, 48000, 0, purpose)

    private fun subtitle(index: Int, language: String?, purpose: SubtitlePurpose = SubtitlePurpose.NORMAL) =
        DvdSubtitleStream(index, 0x20 + index, language, purpose)

    @Test
    fun `Dutch and English audio go first, others keep the disc order`() {
        val t = title(audio = listOf(audio(0, "fr"), audio(1, "en"), audio(2, "de"), audio(3, "nl")))

        val tracks = RipPlanning.audioTracks(t)

        assertEquals(listOf("dut", "eng", "fre", "ger"), tracks.map { it.language })
        assertEquals(listOf(true, false, false, false), tracks.map { it.isDefault })
        assertEquals(listOf(TrackFlags.DEFAULT, 0, 0, 0), tracks.map { it.flags })
    }

    @Test
    fun `commentary comes after the main track of its language, is flagged and never the default`() {
        val t = title(
            audio = listOf(
                audio(0, "en", purpose = AudioPurpose.COMMENTARY),
                audio(1, "en"),
                audio(2, "en", purpose = AudioPurpose.VISUALLY_IMPAIRED),
            ),
        )

        val tracks = RipPlanning.audioTracks(t)

        assertEquals(listOf(0x81, 0x80, 0x82), tracks.map { it.streamId })
        assertEquals(listOf(true, false, false), tracks.map { it.isDefault })
        assertEquals(listOf(TrackFlags.DEFAULT, TrackFlags.COMMENTARY, TrackFlags.VISUAL_IMPAIRED), tracks.map { it.flags })
    }

    @Test
    fun `only selected audio goes in, with the chosen default when it is selected`() {
        val t = title(audio = listOf(audio(0, "en"), audio(1, "nl"), audio(2, "fr")))

        val chosen = RipPlanning.audioTracks(t, selected = setOf(0x80, 0x82), defaultStreamId = 0x82)
        assertEquals(listOf(0x80, 0x82), chosen.map { it.streamId })
        assertEquals(listOf(false, true), chosen.map { it.isDefault })

        // A default that is not selected falls back to the first main track.
        val fallback = RipPlanning.audioTracks(t, selected = setOf(0x80, 0x82), defaultStreamId = 0x81)
        assertEquals(listOf(true, false), fallback.map { it.isDefault })
    }

    @Test
    fun `tracks without a language stay in, as undetermined`() {
        val tracks = RipPlanning.audioTracks(title(audio = listOf(audio(0, null), audio(1, "en"))))

        assertEquals(listOf("eng", null), tracks.map { it.language })
    }

    @Test
    fun `audio of an unknown format is left out`() {
        val tracks = RipPlanning.audioTracks(title(audio = listOf(audio(0, "en", AudioCodec.UNKNOWN), audio(1, "en", AudioCodec.DTS))))

        assertEquals(listOf(0x81), tracks.map { it.streamId })
        assertEquals(true, tracks.single().isDefault)
    }

    @Test
    fun `with only commentary the first track is still the default`() {
        val tracks = RipPlanning.audioTracks(title(audio = listOf(audio(0, "en", purpose = AudioPurpose.COMMENTARY))))

        assertEquals(listOf(true), tracks.map { it.isDefault })
    }

    @Test
    fun `subtitles go Dutch and English first, plain before forced, captions and commentary`() {
        val t = title(
            subtitles = listOf(
                subtitle(0, "fr"),
                subtitle(1, "en", SubtitlePurpose.COMMENTARY),
                subtitle(2, "en", SubtitlePurpose.CAPTIONS),
                subtitle(3, "en"),
                subtitle(4, "nl", SubtitlePurpose.FORCED),
                subtitle(5, "nl"),
                subtitle(6, null),
            ),
        )

        val tracks = RipPlanning.subtitleTracks(t)

        assertEquals(listOf(0x25, 0x24, 0x23, 0x22, 0x21, 0x20, 0x26), tracks.map { it.streamId })
        assertEquals(listOf("dut", "dut", "eng", "eng", "eng", "fre", null), tracks.map { it.language })
        assertEquals(
            listOf(0, TrackFlags.FORCED, 0, TrackFlags.HEARING_IMPAIRED, TrackFlags.COMMENTARY, 0, 0),
            tracks.map { it.flags },
        )
    }

    @Test
    fun `only selected subtitles go in`() {
        val t = title(subtitles = listOf(subtitle(0, "en"), subtitle(1, "nl"), subtitle(2, "de")))

        assertEquals(listOf(0x21, 0x22), RipPlanning.subtitleTracks(t, selected = setOf(0x21, 0x22)).map { it.streamId })
        assertEquals(emptyList(), RipPlanning.subtitleTracks(t, selected = emptySet()))
    }
}
