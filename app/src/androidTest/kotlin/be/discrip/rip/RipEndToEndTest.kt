package be.discrip.rip

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import be.discrip.core.dvd.AudioCodec
import be.discrip.core.dvd.MainFeature
import be.discrip.core.dvd.RipFailure
import be.discrip.core.dvd.RipOutcome
import be.discrip.core.dvd.RipPlanning
import be.discrip.core.history.RipResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Rips the test DVD of native/scripts/make-test-dvd.sh on the device into a local folder, through
 * the same manager, JNI bridge and native engine as the app (see [RipTestRig]).
 */
@RunWith(AndroidJUnit4::class)
class RipEndToEndTest {

    private lateinit var rig: RipTestRig
    private val manager get() = rig.manager
    private val work get() = rig.work
    private val target get() = rig.target
    private val image get() = rig.image

    @Before
    fun setUp() {
        rig = RipTestRig("rip-test")
    }

    @After
    fun tearDown() = rig.close()

    @Test
    fun ripsTheImageLosslesslyIntoAPlexNamedFile() = runBlocking {
        val ready = rig.openImage()
        assertEquals("DISCRIP_TEST_DVD", ready.name)
        assertEquals(listOf(1, 2), ready.titles.map { it.number })
        val title = ready.mainTitle!!
        assertEquals(1, title.number)
        assertEquals(listOf(0L, 3000L, 6000L), title.chapterStartsMs)

        val job = rig.job(ready)
        assertEquals(listOf(AudioCodec.LPCM, AudioCodec.AC3), job.audio.map { it.codec })
        assertEquals(listOf("dut", "eng"), job.audio.map { it.language })
        val done = rig.ripAndWait(job)
        assertTrue(done.toString(), done is RipState.Succeeded)
        done as RipState.Succeeded

        val file = File(rig.out, "Films/Testfilm (2024) {tmdb-603}/Testfilm (2024) {tmdb-603}.mkv")
        assertTrue(file.isFile)
        assertFalse(File(file.path + ".part").exists())
        assertEquals(done.result.outputBytes, file.length())
        assertEquals(emptyList<Any>(), done.result.badSectors)
        assertEquals("Movies/Films/Testfilm (2024) {tmdb-603}/Testfilm (2024) {tmdb-603}.mkv", done.localFile)
        assertNull(done.nas)
        assertNull(done.plex)

        val summary = done.file
        assertEquals(listOf("mpeg2video"), summary.video.map { it.codec })
        assertEquals(720 to 576, summary.video.single().width to summary.video.single().height)
        assertEquals(listOf("pcm_s16le", "ac3"), summary.audio.map { it.codec })
        assertEquals(listOf("dut", "eng"), summary.audio.map { it.language })
        assertEquals(listOf("dvd_subtitle", "dvd_subtitle"), summary.subtitles.map { it.codec })
        assertEquals(listOf("dut", "eng"), summary.subtitles.map { it.language })
        assertEquals(2, done.result.subtitleTracks)
        assertEquals("Testfilm", summary.tags["title"])
        assertEquals("2024", summary.tags["DATE_RELEASED"])
        assertEquals("movie/603", summary.tags["TMDB"])
        assertEquals(3, summary.chapterStartsMs.size)
        assertEquals(6000.0, summary.chapterStartsMs[2].toDouble(), 40.0)
        assertEquals(12_000.0, summary.durationMs.toDouble(), 100.0)

        val record = rig.history.load().single()
        assertEquals(RipResult.SUCCEEDED, record.result)
        assertEquals("Testfilm", record.title)
        assertEquals(done.result.outputBytes, record.bytes)
        assertEquals(done.localFile, record.localFile)
        assertNull(record.nasFile)
    }

    @Test
    fun convertsLpcmToFlacWhenAsked() = runBlocking {
        val done = rig.ripAndWait(rig.job(rig.openImage(), lpcmToFlac = true))
        assertTrue(done.toString(), done is RipState.Succeeded)
        assertEquals(listOf("flac", "ac3"), (done as RipState.Succeeded).file.audio.map { it.codec })
    }

    @Test
    fun aCancelledRipLeavesNoFile() = runBlocking {
        val ready = rig.openImage()
        assertTrue(manager.start(rig.job(ready)))
        manager.cancel()
        val done = withTimeout(RipTestRig.RIP_TIMEOUT) { manager.rip.first { it !is RipState.Running } }
        assertEquals(RipFailure.CANCELLED, (done as RipState.Failed).reason)
        assertEquals(emptyList<File>(), rig.filesIn(rig.out))
        assertEquals(RipResult.CANCELLED, rig.history.load().single().result)
    }

    @Test
    fun aRipThatCannotFitDoesNotStart() = runBlocking {
        val done = rig.ripAndWait(rig.job(rig.openImage(), estimatedBytes = Long.MAX_VALUE / 4))
        done as RipState.Failed
        assertEquals(RipFailure.NO_SPACE, done.reason)
        assertTrue(done.neededBytes!! > done.freeBytes!!)
        assertNull(done.nasProblem)
        assertEquals(emptyList<File>(), rig.filesIn(rig.out))
    }

    @Test
    fun theEngineStopsWhenCancelledMidRip() = runBlocking {
        manager.close(image)?.join()
        val iso = ParcelFileDescriptor.open(File(work, "test-dvd.iso"), ParcelFileDescriptor.MODE_READ_ONLY)
        DvdSession.configure()
        DvdSession.openImage(iso).use { session ->
            val title = MainFeature.choose(session.titles())!!
            val output = File(work, "cancelled.mkv")
            val request = RipRequest(title.number, audio = emptyList(), lpcmToFlac = false, movieTitle = null, releaseDate = null, chapterNames = emptyList())
            var reports = 0
            val outcome = descriptor(output).use { file ->
                // The engine reports progress with its first packet; cancelling there stops it mid-title.
                session.rip(request, file, isCancelled = { false }) {
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
        val imageTitles = rig.openImage().titles
        manager.close(image)?.join()
        copyAssetTree("folder", File(work, "folder"))

        DvdSession.openFolder(FileDvdTree(File(work, "folder"))).use { session ->
            val titles = session.titles()
            assertEquals(imageTitles, titles)
            val output = File(work, "folder.mkv")
            val extra = titles.single { it.number == 2 }
            val audio = RipPlanning.audioTracks(extra).map { RipTrack(it.streamId, it.language, null, it.flags) }
            val request = RipRequest(2, audio = audio, lpcmToFlac = false, movieTitle = "Extra", releaseDate = null, chapterNames = emptyList())
            descriptor(output).use { file ->
                val outcome = session.rip(request, file, isCancelled = { false }) { }
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
        val state = withTimeout(RipTestRig.OPEN_TIMEOUT) { manager.source.first { it is SourceState.Ready || it is SourceState.Failed } }
        assertEquals(SourceProblem.CANNOT_OPEN, (state as SourceState.Failed).problem)
    }

    private fun descriptor(file: File): EngineFile = EngineFile.Descriptor(
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE),
    )

    private fun copyAsset(name: String, to: File) {
        to.parentFile?.mkdirs()
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input -> to.outputStream().use { input.copyTo(it) } }
    }

    private fun copyAssetTree(path: String, to: File) {
        val children = InstrumentationRegistry.getInstrumentation().context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            copyAsset(path, to)
        } else {
            to.mkdirs()
            for (child in children) copyAssetTree("$path/$child", File(to, child))
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
