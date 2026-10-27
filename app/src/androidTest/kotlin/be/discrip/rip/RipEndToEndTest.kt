package be.discrip.rip

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import be.discrip.core.dvd.AudioCodec
import be.discrip.core.dvd.RipFailure
import be.discrip.core.dvd.RipOutcome
import be.discrip.core.dvd.RipPlanning
import be.discrip.core.model.MovieMetadata
import be.discrip.core.naming.PlexNaming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Rips the test DVD of native/scripts/make-test-dvd.sh on the device, through the same manager,
 * JNI bridge and native engine as the app. Title 1 is 12 s in two VOBs with chapters at 0, 3 and
 * 6 s, an English AC-3 track and a Dutch LPCM track; title 2 is 3 s.
 */
@RunWith(AndroidJUnit4::class)
class RipEndToEndTest {

    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    private lateinit var work: File
    private lateinit var scope: CoroutineScope
    private lateinit var manager: RipManager
    private lateinit var image: DvdSource

    @Before
    fun setUp() {
        work = File(target.cacheDir, "rip-test").apply {
            deleteRecursively()
            mkdirs()
        }
        copyAsset("test-dvd.iso", File(work, "test-dvd.iso"))
        image = DvdSource.Image(Uri.fromFile(File(work, "test-dvd.iso")))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        manager = RipManager(target.contentResolver, target.cacheDir, RipLabels(target), scope, FileOutputs(File(work, "out")))
    }

    @After
    fun tearDown() = runBlocking {
        manager.close(image)?.join()
        scope.cancel()
        work.deleteRecursively()
        Unit
    }

    @Test
    fun ripsTheImageLosslesslyIntoAPlexNamedFile() = runBlocking {
        val ready = openImage()
        assertEquals("DISCRIP_TEST_DVD", ready.name)
        assertEquals(listOf(1, 2), ready.titles.map { it.number })
        val title = ready.mainTitle!!
        assertEquals(1, title.number)
        assertEquals(listOf(0L, 3000L, 6000L), title.chapterStartsMs)

        val job = job(ready, lpcmToFlac = false)
        assertEquals(listOf("dut", "eng"), job.audio.map { it.language })
        assertTrue(manager.start(job))
        val done = withTimeout(RIP_TIMEOUT) { manager.rip.first { it !is RipState.Running } }
        assertTrue(done.toString(), done is RipState.Succeeded)
        done as RipState.Succeeded

        val file = File(work, "out/Films/Testfilm (2024) {tmdb-603}/Testfilm (2024) {tmdb-603}.mkv")
        assertTrue(file.isFile)
        assertFalse(File(file.path + ".part").exists())
        assertEquals(done.result.outputBytes, file.length())
        assertEquals(emptyList<Any>(), done.result.badSectors)

        val summary = done.file
        assertEquals(listOf("mpeg2video"), summary.video.map { it.codec })
        assertEquals(720 to 576, summary.video.single().width to summary.video.single().height)
        assertEquals(listOf("pcm_s16le", "ac3"), summary.audio.map { it.codec })
        assertEquals(listOf("dut", "eng"), summary.audio.map { it.language })
        assertEquals(3, summary.chapterStartsMs.size)
        assertEquals(6000.0, summary.chapterStartsMs[2].toDouble(), 40.0)
        assertEquals(12_000.0, summary.durationMs.toDouble(), 100.0)
    }

    @Test
    fun convertsLpcmToFlacWhenAsked() = runBlocking {
        val ready = openImage()
        assertTrue(manager.start(job(ready, lpcmToFlac = true)))
        val done = withTimeout(RIP_TIMEOUT) { manager.rip.first { it !is RipState.Running } }
        assertTrue(done.toString(), done is RipState.Succeeded)
        assertEquals(listOf("flac", "ac3"), (done as RipState.Succeeded).file.audio.map { it.codec })
    }

    @Test
    fun aCancelledRipLeavesNoFile() = runBlocking {
        val ready = openImage()
        assertTrue(manager.start(job(ready, lpcmToFlac = false)))
        manager.cancel()
        val done = withTimeout(RIP_TIMEOUT) { manager.rip.first { it !is RipState.Running } }
        assertEquals(RipFailure.CANCELLED, (done as RipState.Failed).reason)
        assertEquals(emptyList<File>(), File(work, "out").walk().filter { it.isFile }.toList())
    }

    @Test
    fun theEngineStopsWhenCancelledMidRip() = runBlocking {
        manager.close(image)?.join()
        val iso = ParcelFileDescriptor.open(File(work, "test-dvd.iso"), ParcelFileDescriptor.MODE_READ_ONLY)
        DvdSession.configure(target.cacheDir)
        DvdSession.openImage(iso).use { session ->
            val title = RipPlanning.mainTitle(session.titles())!!
            val output = File(work, "cancelled.mkv")
            val request = RipRequest(title.number, audio = emptyList(), lpcmToFlac = false, movieTitle = null, releaseDate = null, chapterNames = emptyList())
            var reports = 0
            val outcome = ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use { file ->
                // The engine reports progress with its first packet; cancelling there stops it mid-title.
                session.rip(request, file, isCancelled = { false }) { _, _, _, _ ->
                    reports++
                    session.cancel()
                }
            }
            assertEquals(1, reports)
            assertEquals(RipFailure.CANCELLED, (outcome as RipOutcome.Failure).reason)
        }
    }

    @Test
    fun readsAVideoTsFolderLikeTheImage() = runBlocking {
        val imageTitles = openImage().titles
        manager.close(image)?.join()
        copyAssetTree("folder", File(work, "folder"))

        DvdSession.openFolder(FileDvdTree(File(work, "folder"))).use { session ->
            val titles = session.titles()
            assertEquals(imageTitles, titles)
            val output = File(work, "folder.mkv")
            val extra = titles.single { it.number == 2 }
            val audio = RipPlanning.audioTracks(extra).map { RipAudioTrack(it.streamId, it.language, null, it.isDefault) }
            val request = RipRequest(2, audio = audio, lpcmToFlac = false, movieTitle = "Extra", releaseDate = null, chapterNames = emptyList())
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use { file ->
                val outcome = session.rip(request, file, isCancelled = { false }) { _, _, _, _ -> }
                assertTrue(outcome.toString(), outcome is RipOutcome.Success)
                assertEquals(3000.0, (outcome as RipOutcome.Success).durationMs.toDouble(), 50.0)
                assertEquals(listOf("dut", "eng"), DvdSession.probe(file).getOrThrow().audio.map { it.language })
            }
        }
    }

    @Test
    fun aMissingImageIsReportedNotThrown() = runBlocking {
        val missing = DvdSource.Image(Uri.fromFile(File(work, "missing.iso")))
        manager.open(missing)
        val state = withTimeout(OPEN_TIMEOUT) { manager.source.first { it is SourceState.Ready || it is SourceState.Failed } }
        assertEquals(SourceProblem.CANNOT_OPEN, (state as SourceState.Failed).problem)
    }

    private suspend fun openImage(): SourceState.Ready {
        manager.open(image)
        val state = withTimeout(OPEN_TIMEOUT) { manager.source.first { it is SourceState.Ready || it is SourceState.Failed } }
        assertTrue(state.toString(), state is SourceState.Ready)
        return state as SourceState.Ready
    }

    private fun job(ready: SourceState.Ready, lpcmToFlac: Boolean): RipJob {
        val title = ready.mainTitle!!
        val metadata = MovieMetadata(title = "Testfilm", year = 2024, tmdbId = 603)
        val audio = RipPlanning.audioTracks(title)
        assertEquals(listOf(AudioCodec.LPCM, AudioCodec.AC3), audio.map { it.codec })
        return RipJob(image, ready.name, metadata, title, audio, lpcmToFlac, PlexNaming.plan(metadata, "Films"), treeUri = "")
    }

    private fun copyAsset(name: String, to: File) {
        to.parentFile?.mkdirs()
        testAssets.open(name).use { input -> to.outputStream().use { input.copyTo(it) } }
    }

    private fun copyAssetTree(path: String, to: File) {
        val children = testAssets.list(path).orEmpty()
        if (children.isEmpty()) {
            copyAsset(path, to)
        } else {
            to.mkdirs()
            for (child in children) copyAssetTree("$path/$child", File(to, child))
        }
    }

    private companion object {
        const val OPEN_TIMEOUT = 60_000L
        const val RIP_TIMEOUT = 180_000L
    }
}

/** Writes rips under [root] with the same .part-then-rename steps as [LocalOutput]. */
private class FileOutputs(private val root: File) : RipOutputs {
    override fun create(job: RipJob): RipOutput {
        val target = File(root, job.path.relativeFile)
        target.parentFile!!.mkdirs()
        val part = File(target.path + ".part")
        part.delete()
        part.createNewFile()
        return object : RipOutput {
            override fun open(): ParcelFileDescriptor =
                ParcelFileDescriptor.open(part, ParcelFileDescriptor.MODE_READ_WRITE)

            override fun complete() {
                target.delete()
                check(part.renameTo(target)) { "Renaming $part failed" }
            }

            override fun discard() {
                part.delete()
            }
        }
    }
}

/** A VIDEO_TS folder on the device's own storage. */
private class FileDvdTree(private val root: File) : DvdTree {
    private fun file(path: List<String>) = path.fold(root) { folder, name -> File(folder, name) }

    override fun stat(path: List<String>): DvdTree.Entry? {
        val file = file(path)
        return when {
            file.isDirectory -> DvdTree.Entry.Directory
            file.isFile -> DvdTree.Entry.File(file.length())
            else -> null
        }
    }

    override fun list(path: List<String>): List<String>? = file(path).list()?.toList()

    override fun open(path: List<String>): ParcelFileDescriptor? =
        file(path).takeIf { it.isFile }?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
}
