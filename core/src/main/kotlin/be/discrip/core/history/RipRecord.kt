package be.discrip.core.history

import be.discrip.core.dvd.RipFailure
import be.discrip.core.model.QualityPreset
import be.discrip.core.model.StorageTarget
import be.discrip.core.model.VideoCodec
import be.discrip.core.nas.NasProblem
import be.discrip.core.plex.PlexResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
enum class RipResult {
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

/** One finished rip in the history ("Geschiedenis"), newest first in the list. */
@Serializable
data class RipRecord(
    val id: String,
    /** When the rip ended, in milliseconds since 1970. */
    val endedAt: Long,
    val title: String,
    val year: Int? = null,
    /** The DVD's name: volume label, image or folder name. */
    val sourceName: String? = null,
    val titleNumber: Int = 0,
    val storage: StorageTarget = StorageTarget.LOCAL,
    /** Null in records from before the lossy presets, which were all lossless. */
    val preset: QualityPreset? = null,
    /** The codec a lossy preset encoded to; null for the copy. */
    val videoCodec: VideoCodec? = null,
    /** The MediaCodec component a lossy preset encoded with, such as "c2.qti.hevc.encoder". */
    val videoEncoder: String? = null,
    /** Whether that component is a hardware encoder. */
    val videoHardware: Boolean? = null,
    val result: RipResult = RipResult.SUCCEEDED,
    /** Why the rip failed; null when it did not. */
    val failure: RipFailure? = null,
    val message: String? = null,
    val bytes: Long = 0,
    val durationMs: Long = 0,
    val badSectors: Long = 0,
    val warnings: List<String> = emptyList(),
    /** The file in the local folder ("Films/…/….mkv"), when one was kept there. */
    val localFile: String? = null,
    /** The file on the NAS (`\\nas\video\Films\…\….mkv`), once it arrived there. */
    val nasFile: String? = null,
    /** Why the file did not get to the NAS, when it was meant to. */
    val nasProblem: NasProblem? = null,
    /** What Plex made of it; null when Plex was not asked. */
    val plex: PlexResult? = null,
    /** The film in Plex Web, once Plex listed it. */
    val plexLink: String? = null,
    /** A finished rip whose upload failed, to try again: in the app's storage or the local folder. */
    val pendingUpload: PendingUpload? = null,
)

/** What an upload needs to be tried again from the history. */
@Serializable
data class PendingUpload(
    /** The MKV in the app's own storage (a NAS rip), which goes once it is uploaded. */
    val stagedFile: String? = null,
    /** The MKV in the local folder (a "Beide" rip), as a document URI; it stays. */
    val localDocument: String? = null,
    /** Its path below the share: "Films/Title (Year)/Title (Year).mkv". */
    val nasPath: String,
    /** The Plex library to scan once it has arrived, if any. */
    val plexSectionKey: String? = null,
)

/** The history file's JSON. */
object RipHistoryJson {

    private val json = Json {
        ignoreUnknownKeys = true
        // An unknown enum value from a newer version falls back to the field's default.
        coerceInputValues = true
        encodeDefaults = false
    }

    fun encode(records: List<RipRecord>): String = json.encodeToString(records)

    /** Throws [SerializationException] or [IllegalArgumentException] when [text] is not a history. */
    fun decode(text: String): List<RipRecord> = json.decodeFromString(text)
}
