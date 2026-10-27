package be.discrip.core.history

import be.discrip.core.dvd.RipFailure
import be.discrip.core.model.StorageTarget
import be.discrip.core.nas.NasProblem
import be.discrip.core.plex.PlexResult
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class RipRecordTest {

    private val done = RipRecord(
        id = "a",
        endedAt = 1_800_000_000_000,
        title = "Testfilm",
        year = 2024,
        sourceName = "TESTFILM",
        titleNumber = 1,
        storage = StorageTarget.BOTH,
        bytes = 4_500_000_000,
        durationMs = 7_200_000,
        badSectors = 4,
        warnings = listOf("Audio stream 0x81 is not in this title; skipped"),
        localFile = "Films/Testfilm (2024) {tmdb-603}/Testfilm (2024) {tmdb-603}.mkv",
        nasProblem = NasProblem.UNREACHABLE,
        pendingUpload = PendingUpload(
            localDocument = "content://com.android.externalstorage.documents/tree/primary%3AMovies/document/x",
            nasPath = "Films/Testfilm (2024) {tmdb-603}/Testfilm (2024) {tmdb-603}.mkv",
            plexSectionKey = "1",
        ),
    )
    private val failed = RipRecord(
        id = "b",
        endedAt = 1_800_000_100_000,
        title = "Andere film",
        storage = StorageTarget.NAS,
        result = RipResult.FAILED,
        failure = RipFailure.UNREADABLE,
        message = "Input/output error",
        nasFile = null,
        plex = PlexResult.NOT_SEEN,
    )

    @Test
    fun `records survive a round trip`() {
        assertEquals(listOf(done, failed), RipHistoryJson.decode(RipHistoryJson.encode(listOf(done, failed))))
    }

    @Test
    fun `fields and values from a newer version do not lose the history`() {
        val text = """[{"id":"c","endedAt":1,"title":"X","storage":"CLOUD","result":"SUCCEEDED","plex":"QUEUED","rating":5}]"""

        val record = RipHistoryJson.decode(text).single()

        assertEquals(StorageTarget.LOCAL, record.storage)
        assertEquals(null, record.plex)
        assertEquals("X", record.title)
    }

    @Test
    fun `a damaged file is reported, not read as empty`() {
        assertThrows<SerializationException> { RipHistoryJson.decode("""[{"id":"c","endedAt":""") }
    }
}
