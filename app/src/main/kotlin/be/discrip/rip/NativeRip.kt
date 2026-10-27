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

    /** Sets up libdvdcss: title keys recovered from the disc and cached in [cacheDir]. */
    @JvmStatic external fun configure(cacheDir: ByteArray)

    /** Opens the image on [fd], which native code owns from here on; 0 with [error] set on failure. */
    @JvmStatic external fun openImage(fd: Int, error: Array<ByteArray?>): Long

    @JvmStatic external fun openFolder(folder: NativeFolder, error: Array<ByteArray?>): Long

    /** `{"titles":[…]}` or `{"error":"…"}`. */
    @JvmStatic external fun titles(session: Long): ByteArray

    /** The engine's JSON report; see `dr_rip.h`. [outputFd] stays the caller's. */
    @JvmStatic external fun rip(
        session: Long,
        title: Int,
        angle: Int,
        audioStreamIds: IntArray,
        audioLanguages: Array<ByteArray?>,
        audioNames: Array<ByteArray?>,
        defaultAudio: Int,
        lpcmToFlac: Boolean,
        movieTitle: ByteArray?,
        releaseDate: ByteArray?,
        chapterNames: Array<ByteArray?>,
        outputFd: Int,
        listener: NativeProgress,
    ): ByteArray

    @JvmStatic external fun cancel(session: Long)

    @JvmStatic external fun close(session: Long)

    /** Reads the Matroska file on [fd] back; see `dr_probe.h`. [fd] stays the caller's. */
    @JvmStatic external fun probe(fd: Int): ByteArray
}

/** Called from native code about twice a second while a rip runs. */
internal fun interface NativeProgress {
    fun onProgress(sectorsDone: Long, sectorsTotal: Long, outputBytes: Long, positionMs: Long)
}
