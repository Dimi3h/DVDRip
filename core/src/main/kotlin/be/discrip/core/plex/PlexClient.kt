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
import java.net.URLEncoder

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

/** A film or video in a Plex library, with the files it plays from as the server sees them. */
data class PlexItem(
    val ratingKey: String,
    val title: String,
    val year: Int?,
    val files: List<String>,
)

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
        val response = get<SectionsResponseDto>(serverUrl, token, listOf("library", "sections"))
        return response.container.directories.map { dir ->
            PlexSection(
                key = dir.key,
                title = dir.title,
                type = dir.type,
                locations = dir.locations.map { it.path },
            )
        }
    }

    /**
     * Asks Plex to scan library section [sectionKey]: only [folder] (a path as the server sees
     * it) when given, else the whole library. Plex answers at once and scans in the background.
     */
    suspend fun refresh(serverUrl: String, token: String, sectionKey: String, folder: String? = null) {
        val query = if (folder != null) mapOf("path" to folder) else emptyMap()
        call(serverUrl, token, listOf("library", "sections", sectionKey, "refresh"), query)
    }

    /** The server's machine identifier, which links into Plex Web need. */
    suspend fun machineIdentifier(serverUrl: String, token: String): String {
        val identifier = get<IdentityResponseDto>(serverUrl, token, listOf("identity")).container.machineIdentifier
        if (identifier.isBlank()) throw PlexException.BadResponse(IllegalStateException("No machineIdentifier"))
        return identifier
    }

    /** The [count] items most recently added to library section [sectionKey], newest first. */
    suspend fun recentlyAdded(serverUrl: String, token: String, sectionKey: String, count: Int = 50): List<PlexItem> {
        val response = get<ItemsResponseDto>(
            serverUrl,
            token,
            listOf("library", "sections", sectionKey, "all"),
            mapOf("sort" to "addedAt:desc", "X-Plex-Container-Start" to "0", "X-Plex-Container-Size" to count.toString()),
        )
        return response.container.items.map { item ->
            PlexItem(
                ratingKey = item.ratingKey,
                title = item.title,
                year = item.year,
                files = item.media.flatMap { media -> media.parts.map { it.file } }.filter { it.isNotEmpty() },
            )
        }
    }

    private suspend inline fun <reified T> get(
        serverUrl: String,
        token: String,
        path: List<String>,
        query: Map<String, String> = emptyMap(),
    ): T {
        val body = call(serverUrl, token, path, query)
        return try {
            ApiJson.decodeFromString<T>(body)
        } catch (e: SerializationException) {
            throw PlexException.BadResponse(e)
        } catch (e: IllegalArgumentException) {
            throw PlexException.BadResponse(e)
        }
    }

    /** The body of a successful answer; throws [PlexException] otherwise. */
    private suspend fun call(serverUrl: String, token: String, path: List<String>, query: Map<String, String>): String {
        val base = normalizeServerUrl(serverUrl) ?: throw PlexException.InvalidUrl()
        if (token.isBlank()) throw PlexException.MissingToken()
        val url = base.newBuilder().apply {
            path.forEach(::addPathSegment)
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        val request = Request.Builder()
            .url(url)
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
            try {
                it.body.string()
            } catch (e: IOException) {
                throw PlexException.Unreachable(e)
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
        /** Plex Web on the server itself, open at the item [ratingKey]; null for an unusable [serverUrl]. */
        fun webLink(serverUrl: String, machineIdentifier: String, ratingKey: String): String? {
            val base = normalizeServerUrl(serverUrl) ?: return null
            val web = base.newBuilder().addPathSegment("web").addPathSegment("index.html").build()
            val key = URLEncoder.encode("/library/metadata/$ratingKey", "UTF-8")
            return "$web#!/server/$machineIdentifier/details?key=$key"
        }

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

@Serializable
internal data class IdentityResponseDto(
    @SerialName("MediaContainer") val container: IdentityDto = IdentityDto(),
)

@Serializable
internal data class IdentityDto(
    val machineIdentifier: String = "",
)

@Serializable
internal data class ItemsResponseDto(
    @SerialName("MediaContainer") val container: ItemsContainerDto = ItemsContainerDto(),
)

@Serializable
internal data class ItemsContainerDto(
    @SerialName("Metadata") val items: List<ItemDto> = emptyList(),
)

@Serializable
internal data class ItemDto(
    val ratingKey: String = "",
    val title: String = "",
    val year: Int? = null,
    @SerialName("Media") val media: List<MediaDto> = emptyList(),
)

@Serializable
internal data class MediaDto(
    @SerialName("Part") val parts: List<PartDto> = emptyList(),
)

@Serializable
internal data class PartDto(
    val file: String = "",
)
