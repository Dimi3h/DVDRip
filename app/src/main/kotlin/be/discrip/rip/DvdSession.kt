package be.discrip.rip

import android.os.ParcelFileDescriptor
import be.discrip.core.dvd.DvdReadException
import be.discrip.core.dvd.DvdTitle
import be.discrip.core.dvd.EngineJson
import be.discrip.core.dvd.MkvSummary
import be.discrip.core.dvd.RipFailure
import be.discrip.core.dvd.RipOutcome
import java.io.Closeable

/** One audio or subtitle track of a rip, in output order. */
data class RipTrack(
    val streamId: Int,
    /** ISO 639-2/B code, or null for "und". */
    val language: String?,
    /** Track name shown by players, or null. */
    val name: String?,
    /** [be.discrip.core.dvd.TrackFlags]. */
    val flags: Int,
)

/** What to rip and how to tag it. */
data class RipRequest(
    val title: Int,
    val angle: Int = 1,
    val audio: List<RipTrack>,
    val subtitles: List<RipTrack> = emptyList(),
    val lpcmToFlac: Boolean,
    val movieTitle: String?,
    /** "YYYY" or "YYYY-MM-DD". */
    val releaseDate: String?,
    val chapterNames: List<String>,
    /** More Matroska tags of the film, such as IMDB, TMDB and SUMMARY. */
    val tags: Map<String, String> = emptyMap(),
)

/**
 * A DVD open in the native engine: an ISO image or a VIDEO_TS folder. The engine allows one at a
 * time, so close a session before opening the next. Calls block; run them off the main thread.
 * [cancel] may be called from any thread while [rip] runs.
 */
class DvdSession private constructor(private val handle: Long) : Closeable {

    /** Serializes engine calls; held for the whole of [rip]. */
    private val callLock = Any()

    /** Guards [closed] against [cancel] racing [close]. */
    private val stateLock = Any()
    private var closed = false

    fun titles(): List<DvdTitle> = synchronized(callLock) {
        checkOpen()
        EngineJson.titles(NativeRip.titles(handle).decodeToString())
    }

    /**
     * Rips to [output], a descriptor open for reading and writing or a [NativeFile]; it stays the
     * caller's. [onProgress] gets sectors done, sectors in total, bytes written and the position
     * in the title.
     *
     * [isCancelled] is the caller's own flag for this rip. [cancel] stops the engine at once, but a
     * cancel that comes just before the engine starts would be lost, so the flag is checked here
     * and again with every progress report.
     */
    fun rip(
        request: RipRequest,
        output: EngineFile,
        isCancelled: () -> Boolean,
        onProgress: (Long, Long, Long, Long) -> Unit,
    ): RipOutcome =
        synchronized(callLock) {
            checkOpen()
            if (isCancelled()) return RipOutcome.Failure(RipFailure.CANCELLED, "The rip was cancelled")
            val listener = NativeProgress { done, total, bytes, position ->
                if (isCancelled()) NativeRip.cancel(handle)
                onProgress(done, total, bytes, position)
            }
            val report = NativeRip.rip(
                session = handle,
                title = request.title,
                angle = request.angle,
                audioStreamIds = request.audio.map { it.streamId }.toIntArray(),
                audioFlags = request.audio.map { it.flags }.toIntArray(),
                audioLanguages = request.audio.map { it.language?.encodeToByteArray() }.toTypedArray(),
                audioNames = request.audio.map { it.name?.encodeToByteArray() }.toTypedArray(),
                subtitleStreamIds = request.subtitles.map { it.streamId }.toIntArray(),
                subtitleFlags = request.subtitles.map { it.flags }.toIntArray(),
                subtitleLanguages = request.subtitles.map { it.language?.encodeToByteArray() }.toTypedArray(),
                subtitleNames = request.subtitles.map { it.name?.encodeToByteArray() }.toTypedArray(),
                lpcmToFlac = request.lpcmToFlac,
                movieTitle = request.movieTitle?.encodeToByteArray(),
                releaseDate = request.releaseDate?.encodeToByteArray(),
                tagKeys = request.tags.keys.map { it.encodeToByteArray() }.toTypedArray<ByteArray?>(),
                tagValues = request.tags.values.map { it.encodeToByteArray() }.toTypedArray<ByteArray?>(),
                chapterNames = request.chapterNames.map { it.encodeToByteArray() }.toTypedArray<ByteArray?>(),
                outputFd = (output as? EngineFile.Descriptor)?.descriptor?.fd ?: -1,
                output = (output as? EngineFile.Callbacks)?.file,
                listener = listener,
            )
            RipOutcome.parse(report.decodeToString())
        }

    /** Asks a running [rip] to stop; it then returns a cancelled outcome. */
    fun cancel() {
        synchronized(stateLock) {
            if (!closed) NativeRip.cancel(handle)
        }
    }

    override fun close() {
        synchronized(callLock) {
            synchronized(stateLock) {
                if (closed) return
                closed = true
                NativeRip.close(handle)
            }
        }
    }

    private fun checkOpen() {
        synchronized(stateLock) { check(!closed) { "The DVD session is closed" } }
    }

    companion object {

        @Volatile
        private var configured = false

        /** Sets up libdvdcss; done once, before the first session. */
        fun configure() {
            if (configured) return
            synchronized(this) {
                if (configured) return
                NativeRip.configure()
                configured = true
            }
        }

        /** Opens the image read through [image]; the descriptor is handed to the engine. */
        fun openImage(image: ParcelFileDescriptor): DvdSession {
            val error = arrayOfNulls<ByteArray>(1)
            val handle = NativeRip.openImage(image.detachFd(), error)
            if (handle == 0L) throw DvdReadException(error[0]?.decodeToString() ?: "The image cannot be opened")
            return DvdSession(handle)
        }

        fun openFolder(tree: DvdTree): DvdSession {
            val error = arrayOfNulls<ByteArray>(1)
            val handle = NativeRip.openFolder(NativeFolder(tree), error)
            if (handle == 0L) throw DvdReadException(error[0]?.decodeToString() ?: "The folder cannot be opened")
            return DvdSession(handle)
        }

        /** Reads a finished Matroska file back; [file] stays the caller's. */
        fun probe(file: EngineFile): Result<MkvSummary> = MkvSummary.parse(
            NativeRip.probe((file as? EngineFile.Descriptor)?.descriptor?.fd ?: -1, (file as? EngineFile.Callbacks)?.file).decodeToString(),
        )
    }
}
