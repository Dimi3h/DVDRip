package be.discrip.rip

/**
 * JNI entry points of the rip engine in libdiscrip.so (`app/src/main/cpp/jni/rip_jni.c`). Text
 * goes both ways as UTF-8 byte arrays. Only one session can be open at a time, and calls on a
 * session must not overlap, except [cancel]; [DvdSession] takes care of both.
 */
internal object NativeRip {

    init {
        System.loadLibrary("discrip")
    }

    /** Sets up libdvdcss: title keys cracked from the scrambled sectors, no key cache on disk. */
    @JvmStatic external fun configure()

    /** Opens the image on [fd], which native code owns from here on; 0 with [error] set on failure. */
    @JvmStatic external fun openImage(fd: Int, error: Array<ByteArray?>): Long

    /** Opens the disc [blocks] reads (a USB drive); 0 with [error] set on failure. */
    @JvmStatic external fun openBlocks(blocks: NativeBlocks, error: Array<ByteArray?>): Long

    @JvmStatic external fun openFolder(folder: NativeFolder, error: Array<ByteArray?>): Long

    /** `{"titles":[…]}` or `{"error":"…"}`. */
    @JvmStatic external fun titles(session: Long): ByteArray

    /**
     * The engine's JSON report; see `dr_rip.h`. Tracks come as parallel arrays in output order:
     * stream id, `DR_TRACK_` flags ([be.discrip.core.dvd.TrackFlags]), language and name. With
     * [videoEncoder] ("hevc_mediacodec" or "h264_mediacodec") the video is re-encoded by the
     * MediaCodec component [videoComponent], which takes frames in colour format
     * [videoColorFormat]. The MKV goes to [output] when given, else to [outputFd]; both stay the
     * caller's.
     */
    @JvmStatic external fun rip(
        session: Long,
        title: Int,
        angle: Int,
        audioStreamIds: IntArray,
        audioFlags: IntArray,
        audioLanguages: Array<ByteArray?>,
        audioNames: Array<ByteArray?>,
        subtitleStreamIds: IntArray,
        subtitleFlags: IntArray,
        subtitleLanguages: Array<ByteArray?>,
        subtitleNames: Array<ByteArray?>,
        lpcmToFlac: Boolean,
        movieTitle: ByteArray?,
        releaseDate: ByteArray?,
        tagKeys: Array<ByteArray?>,
        tagValues: Array<ByteArray?>,
        chapterNames: Array<ByteArray?>,
        videoEncoder: ByteArray?,
        videoComponent: ByteArray?,
        videoBitRate: Long,
        videoBitrateMode: ByteArray?,
        videoColorFormat: Int,
        outputFd: Int,
        output: NativeFile?,
        listener: NativeProgress,
    ): ByteArray

    @JvmStatic external fun cancel(session: Long)

    @JvmStatic external fun close(session: Long)

    /** Reads a Matroska file back, [file] when given, else the one on [fd]; see `dr_probe.h`. Both stay the caller's. */
    @JvmStatic external fun probe(fd: Int, file: NativeFile?): ByteArray
}

/** Called from native code about twice a second while a rip runs. */
internal fun interface NativeProgress {
    fun onProgress(sectorsDone: Long, sectorsTotal: Long, outputBytes: Long, positionMs: Long, framesEncoded: Long)
}
