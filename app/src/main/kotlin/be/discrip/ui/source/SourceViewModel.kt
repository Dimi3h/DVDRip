package be.discrip.ui.source

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import be.discrip.AppContainer
import be.discrip.core.dvd.AudioTrackPlan
import be.discrip.core.dvd.DvdTitle
import be.discrip.core.dvd.MainFeature
import be.discrip.core.dvd.RipEstimate
import be.discrip.core.dvd.RipPlanning
import be.discrip.core.dvd.SubtitleTrackPlan
import be.discrip.core.estimate.SizeEstimator
import be.discrip.core.model.MovieMetadata
import be.discrip.core.naming.PlexNaming
import be.discrip.core.naming.RipPath
import be.discrip.core.settings.AppSettings
import be.discrip.data.FreeSpace
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which streams of the chosen title go into the file; null sets mean "all of them". */
data class TrackSelection(
    val title: Int? = null,
    val audio: Set<Int>? = null,
    val defaultAudio: Int? = null,
    val subtitles: Set<Int>? = null,
)

data class SourceUiState(
    val source: SourceState,
    val selection: TrackSelection,
    val showAllTitles: Boolean,
    val metadata: MovieMetadata?,
    val settings: AppSettings,
    val folderAccess: FolderAccess,
    /** Free space in the local folder, when its storage can tell. */
    val freeBytes: Long?,
    val ripRunning: Boolean,
) {
    val ready: SourceState.Ready? get() = source as? SourceState.Ready

    /** The title to rip: the user's pick, or else the main feature. */
    val title: DvdTitle?
        get() = ready?.let { ready -> ready.titles.firstOrNull { it.number == selection.title } ?: ready.mainTitle }

    /** The titles the list shows: all of them, or those that are neither short nor a decoy. */
    val listedTitles: List<DvdTitle>
        get() {
            val ready = ready ?: return emptyList()
            return if (showAllTitles) ready.titles else MainFeature.listed(ready.titles, title?.number)
        }

    val hiddenTitleCount: Int get() = (ready?.titles?.size ?: 0) - listedTitles.size

    val audio: List<AudioTrackPlan>
        get() = title?.let { RipPlanning.audioTracks(it, selection.audio, selection.defaultAudio) }.orEmpty()

    val subtitles: List<SubtitleTrackPlan>
        get() = title?.let { RipPlanning.subtitleTracks(it, selection.subtitles) }.orEmpty()

    /** Where the file goes in the local folder; this version always rips there. */
    val path: RipPath? get() = metadata?.let { PlexNaming.plan(it, settings.local.baseFolder(it.kind)) }

    val estimatedBytes: Long?
        get() = title?.let { RipEstimate.losslessBytes(it, audio, subtitles, settings.convertLpcmToFlac) }

    /** Space the rip needs before it may start: the estimate plus 10 %. */
    val neededBytes: Long? get() = estimatedBytes?.let(SizeEstimator::requiredFreeBytes)

    val enoughSpace: Boolean
        get() {
            val free = freeBytes ?: return true
            val needed = neededBytes ?: return true
            return free >= needed
        }
}

/** What [SourceViewModel.prepareStart] found. */
sealed interface StartCheck {
    data object NeedsMetadata : StartCheck
    data object NeedsFolder : StartCheck
    data object NotEnoughSpace : StartCheck
    data object Busy : StartCheck

    /** A file of an earlier rip is in the way; ask before replacing it. */
    data class Exists(val job: RipJob) : StartCheck
    data class Ready(val job: RipJob) : StartCheck
}

class SourceViewModel(private val container: AppContainer, private val dvd: DvdSource) : ViewModel() {

    private val selection = MutableStateFlow(TrackSelection())
    private val showAllTitles = MutableStateFlow(false)
    private val folderAccess = MutableStateFlow(FolderAccess.NONE)
    private val freeBytes = MutableStateFlow<Long?>(null)

    val state: StateFlow<SourceUiState?> = combine(
        combine(container.rips.source, container.rips.rip, ::Pair),
        container.ripDraft.metadata,
        container.settingsRepository.settings,
        combine(selection, showAllTitles, ::Pair),
        combine(folderAccess, freeBytes, ::Pair),
    ) { (source, rip), metadata, settings, (chosen, showAll), (access, free) ->
        SourceUiState(
            source = if (source.sourceOrNull == dvd) source else SourceState.Opening(dvd),
            selection = chosen,
            showAllTitles = showAll,
            metadata = metadata,
            settings = settings,
            folderAccess = access,
            freeBytes = free,
            ripRunning = rip is RipState.Running,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        container.rips.open(dvd)
    }

    /** Choosing another title starts again from all of its streams. */
    fun selectTitle(title: Int) {
        if (selection.value.title != title) selection.value = TrackSelection(title = title)
    }

    fun toggleAudio(streamId: Int) = toggle(streamId, audio = true)

    fun toggleSubtitle(streamId: Int) = toggle(streamId, audio = false)

    fun setDefaultAudio(streamId: Int) {
        selection.update { it.copy(defaultAudio = streamId) }
    }

    fun setShowAllTitles(show: Boolean) {
        showAllTitles.value = show
    }

    fun retry() = container.rips.open(dvd)

    /** Called on every resume: the folder grant can be withdrawn and the space used while the app is away. */
    fun refreshFolder() {
        viewModelScope.launch {
            val uri = container.settingsRepository.current().local.treeUri
            val access = when {
                uri == null -> FolderAccess.NONE
                withContext(Dispatchers.IO) { container.localFolders.hasAccess(uri) } -> FolderAccess.GRANTED
                else -> FolderAccess.REVOKED
            }
            folderAccess.value = access
            freeBytes.value = if (access == FolderAccess.GRANTED && uri != null) {
                withContext(Dispatchers.IO) { FreeSpace.of(container.appContext, uri.toUri()) }
            } else {
                null
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
        if (!current.enoughSpace) return StartCheck.NotEnoughSpace
        if (current.ripRunning) return StartCheck.Busy
        val job = RipJob(
            source = dvd,
            sourceName = current.ready?.name,
            metadata = metadata,
            title = title,
            audio = current.audio,
            subtitles = current.subtitles,
            lpcmToFlac = current.settings.convertLpcmToFlac,
            estimatedBytes = current.estimatedBytes ?: 0,
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

    private fun toggle(streamId: Int, audio: Boolean) {
        val title = state.value?.title ?: return
        selection.update { current ->
            val base = current.copy(title = title.number)
            if (audio) {
                val all = RipPlanning.orderedAudio(title).map { it.streamId }.toSet()
                val chosen = base.audio ?: all
                base.copy(audio = if (streamId in chosen) chosen - streamId else chosen + streamId)
            } else {
                val all = title.subtitles.map { it.streamId }.toSet()
                val chosen = base.subtitles ?: all
                base.copy(subtitles = if (streamId in chosen) chosen - streamId else chosen + streamId)
            }
        }
    }
}
