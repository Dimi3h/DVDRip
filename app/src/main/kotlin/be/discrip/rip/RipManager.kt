package be.discrip.rip

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import androidx.core.net.toUri
import be.discrip.core.css.CssAuthenticationException
import be.discrip.core.disc.DiscProbe
import be.discrip.core.disc.DiscProbeException
import be.discrip.core.disc.DriveProblem
import be.discrip.core.disc.FileBlockDevice
import be.discrip.core.dvd.AudioTrackPlan
import be.discrip.core.dvd.DvdReadException
import be.discrip.core.dvd.DvdTitle
import be.discrip.core.dvd.MainFeature
import be.discrip.core.dvd.MkvSummary
import be.discrip.core.dvd.ProgressTracker
import be.discrip.core.dvd.RipFailure
import be.discrip.core.dvd.RipOutcome
import be.discrip.core.dvd.RipProgress
import be.discrip.core.dvd.SubtitleTrackPlan
import be.discrip.core.estimate.SizeEstimator
import be.discrip.core.history.PendingUpload
import be.discrip.core.history.RipRecord
import be.discrip.core.history.RipResult
import be.discrip.core.model.MovieMetadata
import be.discrip.core.model.QualityPreset
import be.discrip.core.naming.RipPath
import be.discrip.core.nas.Nas
import be.discrip.core.nas.NasException
import be.discrip.core.nas.NasProblem
import be.discrip.core.nas.NasShare
import be.discrip.core.nas.NasTarget
import be.discrip.core.nas.TransferProgress
import be.discrip.core.nas.TransferTracker
import be.discrip.core.plex.PlexException
import be.discrip.core.plex.PlexPublication
import be.discrip.core.plex.PlexPublisher
import be.discrip.core.plex.PlexResult
import be.discrip.core.plex.result
import be.discrip.core.settings.AppSettings
import be.discrip.core.video.VideoEncoding
import be.discrip.core.video.VideoPlan
import be.discrip.data.HistoryStore
import be.discrip.data.Secrets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "DiscRip"

/** A DVD to rip: an ISO image or a VIDEO_TS folder picked in the document UI, or the disc in the USB drive. */
sealed interface DvdSource {
    data class Image(val uri: Uri) : DvdSource
    data class Folder(val uri: Uri) : DvdSource

    /** The disc in the USB drive, as it was when it went in ([be.discrip.core.disc.DiscState.Ready.insertion]). */
    data class Drive(val insertion: Long) : DvdSource
}

enum class SourceProblem {
    /** The file or folder cannot be opened, for example because access was withdrawn. */
    CANNOT_OPEN,

    /** There is no VIDEO_TS on the image or in the folder. */
    NOT_DVD_VIDEO,

    /** The DVD's structure (IFO files) cannot be read. */
    UNREADABLE,

    /** The DVD has no playable titles. */
    NO_TITLES,

    /** A rip from another DVD is running. */
    BUSY,

    /** The disc is no longer in the drive, or the drive is gone. */
    DISC_GONE,

    /** The drive's region is not one the disc allows (or was never set), so it refuses the disc. */
    REGION_MISMATCH,

    /** The drive would not authenticate for the CSS-protected disc. */
    CSS_FAILED,

    /** The native rip engine could not be loaded on this device. */
    ENGINE_UNAVAILABLE,
}

sealed interface SourceState {
    data object None : SourceState

    data class Opening(val source: DvdSource) : SourceState

    data class Ready(val source: DvdSource, val name: String?, val titles: List<DvdTitle>) : SourceState {
        val mainTitle: DvdTitle? by lazy { MainFeature.choose(titles) }
    }

    data class Failed(val source: DvdSource, val problem: SourceProblem, val detail: String?) : SourceState

    val sourceOrNull: DvdSource?
        get() = when (this) {
            None -> null
            is Opening -> source
            is Ready -> source
            is Failed -> source
        }
}

/** A rip as the user asked for it. */
data class RipJob(
    val source: DvdSource,
    val sourceName: String?,
    val metadata: MovieMetadata,
    val title: DvdTitle,
    val audio: List<AudioTrackPlan>,
    val subtitles: List<SubtitleTrackPlan>,
    val lpcmToFlac: Boolean,
    /** The size the rip is expected to reach; the free-space check asks for 10 % more. */
    val estimatedBytes: Long,
    val destination: RipDestination,
    val quality: QualityPreset = QualityPreset.LOSSLESS,
    /** The encoders and bit rate of a lossy [quality]; null for the copy. */
    val video: VideoPlan? = null,
)

enum class RipStage {
    /** Connecting to the NAS and checking its free space. */
    PREPARING,
    RIPPING,
    /** Copying the finished file to the NAS. */
    UPLOADING,
    /** Waiting for Plex to list the film. */
    PLEX,
}

/** What happened to the NAS part of a rip. */
sealed interface NasOutcome {
    /** The file is on the share, at [location]. */
    data class Stored(val location: String) : NasOutcome

    /**
     * The file did not get there. With [pending], the upload can be tried again from the history:
     * the finished MKV waits in the app's storage, or stays in the local folder.
     */
    data class Failed(val problem: NasProblem, val message: String, val pending: Boolean) : NasOutcome

    /** The upload was stopped; a copy in the local folder stays. */
    data object Cancelled : NasOutcome
}

/** What happened when Plex was told about the rip. */
sealed interface PlexOutcome {
    data class Published(val publication: PlexPublication) : PlexOutcome

    /** The user stopped waiting; Plex had been asked to scan. */
    data object NotAwaited : PlexOutcome

    data class Failed(val reason: PlexResult, val message: String) : PlexOutcome

    val result: PlexResult
        get() = when (this) {
            is Published -> publication.result
            NotAwaited -> PlexResult.NOT_SEEN
            is Failed -> reason
        }

    /** The film in Plex Web, when Plex listed it. */
    val link: String? get() = ((this as? Published)?.publication as? PlexPublication.Added)?.link
}

sealed interface RipState {
    data object Idle : RipState

    data class Running(
        val job: RipJob,
        val progress: RipProgress?,
        val stage: RipStage = RipStage.RIPPING,
        val upload: TransferProgress? = null,
        val cancelling: Boolean = false,
        /** The disc from the drive is done and came out; the next can go in while the file travels. */
        val discEjected: Boolean = false,
        /** The encoder a lossy preset uses; null for the copy. */
        val encoder: VideoEncoding? = null,
    ) : RipState

    /** The disc was ripped; [nas] and [plex] tell whether the file also got where it was meant to. */
    data class Succeeded(
        val job: RipJob,
        val result: RipOutcome.Success,
        val file: MkvSummary,
        /** The file in the local folder, when it stays there. */
        val localFile: String?,
        /** A disc from the drive came out of it after the rip, ready for the next. */
        val discEjected: Boolean = false,
        /** Null for a rip that stays local. */
        val nas: NasOutcome? = null,
        /** Null when Plex was not asked. */
        val plex: PlexOutcome? = null,
        /** The encoder a lossy preset used; null for the copy. */
        val encoder: VideoEncoding? = null,
    ) : RipState

    /**
     * [neededBytes] and [freeBytes] are set when the rip did not start for lack of space,
     * [nasProblem] when the NAS was the trouble (with [RipFailure.NO_SPACE]: the NAS is too full).
     */
    data class Failed(
        val job: RipJob,
        val reason: RipFailure,
        val message: String,
        val neededBytes: Long? = null,
        val freeBytes: Long? = null,
        val nasProblem: NasProblem? = null,
    ) : RipState
}

/** The rip still reads its DVD: it is preparing or ripping, not yet uploading or waiting for Plex. */
val RipState.usesDisc: Boolean
    get() = this is RipState.Running && (stage == RipStage.PREPARING || stage == RipStage.RIPPING)

/** An upload started again from the history. */
data class Retry(val recordId: String, val stage: RipStage, val progress: TransferProgress?)

/**
 * Owns the open DVD and the running rip, so both outlive the screens that show them. The native
 * engine allows one DVD at a time and its calls block, so they all run on one background thread;
 * the upload to the NAS and the wait for Plex run on other threads.
 */
class RipManager(
    private val resolver: ContentResolver,
    private val labels: RipLabels,
    private val scope: CoroutineScope,
    /** NAS rips wait here until they are uploaded. */
    private val stagingDir: File,
    private val credentials: suspend () -> Secrets,
    private val settings: suspend () -> AppSettings,
    private val history: HistoryStore,
    private val plex: PlexPublisher,
    private val outputs: RipOutputs = RipOutputs { _, target -> LocalOutput.create(resolver, target.treeUri.toUri(), target.path) },
    private val connectNas: (NasTarget) -> NasShare = { Nas.connect(it) },
    /** Lends the disc in the USB drive ([be.discrip.drive.DriveManager.lease]). */
    private val drives: (insertion: Long) -> DriveDisc? = { null },
    /** A rip went well; called once it is in the history. */
    private val onSucceeded: (RipJob) -> Unit = {},
) {
    private val engine = Dispatchers.IO.limitedParallelism(1)

    private val sourceState = MutableStateFlow<SourceState>(SourceState.None)
    val source: StateFlow<SourceState> = sourceState.asStateFlow()

    private val ripState = MutableStateFlow<RipState>(RipState.Idle)
    val rip: StateFlow<RipState> = ripState.asStateFlow()

    private val retryState = MutableStateFlow<Retry?>(null)
    val retry: StateFlow<Retry?> = retryState.asStateFlow()

    /** Only touched on [engine]. */
    private var session: DvdSession? = null

    /** The drive's disc [session] reads, when it does; only touched on [engine]. */
    private var drive: DriveDisc? = null

    /** The session a rip is running on, for [cancel]. */
    @Volatile
    private var ripping: DvdSession? = null

    /** Set by [cancel] for the rip that is running; a new flag for every rip. */
    @Volatile
    private var cancelRequested = AtomicBoolean(false)

    /** The wait for Plex to list the film, which [cancel] ends. */
    @Volatile
    private var plexWait: Job? = null

    /**
     * Opens [dvd] and lists its titles, closing the DVD that was open; a no-op if it is open
     * already. The next disc can be opened while a rip's file still travels to the NAS.
     */
    fun open(dvd: DvdSource) {
        val current = sourceState.value
        if (current.sourceOrNull == dvd && current !is SourceState.Failed) return
        if (ripState.value.usesDisc) {
            sourceState.value = SourceState.Failed(dvd, SourceProblem.BUSY, null)
            return
        }
        sourceState.value = SourceState.Opening(dvd)
        scope.launch(engine) {
            DvdSession.configure()
            closeSession()
            sourceState.value = try {
                val (opened, name) = when (dvd) {
                    is DvdSource.Image -> openImage(dvd.uri)
                    is DvdSource.Folder -> openFolder(dvd.uri)
                    is DvdSource.Drive -> openDrive(dvd)
                }
                val titles = try {
                    opened.titles()
                } catch (e: DvdReadException) {
                    opened.close()
                    throw e
                }
                if (titles.isEmpty()) {
                    opened.close()
                    SourceState.Failed(dvd, SourceProblem.NO_TITLES, null)
                } else {
                    session = opened
                    SourceState.Ready(dvd, name, titles)
                }
            } catch (e: NotDvdVideoException) {
                SourceState.Failed(dvd, SourceProblem.NOT_DVD_VIDEO, null)
            } catch (e: DiscGoneException) {
                SourceState.Failed(dvd, SourceProblem.DISC_GONE, null)
            } catch (e: CssAuthenticationException) {
                val problem = if (e.regionMismatch) SourceProblem.REGION_MISMATCH else SourceProblem.CSS_FAILED
                SourceState.Failed(dvd, driveSourceProblem() ?: problem, e.message)
            } catch (e: DvdReadException) {
                SourceState.Failed(dvd, driveSourceProblem() ?: SourceProblem.UNREADABLE, e.message)
            } catch (e: IOException) {
                SourceState.Failed(dvd, driveSourceProblem() ?: SourceProblem.CANNOT_OPEN, e.message)
            } catch (e: SecurityException) {
                SourceState.Failed(dvd, SourceProblem.CANNOT_OPEN, e.message)
            } catch (e: IllegalArgumentException) {
                // A document URI the provider no longer knows.
                SourceState.Failed(dvd, SourceProblem.CANNOT_OPEN, e.message)
            } catch (e: LinkageError) {
                // libdiscrip.so is arm64-v8a only.
                SourceState.Failed(dvd, SourceProblem.ENGINE_UNAVAILABLE, e.message)
            }
            // A drive whose disc did not open goes back to being polled.
            if (session == null) releaseDrive()
        }
    }

    /**
     * Closes the open DVD, unless a rip still needs it (it is closed with the next [open]).
     * Returns the work that closes it, or null when there is nothing to close.
     */
    fun close(dvd: DvdSource): Job? {
        if (sourceState.value.sourceOrNull != dvd || ripState.value.usesDisc) return null
        sourceState.value = SourceState.None
        return scope.launch(engine) {
            if (sourceState.value == SourceState.None) closeSession()
        }
    }

    /** Starts [job] on its DVD, which must be the open one. False when a rip is already running. */
    fun start(job: RipJob): Boolean {
        val source = sourceState.value
        if (ripState.value is RipState.Running || source !is SourceState.Ready || source.source != job.source) return false
        val cancelled = AtomicBoolean(false)
        cancelRequested = cancelled
        ripState.value = RipState.Running(job, progress = null, stage = if (job.destination.nas != null) RipStage.PREPARING else RipStage.RIPPING)
        scope.launch(engine) {
            val (state, pending) = runRip(job, cancelled)
            // In the history before anyone sees the rip end.
            record(job, state, pending)
            if (state is RipState.Succeeded) onSucceeded(job)
            ripState.value = state
        }
        return true
    }

    /** Stops the rip or the upload; while waiting for Plex, stops waiting (Plex scans anyway). */
    fun cancel() {
        val running = ripState.value as? RipState.Running ?: return
        if (running.stage == RipStage.PLEX) {
            plexWait?.cancel()
            return
        }
        ripState.update { if (it is RipState.Running) it.copy(cancelling = true) else it }
        cancelRequested.set(true)
        ripping?.cancel()
    }

    /** Clears a finished rip from the screen. */
    fun dismiss() {
        if (ripState.value !is RipState.Running) ripState.value = RipState.Idle
    }

    /**
     * Uploads a rip that waits in the app's storage again, then tells Plex, with the NAS and Plex
     * settings of now. False when another upload from the history is running.
     */
    fun retryUpload(record: RipRecord): Boolean {
        val pending = record.pendingUpload ?: return false
        if (!retryState.compareAndSet(null, Retry(record.id, RipStage.UPLOADING, null))) return false
        scope.launch(Dispatchers.IO) {
            try {
                val current = settings()
                val secrets = credentials()
                val staged = pending.stagedFile?.let(::File)
                val source = staged?.let { file -> { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) } }
                    ?: pending.localDocument?.let { uri -> { resolver.openFileDescriptor(uri.toUri(), "r") ?: throw FileNotFoundException(uri) } }
                val destination = NasDestination(current.nas, RipPath.of(pending.nasPath))
                val nas = when {
                    source == null -> NasOutcome.Failed(NasProblem.FAILED, "Nothing to upload", pending = false)
                    !current.nas.isConfigured -> NasOutcome.Failed(NasProblem.UNREACHABLE, "The NAS is not set up", pending = true)
                    else -> upload(destination, secrets.nasPassword, source, pending = true, AtomicBoolean(false)) { progress ->
                        retryState.update { it?.copy(progress = progress) }
                    }
                }
                var plexOutcome: PlexOutcome? = null
                if (nas is NasOutcome.Stored) {
                    staged?.delete()
                    val section = pending.plexSectionKey
                    if (section != null && current.plex.isConfigured) {
                        retryState.update { it?.copy(stage = RipStage.PLEX) }
                        plexOutcome = publish(destination, PlexDestination(current.plex.serverUrl, section), secrets.plexToken)
                    }
                }
                history.update(record.id) { old ->
                    when (nas) {
                        is NasOutcome.Stored -> old.copy(
                            nasFile = nas.location,
                            nasProblem = null,
                            pendingUpload = null,
                            plex = plexOutcome?.result ?: old.plex,
                            plexLink = plexOutcome?.link ?: old.plexLink,
                        )
                        is NasOutcome.Failed -> old.copy(nasProblem = nas.problem)
                        NasOutcome.Cancelled -> old
                    }
                }
            } finally {
                retryState.value = null
            }
        }
        return true
    }

    /**
     * Gives up on the upload: deletes the file a failed upload left in the app's storage (a copy
     * in the local folder stays); the history keeps the rip.
     */
    fun discardPending(record: RipRecord) {
        val pending = record.pendingUpload ?: return
        if (retryState.value?.recordId == record.id) return
        scope.launch(Dispatchers.IO) {
            pending.stagedFile?.let { File(it).delete() }
            history.update(record.id) { it.copy(pendingUpload = null) }
        }
    }

    /** Removes a rip from the history, with the file a failed upload left in the app's storage. */
    fun forget(record: RipRecord) {
        if (retryState.value?.recordId == record.id) return
        scope.launch(Dispatchers.IO) {
            record.pendingUpload?.stagedFile?.let { File(it).delete() }
            history.remove(record.id)
        }
    }

    /** The rip and, for the NAS, the upload and Plex; also what a failed upload needs to be tried again. */
    private suspend fun runRip(job: RipJob, cancelled: AtomicBoolean): Pair<RipState, PendingUpload?> {
        val drive = job.source as? DvdSource.Drive
        var discDone = false
        try {
            val dvd = session ?: return RipState.Failed(job, RipFailure.FAILED, "No DVD is open") to null
            val destination = job.destination
            val needed = SizeEstimator.requiredFreeBytes(job.estimatedBytes)
            val secrets = credentials()

            // The NAS first: a rip should not start when its file cannot get there.
            var direct: NasShare? = null
            destination.nas?.let { nas ->
                val share = try {
                    connectNas(nas.target(secrets.nasPassword))
                } catch (e: NasException) {
                    return nasFailure(job, e) to null
                }
                val free = try {
                    share.checkWritable(nas.path.baseSegments)
                    share.freeBytes()
                } catch (e: NasException) {
                    share.close()
                    return nasFailure(job, e) to null
                }
                if (free < needed) {
                    share.close()
                    return RipState.Failed(job, RipFailure.NO_SPACE, "", needed, free, NasProblem.FULL) to null
                }
                if (destination.writesDirectly) direct = share else share.close()
                ripState.update { if (it is RipState.Running) it.copy(stage = RipStage.RIPPING) else it }
            }

            val staged = if (destination.local == null && !destination.writesDirectly) File(stagingDir, "${UUID.randomUUID()}.mkv") else null
            try {
                val output = try {
                    when {
                        destination.local != null -> outputs.create(job, destination.local)
                        direct != null -> NasOutput(direct, destination.nas!!.path)
                        else -> StagedOutput(staged!!.also { stagingDir.mkdirs() })
                    }
                } catch (e: NasException) {
                    return nasFailure(job, e) to null
                } catch (e: Exception) {
                    // SecurityException when access was withdrawn; IOException or IllegalStateException
                    // from the provider when the storage is gone.
                    return RipState.Failed(job, RipFailure.OUTPUT_UNAVAILABLE, e.message.orEmpty()) to null
                }
                var ripped = ripInto(job, dvd, output, needed, cancelled)
                if (drive != null) {
                    // Done with the disc; after a good rip it comes out, for the next one.
                    discDone = true
                    val ejected = finishDisc(drive, eject = ripped is RipState.Succeeded)
                    if (ripped is RipState.Succeeded) ripped = ripped.copy(discEjected = ejected)
                    ripState.update { if (it is RipState.Running) it.copy(discEjected = ejected) else it }
                }
                if (ripped !is RipState.Succeeded) return ripped to null

                // From here on the disc is done; the engine thread is free while the file travels.
                return withContext(Dispatchers.IO) { deliver(ripped, output, staged, secrets, cancelled) }
            } finally {
                direct?.close()
            }
        } finally {
            // A rip from the drive that stopped before reading it gives the drive back all the same.
            if (drive != null && !discDone) finishDisc(drive, eject = false)
        }
    }

    /** The engine's part: the rip into [output], read back, under its final name. */
    private fun ripInto(job: RipJob, dvd: DvdSession, output: RipOutput, needed: Long, cancelled: AtomicBoolean): RipState {
        var free: Long? = null
        var encoder: VideoEncoding? = null
        val (outcome, summary) = try {
            output.open().use { file ->
                if (file is EngineFile.Descriptor) {
                    free = freeBytes(file.descriptor)
                    if (free != null && free < needed) return@use null to null
                }
                ripping = dvd
                // A locked tray keeps the disc in while it is read.
                drive?.preventRemoval(true)
                val outcome = try {
                    // An encoder that will not start fails before anything is written, so the next can take over.
                    val encoders: List<VideoEncoding?> = job.video?.encoders ?: listOf(null)
                    var attempt: RipOutcome? = null
                    for ((index, candidate) in encoders.withIndex()) {
                        encoder = candidate
                        ripState.update { if (it is RipState.Running) it.copy(encoder = candidate) else it }
                        val tracker = ProgressTracker(SystemClock::elapsedRealtime)
                        attempt = dvd.rip(request(job, candidate), file, cancelled::get) { engine ->
                            val progress = tracker.update(engine.sectorsDone, engine.sectorsTotal, engine.outputBytes, engine.positionMs, engine.framesEncoded)
                            ripState.update { if (it is RipState.Running) it.copy(progress = progress) else it }
                        }
                        val unavailable = (attempt as? RipOutcome.Failure)?.reason == RipFailure.ENCODER_UNAVAILABLE
                        if (!unavailable || index == encoders.lastIndex) break
                        Log.w(TAG, "${candidate?.component} would not start (${attempt.message}); trying the next encoder")
                    }
                    checkNotNull(attempt)
                } finally {
                    ripping = null
                    drive?.preventRemoval(false)
                }
                outcome to if (outcome is RipOutcome.Success) DvdSession.probe(file) else null
            }
        } catch (e: NasException) {
            output.discard()
            return nasFailure(job, e)
        } catch (e: IOException) {
            output.discard()
            return RipState.Failed(job, RipFailure.WRITE_FAILED, e.message.orEmpty())
        } catch (e: SecurityException) {
            output.discard()
            return RipState.Failed(job, RipFailure.OUTPUT_UNAVAILABLE, e.message.orEmpty())
        }

        if (outcome == null) {
            output.discard()
            return RipState.Failed(job, RipFailure.NO_SPACE, "", neededBytes = needed, freeBytes = free)
        }
        if (outcome is RipOutcome.Failure) {
            output.discard()
            // The drive stopped the rip: say why, rather than "cancelled".
            driveFailure()?.let { return RipState.Failed(job, it, outcome.message) }
            // On the NAS the engine only sees an errno; the NAS client knows what went wrong.
            (output as? NasOutput)?.error?.let { if (outcome.reason != RipFailure.CANCELLED) return nasFailure(job, it) }
            return RipState.Failed(job, outcome.reason, outcome.message)
        }
        val file = summary?.getOrNull()
        if (outcome !is RipOutcome.Success || file == null || file.video.isEmpty()) {
            output.discard()
            return RipState.Failed(job, RipFailure.VERIFY_FAILED, summary?.exceptionOrNull()?.message.orEmpty())
        }
        return try {
            output.complete()
            RipState.Succeeded(job, outcome, file, localFile = job.destination.local?.displayPath, encoder = encoder)
        } catch (e: NasException) {
            output.discard()
            nasFailure(job, e)
        } catch (e: Exception) {
            output.discard()
            RipState.Failed(job, RipFailure.WRITE_FAILED, e.message.orEmpty())
        }
    }

    /** Gets a ripped file onto the NAS (unless the engine wrote it there) and into Plex. */
    private suspend fun deliver(
        ripped: RipState.Succeeded,
        output: RipOutput,
        staged: File?,
        secrets: Secrets,
        cancelled: AtomicBoolean,
    ): Pair<RipState, PendingUpload?> {
        val destination = ripped.job.destination
        val nasDestination = destination.nas ?: return ripped to null
        // What another try needs: the staged file, or the document of the local copy.
        val pending = when {
            destination.writesDirectly -> null
            staged != null -> PendingUpload(stagedFile = staged.path, nasPath = nasDestination.path.relativeFile, plexSectionKey = destination.plex?.sectionKey)
            output is LocalOutput -> PendingUpload(
                localDocument = output.document.toString(),
                nasPath = nasDestination.path.relativeFile,
                plexSectionKey = destination.plex?.sectionKey,
            )
            else -> null
        }
        val nas = if (destination.writesDirectly) {
            NasOutcome.Stored(nasDestination.location)
        } else {
            ripState.update { if (it is RipState.Running) it.copy(stage = RipStage.UPLOADING) else it }
            upload(nasDestination, secrets.nasPassword, (output as LocalRipOutput)::read, pending = pending != null, cancelled) { progress ->
                ripState.update { if (it is RipState.Running) it.copy(upload = progress) else it }
            }
        }
        when (nas) {
            is NasOutcome.Stored -> staged?.delete()
            NasOutcome.Cancelled -> if (staged != null) {
                // Without a local copy, stopping the upload stops the rip: nothing is kept.
                staged.delete()
                return RipState.Failed(ripped.job, RipFailure.CANCELLED, "The upload was stopped") to null
            }
            is NasOutcome.Failed -> Unit
        }
        val plexDestination = destination.plex
        val plexOutcome = if (nas is NasOutcome.Stored && plexDestination != null) {
            coroutineScope {
                val waiting = async(start = CoroutineStart.LAZY) { publish(nasDestination, plexDestination, secrets.plexToken) }
                plexWait = waiting
                ripState.update { if (it is RipState.Running) it.copy(stage = RipStage.PLEX) else it }
                try {
                    waiting.await()
                } catch (e: CancellationException) {
                    ensureActive()
                    PlexOutcome.NotAwaited
                } finally {
                    plexWait = null
                }
            }
        } else {
            null
        }
        return ripped.copy(nas = nas, plex = plexOutcome) to if (nas is NasOutcome.Failed) pending else null
    }

    /** Copies a finished file from this device to the NAS: as .part, its size compared, then renamed. */
    private fun upload(
        destination: NasDestination,
        password: String,
        source: () -> ParcelFileDescriptor,
        pending: Boolean,
        cancelled: AtomicBoolean,
        onProgress: (TransferProgress) -> Unit,
    ): NasOutcome = try {
        ParcelFileDescriptor.AutoCloseInputStream(source()).channel.use { channel ->
            val size = channel.size()
            val tracker = TransferTracker(SystemClock::elapsedRealtime)
            onProgress(tracker.update(0, size))
            connectNas(destination.target(password)).use { share ->
                share.ensureFolders(destination.path.folderSegments)
                val done = share.upload(
                    path = destination.path.relativeFile,
                    size = size,
                    read = { buffer, position -> channel.read(ByteBuffer.wrap(buffer), position) },
                    onProgress = { bytes -> onProgress(tracker.update(bytes, size)) },
                    isCancelled = cancelled::get,
                )
                if (done) NasOutcome.Stored(destination.location) else NasOutcome.Cancelled
            }
        }
    } catch (e: NasException) {
        NasOutcome.Failed(e.problem, e.message.orEmpty(), pending)
    } catch (e: IOException) {
        // The file on this device could not be read.
        NasOutcome.Failed(NasProblem.FAILED, e.message.orEmpty(), pending)
    } catch (e: SecurityException) {
        // The local folder's grant was withdrawn.
        NasOutcome.Failed(NasProblem.FAILED, e.message.orEmpty(), pending)
    }

    private suspend fun publish(nas: NasDestination, destination: PlexDestination, token: String): PlexOutcome = try {
        PlexOutcome.Published(
            plex.publish(destination.serverUrl, token, destination.sectionKey, nas.shareName, nas.path.folderSegments, nas.path.fileName),
        )
    } catch (e: PlexException) {
        PlexOutcome.Failed(e.result, e.message.orEmpty())
    }

    private fun nasFailure(job: RipJob, e: NasException): RipState.Failed {
        val reason = if (e.problem == NasProblem.FULL) RipFailure.NO_SPACE else RipFailure.OUTPUT_UNAVAILABLE
        return RipState.Failed(job, reason, e.message.orEmpty(), nasProblem = e.problem)
    }

    /** Adds the finished rip to the history. */
    private suspend fun record(job: RipJob, state: RipState, pending: PendingUpload?) {
        val metadata = job.metadata
        // The encoder that did the work, or the first one tried.
        val encoder = (state as? RipState.Succeeded)?.encoder ?: job.video?.encoder
        val base = RipRecord(
            id = UUID.randomUUID().toString(),
            endedAt = System.currentTimeMillis(),
            title = metadata.title,
            year = metadata.year,
            sourceName = job.sourceName,
            titleNumber = job.title.number,
            storage = job.destination.storage,
            preset = job.quality,
            videoCodec = encoder?.codec,
            videoEncoder = encoder?.component,
            videoHardware = encoder?.hardware,
        )
        val record = when (state) {
            is RipState.Succeeded -> base.copy(
                bytes = state.result.outputBytes,
                durationMs = state.file.durationMs,
                badSectors = state.result.badSectorCount,
                warnings = state.result.warnings,
                localFile = state.localFile,
                nasFile = (state.nas as? NasOutcome.Stored)?.location,
                nasProblem = (state.nas as? NasOutcome.Failed)?.problem,
                plex = state.plex?.result,
                plexLink = state.plex?.link,
                pendingUpload = pending,
            )
            is RipState.Failed -> base.copy(
                result = if (state.reason == RipFailure.CANCELLED) RipResult.CANCELLED else RipResult.FAILED,
                failure = state.reason,
                message = state.message.ifBlank { null },
                nasProblem = state.nasProblem,
            )
            else -> return
        }
        history.add(record)
    }

    private fun request(job: RipJob, encoder: VideoEncoding?) = RipRequest(
        title = job.title.number,
        audio = job.audio.map { RipTrack(it.streamId, it.language, labels.trackName(it), it.flags) },
        subtitles = job.subtitles.map { RipTrack(it.streamId, it.language, labels.subtitleName(it), it.flags) },
        lpcmToFlac = job.lpcmToFlac,
        movieTitle = job.metadata.title,
        releaseDate = job.metadata.year?.toString(),
        chapterNames = (1..job.title.chapterStartsMs.size).map(labels::chapterName),
        tags = filmTags(job.metadata),
        video = encoder,
        videoBitRateKbps = job.video?.bitRateKbps ?: 0,
    )

    /** Matroska's IMDB, TMDB and SUMMARY tags, which media centres such as Kodi and Jellyfin read. */
    private fun filmTags(metadata: MovieMetadata): Map<String, String> {
        val film = metadata.normalized()
        return buildMap {
            film.imdbId?.let { put("IMDB", it) }
            film.tmdbId?.let { put("TMDB", "movie/$it") }
            film.overview?.takeIf { it.isNotBlank() }?.let { put("SUMMARY", it) }
        }
    }

    /** Free space on the file system of [file], or null when it cannot be told. */
    private fun freeBytes(file: ParcelFileDescriptor): Long? = try {
        Os.fstatvfs(file.fileDescriptor).let { it.f_bavail * it.f_frsize }
    } catch (e: ErrnoException) {
        null
    }

    private fun closeSession() {
        session?.close()
        session = null
        releaseDrive()
    }

    /** Gives the drive back, to be polled again. */
    private fun releaseDrive() {
        drive?.close()
        drive = null
    }

    /**
     * The disc in the drive, authenticated when it is (or may be) CSS protected, so a region the
     * drive refuses shows before the rip; named by its volume label.
     */
    private fun openDrive(source: DvdSource.Drive): Pair<DvdSession, String?> {
        val disc = drives(source.insertion) ?: throw DiscGoneException()
        drive = disc
        if (!disc.info.isDvdVideo) throw NotDvdVideoException()
        if (disc.info.cssProtected != false) {
            try {
                disc.unlock()
            } catch (e: CssAuthenticationException) {
                // A disc that did not say whether it is protected may not be; its reads will tell.
                if (e.regionMismatch || disc.info.cssProtected == true) throw e
            }
        }
        return DvdSession.openDrive(disc) to disc.info.label
    }

    /** Closes the drive's disc after a rip, ejecting it first when asked; true when it came out. */
    private fun finishDisc(source: DvdSource.Drive, eject: Boolean): Boolean {
        session?.close()
        session = null
        val ejected = eject && drive?.eject() == true
        releaseDrive()
        sourceState.update { if (it.sourceOrNull == source) SourceState.None else it }
        return ejected
    }

    private fun driveSourceProblem(): SourceProblem? = when (drive?.problem) {
        null -> null
        DriveProblem.DISCONNECTED, DriveProblem.DISC_CHANGED -> SourceProblem.DISC_GONE
        DriveProblem.REGION_MISMATCH -> SourceProblem.REGION_MISMATCH
        DriveProblem.CSS_FAILED -> SourceProblem.CSS_FAILED
    }

    private fun driveFailure(): RipFailure? = when (drive?.problem) {
        null -> null
        DriveProblem.DISCONNECTED -> RipFailure.DRIVE_DISCONNECTED
        DriveProblem.DISC_CHANGED -> RipFailure.DISC_CHANGED
        DriveProblem.REGION_MISMATCH -> RipFailure.REGION_MISMATCH
        DriveProblem.CSS_FAILED -> RipFailure.CSS_REFUSED
    }

    private class NotDvdVideoException : Exception()

    private class DiscGoneException : Exception()

    /** The image's volume label names it; its file name when the label is missing. */
    private fun openImage(uri: Uri): Pair<DvdSession, String?> {
        val image = resolver.openFileDescriptor(uri, "r") ?: throw IOException("No file at $uri")
        image.use {
            val disc = try {
                FileBlockDevice(ParcelFileDescriptor.AutoCloseInputStream(image.dup()).channel).use(DiscProbe::probe)
            } catch (e: DiscProbeException) {
                throw NotDvdVideoException()
            }
            if (!disc.isDvdVideo) throw NotDvdVideoException()
            val name = disc.label ?: displayName(uri)?.substringBeforeLast('.')
            return DvdSession.openImage(image) to name
        }
    }

    private fun openFolder(uri: Uri): Pair<DvdSession, String?> {
        val tree = SafDvdTree(resolver, uri)
        val hasVideoTs = tree.stat(listOf("VIDEO_TS", "VIDEO_TS.IFO")) != null || tree.stat(listOf("VIDEO_TS.IFO")) != null
        if (!hasVideoTs) throw NotDvdVideoException()
        return DvdSession.openFolder(tree) to folderName(uri)
    }

    /**
     * The picked folder's name; for a folder called VIDEO_TS, the one above it when the provider's
     * document id shows the path (as local storage does: `primary:Films/The Matrix/VIDEO_TS`).
     */
    private fun folderName(treeUri: Uri): String? {
        val path = DocumentsContract.getTreeDocumentId(treeUri).substringAfter(':').split('/').filter { it.isNotEmpty() }
        val name = path.lastOrNull()
        if (name != null && name.equals("VIDEO_TS", ignoreCase = true)) return path.getOrNull(path.size - 2)
        return name
    }

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}

/**
 * Deletes rips the app's storage holds for nothing: left by a crash, or by a failed upload the
 * history no longer offers again. Files from [before] on may belong to a rip running now.
 */
fun cleanStaging(stagingDir: File, records: List<RipRecord>, before: Long) {
    val kept = records.mapNotNull { it.pendingUpload?.stagedFile }.toSet()
    stagingDir.listFiles()?.forEach { file ->
        if (file.path !in kept && file.lastModified() < before) file.delete()
    }
}
