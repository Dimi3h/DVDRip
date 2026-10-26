package be.discrip.core.plex

import be.discrip.core.net.ApiJson
import be.discrip.core.net.await
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** A library section on the Plex server (`/library/sections`). */
data class PlexSection(
    val key: String,
    val title: String,
    /** `movie`, `show`, `artist` or `photo`. */
    val type: String,
    /** Folders as the Plex server sees them, e.g. `/volume1/video/Films`. */
    val locations: List<String>,
) {
    val isMovieLibrary: Boolean get() = type == "movie"
}

sealed class PlexException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidUrl : PlexException("Not a valid Plex server URL")
    class MissingToken : PlexException("No Plex token configured")
    class InvalidToken : PlexException("Plex rejected the token")
    class Unreachable(cause: IOException) : PlexException("Plex server unreachable: ${cause.message}", cause)
    class Http(val code: Int) : PlexException("Plex answered HTTP $code")
    class BadResponse(cause: Throwable) : PlexException("Unexpected Plex response", cause)
}

/** Client for the Plex Media Server HTTP API on the local network. */
class PlexClient(private val http: OkHttpClient) {

    suspend fun librarySections(serverUrl: String, token: String): List<PlexSection> {
        val response = get<SectionsResponseDto>(serverUrl, token, "library/sections")
        return response.container.directories.map { dir ->
            PlexSection(
                key = dir.key,
                title = dir.title,
                type = dir.type,
                locations = dir.locations.map { it.path },
            )
        }
    }

    private suspend inline fun <reified T> get(serverUrl: String, token: String, path: String): T {
        val base = normalizeServerUrl(serverUrl) ?: throw PlexException.InvalidUrl()
        if (token.isBlank()) throw PlexException.MissingToken()
        val request = Request.Builder()
            .url(base.newBuilder().addPathSegments(path).build())
            .header("Accept", "application/json")
            .header("X-Plex-Token", token.trim())
            .header("X-Plex-Product", PRODUCT)
            .header("X-Plex-Client-Identifier", PRODUCT)
            .build()
        val response = try {
            http.newCall(request).await()
        } catch (e: IOException) {
            throw PlexException.Unreachable(e)
        }
        return response.use {
            when {
                it.code == 401 -> throw PlexException.InvalidToken()
                !it.isSuccessful -> throw PlexException.Http(it.code)
            }
            val body = try {
                it.body.string()
            } catch (e: IOException) {
                throw PlexException.Unreachable(e)
            }
            try {
                ApiJson.decodeFromString<T>(body)
            } catch (e: SerializationException) {
                throw PlexException.BadResponse(e)
            } catch (e: IllegalArgumentException) {
                throw PlexException.BadResponse(e)
            }
        }
    }

    companion object {
        private const val PRODUCT = "DiscRip"
        const val DEFAULT_PORT = 32400

        /**
         * Accepts `192.168.1.10`, `nas.local:32400` or a full `http(s)://` URL. Without a scheme,
         * plain HTTP and Plex's default port 32400 are assumed.
         */
        fun normalizeServerUrl(input: String): HttpUrl? {
            val trimmed = input.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val hasScheme = trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            val candidate = if (hasScheme) {
                trimmed
            } else {
                val hostPart = trimmed.substringBefore('/')
                val hasPort = hostPart.substringAfterLast(']').contains(':')
                if (hasPort) "http://$trimmed" else "http://$hostPart:$DEFAULT_PORT" + trimmed.removePrefix(hostPart)
            }
            val url = candidate.toHttpUrlOrNull() ?: return null
            // Path segments end with an empty one so addPathSegments() appends below the base.
            return if (url.encodedPath.endsWith("/")) url else url.newBuilder().addPathSegment("").build()
        }
    }
}

@Serializable
internal data class SectionsResponseDto(
    @SerialName("MediaContainer") val container: SectionsContainerDto = SectionsContainerDto(),
)

@Serializable
internal data class SectionsContainerDto(
    @SerialName("Directory") val directories: List<DirectoryDto> = emptyList(),
)

@Serializable
internal data class DirectoryDto(
    val key: String,
    val title: String = "",
    val type: String = "",
    @SerialName("Location") val locations: List<LocationDto> = emptyList(),
)

@Serializable
internal data class LocationDto(
    val path: String = "",
)
