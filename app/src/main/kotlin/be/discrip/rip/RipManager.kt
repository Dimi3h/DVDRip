package be.discrip.rip

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import androidx.core.net.toUri
import be.discrip.core.disc.DiscProbe
import be.discrip.core.disc.DiscProbeException
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
import be.discrip.core.model.MovieMetadata
import be.discrip.core.naming.RipPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** A DVD picked in the document UI: an ISO image or a VIDEO_TS folder. */
sealed interface DvdSource {
    val uri: Uri

    data class Image(override val uri: Uri) : DvdSource
    data class Folder(override val uri: Uri) : DvdSource
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
    /** Where the file goes, relative to the local folder. */
    val path: RipPath,
    val treeUri: String,
)

sealed interface RipState {
    data object Idle : RipState

    data class Running(val job: RipJob, val progress: RipProgress?, val cancelling: Boolean = false) : RipState

    data class Succeeded(val job: RipJob, val result: RipOutcome.Success, val file: MkvSummary) : RipState

    /** [neededBytes] and [freeBytes] are set when the rip did not start for lack of space. */
    data class Failed(
        val job: RipJob,
        val reason: RipFailure,
        val message: String,
        val neededBytes: Long? = null,
        val freeBytes: Long? = null,
    ) : RipState
}

/**
 * Owns the open DVD and the running rip, so both outlive the screens that show them. The native
 * engine allows one DVD at a time and its calls block, so they all run on one background thread.
 */
class RipManager(
    private val resolver: ContentResolver,
    private val cacheDir: File,
    private val labels: RipLabels,
    private val scope: CoroutineScope,
    private val outputs: RipOutputs = RipOutputs { job -> LocalOutput.create(resolver, job.treeUri.toUri(), job.path) },
) {
    private val engine = Dispatchers.IO.limitedParallelism(1)

    private val sourceState = MutableStateFlow<SourceState>(SourceState.None)
    val source: StateFlow<SourceState> = sourceState.asStateFlow()

    private val ripState = MutableStateFlow<RipState>(RipState.Idle)
    val rip: StateFlow<RipState> = ripState.asStateFlow()

    /** Only touched on [engine]. */
    private var session: DvdSession? = null

    /** The session a rip is running on, for [cancel]. */
    @Volatile
    private var ripping: DvdSession? = null

    /** Set by [cancel] for the rip that is running; a new flag for every rip. */
    @Volatile
    private var cancelRequested = AtomicBoolean(false)

    /** Opens [dvd] and lists its titles, closing the DVD that was open; a no-op if it is open already. */
    fun open(dvd: DvdSource) {
        val current = sourceState.value
        if (current.sourceOrNull == dvd && current !is SourceState.Failed) return
        if (ripState.value is RipState.Running) {
            sourceState.value = SourceState.Failed(dvd, SourceProblem.BUSY, null)
            return
        }
        sourceState.value = SourceState.Opening(dvd)
        scope.launch(engine) {
            DvdSession.configure(cacheDir)
            closeSession()
            sourceState.value = try {
                val (opened, name) = when (dvd) {
                    is DvdSource.Image -> openImage(dvd.uri)
                    is DvdSource.Folder -> openFolder(dvd.uri)
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
            } catch (e: DvdReadException) {
                SourceState.Failed(dvd, SourceProblem.UNREADABLE, e.message)
            } catch (e: IOException) {
                SourceState.Failed(dvd, SourceProblem.CANNOT_OPEN, e.message)
            } catch (e: SecurityException) {
                SourceState.Failed(dvd, SourceProblem.CANNOT_OPEN, e.message)
            } catch (e: IllegalArgumentException) {
                // A document URI the provider no longer knows.
                SourceState.Failed(dvd, SourceProblem.CANNOT_OPEN, e.message)
            } catch (e: LinkageError) {
                // libdiscrip.so is arm64-v8a only.
                SourceState.Failed(dvd, SourceProblem.ENGINE_UNAVAILABLE, e.message)
            }
        }
    }

    /**
     * Closes the open DVD, unless a rip still needs it (it is closed with the next [open]).
     * Returns the work that closes it, or null when there is nothing to close.
     */
    fun close(dvd: DvdSource): Job? {
        if (sourceState.value.sourceOrNull != dvd || ripState.value is RipState.Running) return null
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
        ripState.value = RipState.Running(job, progress = null)
        scope.launch(engine) { ripState.value = runRip(job, cancelled) }
        return true
    }

    fun cancel() {
        if (ripState.value !is RipState.Running) return
        ripState.update { if (it is RipState.Running) it.copy(cancelling = true) else it }
        cancelRequested.set(true)
        ripping?.cancel()
    }

    /** Clears a finished rip from the screen. */
    fun dismiss() {
        if (ripState.value !is RipState.Running) ripState.value = RipState.Idle
    }

    private fun runRip(job: RipJob, cancelled: AtomicBoolean): RipState {
        val dvd = session ?: return RipState.Failed(job, RipFailure.FAILED, "No DVD is open")
        val output = try {
            outputs.create(job)
        } catch (e: Exception) {
            // SecurityException when access was withdrawn; IOException or IllegalStateException
            // from the provider when the storage is gone.
            return RipState.Failed(job, RipFailure.OUTPUT_UNAVAILABLE, e.message.orEmpty())
        }
        val tracker = ProgressTracker(SystemClock::elapsedRealtime)
        val needed = SizeEstimator.requiredFreeBytes(job.estimatedBytes)
        var free: Long? = null
        val (outcome, summary) = try {
            output.open().use { file ->
                free = freeBytes(file)
                if (free != null && free < needed) return@use null to null
                ripping = dvd
                val outcome = try {
                    dvd.rip(request(job), file, cancelled::get) { done, total, bytes, position ->
                        val progress = tracker.update(done, total, bytes, position)
                        ripState.update { if (it is RipState.Running) it.copy(progress = progress) else it }
                    }
                } finally {
                    ripping = null
                }
                outcome to if (outcome is RipOutcome.Success) DvdSession.probe(file) else null
            }
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
            return RipState.Failed(job, outcome.reason, outcome.message)
        }
        val file = summary?.getOrNull()
        if (outcome !is RipOutcome.Success || file == null || file.video.isEmpty()) {
            output.discard()
            return RipState.Failed(job, RipFailure.VERIFY_FAILED, summary?.exceptionOrNull()?.message.orEmpty())
        }
        return try {
            output.complete()
            RipState.Succeeded(job, outcome, file)
        } catch (e: Exception) {
            output.discard()
            RipState.Failed(job, RipFailure.WRITE_FAILED, e.message.orEmpty())
        }
    }

    private fun request(job: RipJob) = RipRequest(
        title = job.title.number,
        audio = job.audio.map { RipTrack(it.streamId, it.language, labels.trackName(it), it.flags) },
        subtitles = job.subtitles.map { RipTrack(it.streamId, it.language, labels.subtitleName(it), it.flags) },
        lpcmToFlac = job.lpcmToFlac,
        movieTitle = job.metadata.title,
        releaseDate = job.metadata.year?.toString(),
        chapterNames = (1..job.title.chapterStartsMs.size).map(labels::chapterName),
        tags = filmTags(job.metadata),
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
    }

    private class NotDvdVideoException : Exception()

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
