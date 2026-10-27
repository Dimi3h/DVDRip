package be.discrip.rip

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import be.discrip.core.dvd.RipPlanning
import be.discrip.core.model.MovieMetadata
import be.discrip.core.model.StorageTarget
import be.discrip.core.nas.Nas
import be.discrip.core.nas.NasException
import be.discrip.core.plex.PlexClient
import be.discrip.core.plex.PlexPublisher
import be.discrip.core.settings.AppSettings
import be.discrip.core.settings.LocalFolderSettings
import be.discrip.data.HistoryStore
import be.discrip.data.Secrets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * The app's rip manager, engine and NAS client over the test DVD of native/scripts/make-test-dvd.sh,
 * with folders, settings, secrets and history of its own. Title 1 is 12 s in two VOBs with
 * chapters at 0, 3 and 6 s, an English AC-3 and a Dutch LPCM track and two subtitle streams;
 * title 2 is 3 s.
 */
class RipTestRig(name: String) : Closeable {

    val target = InstrumentationRegistry.getInstrumentation().targetContext
    val work = File(target.cacheDir, name).apply {
        deleteRecursively()
        mkdirs()
    }

    /** The "local folder": rips land here with the same .part-then-rename steps as [LocalOutput]. */
    val out = File(work, "out")
    val staging = File(work, "staging")
    val history = HistoryStore(File(work, "history.json"))
    var settings = AppSettings(local = LocalFolderSettings(treeUri = "rig", displayName = "Movies"))
    var secrets = Secrets()

    /** From this NAS connection on (1 is the first), connecting fails as if the NAS went away. */
    var nasGoneFromConnection: Int? = null
    private var connections = 0

    /** Lends the disc in the "USB drive", by its insertion number. */
    var lend: (insertion: Long) -> DriveDisc? = { null }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val image: DvdSource
    val manager: RipManager

    init {
        val iso = File(work, "test-dvd.iso")
        InstrumentationRegistry.getInstrumentation().context.assets.open("test-dvd.iso").use { input ->
            iso.outputStream().use { input.copyTo(it) }
        }
        image = DvdSource.Image(Uri.fromFile(iso))
        manager = RipManager(
            resolver = target.contentResolver,
            labels = RipLabels(target),
            scope = scope,
            stagingDir = staging,
            credentials = { secrets },
            settings = { settings },
            history = history,
            plex = PlexPublisher(PlexClient(OkHttpClient()), pollMillis = 250, waitMillis = 20_000),
            outputs = FileOutputs(out),
            connectNas = { nasTarget ->
                connections++
                val gone = nasGoneFromConnection
                if (gone != null && connections >= gone) throw NasException.Unreachable(IOException("The test took the NAS away"))
                Nas.connect(nasTarget)
            },
            drives = { lend(it) },
        )
    }

    suspend fun openImage(): SourceState.Ready = open(image)

    /** Opens [source] and waits until it is ready. */
    suspend fun open(source: DvdSource): SourceState.Ready {
        val state = openUntilSettled(source)
        assertTrue(state.toString(), state is SourceState.Ready)
        return state as SourceState.Ready
    }

    /** Opens [source] and waits until it is ready or failed. */
    suspend fun openUntilSettled(source: DvdSource): SourceState {
        manager.open(source)
        return withTimeout(OPEN_TIMEOUT) {
            manager.source.first { (it is SourceState.Ready || it is SourceState.Failed) && it.sourceOrNull == source }
        }
    }

    /** All audio and subtitle tracks of the main title, to [storage] with the current settings. */
    fun job(
        ready: SourceState.Ready,
        storage: StorageTarget = StorageTarget.LOCAL,
        metadata: MovieMetadata = MovieMetadata(title = "Testfilm", year = 2024, tmdbId = 603),
        lpcmToFlac: Boolean = false,
        estimatedBytes: Long = 0,
        source: DvdSource = image,
    ): RipJob {
        val title = ready.mainTitle!!
        return RipJob(
            source = source,
            sourceName = ready.name,
            metadata = metadata,
            title = title,
            audio = RipPlanning.audioTracks(title),
            subtitles = RipPlanning.subtitleTracks(title),
            lpcmToFlac = lpcmToFlac,
            estimatedBytes = estimatedBytes,
            destination = checkNotNull(RipDestination.plan(settings, storage, metadata)) { "$storage is not set up" },
        )
    }

    /** Starts [job] and waits until it has finished, upload and Plex included. */
    suspend fun ripAndWait(job: RipJob): RipState {
        assertTrue(manager.start(job))
        return withTimeout(RIP_TIMEOUT) { manager.rip.first { it !is RipState.Running } }
    }

    fun filesIn(folder: File): List<File> = folder.walk().filter { it.isFile }.toList()

    override fun close() = runBlocking {
        // The engine has one DVD open at a time, for every rig: whatever this one opened goes.
        manager.source.value.sourceOrNull?.let { manager.close(it)?.join() }
        scope.cancel()
        work.deleteRecursively()
        Unit
    }

    companion object {
        const val OPEN_TIMEOUT = 60_000L
        const val RIP_TIMEOUT = 180_000L
    }
}

/** Writes rips under [root] with the same .part-then-rename steps as [LocalOutput]. */
class FileOutputs(private val root: File) : RipOutputs {
    override fun create(job: RipJob, target: LocalTarget): LocalRipOutput {
        val file = File(root, target.path.relativeFile)
        file.parentFile!!.mkdirs()
        val part = File(file.path + ".part")
        part.delete()
        part.createNewFile()
        return object : LocalRipOutput {
            override fun open(): EngineFile =
                EngineFile.Descriptor(ParcelFileDescriptor.open(part, ParcelFileDescriptor.MODE_READ_WRITE))

            override fun complete() {
                file.delete()
                check(part.renameTo(file)) { "Renaming $part failed" }
            }

            override fun discard() {
                part.delete()
            }

            override fun read(): ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }
}
