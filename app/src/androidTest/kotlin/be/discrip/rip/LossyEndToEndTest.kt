package be.discrip.rip

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import be.discrip.core.dvd.DvdTitle
import be.discrip.core.dvd.RipFailure
import be.discrip.core.history.RipResult
import be.discrip.core.model.QualityPreset
import be.discrip.core.model.VideoCodec
import be.discrip.core.video.BitrateMode
import be.discrip.core.video.EncoderChoice
import be.discrip.core.video.VideoEncoding
import be.discrip.core.video.VideoPlan
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Rips the test DVD with "Gebalanceerd" on the device's own MediaCodec encoders, as the app
 * chooses them: a hardware HEVC encoder where there is one, else what the choice falls back to
 * (on the emulator, which has no video encoding hardware, a software encoder). The file stays
 * behind for CI to look into with ffprobe as well (scripts/check_lossy_output.py).
 */
@RunWith(AndroidJUnit4::class)
class LossyEndToEndTest {

    private val rig = RipTestRig("lossy-e2e")

    @After
    fun tearDown() = rig.close()

    @Test
    fun ripsWithTheBalancedPreset() = runBlocking {
        val ready = rig.openImage()
        val plan = balanced(ready.mainTitle!!)

        val done = rig.ripAndWait(rig.job(ready).copy(quality = QualityPreset.BALANCED, video = plan))

        assertTrue(done.toString(), done is RipState.Succeeded)
        done as RipState.Succeeded
        val encoder = checkNotNull(done.encoder) { "The rip names no encoder" }
        assertEquals(plan.encoder, encoder)
        val video = done.file.video.single()
        assertEquals(if (encoder.codec == VideoCodec.HEVC) "hevc" else "h264", video.codec)
        // The title's own size and its 16:9 picture, never scaled up.
        assertEquals(720 to 576, video.width to video.height)
        assertEquals("16:9", video.displayAspect)
        // 12 s at 25 frames a second, every one encoded; none of title 1 is interlaced.
        assertEquals(300, done.result.framesEncoded)
        assertEquals(0, done.result.interlacedFrames)
        // Audio, subtitles, chapters and tags as in the lossless rip.
        assertEquals(listOf("pcm_s16le", "ac3"), done.file.audio.map { it.codec })
        assertEquals(listOf("dut", "eng"), done.file.audio.map { it.language })
        assertEquals(listOf("dut", "eng"), done.file.subtitles.map { it.language })
        assertEquals(3, done.file.chapterStartsMs.size)
        assertEquals(6000.0, done.file.chapterStartsMs[2].toDouble(), 40.0)
        assertEquals(12_000.0, done.file.durationMs.toDouble(), 100.0)
        assertEquals("Testfilm", done.file.tags["title"])

        val record = rig.history.load().single()
        assertEquals(RipResult.SUCCEEDED, record.result)
        assertEquals(QualityPreset.BALANCED, record.preset)
        assertEquals(encoder.codec, record.videoCodec)
        assertEquals(encoder.component, record.videoEncoder)
        assertEquals(encoder.hardware, record.videoHardware)

        val file = File(rig.out, "Films/Testfilm (2024) {tmdb-603}/Testfilm (2024) {tmdb-603}.mkv")
        assertEquals(done.result.outputBytes, file.length())
        file.copyTo(File(additionalOutput(), "balanced.mkv"), overwrite = true)
        File(additionalOutput(), "balanced.txt").writeText("${encoder.component} ${encoder.codec} hardware=${encoder.hardware}\n")
    }

    @Test
    fun anEncoderThatWillNotStartGivesWayToTheNext() = runBlocking {
        val ready = rig.openImage()
        val plan = balanced(ready.mainTitle!!)

        val done = rig.ripAndWait(
            rig.job(ready).copy(quality = QualityPreset.BALANCED, video = plan.copy(encoders = listOf(MISSING) + plan.encoders)),
        )

        assertTrue(done.toString(), done is RipState.Succeeded)
        done as RipState.Succeeded
        assertEquals(plan.encoder, done.encoder)
        assertEquals(300, done.result.framesEncoded)
        assertEquals(plan.encoder.component, rig.history.load().single().videoEncoder)
    }

    @Test
    fun withoutAnEncoderThatStartsNothingIsWritten() = runBlocking {
        val ready = rig.openImage()

        val done = rig.ripAndWait(
            rig.job(ready).copy(quality = QualityPreset.BALANCED, video = VideoPlan(QualityPreset.BALANCED, 2_500, listOf(MISSING))),
        )

        assertTrue(done.toString(), done is RipState.Failed)
        assertEquals(RipFailure.ENCODER_UNAVAILABLE, (done as RipState.Failed).reason)
        assertEquals(emptyList<File>(), rig.filesIn(rig.out))
        val record = rig.history.load().single()
        assertEquals(RipFailure.ENCODER_UNAVAILABLE, record.failure)
        assertEquals(QualityPreset.BALANCED, record.preset)
    }

    /** What the app would use for "Gebalanceerd" on this device. */
    private fun balanced(title: DvdTitle): VideoPlan {
        val encoders = DeviceEncoders().catalog().forTitle(title.video.width, title.video.height)
        return checkNotNull(EncoderChoice.plan(QualityPreset.BALANCED, 2_500, encoders)) {
            "No video encoder takes ${title.video.width}x${title.video.height}: $encoders"
        }
    }

    /**
     * Where AGP collects files after the run (build/outputs/connected_android_test_additional_output):
     * the folder it names, else the one it looks in by default, in the app's media folder.
     */
    @Suppress("DEPRECATION") // externalMediaDirs: AGP still reads that folder.
    private fun additionalOutput(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val given = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        return (given?.let(::File) ?: File(context.externalMediaDirs.first(), "additional_test_output")).apply { mkdirs() }
    }

    private companion object {
        /** No such component: MediaCodec cannot create it, so FFmpeg's encoder will not open. */
        val MISSING = VideoEncoding(VideoCodec.HEVC, "c2.discrip.missing.encoder", hardware = true, bitrateMode = BitrateMode.VBR)
    }
}
