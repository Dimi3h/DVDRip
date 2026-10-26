package be.discrip.core.model

import be.discrip.core.naming.PlexNaming

/** What the manual form (or an edited TMDB match) holds before it becomes [MovieMetadata]. */
data class MetadataInput(
    val title: String,
    val year: String,
    val kind: MediaKind,
    val tmdbId: String = "",
    val imdbId: String = "",
    val originalTitle: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
)

enum class MetadataProblem {
    TITLE_MISSING,
    TITLE_UNUSABLE,
    YEAR_MISSING,
    YEAR_INVALID,
    TMDB_ID_INVALID,
    IMDB_ID_INVALID,
}

data class MetadataValidation(
    val metadata: MovieMetadata?,
    val problems: Set<MetadataProblem>,
) {
    val isValid: Boolean get() = metadata != null
}

object MetadataValidator {

    /** The first feature film is from 1888; allow announced films up to two years ahead. */
    const val FIRST_FILM_YEAR = 1888
    const val YEARS_AHEAD = 2

    private val imdbPattern = Regex("""tt\d{7,10}""")
    private val imdbDigitsOnly = Regex("""\d{7,10}""")
    private val tmdbUrl = Regex("""themoviedb\.org/movie/(\d{1,10})""")
    private val tmdbHint = Regex("""^\{?tmdb-(\d{1,10})}?$""")
    private val digitsOnly = Regex("""\d{1,10}""")

    fun validate(input: MetadataInput, currentYear: Int): MetadataValidation {
        val problems = mutableSetOf<MetadataProblem>()
        val title = input.title.trim()
        when {
            title.isEmpty() -> problems += MetadataProblem.TITLE_MISSING
            !PlexNaming.isUsableTitle(title) -> problems += MetadataProblem.TITLE_UNUSABLE
        }

        val yearText = input.year.trim()
        val year = yearText.toIntOrNull()
        when {
            yearText.isEmpty() -> if (input.kind.usesMovieIds) problems += MetadataProblem.YEAR_MISSING
            year == null || year < FIRST_FILM_YEAR || year > currentYear + YEARS_AHEAD ->
                problems += MetadataProblem.YEAR_INVALID
        }

        var tmdbId: Int? = null
        var imdbId: String? = null
        if (input.kind.usesMovieIds) {
            if (input.tmdbId.isNotBlank()) {
                tmdbId = parseTmdbId(input.tmdbId)
                if (tmdbId == null) problems += MetadataProblem.TMDB_ID_INVALID
            }
            if (input.imdbId.isNotBlank()) {
                imdbId = parseImdbId(input.imdbId)
                if (imdbId == null) problems += MetadataProblem.IMDB_ID_INVALID
            }
        }

        if (problems.isNotEmpty()) return MetadataValidation(null, problems)
        return MetadataValidation(
            MovieMetadata(
                title = title,
                year = if (yearText.isEmpty()) null else year,
                kind = input.kind,
                tmdbId = tmdbId,
                imdbId = imdbId,
                originalTitle = input.originalTitle,
                overview = input.overview,
                posterPath = input.posterPath,
            ),
            emptySet(),
        )
    }

    /** Accepts `603`, `tmdb-603`, `{tmdb-603}` or a themoviedb.org movie URL. */
    fun parseTmdbId(text: String): Int? {
        val trimmed = text.trim()
        val digits = tmdbUrl.find(trimmed)?.groupValues?.get(1)
            ?: tmdbHint.find(trimmed)?.groupValues?.get(1)
            ?: trimmed.takeIf { digitsOnly.matches(it) }
            ?: return null
        return digits.toLongOrNull()?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
    }

    /** Accepts `tt0133093`, `0133093` or an imdb.com title URL; returns the `tt…` form. */
    fun parseImdbId(text: String): String? {
        val trimmed = text.trim()
        imdbPattern.find(trimmed)?.let { return it.value }
        return if (imdbDigitsOnly.matches(trimmed)) "tt$trimmed" else null
    }
}
