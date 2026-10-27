package be.discrip.core.plex

import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlexPathsTest {

    private val film = listOf("Films", "Testfilm (2024) {tmdb-603}")

    @Test
    fun `a Synology library maps the share onto its volume`() {
        assertEquals(
            "/volume1/video/Films/Testfilm (2024) {tmdb-603}",
            PlexPaths.serverFolder(listOf("/volume1/video/Films"), "video", film),
        )
    }

    @Test
    fun `a library on the share's root still gets the folders below it`() {
        assertEquals(
            "/volume1/video/Films/Testfilm (2024) {tmdb-603}",
            PlexPaths.serverFolder(listOf("/volume2/other", "/volume1/video/"), "video", film),
        )
    }

    @Test
    fun `a Windows server keeps its separators and letters`() {
        assertEquals("D:\\Films\\Testfilm (2024) {tmdb-603}", PlexPaths.serverFolder(listOf("D:\\Films"), "video", film))
        assertEquals(
            "\\\\nas\\video\\Films\\Testfilm (2024) {tmdb-603}",
            PlexPaths.serverFolder(listOf("\\\\nas\\video\\Films"), "video", film),
        )
    }

    @Test
    fun `names match regardless of case and the longest match wins`() {
        assertEquals(
            "/volume1/Video/films/Testfilm (2024) {tmdb-603}",
            PlexPaths.serverFolder(listOf("/srv/films", "/volume1/Video/films"), "video", film),
        )
    }

    @Test
    fun `a library that does not hold the folder has no path`() {
        assertNull(PlexPaths.serverFolder(listOf("/volume1/video/Series"), "video", film))
        assertNull(PlexPaths.serverFolder(emptyList(), "video", film))
    }
}

class PlexPublisherTest {

    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val publisher = PlexPublisher(PlexClient(OkHttpClient()), pollMillis = 1_000, waitMillis = 10_000)
    private val fileName = "Testfilm (2024) {tmdb-603}.mkv"
    private val folder = listOf("Films", "Testfilm (2024) {tmdb-603}")

    /** Recently added answers in turn; the last one repeats. */
    private var recent = listOf(items())

    @BeforeEach
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.url.encodedPath
                return when {
                    path == "/library/sections" -> MockResponse(
                        body = """{"MediaContainer":{"Directory":[
                            {"key":"1","type":"movie","title":"Films","Location":[{"path":"/volume1/video/Films"}]}]}}""",
                    )
                    path.endsWith("/refresh") -> MockResponse()
                    path == "/library/sections/1/all" -> {
                        val answer = recent.first()
                        if (recent.size > 1) recent = recent.drop(1)
                        MockResponse(body = answer)
                    }
                    else -> MockResponse(code = 404)
                }
            }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() = server.close()

    private val url get() = server.url("/").toString()

    private fun items(vararg files: String) = """{"MediaContainer":{"Metadata":[${
        files.joinToString(",") { """{"ratingKey":"7","title":"Testfilm","year":2024,"Media":[{"Part":[{"file":"$it"}]}]}""" }
    }]}}"""

    private fun refreshes() = requests.filter { it.url.encodedPath.endsWith("/refresh") }

    @Test
    fun `scans the film's folder and waits until Plex lists the file`() = runTest {
        recent = listOf(items(), items("/volume1/video/Films/Testfilm (2024) {tmdb-603}/$fileName"))

        val result = publisher.publish(url, "token", "1", "video", folder, fileName)

        assertEquals(PlexPublication.Added("Testfilm", "/volume1/video/Films/Testfilm (2024) {tmdb-603}"), result)
        val refresh = refreshes().single()
        assertEquals("/library/sections/1/refresh", refresh.url.encodedPath)
        assertEquals("/volume1/video/Films/Testfilm (2024) {tmdb-603}", refresh.url.queryParameter("path"))
        assertEquals("token", refresh.headers["X-Plex-Token"])
        val listing = requests.last()
        assertEquals("addedAt:desc", listing.url.queryParameter("sort"))
    }

    @Test
    fun `falls back to scanning the whole library when the folder scan finds nothing`() = runTest {
        recent = listOf(items(), items(), items(), items(), items(), items("/elsewhere/$fileName"))

        val result = publisher.publish(url, "token", "1", "video", folder, fileName)

        assertEquals(PlexPublication.Added("Testfilm", null), result)
        assertEquals(listOf("/volume1/video/Films/Testfilm (2024) {tmdb-603}", null), refreshes().map { it.url.queryParameter("path") })
    }

    @Test
    fun `scans the whole library when the share is not in it and says when the film never shows up`() = runTest {
        val result = publisher.publish(url, "token", "1", "media", listOf("Movies", "X"), "X.mkv")

        assertEquals(PlexPublication.NotSeen(null), result)
        assertEquals(listOf(null), refreshes().map { it.url.queryParameter("path") })
    }

    @Test
    fun `a library that is gone is named as such`() = runTest {
        assertEquals(PlexPublication.SectionMissing, publisher.publish(url, "token", "9", "video", folder, fileName))
        assertEquals(emptyList(), refreshes())
    }

    @Test
    fun `a rejected token stops before any scan`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse(code = 401)
        }

        assertThrows<PlexException.InvalidToken> { publisher.publish(url, "wrong", "1", "video", folder, fileName) }
    }
}
