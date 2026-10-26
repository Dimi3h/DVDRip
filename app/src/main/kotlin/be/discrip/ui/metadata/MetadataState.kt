package be.discrip.ui.metadata

import be.discrip.core.model.MediaKind
import be.discrip.core.model.MetadataInput
import be.discrip.core.model.MetadataMode
import be.discrip.core.model.MetadataValidation
import be.discrip.core.model.MetadataValidator
import be.discrip.core.model.MovieMetadata
import be.discrip.core.naming.PlexNaming
import be.discrip.core.naming.RipPath
import be.discrip.core.settings.AppSettings
import be.discrip.core.tmdb.TmdbMovie
import be.discrip.ui.home.baseFolderFor

enum class TmdbProblem { UNREACHABLE, INVALID_KEY, RATE_LIMITED, OTHER }

sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Results(val query: String, val movies: List<TmdbMovie>) : SearchState
    data class Failed(val problem: TmdbProblem) : SearchState
}

/** The editable fields, as text, exactly as the user sees them. */
data class MetadataForm(
    val title: String = "",
    val year: String = "",
    val kind: MediaKind = MediaKind.FILM,
    val tmdbId: String = "",
    val imdbId: String = "",
    val originalTitle: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
) {
    fun toInput() = MetadataInput(title, year, kind, tmdbId, imdbId, originalTitle, overview, posterPath)

    companion object {
        fun from(metadata: MovieMetadata) = MetadataForm(
            title = metadata.title,
            year = metadata.year?.toString().orEmpty(),
            kind = metadata.kind,
            tmdbId = metadata.tmdbId?.toString().orEmpty(),
            imdbId = metadata.imdbId.orEmpty(),
            originalTitle = metadata.originalTitle,
            overview = metadata.overview,
            posterPath = metadata.posterPath,
        )

        fun from(movie: TmdbMovie) = from(movie.toMetadata())
    }
}

data class MetadataUiState(
    val loading: Boolean = true,
    val mode: MetadataMode = MetadataMode.AUTOMATIC,
    val hasTmdbKey: Boolean = false,
    /** True when the first automatic search failed and the screen switched to manual entry. */
    val fellBackToManual: Boolean = false,
    val query: String = "",
    val yearFilter: String = "",
    val search: SearchState = SearchState.Idle,
    val selectedTmdbId: Int? = null,
    val loadingDetails: Boolean = false,
    val form: MetadataForm = MetadataForm(),
    val showProblems: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val currentYear: Int,
) {
    val validation: MetadataValidation get() = MetadataValidator.validate(form.toInput(), currentYear)

    /** Where the file will go, relative to the local folder or the share. */
    val preview: RipPath? get() = validation.metadata?.let { PlexNaming.plan(it, settings.baseFolderFor(it)) }

    /** The editable form shows in manual mode, and in automatic mode once a match is chosen. */
    val showForm: Boolean get() = mode == MetadataMode.MANUAL || selectedTmdbId != null

    val canConfirm: Boolean get() = !loading && showForm
}
