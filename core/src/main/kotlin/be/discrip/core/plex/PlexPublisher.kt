package be.discrip.core.plex

import kotlinx.coroutines.delay

/** Where a folder on the NAS share is, as the Plex server sees it. */
object PlexPaths {

    /**
     * The Plex server's path of [folderSegments] below [share], worked out from a library's
     * [locations]: a location ending in the share and the first folders below it
     * (`/volume1/video/Films` for share `video` and `Films/…`), or else in the first folders below
     * the share alone (`D:\Films` on a server that has the share as a drive). The longest such
     * match wins. Null when no location matches, as when the library does not hold the folder.
     */
    fun serverFolder(locations: List<String>, share: String, folderSegments: List<String>): String? {
        var best: Pair<Int, String>? = null
        for (location in locations) {
            val separator = if ('\\' in location && '/' !in location) '\\' else '/'
            val parts = location.split('/', '\\').filter { it.isNotEmpty() }
            for (candidate in listOf(listOf(share) + folderSegments, folderSegments)) {
                val matched = (minOf(parts.size, candidate.size) downTo 1).firstOrNull { k ->
                    parts.takeLast(k).zip(candidate.take(k)).all { (a, b) -> a.equals(b, ignoreCase = true) }
                } ?: continue
                val path = location.trimEnd('/', '\\') + candidate.drop(matched).joinToString("") { "$separator$it" }
                val score = if (candidate.size > folderSegments.size) matched + 1 else matched
                if (best == null || score > best.first) best = score to path
            }
        }
        return best?.second
    }
}

/** What came of telling Plex about a rip, short enough to keep in the history. */
enum class PlexResult {
    ADDED,
    NOT_SEEN,
    SECTION_MISSING,
    UNREACHABLE,
    TOKEN_REJECTED,
    FAILED,
}

val PlexPublication.result: PlexResult
    get() = when (this) {
        is PlexPublication.Added -> PlexResult.ADDED
        is PlexPublication.NotSeen -> PlexResult.NOT_SEEN
        PlexPublication.SectionMissing -> PlexResult.SECTION_MISSING
    }

val PlexException.result: PlexResult
    get() = when (this) {
        is PlexException.Unreachable -> PlexResult.UNREACHABLE
        is PlexException.InvalidToken, is PlexException.MissingToken -> PlexResult.TOKEN_REJECTED
        is PlexException.InvalidUrl, is PlexException.Http, is PlexException.BadResponse -> PlexResult.FAILED
    }

/** What Plex made of a new rip. */
sealed interface PlexPublication {
    /** Plex lists the film, under [title]. [scannedFolder] is the folder it was asked to scan, if not the whole library. */
    data class Added(val title: String, val scannedFolder: String?) : PlexPublication

    /** Plex was asked to scan but did not list the film in time: it is still busy, or the file is outside the library. */
    data class NotSeen(val scannedFolder: String?) : PlexPublication

    /** The library chosen in the settings is no longer on the server. */
    data object SectionMissing : PlexPublication
}

/**
 * Tells Plex about a rip on the NAS and waits until the library lists it. Plex scans just the
 * film's folder when the library's locations show where the share is on the server; if the film
 * does not turn up there, or the folder cannot be mapped, the whole library is scanned.
 */
class PlexPublisher(
    private val client: PlexClient,
    private val pollMillis: Long = 3_000,
    private val waitMillis: Long = 120_000,
) {
    /** Throws [PlexException] when Plex cannot be reached or refuses the token before the scan is asked for. */
    suspend fun publish(
        serverUrl: String,
        token: String,
        sectionKey: String,
        share: String,
        folderSegments: List<String>,
        fileName: String,
    ): PlexPublication {
        val section = client.librarySections(serverUrl, token).firstOrNull { it.key == sectionKey }
            ?: return PlexPublication.SectionMissing
        val folder = PlexPaths.serverFolder(section.locations, share, folderSegments)
        if (folder != null) {
            client.refresh(serverUrl, token, sectionKey, folder)
            waitFor(serverUrl, token, sectionKey, fileName, waitMillis / 2)?.let { return PlexPublication.Added(it.title, folder) }
        }
        client.refresh(serverUrl, token, sectionKey)
        val item = waitFor(serverUrl, token, sectionKey, fileName, if (folder != null) waitMillis / 2 else waitMillis)
        return if (item != null) PlexPublication.Added(item.title, null) else PlexPublication.NotSeen(folder)
    }

    private suspend fun waitFor(serverUrl: String, token: String, sectionKey: String, fileName: String, millis: Long): PlexItem? {
        var waited = 0L
        while (waited < millis) {
            delay(pollMillis)
            waited += pollMillis
            val items = try {
                client.recentlyAdded(serverUrl, token, sectionKey)
            } catch (e: PlexException.Unreachable) {
                // A busy server or a Wi-Fi hiccup while it scans; ask again.
                continue
            }
            items.firstOrNull { item -> item.files.any { nameOf(it) == fileName } }?.let { return it }
        }
        return null
    }

    private fun nameOf(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')
}
