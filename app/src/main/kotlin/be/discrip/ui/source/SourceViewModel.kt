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
import be.discrip.core.model.QualityPreset
import be.discrip.core.model.StorageTarget
import be.discrip.core.naming.RipPath
import be.discrip.core.nas.Nas
import be.discrip.core.nas.NasException
import be.discrip.core.nas.NasProblem
import be.discrip.core.settings.AppSettings
import be.discrip.core.video.EncoderChoice
import be.discrip.core.video.VideoPlan
import be.discrip.data.FreeSpace
import be.discrip.rip.DvdSource
import be.discrip.rip.EncoderCatalog
import be.discrip.rip.LocalOutput
import be.discrip.rip.RipDestination
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
    /** Where this rip goes; the default from the settings until the user picks another. */
    val storage: StorageTarget,
    /** The quality of this rip; the default from the settings until the user picks another. */
    val preset: QualityPreset,
    /** The device's video encoders; null while they are being read. */
    val encoders: EncoderCatalog?,
    val folderAccess: FolderAccess,
    /** Free space in the local folder, when its storage can tell. */
    val localFreeBytes: Long?,
    /** Free space in the app's own storage, where a NAS rip waits for its upload. */
    val appFreeBytes: Long?,
    val ripRunning: Boolean,
    /** The start button is checking the NAS. */
    val starting: Boolean = false,
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

    /** Where the file goes; null without metadata, or while a part [storage] needs is not set up. */
    val destination: RipDestination? get() = metadata?.let { RipDestination.plan(settings, storage, it) }

    /** The film's folder and file in the local folder, or else on the share. */
    val path: RipPath? get() = destination?.let { it.local?.path ?: it.nas?.path }

    /** The local folder is needed but not usable. */
    val needsFolder: Boolean get() = storage.usesLocalFolder && folderAccess != FolderAccess.GRANTED

    /** The NAS is needed but its host or share is missing in the settings. */
    val needsNas: Boolean get() = storage.usesNas && !settings.nas.isConfigured

    /**
     * Free space where the engine writes on this device: the local folder, the app's storage for
     * a NAS rip that uploads afterwards, nothing for one written straight to the NAS (its space is
     * checked at the start).
     */
    val freeBytes: Long?
        get() = when {
            storage.usesLocalFolder -> localFreeBytes
            settings.nas.writeDirectly -> null
            else -> appFreeBytes
        }

    val estimatedBytes: Long? get() = estimatedBytes(preset)

    /** The size of the rip with [preset] instead, for the quality picker. */
    fun estimatedBytes(preset: QualityPreset): Long? =
        title?.let { RipEstimate.bytes(it, preset, settings.videoBitrateKbps(preset), audio, subtitles, settings.convertLpcmToFlac) }

    /**
     * The encoders a lossy [preset] would use on this title, at the bit rate from the settings;
     * null for the copy, while the encoders are being read, or when none fits.
     */
    fun videoPlan(preset: QualityPreset = this.preset): VideoPlan? {
        val title = title ?: return null
        val catalog = encoders ?: return null
        val kbps = settings.videoBitrateKbps(preset) ?: return null
        return EncoderChoice.plan(preset, kbps, catalog.forTitle(title.video.width, title.video.height))
    }

    /** A lossy [preset] that cannot be used: the device has no encoder for the title. */
    fun unavailable(preset: QualityPreset): Boolean = preset.isLossy && encoders != null && title != null && videoPlan(preset) == null

    /** The chosen quality can start: the copy always, a lossy preset once its encoder is known. */
    val qualityReady: Boolean get() = !preset.isLossy || videoPlan() != null

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
    data object NeedsNas : StartCheck
    data object NotEnoughSpace : StartCheck
    data object Busy : StartCheck

    /** The NAS cannot take the rip: unreachable, login refused, and so on. */
    data class NasUnavailable(val problem: NasProblem) : StartCheck

    /** The share has less room than the rip needs. */
    data class NasFull(val neededBytes: Long, val freeBytes: Long) : StartCheck

    /** A file of an earlier rip is in the way; ask before replacing it. */
    data class Exists(val job: RipJob) : StartCheck
    data class Ready(val job: RipJob) : StartCheck
}

class SourceViewModel(private val container: AppContainer, private val dvd: DvdSource) : ViewModel() {

    private val selection = MutableStateFlow(TrackSelection())
    private val showAllTitles = MutableStateFlow(false)
    private val storageChoice = MutableStateFlow<StorageTarget?>(null)
    private val presetChoice = MutableStateFlow<QualityPreset?>(null)
    private val starting = MutableStateFlow(false)
    private val space = MutableStateFlow(Space())
    private val catalog = MutableStateFlow<EncoderCatalog?>(null)

    /** What [refreshFolder] found. */
    private data class Space(val access: FolderAccess = FolderAccess.NONE, val local: Long? = null, val app: Long? = null)

    val state: StateFlow<SourceUiState?> = combine(
        combine(container.rips.source, container.rips.rip, catalog, ::Triple),
        container.ripDraft.metadata,
        container.settingsRepository.settings,
        combine(selection, showAllTitles, storageChoice, presetChoice, starting, ::Choices),
        space,
    ) { (source, rip, encoders), metadata, settings, choices, found ->
        SourceUiState(
            source = if (source.sourceOrNull == dvd) source else SourceState.Opening(dvd),
            selection = choices.selection,
            showAllTitles = choices.showAll,
            metadata = metadata,
            settings = settings,
            storage = choices.storage ?: settings.defaultStorage,
            preset = choices.preset ?: settings.defaultPreset,
            encoders = encoders,
            folderAccess = found.access,
            localFreeBytes = found.local,
            appFreeBytes = found.app,
            ripRunning = rip is RipState.Running,
            starting = choices.starting,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private data class Choices(
        val selection: TrackSelection,
        val showAll: Boolean,
        val storage: StorageTarget?,
        val preset: QualityPreset?,
        val starting: Boolean,
    )

    init {
        container.rips.open(dvd)
        viewModelScope.launch { catalog.value = withContext(Dispatchers.IO) { container.encoders.catalog() } }
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

    /** Where this rip goes, instead of the default from the settings. */
    fun setStorage(storage: StorageTarget) {
        storageChoice.value = storage
    }

    fun retry() = container.rips.open(dvd)

    /** The quality of this rip, instead of the default from the settings. */
    fun setPreset(preset: QualityPreset) {
        presetChoice.value = preset
    }

    /** Called on every resume: the folder grant can be withdrawn and the space used while the app is away. */
    fun refreshFolder() {
        viewModelScope.launch {
            val uri = container.settingsRepository.current().local.treeUri
            val access = when {
                uri == null -> FolderAccess.NONE
                withContext(Dispatchers.IO) { container.localFolders.hasAccess(uri) } -> FolderAccess.GRANTED
                else -> FolderAccess.REVOKED
            }
            val local = if (access == FolderAccess.GRANTED && uri != null) {
                withContext(Dispatchers.IO) { FreeSpace.of(container.appContext, uri.toUri()) }
            } else {
                null
            }
            space.value = Space(access, local, withContext(Dispatchers.IO) { FreeSpace.ofAppStorage(container.appContext) })
        }
    }

    /**
     * Checks what the rip needs before it starts. For the NAS that means connecting to it: is it
     * there, does the login work, is there room, and is an earlier rip of the film in the way.
     */
    suspend fun prepareStart(): StartCheck {
        val current = state.value ?: return StartCheck.Busy
        val title = current.title ?: return StartCheck.Busy
        val metadata = current.metadata ?: return StartCheck.NeedsMetadata
        if (current.needsFolder) return StartCheck.NeedsFolder
        if (current.needsNas) return StartCheck.NeedsNas
        val destination = current.destination ?: return StartCheck.NeedsMetadata
        if (!current.enoughSpace) return StartCheck.NotEnoughSpace
        if (current.ripRunning || current.starting || !current.qualityReady) return StartCheck.Busy
        val job = RipJob(
            source = dvd,
            sourceName = current.ready?.name,
            metadata = metadata,
            title = title,
            audio = current.audio,
            subtitles = current.subtitles,
            lpcmToFlac = current.settings.convertLpcmToFlac,
            estimatedBytes = current.estimatedBytes ?: 0,
            destination = destination,
            quality = current.preset,
            video = current.videoPlan(),
        )
        val local = destination.local
        val localExists = local != null && withContext(Dispatchers.IO) {
            runCatching { LocalOutput.existing(container.appContentResolver, local.treeUri.toUri(), local.path) != null }.getOrDefault(false)
        }
        val nas = destination.nas ?: return if (localExists) StartCheck.Exists(job) else StartCheck.Ready(job)

        starting.value = true
        val needed = current.neededBytes ?: 0
        val password = container.secretStore.load().nasPassword
        return try {
            withContext(Dispatchers.IO) {
                Nas.connect(nas.target(password)).use { share ->
                    share.checkWritable(nas.path.baseSegments)
                    val free = share.freeBytes()
                    when {
                        free < needed -> StartCheck.NasFull(needed, free)
                        localExists || share.exists(nas.path.relativeFile) -> StartCheck.Exists(job)
                        else -> StartCheck.Ready(job)
                    }
                }
            }
        } catch (e: NasException) {
            StartCheck.NasUnavailable(e.problem)
        } finally {
            starting.value = false
        }
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
