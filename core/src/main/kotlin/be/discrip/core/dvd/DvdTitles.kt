package be.discrip.core.dvd

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A DVD title as the native engine lists it: what plays, for how long, with which streams. */
@Serializable
data class DvdTitle(
    val number: Int,
    val titleSet: Int,
    val angles: Int,
    val chapters: Int,
    val cells: Int,
    val sectors: Long,
    val durationMs: Long,
    val video: DvdVideo,
    val audio: List<DvdAudioStream>,
    val subtitles: List<DvdSubtitleStream>,
    val chapterStartsMs: List<Long>,
) {
    /** Bytes of VOB data the title covers on the disc. */
    val sizeBytes: Long get() = sectors * SECTOR_SIZE

    companion object {
        const val SECTOR_SIZE = 2048
    }
}

@Serializable
data class DvdVideo(
    val mpegVersion: Int,
    /** "PAL" or "NTSC". */
    val standard: String,
    /** "16:9" or "4:3". */
    val aspect: String,
    val width: Int,
    val height: Int,
    val letterboxed: Boolean,
    val film: Boolean,
)

@Serializable
enum class AudioCodec {
    @SerialName("ac3") AC3,
    @SerialName("mp2") MP2,
    @SerialName("mp2ext") MP2_EXT,
    @SerialName("lpcm") LPCM,
    @SerialName("dts") DTS,
    @SerialName("unknown") UNKNOWN,
}

/** What an audio stream is for, from the IFO's code extension. */
@Serializable
enum class AudioPurpose {
    @SerialName("normal") NORMAL,
    @SerialName("visually_impaired") VISUALLY_IMPAIRED,
    @SerialName("commentary") COMMENTARY,
    @SerialName("alternate_commentary") ALTERNATE_COMMENTARY,
    @SerialName("unspecified") UNSPECIFIED,
}

@Serializable
data class DvdAudioStream(
    /** Position among the title set's audio streams. */
    val index: Int,
    /** The stream id the rip engine selects it by (0x80+n AC-3, 0xA0+n LPCM, …). */
    val streamId: Int,
    val codec: AudioCodec = AudioCodec.UNKNOWN,
    /** ISO 639-1 code from the IFO, or null when the disc gives none. */
    val language: String? = null,
    val channels: Int,
    val sampleRate: Int,
    /** 16, 20 or 24 for LPCM, 0 for compressed formats. */
    val bitsPerSample: Int,
    @SerialName("extension") val purpose: AudioPurpose = AudioPurpose.UNSPECIFIED,
) {
    val isCommentary: Boolean get() = purpose == AudioPurpose.COMMENTARY || purpose == AudioPurpose.ALTERNATE_COMMENTARY
}

@Serializable
data class DvdSubtitleStream(
    val index: Int,
    val streamId: Int,
    val language: String? = null,
    val extension: String,
)

/** The engine could not read the DVD; [message] is its (English) explanation. */
class DvdReadException(message: String) : Exception(message)

/** Parses what the native engine returns. */
object EngineJson {

    internal val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Serializable
    private data class TitleList(val titles: List<DvdTitle> = emptyList(), val error: String? = null)

    /** The titles of `{"titles":[…]}`; throws [DvdReadException] for `{"error":"…"}`. */
    fun titles(text: String): List<DvdTitle> {
        val list = try {
            json.decodeFromString<TitleList>(text)
        } catch (e: SerializationException) {
            throw DvdReadException("Unexpected title list from the engine: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw DvdReadException("Unexpected title list from the engine: ${e.message}")
        }
        list.error?.let { throw DvdReadException(it) }
        return list.titles
    }
}
