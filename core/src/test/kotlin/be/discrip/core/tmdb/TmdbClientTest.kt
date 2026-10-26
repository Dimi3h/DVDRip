package be.discrip.core.tmdb

import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TmdbClientTest {

    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var route: (RecordedRequest) -> MockResponse = { MockResponse(code = 404) }

    private val apiKey = "0123456789abcdef0123456789abcdef"

    @BeforeEach
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return route(request)
            }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    private fun client(key: String? = apiKey) =
        TmdbClient(OkHttpClient(), credential = { key }, baseUrl = server.url("/3/"))

    private fun json(body: String) = MockResponse(code = 200, body = body)

    private val RecordedRequest.language: String? get() = url.queryParameter("language")

    @Test
    fun `search merges both languages and keeps relevance order`() = runTest {
        route = { request ->
            when (request.language) {
                "nl-BE" -> json(
                    """
                    {"page":1,"results":[
                      {"id":603,"title":"The Matrix","original_title":"The Matrix","original_language":"en",
                       "release_date":"1999-03-30","overview":"","poster_path":"/matrix-nl.jpg"},
                      {"id":1891,"title":"Het Rijk slaat terug","original_title":"The Empire Strikes Back",
                       "original_language":"en","release_date":"1980-05-20","overview":"Nederlandse samenvatting",
                       "poster_path":"/esb.jpg"},
                      {"id":194,"title":"Le Fabuleux Destin d'Amélie Poulain",
                       "original_title":"Le Fabuleux Destin d'Amélie Poulain","original_language":"fr",
                       "release_date":"2001-04-25","overview":"","poster_path":null}
                    ],"total_results":3}
                    """.trimIndent(),
                )
                else -> json(
                    """
                    {"page":1,"results":[
                      {"id":194,"title":"Amélie","original_title":"Le Fabuleux Destin d'Amélie Poulain",
                       "original_language":"fr","release_date":"2001-04-25","overview":"At a tiny Parisian café",
                       "poster_path":"/amelie.jpg"},
                      {"id":603,"title":"The Matrix","original_title":"The Matrix","original_language":"en",
                       "release_date":"1999-03-30","overview":"Set in the 22nd century","poster_path":"/matrix.jpg"},
                      {"id":1891,"title":"The Empire Strikes Back","original_title":"The Empire Strikes Back",
                       "original_language":"en","release_date":"1980-05-20","overview":"English overview"}
                    ]}
                    """.trimIndent(),
                )
            }
        }

        val results = client().searchMovies("  matrix ")

        assertEquals(listOf(603, 1891, 194), results.map { it.id })
        assertEquals(
            TmdbMovie(603, "The Matrix", "The Matrix", 1999, "Set in the 22nd century", "/matrix-nl.jpg"),
            results[0],
        )
        assertEquals("Het Rijk slaat terug", results[1].title)
        assertEquals("Nederlandse samenvatting", results[1].overview)
        assertEquals("Amélie", results[2].title)
        assertEquals("At a tiny Parisian café", results[2].overview)
        assertEquals("/amelie.jpg", results[2].posterPath)

        assertEquals(setOf("nl-BE", "en-US"), requests.map { it.language }.toSet())
        requests.forEach { request ->
            assertEquals("/3/search/movie", request.url.encodedPath)
            assertEquals("matrix", request.url.queryParameter("query"))
            assertEquals("false", request.url.queryParameter("include_adult"))
            assertEquals(apiKey, request.url.queryParameter("api_key"))
            assertNull(request.headers["Authorization"])
        }
    }

    @Test
    fun `search without hits for the year retries without it`() = runTest {
        route = { request ->
            if (request.url.queryParameter("year") != null) {
                json("""{"results":[]}""")
            } else {
                json("""{"results":[{"id":78,"title":"Blade Runner","original_title":"Blade Runner","original_language":"en","release_date":"1982-06-25"}]}""")
            }
        }

        val results = client().searchMovies("Blade Runner", year = 1983)

        assertEquals(listOf(78), results.map { it.id })
        assertEquals(4, requests.size)
        assertEquals(2, requests.count { it.url.queryParameter("year") == "1983" })
    }

    @Test
    fun `blank query makes no request`() = runTest {
        assertTrue(client().searchMovies("   ").isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `v4 tokens are sent as bearer header`() = runTest {
        val token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJl"
        route = { json("""{"success":true}""") }

        client(token).verifyCredential()

        val request = requests.single()
        assertEquals("Bearer $token", request.headers["Authorization"])
        assertNull(request.url.queryParameter("api_key"))
        assertEquals("/3/authentication", request.url.encodedPath)
    }

    @Test
    fun `rejected key maps to InvalidApiKey`() = runTest {
        route = { MockResponse(code = 401, body = """{"success":false,"status_code":7}""") }

        assertFailsWith<TmdbException.InvalidApiKey> { client().verifyCredential() }
    }

    @Test
    fun `missing key fails without a request`() = runTest {
        assertFailsWith<TmdbException.MissingApiKey> { client(key = "  ").searchMovies("x") }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `rate limit and server errors are reported`() = runTest {
        route = { MockResponse(code = 429, body = "", headers = okhttp3.Headers.headersOf("Retry-After", "3")) }
        val limited = assertFailsWith<TmdbException.RateLimited> { client().movie(1) }
        assertEquals(3, limited.retryAfterSeconds)

        route = { MockResponse(code = 503) }
        val error = assertFailsWith<TmdbException.Http> { client().movie(1) }
        assertEquals(503, error.code)

        route = { json("not json") }
        assertFailsWith<TmdbException.BadResponse> { client().movie(1) }
    }

    @Test
    fun `unreachable server maps to Unreachable`() = runTest {
        val deadUrl = server.url("/3/")
        server.close()
        val client = TmdbClient(OkHttpClient(), credential = { apiKey }, baseUrl = deadUrl)

        assertFailsWith<TmdbException.Unreachable> { client.searchMovies("matrix") }
    }

    @Test
    fun `details prefer nl-BE, then other Dutch, then English`() = runTest {
        route = {
            json(
                """
                {"id":11,"title":"Star Wars","original_title":"Star Wars","original_language":"en",
                 "release_date":"1977-05-25","overview":"Princess Leia is captured","poster_path":"/sw.jpg",
                 "imdb_id":"tt0076759","runtime":121,
                 "translations":{"translations":[
                   {"iso_639_1":"nl","iso_3166_1":"NL","data":{"title":"Star Wars: Een nieuwe hoop","overview":"NL samenvatting"}},
                   {"iso_639_1":"nl","iso_3166_1":"BE","data":{"title":"","overview":"BE samenvatting"}},
                   {"iso_639_1":"en","iso_3166_1":"US","data":{"title":"Star Wars","overview":"Princess Leia is captured"}}
                 ]}}
                """.trimIndent(),
            )
        }

        val movie = client().movie(11)

        assertEquals(
            TmdbMovie(11, "Star Wars: Een nieuwe hoop", "Star Wars", 1977, "BE samenvatting", "/sw.jpg", "tt0076759", 121),
            movie,
        )
        val request = requests.single()
        assertEquals("/3/movie/11", request.url.encodedPath)
        assertEquals("translations,external_ids", request.url.queryParameter("append_to_response"))
    }

    @Test
    fun `details of a Dutch film keep the original title`() = runTest {
        route = {
            json(
                """
                {"id":9075,"title":"Black Book","original_title":"Zwartboek","original_language":"nl",
                 "release_date":"2006-09-01","overview":"English text","external_ids":{"imdb_id":"tt0389557"},
                 "translations":{"translations":[
                   {"iso_639_1":"nl","iso_3166_1":"NL","data":{"title":"","overview":"Nederlandse tekst"}},
                   {"iso_639_1":"en","iso_3166_1":"US","data":{"title":"Black Book","overview":"English text"}}
                 ]}}
                """.trimIndent(),
            )
        }

        val movie = client().movie(9075)

        assertEquals("Zwartboek", movie.title)
        assertEquals("Nederlandse tekst", movie.overview)
        assertEquals("tt0389557", movie.imdbId)
    }

    @Test
    fun `details fall back to English when no Dutch exists`() = runTest {
        route = {
            json(
                """
                {"id":1,"title":"Obscure","original_title":"Obscure","original_language":"en",
                 "release_date":"","overview":"Only English","translations":{"translations":[]}}
                """.trimIndent(),
            )
        }

        val movie = client().movie(1)

        assertEquals("Obscure", movie.title)
        assertEquals("Only English", movie.overview)
        assertNull(movie.year)
    }

    @Test
    fun `imdb lookup resolves to full details`() = runTest {
        route = { request ->
            when (request.url.encodedPath) {
                "/3/find/tt0133093" -> json("""{"movie_results":[{"id":603,"title":"The Matrix"}]}""")
                "/3/movie/603" -> json(
                    """{"id":603,"title":"The Matrix","original_title":"The Matrix","original_language":"en","release_date":"1999-03-30","imdb_id":"tt0133093"}""",
                )
                else -> MockResponse(code = 404)
            }
        }

        val movie = client().findByImdbId("tt0133093")

        assertEquals(603, movie?.id)
        assertEquals("tt0133093", movie?.imdbId)
        assertEquals("imdb_id", requests.first().url.queryParameter("external_source"))
    }

    @Test
    fun `unknown imdb id gives null`() = runTest {
        route = { json("""{"movie_results":[]}""") }

        assertNull(client().findByImdbId("tt9999999"))
    }

    @Test
    fun `poster urls and token detection`() {
        assertEquals("https://image.tmdb.org/t/p/w185/abc.jpg", TmdbClient.posterUrl("/abc.jpg"))
        assertEquals("https://image.tmdb.org/t/p/w342/abc.jpg", TmdbClient.posterUrl("abc.jpg", "w342"))
        assertNull(TmdbClient.posterUrl(null))
        assertTrue(TmdbClient.isBearerToken("eyJa.eyJb.c"))
        assertTrue(!TmdbClient.isBearerToken(apiKey))
    }

    @Test
    fun `metadata conversion keeps ids`() {
        val movie = TmdbMovie(603, "The Matrix", "The Matrix", 1999, "", "/m.jpg", "tt0133093")

        val metadata = movie.toMetadata()

        assertEquals(603, metadata.tmdbId)
        assertEquals("tt0133093", metadata.imdbId)
        assertEquals(1999, metadata.year)
    }
}
