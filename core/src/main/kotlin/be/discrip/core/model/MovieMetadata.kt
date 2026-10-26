package be.discrip.core.model

/**
 * Metadata for one rip, either confirmed from TMDB or typed in by hand. It is always editable before
 * ripping, drives the Plex file name and is written as Matroska tags.
 */
data class MovieMetadata(
    val title: String,
    val year: Int?,
    val kind: MediaKind = MediaKind.FILM,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val originalTitle: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
) {
    /** Ids only apply to films; for other kinds they are dropped so nothing leaks into names or tags. */
    fun normalized(): MovieMetadata =
        if (kind.usesMovieIds) this else copy(tmdbId = null, imdbId = null)
}
