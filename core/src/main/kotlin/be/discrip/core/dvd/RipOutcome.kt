package be.discrip.core.dvd

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/** Why a rip stopped without a file. */
enum class RipFailure {
    CANCELLED,
    NO_TITLE,
    /** The disc could not be read, also after retries. */
    UNREADABLE,
    /** The title is CSS protected and its key could not be recovered. */
    SCRAMBLED,
    NO_VIDEO,
    /** The destination is full. */
    NO_SPACE,
    WRITE_FAILED,
    /** The destination folder cannot be written: access withdrawn or storage removed. */
    OUTPUT_UNAVAILABLE,
    /** The file was written but could not be read back as Matroska. */
    VERIFY_FAILED,

    /** The USB drive was unplugged, or stopped answering. */
    DRIVE_DISCONNECTED,

    /** The disc was taken out of the drive, or another one put in. */
    DISC_CHANGED,

    /** The drive's region is not one the disc allows, so it refuses the scrambled sectors. */
    REGION_MISMATCH,

    /** The drive keeps refusing the scrambled sectors, also after CSS authentication. */
    CSS_REFUSED,

    /** No video encoder of the device would start for a lossy preset; nothing was written. */
    ENCODER_UNAVAILABLE,

    /** The video encoder failed during the rip; the half-written file was deleted. */
    ENCODER_FAILED,
    FAILED,
}

/** A run of sectors that could not be read and was left out. */
data class BadSectors(val first: Long, val count: Long)

sealed interface RipOutcome {

    data class Success(
        val outputBytes: Long,
        val durationMs: Long,
        val chapterStartsMs: List<Long>,
        val audioTracks: Int,
        val subtitleTracks: Int,
        val badSectors: List<BadSectors>,
        val warnings: List<String>,
        /** Video frames encoded by a lossy preset; 0 when the video was copied. */
        val framesEncoded: Long = 0,
        /** Of the frames decoded for a lossy preset, those flagged interlaced, which were deinterlaced. */
        val interlacedFrames: Long = 0,
    ) : RipOutcome {
        val badSectorCount: Long get() = badSectors.sumOf { it.count }
    }

    data class Failure(val reason: RipFailure, val message: String) : RipOutcome

    companion object {
        @Serializable
        private data class Report(
            val ok: Boolean,
            val error: String? = null,
            val message: String? = null,
            val outputBytes: Long = 0,
            val durationMs: Long = 0,
            val chapterStartsMs: List<Long> = emptyList(),
            val audioTracks: Int = 0,
            val subtitleTracks: Int = 0,
            val badSectors: List<List<Long>> = emptyList(),
            val warnings: List<String> = emptyList(),
            val framesEncoded: Long = 0,
            val interlacedFrames: Long = 0,
        )

        /** Parses the engine's report (dr_rip). */
        fun parse(text: String): RipOutcome {
            val report = try {
                EngineJson.json.decodeFromString<Report>(text)
            } catch (e: SerializationException) {
                return Failure(RipFailure.FAILED, "Unexpected report from the engine: ${e.message}")
            } catch (e: IllegalArgumentException) {
                return Failure(RipFailure.FAILED, "Unexpected report from the engine: ${e.message}")
            }
            if (!report.ok) {
                val reason = when (report.error) {
                    "cancelled" -> RipFailure.CANCELLED
                    "no_title" -> RipFailure.NO_TITLE
                    "unreadable" -> RipFailure.UNREADABLE
                    "scrambled" -> RipFailure.SCRAMBLED
                    "no_video" -> RipFailure.NO_VIDEO
                    "no_space" -> RipFailure.NO_SPACE
                    "write_failed" -> RipFailure.WRITE_FAILED
                    "encoder_unavailable" -> RipFailure.ENCODER_UNAVAILABLE
                    "encoder_failed" -> RipFailure.ENCODER_FAILED
                    else -> RipFailure.FAILED
                }
                return Failure(reason, report.message.orEmpty())
            }
            return Success(
                outputBytes = report.outputBytes,
                durationMs = report.durationMs,
                chapterStartsMs = report.chapterStartsMs,
                audioTracks = report.audioTracks,
                subtitleTracks = report.subtitleTracks,
                badSectors = report.badSectors.filter { it.size == 2 }.map { BadSectors(it[0], it[1]) },
                warnings = report.warnings,
                framesEncoded = report.framesEncoded,
                interlacedFrames = report.interlacedFrames,
            )
        }
    }
}

/** What a finished Matroska file contains, read back by the engine (dr_probe). */
data class MkvSummary(
    val durationMs: Long,
    val streams: List<MkvStream>,
    val chapterStartsMs: List<Long>,
    /** Global tags as FFmpeg reads them back ("title", "DATE_RELEASED", "IMDB", …). */
    val tags: Map<String, String> = emptyMap(),
) {
    val video: List<MkvStream> get() = streams.filter { it.type == "video" }
    val audio: List<MkvStream> get() = streams.filter { it.type == "audio" }
    val subtitles: List<MkvStream> get() = streams.filter { it.type == "subtitle" }

    companion object {
        @Serializable
        private data class Probe(
            val ok: Boolean,
            val message: String? = null,
            val durationMs: Long = 0,
            val streams: List<MkvStream> = emptyList(),
            val chapterStartsMs: List<Long> = emptyList(),
            val tags: Map<String, String> = emptyMap(),
        )

        /** The summary, or null with the reason when the file could not be read back. */
        fun parse(text: String): Result<MkvSummary> {
            val probe = try {
                EngineJson.json.decodeFromString<Probe>(text)
            } catch (e: SerializationException) {
                return Result.failure(DvdReadException("Unexpected probe result: ${e.message}"))
            } catch (e: IllegalArgumentException) {
                return Result.failure(DvdReadException("Unexpected probe result: ${e.message}"))
            }
            if (!probe.ok) return Result.failure(DvdReadException(probe.message ?: "The file cannot be read"))
            return Result.success(MkvSummary(probe.durationMs, probe.streams, probe.chapterStartsMs, probe.tags))
        }
    }
}

@Serializable
data class MkvStream(
    /** "video", "audio" or "subtitle". */
    val type: String? = null,
    val codec: String? = null,
    /** ISO 639-2 code as written to the file. */
    val language: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    /** How players show the picture, such as "16:9"; video only. */
    val displayAspect: String? = null,
    val channels: Int = 0,
    val sampleRate: Int = 0,
)
