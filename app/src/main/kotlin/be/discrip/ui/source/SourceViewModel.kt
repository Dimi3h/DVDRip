package be.discrip.ui.source

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import be.discrip.AppContainer
import be.discrip.core.dvd.AudioTrackPlan
import be.discrip.core.dvd.DvdTitle
import be.discrip.core.dvd.RipPlanning
import be.discrip.core.model.MovieMetadata
import be.discrip.core.naming.PlexNaming
import be.discrip.core.naming.RipPath
import be.discrip.core.settings.AppSettings
import be.discrip.rip.DvdSource
import be.discrip.rip.LocalOutput
import be.discrip.rip.RipJob
import be.discrip.rip.RipState
import be.discrip.rip.SourceState
import be.discrip.ui.settings.FolderAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SourceUiState(
    val source: SourceState,
    val selectedTitle: Int?,
    val metadata: MovieMetadata?,
    val settings: AppSettings,
    val folderAccess: FolderAccess,
    val ripRunning: Boolean,
) {
    val ready: SourceState.Ready? get() = source as? SourceState.Ready

    /** The title to rip: the user's pick, or else the main feature. */
    val title: DvdTitle?
        get() = ready?.let { ready -> ready.titles.firstOrNull { it.number == selectedTitle } ?: ready.mainTitle }

    val audio: List<AudioTrackPlan> get() = title?.let(RipPlanning::audioTracks).orEmpty()

    /** Where the file goes in the local folder; this version always rips there. */
    val path: RipPath? get() = metadata?.let { PlexNaming.plan(it, settings.local.baseFolder(it.kind)) }

    val canStart: Boolean
        get() = title != null && metadata != null && folderAccess == FolderAccess.GRANTED && !ripRunning
}

/** What [SourceViewModel.prepareStart] found. */
sealed interface StartCheck {
    data object NeedsMetadata : StartCheck
    data object NeedsFolder : StartCheck
    data object Busy : StartCheck

    /** A file of an earlier rip is in the way; ask before replacing it. */
    data class Exists(val job: RipJob) : StartCheck
    data class Ready(val job: RipJob) : StartCheck
}

class SourceViewModel(private val container: AppContainer, private val dvd: DvdSource) : ViewModel() {

    private val selected = MutableStateFlow<Int?>(null)
    private val folderAccess = MutableStateFlow(FolderAccess.NONE)

    val state: StateFlow<SourceUiState?> = combine(
        container.rips.source,
        container.rips.rip,
        container.ripDraft.metadata,
        container.settingsRepository.settings,
        combine(selected, folderAccess, ::Pair),
    ) { source, rip, metadata, settings, (title, access) ->
        SourceUiState(
            source = if (source.sourceOrNull == dvd) source else SourceState.Opening(dvd),
            selectedTitle = title,
            metadata = metadata,
            settings = settings,
            folderAccess = access,
            ripRunning = rip is RipState.Running,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        container.rips.open(dvd)
    }

    fun select(title: Int) {
        selected.value = title
    }

    fun retry() = container.rips.open(dvd)

    /** Called on every resume: the folder grant can be withdrawn while the app is away. */
    fun refreshFolderAccess() {
        viewModelScope.launch {
            val uri = container.settingsRepository.current().local.treeUri
            folderAccess.value = when {
                uri == null -> FolderAccess.NONE
                withContext(Dispatchers.IO) { container.localFolders.hasAccess(uri) } -> FolderAccess.GRANTED
                else -> FolderAccess.REVOKED
            }
        }
    }

    suspend fun prepareStart(): StartCheck {
        val current = state.value ?: return StartCheck.Busy
        val title = current.title ?: return StartCheck.Busy
        val metadata = current.metadata ?: return StartCheck.NeedsMetadata
        val path = current.path ?: return StartCheck.NeedsMetadata
        val treeUri = current.settings.local.treeUri
        if (current.folderAccess != FolderAccess.GRANTED || treeUri == null) return StartCheck.NeedsFolder
        if (current.ripRunning) return StartCheck.Busy
        val job = RipJob(
            source = dvd,
            sourceName = current.ready?.name,
            metadata = metadata,
            title = title,
            audio = current.audio,
            lpcmToFlac = current.settings.convertLpcmToFlac,
            path = path,
            treeUri = treeUri,
        )
        val exists = withContext(Dispatchers.IO) {
            runCatching { LocalOutput.existing(container.appContentResolver, treeUri.toUri(), path) != null }.getOrDefault(false)
        }
        return if (exists) StartCheck.Exists(job) else StartCheck.Ready(job)
    }

    fun start(job: RipJob): Boolean = container.rips.start(job)

    override fun onCleared() {
        container.rips.close(dvd)
    }
}
