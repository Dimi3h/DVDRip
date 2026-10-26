package be.discrip.core.plex

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PlexClientTest {

    private val server = MockWebServer()
    private val client = PlexClient(OkHttpClient())

    @BeforeEach
    fun setUp() = server.start()

    @AfterEach
    fun tearDown() = server.close()

    private val baseUrl get() = server.url("/").toString()

    @Test
    fun `lists library sections with their folders`() = runTest {
        server.enqueue(
            MockResponse(
                body = """
                {"MediaContainer":{"size":3,"allowSync":false,"title1":"Plex Library","Directory":[
                  {"allowSync":true,"key":"1","type":"movie","title":"Films","agent":"tv.plex.agents.movie",
                   "Location":[{"id":1,"path":"/volume1/video/Films"}]},
                  {"key":"2","type":"show","title":"Series","Location":[{"id":2,"path":"/volume1/video/Series"}]},
                  {"key":"4","type":"movie","title":"Thuisvideo's",
                   "Location":[{"id":4,"path":"/volume1/video/Home Videos"},{"id":5,"path":"/volume2/oud"}]}
                ]}}
                """.trimIndent(),
            ),
        )

        val sections = client.librarySections(baseUrl, " secret-token ")

        assertEquals(
            listOf(
                PlexSection("1", "Films", "movie", listOf("/volume1/video/Films")),
                PlexSection("2", "Series", "show", listOf("/volume1/video/Series")),
                PlexSection("4", "Thuisvideo's", "movie", listOf("/volume1/video/Home Videos", "/volume2/oud")),
            ),
            sections,
        )
        assertEquals(listOf(true, false, true), sections.map { it.isMovieLibrary })
        val request = server.takeRequest()
        assertEquals("/library/sections", request.url.encodedPath)
        assertEquals("secret-token", request.headers["X-Plex-Token"])
        assertEquals("application/json", request.headers["Accept"])
    }

    @Test
    fun `empty server answers with no sections`() = runTest {
        server.enqueue(MockResponse(body = """{"MediaContainer":{"size":0}}"""))

        assertEquals(emptyList(), client.librarySections(baseUrl, "token"))
    }

    @Test
    fun `rejected token maps to InvalidToken`() = runTest {
        server.enqueue(MockResponse(code = 401))

        assertFailsWith<PlexException.InvalidToken> { client.librarySections(baseUrl, "wrong") }
    }

    @Test
    fun `missing token and bad url fail before any request`() = runTest {
        assertFailsWith<PlexException.MissingToken> { client.librarySections(baseUrl, " ") }
        assertFailsWith<PlexException.InvalidUrl> { client.librarySections("not a url !!", "token") }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `server urls are completed with scheme and default port`() {
        assertEquals("http://192.168.1.10:32400/", PlexClient.normalizeServerUrl("192.168.1.10")?.toString())
        assertEquals("http://nas.local:32401/", PlexClient.normalizeServerUrl("nas.local:32401")?.toString())
        assertEquals("http://nas.local:32400/plex/", PlexClient.normalizeServerUrl("nas.local/plex")?.toString())
        assertEquals("https://plex.example.com/", PlexClient.normalizeServerUrl("https://plex.example.com")?.toString())
        assertEquals("https://example.com/plex/", PlexClient.normalizeServerUrl("https://example.com/plex/")?.toString())
        assertNull(PlexClient.normalizeServerUrl("  "))
        assertNull(PlexClient.normalizeServerUrl("not a url !!"))
    }
}
