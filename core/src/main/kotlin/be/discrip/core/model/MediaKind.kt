package be.discrip.core.model

/**
 * What a rip is. Decides the base folder, the Plex library and whether ids end up in the name.
 */
enum class MediaKind {
    FILM,
    HOME_VIDEO,
    OTHER;

    /** Only films carry a `{tmdb-…}` or `{imdb-…}` hint; home videos and "other" never do. */
    val usesMovieIds: Boolean get() = this == FILM

    /** Home videos and "other" share the separate home video base folder and Plex library. */
    val usesHomeVideoFolder: Boolean get() = this != FILM
}
