package be.discrip.core.tmdb

import be.discrip.core.model.MediaKind
import be.discrip.core.model.MovieMetadata
import be.discrip.core.net.ApiJson
import be.discrip.core.net.await
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** A film as the app shows and stores it, with title and overview already localized. */
data class TmdbMovie(
    val id: Int,
    val title: String,
    val originalTitle: String,
    val year: Int?,
    val overview: String,
    val posterPath: String?,
    val imdbId: String? = null,
    val runtimeMinutes: Int? = null,
) {
    fun toMetadata(): MovieMetadata = MovieMetadata(
        title = title,
        year = year,
        kind = MediaKind.FILM,
        tmdbId = id,
        imdbId = imdbId,
        originalTitle = originalTitle,
        overview = overview,
        posterPath = posterPath,
    )
}

/** Language preference: the primary language first, then the fallback (nl-BE, then en-US). */
data class TmdbLanguages(val primary: String, val fallback: String) {
    companion object {
        val DEFAULT = TmdbLanguages(primary = "nl-BE", fallback = "en-US")
    }
}

sealed class TmdbException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class MissingApiKey : TmdbException("No TMDB API key configured")
    class InvalidApiKey : TmdbException("TMDB rejected the API key")
    class Unreachable(cause: IOException) : TmdbException("TMDB is unreachable: ${cause.message}", cause)
    class RateLimited(val retryAfterSeconds: Long?) : TmdbException("TMDB rate limit reached")
    class NotFound : TmdbException("Not found on TMDB")
    class Http(val code: Int) : TmdbException("TMDB answered HTTP $code")
    class BadResponse(cause: Throwable) : TmdbException("Unexpected TMDB response", cause)
}

/**
 * TMDB API v3 client. The credential is either a v3 API key (sent as `api_key`) or a v4 read access
 * token (sent as a bearer token); TMDB accepts both on the v3 endpoints.
 */
class TmdbClient(
    private val http: OkHttpClient,
    private val credential: suspend () -> String?,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL,
    private val languages: TmdbLanguages = TmdbLanguages.DEFAULT,
) {

    /**
     * Searches films in both languages and merges the results per id, keeping TMDB's relevance
     * order. When a year filter finds nothing the search is repeated without it, because volume
     * labels and memories are often a year off.
     */
    suspend fun searchMovies(query: String, year: Int? = null): List<TmdbMovie> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val withYear = search(trimmed, year)
        return if (withYear.isEmpty() && year != null) search(trimmed, null) else withYear
    }

    /** Full details for one film, with title and overview picked per the language preference. */
    suspend fun movie(id: Int): TmdbMovie {
        val details = get<MovieDetailsDto>(
            "movie/$id",
            mapOf("language" to languages.fallback, "append_to_response" to "translations,external_ids"),
        )
        return details.toMovie(languages)
    }

    /** Looks a film up by its IMDb id (`tt…`); null when TMDB does not know it. */
    suspend fun findByImdbId(imdbId: String): TmdbMovie? {
        val found = get<FindResponseDto>(
            "find/$imdbId",
            mapOf("external_source" to "imdb_id", "language" to languages.primary),
        )
        val first = found.movieResults.firstOrNull() ?: return null
        return movie(first.id)
    }

    /** Succeeds when TMDB accepts the configured credential, throws [TmdbException] otherwise. */
    suspend fun verifyCredential() {
        get<kotlinx.serialization.json.JsonObject>("authentication", emptyMap())
    }

    private suspend fun search(query: String, year: Int?): List<TmdbMovie> = coroutineScope {
        val params = mapOf(
            "query" to query,
            "include_adult" to "false",
            "page" to "1",
            "year" to year?.toString(),
        )
        val primary = async { get<SearchResponseDto>("search/movie", params + ("language" to languages.primary)) }
        val fallback = async { get<SearchResponseDto>("search/movie", params + ("language" to languages.fallback)) }
        val primaryResults = primary.await().results
        val fallbackById = fallback.await().results.associateBy { it.id }
        val primaryIds = primaryResults.mapTo(HashSet()) { it.id }

        primaryResults.map { mergeSummaries(it, fallbackById[it.id], languages) } +
            fallbackById.values.filter { it.id !in primaryIds }.map { mergeSummaries(it, null, languages) }
    }

    private suspend inline fun <reified T> get(path: String, params: Map<String, String?>): T {
        val key = credential()?.trim().orEmpty()
        if (key.isEmpty()) throw TmdbException.MissingApiKey()
        val bearer = isBearerToken(key)

        val url = baseUrl.newBuilder()
            .addPathSegments(path)
            .apply {
                params.forEach { (name, value) -> if (value != null) addQueryParameter(name, value) }
                if (!bearer) addQueryParameter("api_key", key)
            }
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply { if (bearer) header("Authorization", "Bearer $key") }
            .build()

        val response = try {
            http.newCall(request).await()
        } catch (e: IOException) {
            throw TmdbException.Unreachable(e)
        }
        return response.use {
            when {
                it.code == 401 -> throw TmdbException.InvalidApiKey()
                it.code == 404 -> throw TmdbException.NotFound()
                it.code == 429 -> throw TmdbException.RateLimited(it.header("Retry-After")?.toLongOrNull())
                !it.isSuccessful -> throw TmdbException.Http(it.code)
            }
            val body = try {
                it.body.string()
            } catch (e: IOException) {
                throw TmdbException.Unreachable(e)
            }
            try {
                ApiJson.decodeFromString<T>(body)
            } catch (e: SerializationException) {
                throw TmdbException.BadResponse(e)
            } catch (e: IllegalArgumentException) {
                throw TmdbException.BadResponse(e)
            }
        }
    }

    companion object {
        val DEFAULT_BASE_URL: HttpUrl = "https://api.themoviedb.org/3/".toHttpUrl()
        private const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p/"

        /** v4 read access tokens are JWTs; v3 keys are 32 hex characters. */
        fun isBearerToken(credential: String): Boolean =
            credential.startsWith("eyJ") && credential.count { it == '.' } == 2

        /** Poster image URL; `w185` suits list thumbnails, `w342` the detail view. */
        fun posterUrl(posterPath: String?, size: String = "w185"): String? =
            posterPath?.takeIf { it.isNotBlank() }?.let { IMAGE_BASE_URL + size + (if (it.startsWith("/")) it else "/$it") }
    }
}

internal fun parseYear(releaseDate: String?): Int? =
    releaseDate?.takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull()

/**
 * Combines one search hit in the primary and the fallback language. TMDB answers with the original
 * title when a translation is missing, so a primary title equal to the original title of a film in
 * another language means "not translated" and the fallback title wins.
 */
internal fun mergeSummaries(primary: MovieSummaryDto, fallback: MovieSummaryDto?, languages: TmdbLanguages): TmdbMovie {
    val primaryLanguage = languages.primary.substringBefore('-')
    val primaryIsTranslated = primary.title.isNotBlank() &&
        (primary.title != primary.originalTitle || primary.originalLanguage == primaryLanguage)
    val title = when {
        primaryIsTranslated -> primary.title
        !fallback?.title.isNullOrBlank() -> fallback.title
        primary.title.isNotBlank() -> primary.title
        else -> primary.originalTitle
    }
    val overview = primary.overview?.takeIf { it.isNotBlank() } ?: fallback?.overview.orEmpty()
    return TmdbMovie(
        id = primary.id,
        title = title,
        originalTitle = primary.originalTitle.ifBlank { title },
        year = parseYear(primary.releaseDate) ?: parseYear(fallback?.releaseDate),
        overview = overview,
        posterPath = primary.posterPath ?: fallback?.posterPath,
    )
}

/** Picks title and overview from the translations: exact locale, same language, then fallback. */
internal fun MovieDetailsDto.toMovie(languages: TmdbLanguages): TmdbMovie {
    val all = translations?.translations.orEmpty()

    fun pick(field: (TranslationDataDto) -> String?, useOriginalTitle: Boolean): String? {
        for (tag in listOf(languages.primary, languages.fallback)) {
            val language = tag.substringBefore('-')
            val country = tag.substringAfter('-', "")
            val match = all.filter { it.language == language }
                .sortedBy {
                    when {
                        it.country.equals(country, ignoreCase = true) -> 0
                        it.country.equals(language, ignoreCase = true) -> 1
                        else -> 2
                    }
                }
                .firstNotNullOfOrNull { translation -> translation.data?.let(field)?.takeIf { it.isNotBlank() } }
            if (match != null) return match
            // The translation for a film's own language often leaves the title empty.
            if (useOriginalTitle && language == originalLanguage && originalTitle.isNotBlank()) return originalTitle
        }
        return null
    }

    val pickedTitle = pick({ it.title }, useOriginalTitle = true) ?: title.ifBlank { originalTitle }
    val pickedOverview = pick({ it.overview }, useOriginalTitle = false) ?: overview.orEmpty()
    return TmdbMovie(
        id = id,
        title = pickedTitle,
        originalTitle = originalTitle.ifBlank { pickedTitle },
        year = parseYear(releaseDate),
        overview = pickedOverview,
        posterPath = posterPath,
        imdbId = (imdbId ?: externalIds?.imdbId)?.takeIf { it.isNotBlank() },
        runtimeMinutes = runtime?.takeIf { it > 0 },
    )
}
