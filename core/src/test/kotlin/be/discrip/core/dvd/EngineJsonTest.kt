package be.discrip.core.dvd

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineJsonTest {

    // As discrip_host prints it for the test DVD of native/scripts/make-test-dvd.sh.
    private val titles = """
        {"titles":[{"number":1,"titleSet":1,"angles":1,"chapters":3,"cells":3,"sectors":2007,"durationMs":12000,
        "video":{"mpegVersion":2,"standard":"PAL","aspect":"16:9","width":720,"height":576,"letterboxed":false,"film":false},
        "audio":[{"index":0,"streamId":128,"codec":"ac3","language":"en","channels":2,"sampleRate":48000,"bitsPerSample":0,"extension":"unspecified"},
        {"index":1,"streamId":160,"codec":"lpcm","language":"nl","channels":2,"sampleRate":48000,"bitsPerSample":16,"extension":"commentary"}],
        "subtitles":[{"index":0,"streamId":32,"language":null,"extension":"normal"}],"chapterStartsMs":[0,3000,6000]}]}
    """.trimIndent()

    @Test
    fun `parses the title list`() {
        val title = EngineJson.titles(titles).single()

        assertEquals(1, title.number)
        assertEquals(2007L * 2048, title.sizeBytes)
        assertEquals(12_000, title.durationMs)
        assertEquals("16:9", title.video.aspect)
        assertEquals(listOf(AudioCodec.AC3, AudioCodec.LPCM), title.audio.map { it.codec })
        assertEquals(listOf(0x80, 0xA0), title.audio.map { it.streamId })
        assertEquals(AudioPurpose.COMMENTARY, title.audio[1].purpose)
        assertTrue(title.audio[1].isCommentary)
        assertNull(title.subtitles.single().language)
        assertEquals(listOf(0L, 3000L, 6000L), title.chapterStartsMs)
    }

    @Test
    fun `values the app does not know become unknown`() {
        val json = titles.replace("\"codec\":\"ac3\"", "\"codec\":\"truehd\"").replace("\"extension\":\"commentary\"", "\"extension\":\"karaoke\"")

        val audio = EngineJson.titles(json).single().audio

        assertEquals(AudioCodec.UNKNOWN, audio[0].codec)
        assertEquals(AudioPurpose.UNSPECIFIED, audio[1].purpose)
    }

    @Test
    fun `an engine error becomes an exception with its message`() {
        val error = assertThrows<DvdReadException> { EngineJson.titles("""{"error":"cannot read VIDEO_TS.IFO"}""") }

        assertEquals("cannot read VIDEO_TS.IFO", error.message)
    }

    @Test
    fun `garbage is reported, not crashed on`() {
        assertThrows<DvdReadException> { EngineJson.titles("not json") }
    }

    @Test
    fun `parses a successful rip report`() {
        val report = """{"ok":true,"outputBytes":4000464,"durationMs":12021,"chapterStartsMs":[0,3000,6011],
            "audioTracks":2,"badSectors":[[100,16],[300,2]],"warnings":["Audio stream 0x81 is not in this title; skipped"]}"""

        val outcome = assertIs<RipOutcome.Success>(RipOutcome.parse(report))

        assertEquals(4_000_464, outcome.outputBytes)
        assertEquals(listOf(0L, 3000L, 6011L), outcome.chapterStartsMs)
        assertEquals(listOf(BadSectors(100, 16), BadSectors(300, 2)), outcome.badSectors)
        assertEquals(18, outcome.badSectorCount)
        assertEquals(1, outcome.warnings.size)
        assertEquals(0, outcome.framesEncoded)
    }

    @Test
    fun `a lossy rip reports the frames it encoded`() {
        val report = """{"ok":true,"outputBytes":2256344,"durationMs":3002,"chapterStartsMs":[0],"audioTracks":1,
            "subtitleTracks":0,"badSectors":[],"warnings":[],"framesEncoded":75,"interlacedFrames":75}"""

        val outcome = assertIs<RipOutcome.Success>(RipOutcome.parse(report))

        assertEquals(75, outcome.framesEncoded)
        assertEquals(75, outcome.interlacedFrames)
    }

    @Test
    fun `maps every failure code of the engine`() {
        val codes = mapOf(
            "cancelled" to RipFailure.CANCELLED,
            "no_title" to RipFailure.NO_TITLE,
            "unreadable" to RipFailure.UNREADABLE,
            "scrambled" to RipFailure.SCRAMBLED,
            "no_video" to RipFailure.NO_VIDEO,
            "no_space" to RipFailure.NO_SPACE,
            "write_failed" to RipFailure.WRITE_FAILED,
            "encoder_unavailable" to RipFailure.ENCODER_UNAVAILABLE,
            "encoder_failed" to RipFailure.ENCODER_FAILED,
            "failed" to RipFailure.FAILED,
            "something_new" to RipFailure.FAILED,
        )
        for ((code, reason) in codes) {
            val outcome = assertIs<RipOutcome.Failure>(RipOutcome.parse("""{"ok":false,"error":"$code","message":"m"}"""))
            assertEquals(reason, outcome.reason, code)
            assertEquals("m", outcome.message)
        }
    }

    @Test
    fun `parses a probe of the finished file`() {
        val probe = """{"ok":true,"durationMs":12021,"streams":[{"type":"video","codec":"mpeg2video","language":null,"width":720,"height":576},
            {"type":"audio","codec":"ac3","language":"eng","channels":2,"sampleRate":48000},
            {"type":"subtitle","codec":"dvd_subtitle","language":"dut"}],"chapterStartsMs":[0,3000,6011],
            "tags":{"title":"Testfilm","DATE_RELEASED":"2024","TMDB":"movie/603"}}"""

        val summary = MkvSummary.parse(probe).getOrThrow()

        assertEquals("dut", summary.subtitles.single().language)
        assertEquals(mapOf("title" to "Testfilm", "DATE_RELEASED" to "2024", "TMDB" to "movie/603"), summary.tags)

        assertEquals(720, summary.video.single().width)
        assertEquals("eng", summary.audio.single().language)
        assertEquals(3, summary.chapterStartsMs.size)
        assertTrue(MkvSummary.parse("""{"ok":false,"message":"Invalid data found when processing input"}""").isFailure)
    }
}
