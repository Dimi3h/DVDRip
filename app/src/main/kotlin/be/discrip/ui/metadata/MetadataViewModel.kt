package be.discrip.ui.metadata

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import be.discrip.AppContainer
import be.discrip.core.model.MetadataMode
import be.discrip.core.tmdb.TmdbException
import be.discrip.core.tmdb.TmdbMovie
import be.discrip.core.tmdb.VolumeLabelNormalizer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Year

/**
 * Drives the Metadata screen. State is Compose snapshot state, so text fields bound to it update
 * synchronously while typing.
 */
class MetadataViewModel(
    private val container: AppContainer,
    private val volumeLabel: String?,
) : ViewModel() {

    var state by mutableStateOf(MetadataUiState(currentYear = Year.now().value))
        private set

    private var searchJob: Job? = null
    private var detailsJob: Job? = null

    init {
        viewModelScope.launch {
            val settings = container.settingsRepository.current()
            val hasKey = container.secretStore.load().tmdbApiKey.isNotBlank()
            val draft = container.ripDraft.metadata.value
            state = if (draft != null) {
                // Editing the metadata already chosen for this rip.
                val automatic = hasKey && draft.tmdbId != null
                state.copy(
                    loading = false,
                    mode = if (automatic) MetadataMode.AUTOMATIC else MetadataMode.MANUAL,
                    hasTmdbKey = hasKey,
                    query = draft.title,
                    selectedTmdbId = draft.tmdbId.takeIf { automatic },
                    form = MetadataForm.from(draft),
                    settings = settings,
                )
            } else {
                val guess = VolumeLabelNormalizer.guess(volumeLabel)
                val year = guess.year?.toString().orEmpty()
                state.copy(
                    loading = false,
                    mode = if (hasKey && settings.metadataMode == MetadataMode.AUTOMATIC) MetadataMode.AUTOMATIC else MetadataMode.MANUAL,
                    hasTmdbKey = hasKey,
                    query = guess.query,
                    yearFilter = year,
                    form = MetadataForm(title = guess.query, year = year),
                    settings = settings,
                )
            }
            if (state.mode == MetadataMode.AUTOMATIC && state.query.isNotBlank()) {
                search(fallBackOnFailure = draft == null)
            }
        }
        viewModelScope.launch {
            container.settingsRepository.settings.collect { settings -> state = state.copy(settings = settings) }
        }
    }

    fun setMode(mode: MetadataMode) {
        state = state.copy(mode = mode, showProblems = false, fellBackToManual = false)
        if (mode == MetadataMode.AUTOMATIC && state.search == SearchState.Idle && state.query.isNotBlank()) search()
    }

    fun onQueryChange(text: String) {
        state = state.copy(query = text)
        scheduleSearch()
    }

    fun onYearFilterChange(text: String) {
        state = state.copy(yearFilter = text.filter(Char::isDigit).take(4))
        scheduleSearch()
    }

    fun search(fallBackOnFailure: Boolean = false) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch { performSearch(fallBackOnFailure) }
    }

    fun select(movie: TmdbMovie) {
        val summaryForm = MetadataForm.from(movie)
        state = state.copy(selectedTmdbId = movie.id, form = summaryForm, loadingDetails = true, showProblems = false)
        detailsJob?.cancel()
        detailsJob = viewModelScope.launch {
            // Details carry the localized title per language preference and the IMDb id.
            val details = try {
                container.tmdb.movie(movie.id)
            } catch (e: TmdbException) {
                null
            }
            val current = state
            if (current.selectedTmdbId != movie.id) return@launch
            state = when {
                details == null -> current.copy(loadingDetails = false)
                current.form == summaryForm -> current.copy(form = MetadataForm.from(details), loadingDetails = false)
                // The user already edited title or year; only fill in what they cannot see.
                else -> current.copy(
                    form = current.form.copy(
                        imdbId = current.form.imdbId.ifBlank { details.imdbId.orEmpty() },
                        originalTitle = details.originalTitle,
                        overview = details.overview.ifBlank { current.form.overview.orEmpty() },
                    ),
                    loadingDetails = false,
                )
            }
        }
    }

    fun updateForm(transform: (MetadataForm) -> MetadataForm) {
        state = state.copy(form = transform(state.form))
    }

    /** Stores the metadata in the rip draft; false (and problems shown) when the form is invalid. */
    fun confirm(): Boolean {
        val metadata = state.validation.metadata
        if (!state.canConfirm || metadata == null) {
            state = state.copy(showProblems = true)
            return false
        }
        container.ripDraft.setMetadata(metadata.normalized())
        return true
    }

    private fun scheduleSearch() {
        searchJob?.cancel()
        if (state.mode != MetadataMode.AUTOMATIC || state.query.trim().length < MIN_QUERY_LENGTH) return
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            performSearch(fallBackOnFailure = false)
        }
    }

    private suspend fun performSearch(fallBackOnFailure: Boolean) {
        val query = state.query.trim()
        if (query.isEmpty() || !state.hasTmdbKey) return
        state = state.copy(search = SearchState.Loading)
        state = try {
            val results = container.tmdb.searchMovies(query, state.yearFilter.toIntOrNull())
            state.copy(search = SearchState.Results(query, results))
        } catch (e: TmdbException) {
            val problem = when (e) {
                is TmdbException.Unreachable -> TmdbProblem.UNREACHABLE
                is TmdbException.InvalidApiKey, is TmdbException.MissingApiKey -> TmdbProblem.INVALID_KEY
                is TmdbException.RateLimited -> TmdbProblem.RATE_LIMITED
                else -> TmdbProblem.OTHER
            }
            // Manual entry is the default whenever TMDB cannot be reached.
            if (fallBackOnFailure && problem == TmdbProblem.UNREACHABLE) {
                state.copy(search = SearchState.Failed(problem), mode = MetadataMode.MANUAL, fellBackToManual = true)
            } else {
                state.copy(search = SearchState.Failed(problem))
            }
        }
    }

    private companion object {
        const val MIN_QUERY_LENGTH = 2
        const val SEARCH_DEBOUNCE_MILLIS = 600L
    }
}
