package be.discrip.core.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetadataValidatorTest {

    private val currentYear = 2026

    private fun validate(
        title: String = "The Matrix",
        year: String = "1999",
        kind: MediaKind = MediaKind.FILM,
        tmdbId: String = "",
        imdbId: String = "",
    ) = MetadataValidator.validate(MetadataInput(title, year, kind, tmdbId, imdbId), currentYear)

    @Test
    fun `valid film becomes metadata`() {
        val result = validate(tmdbId = "603")

        assertEquals(MovieMetadata("The Matrix", 1999, MediaKind.FILM, tmdbId = 603), result.metadata)
        assertTrue(result.problems.isEmpty())
    }

    @Test
    fun `film needs a title and a year`() {
        val result = validate(title = "  ", year = "")

        assertNull(result.metadata)
        assertEquals(setOf(MetadataProblem.TITLE_MISSING, MetadataProblem.YEAR_MISSING), result.problems)
    }

    @Test
    fun `home video may skip the year`() {
        val result = validate(title = "Vakantie", year = "", kind = MediaKind.HOME_VIDEO)

        assertEquals(MovieMetadata("Vakantie", null, MediaKind.HOME_VIDEO), result.metadata)
    }

    @Test
    fun `year must be plausible`() {
        assertEquals(setOf(MetadataProblem.YEAR_INVALID), validate(year = "1700").problems)
        assertEquals(setOf(MetadataProblem.YEAR_INVALID), validate(year = "2029").problems)
        assertEquals(setOf(MetadataProblem.YEAR_INVALID), validate(year = "19x9").problems)
        assertTrue(validate(year = "2028").isValid)
    }

    @Test
    fun `title that sanitizes to nothing is unusable`() {
        assertEquals(setOf(MetadataProblem.TITLE_UNUSABLE), validate(title = "???").problems)
    }

    @Test
    fun `ids are parsed from several notations`() {
        assertEquals(603, MetadataValidator.parseTmdbId("603"))
        assertEquals(603, MetadataValidator.parseTmdbId("{tmdb-603}"))
        assertEquals(603, MetadataValidator.parseTmdbId("https://www.themoviedb.org/movie/603-the-matrix"))
        assertNull(MetadataValidator.parseTmdbId("abc"))
        assertNull(MetadataValidator.parseTmdbId("0"))

        assertEquals("tt0133093", MetadataValidator.parseImdbId("tt0133093"))
        assertEquals("tt0133093", MetadataValidator.parseImdbId("0133093"))
        assertEquals("tt0133093", MetadataValidator.parseImdbId("https://www.imdb.com/title/tt0133093/"))
        assertNull(MetadataValidator.parseImdbId("tt12"))
    }

    @Test
    fun `invalid ids are reported for films`() {
        assertEquals(
            setOf(MetadataProblem.TMDB_ID_INVALID, MetadataProblem.IMDB_ID_INVALID),
            validate(tmdbId = "x", imdbId = "y").problems,
        )
    }

    @Test
    fun `ids are ignored for home videos`() {
        val result = validate(kind = MediaKind.HOME_VIDEO, tmdbId = "603", imdbId = "garbage")

        assertEquals(MovieMetadata("The Matrix", 1999, MediaKind.HOME_VIDEO), result.metadata)
    }
}
