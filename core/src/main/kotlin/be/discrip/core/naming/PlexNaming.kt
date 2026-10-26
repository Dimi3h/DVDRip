package be.discrip.core.naming

import be.discrip.core.model.MovieMetadata

/**
 * Where a rip goes, relative to the chosen local folder or NAS share:
 * `<base>/<Title> (<Year>) {tmdb-<id>}/<Title> (<Year>) {tmdb-<id>}.mkv`.
 */
data class RipPath(
    val baseSegments: List<String>,
    val folderName: String,
    val fileName: String,
) {
    /** Folders to create, from the root down to the movie folder. */
    val folderSegments: List<String> get() = baseSegments + folderName

    val relativeFolder: String get() = folderSegments.joinToString("/")

    val relativeFile: String get() = (folderSegments + fileName).joinToString("/")
}

/**
 * Plex naming, following https://support.plex.tv/articles/naming-and-organizing-your-movie-media-files/.
 *
 * - Films with a TMDB id: `Title (Year) {tmdb-603}`.
 * - Films with only an IMDb id (typed by hand): `Title (Year) {imdb-tt0133093}`; Plex accepts both hints.
 * - Films without an id: `Title (Year)`.
 * - Home videos and "other": no id, and no year part when the year is unknown.
 */
object PlexNaming {

    const val EXTENSION = "mkv"

    /** The folder and file name without extension. Requires a title that survives sanitizing. */
    fun baseName(metadata: MovieMetadata): String {
        val normalized = metadata.normalized()
        val title = FileNameSanitizer.sanitizeComponent(normalized.title)
        require(title.isNotEmpty()) { "Title '${normalized.title}' has no usable characters" }

        val name = StringBuilder(title)
        normalized.year?.let { name.append(" (").append(it).append(')') }
        when {
            normalized.tmdbId != null -> name.append(" {tmdb-").append(normalized.tmdbId).append('}')
            normalized.imdbId != null -> name.append(" {imdb-").append(normalized.imdbId).append('}')
        }
        return name.toString()
    }

    fun fileName(metadata: MovieMetadata): String = "${baseName(metadata)}.$EXTENSION"

    /**
     * Full relative path for a rip. [baseFolder] is the configured base for this kind of video, such
     * as `Films` or `video/Home Videos`; an empty base puts the movie folder straight in the root.
     */
    fun plan(metadata: MovieMetadata, baseFolder: String): RipPath {
        val base = baseName(metadata)
        return RipPath(
            baseSegments = FileNameSanitizer.sanitizeRelativePath(baseFolder),
            folderName = base,
            fileName = "$base.$EXTENSION",
        )
    }

    /** True when [title] keeps at least one character after sanitizing. */
    fun isUsableTitle(title: String): Boolean = FileNameSanitizer.sanitizeComponent(title).isNotEmpty()
}
