package be.discrip.core.video

import be.discrip.core.model.QualityPreset
import be.discrip.core.model.VideoCodec
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EncoderChoiceTest {

    private val both = setOf(BitrateMode.VBR, BitrateMode.CBR)

    private val nv12 = BufferFormat.NV12

    private val hardwareHevc = EncoderInfo("c2.qti.hevc.encoder", VideoCodec.HEVC, hardware = true, fitsTitle = true, both, nv12)
    private val hardwareAvc = EncoderInfo("c2.qti.avc.encoder", VideoCodec.H264, hardware = true, fitsTitle = true, both, nv12)
    private val softwareHevc = EncoderInfo("c2.android.hevc.encoder", VideoCodec.HEVC, hardware = false, fitsTitle = true, both, nv12)
    private val softwareAvc = EncoderInfo("c2.android.avc.encoder", VideoCodec.H264, hardware = false, fitsTitle = true, both, nv12)

    private fun components(plan: VideoPlan?) = assertNotNull(plan).encoders.map { it.component }

    @Test
    fun `a hardware HEVC encoder comes first for the HEVC presets`() {
        val plan = EncoderChoice.plan(QualityPreset.BALANCED, 2_500, listOf(softwareAvc, softwareHevc, hardwareAvc, hardwareHevc))

        assertEquals(
            listOf("c2.qti.hevc.encoder", "c2.qti.avc.encoder", "c2.android.hevc.encoder", "c2.android.avc.encoder"),
            components(plan),
        )
        assertEquals(EncoderFallback.NONE, plan!!.fallbackOf())
        assertEquals("hevc_mediacodec", plan.encoder.engineEncoder)
        assertEquals(BitrateMode.VBR, plan.encoder.bitrateMode)
        assertEquals(2_500, plan.bitRateKbps)
    }

    @Test
    fun `without HEVC hardware it is H264 on the hardware, at the preset's bit rate`() {
        val plan = EncoderChoice.plan(QualityPreset.MAX_COMPRESSION, 1_200, listOf(softwareHevc, hardwareAvc))

        assertEquals(listOf("c2.qti.avc.encoder", "c2.android.hevc.encoder"), components(plan))
        assertEquals(EncoderFallback.H264_INSTEAD_OF_HEVC, plan!!.fallbackOf())
        assertEquals("h264_mediacodec", plan.encoder.engineEncoder)
        assertEquals(1_200, plan.bitRateKbps)
        assertEquals(EncoderFallback.SOFTWARE, plan.fallbackOf(plan.encoders[1]))
    }

    @Test
    fun `without any hardware encoder a software one does it`() {
        val plan = EncoderChoice.plan(QualityPreset.BALANCED, 2_500, listOf(softwareAvc, softwareHevc))

        assertEquals(listOf("c2.android.hevc.encoder", "c2.android.avc.encoder"), components(plan))
        assertEquals(EncoderFallback.SOFTWARE, plan!!.fallbackOf())
    }

    @Test
    fun `the compatible preset stays with H264`() {
        val plan = EncoderChoice.plan(QualityPreset.COMPATIBLE, 3_000, listOf(hardwareHevc, softwareHevc, softwareAvc))

        assertEquals(listOf("c2.android.avc.encoder"), components(plan))
        assertEquals(EncoderFallback.SOFTWARE, plan!!.fallbackOf())
    }

    @Test
    fun `encoders that cannot take the title are left out`() {
        val small = hardwareHevc.copy(fitsTitle = false)

        assertEquals(listOf("c2.qti.avc.encoder"), components(EncoderChoice.plan(QualityPreset.BALANCED, 2_500, listOf(small, hardwareAvc))))
        assertNull(EncoderChoice.plan(QualityPreset.BALANCED, 2_500, listOf(small)))
        assertNull(EncoderChoice.plan(QualityPreset.BALANCED, 2_500, emptyList()))
    }

    @Test
    fun `the lossless copy needs no encoder`() {
        assertNull(EncoderChoice.plan(QualityPreset.LOSSLESS, 2_500, listOf(hardwareHevc)))
    }

    @Test
    fun `the bit rate mode is variable where the encoder can, else constant, else its own`() {
        val cbrOnly = hardwareHevc.copy(bitrateModes = setOf(BitrateMode.CBR))
        val unknown = hardwareAvc.copy(bitrateModes = emptySet())

        val plan = assertNotNull(EncoderChoice.plan(QualityPreset.BALANCED, 2_500, listOf(cbrOnly, unknown)))

        assertEquals(listOf(BitrateMode.CBR, null), plan.encoders.map { it.bitrateMode })
    }

    @Test
    fun `each encoder gets frames in the layout it takes`() {
        val planar = softwareAvc.copy(bufferFormat = BufferFormat.I420)

        val plan = assertNotNull(EncoderChoice.plan(QualityPreset.BALANCED, 2_500, listOf(hardwareHevc, planar)))

        assertEquals(listOf(BufferFormat.NV12, BufferFormat.I420), plan.encoders.map { it.bufferFormat })
        assertEquals(listOf(21, 19), plan.encoders.map { it.bufferFormat.colorFormat })
    }

    @Test
    fun `a bit rate out of range is clamped and a component listed twice is tried once`() {
        val plan = assertNotNull(EncoderChoice.plan(QualityPreset.BALANCED, 50_000, listOf(hardwareHevc, hardwareHevc)))

        assertEquals(QualityPreset.MAX_VIDEO_BITRATE_KBPS, plan.bitRateKbps)
        assertEquals(1, plan.encoders.size)
    }
}
