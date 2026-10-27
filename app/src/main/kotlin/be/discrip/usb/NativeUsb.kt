package be.discrip.usb

/** JNI entry points of libdiscrip.so (`app/src/main/cpp/usb_jni.c`), a thin layer over libusb. */
internal object NativeUsb {

    /** False when the native library could not be loaded; callers then use [AndroidBulkTransport]. */
    val isAvailable: Boolean = try {
        System.loadLibrary("discrip")
        true
    } catch (e: UnsatisfiedLinkError) {
        false
    }

    // libusb error codes (libusb.h).
    const val ERROR_IO = -1
    const val ERROR_NO_DEVICE = -4
    const val ERROR_TIMEOUT = -7
    const val ERROR_PIPE = -9
    const val ERROR_OTHER = -99

    /** Wraps an open usbfs descriptor; returns a session, or 0 with the libusb error in [error]. */
    @JvmStatic external fun open(fd: Int, error: IntArray): Long

    /** 0 or a libusb error; the bytes moved, also on failure, go to [transferred]. */
    @JvmStatic external fun bulkTransfer(
        session: Long,
        endpoint: Int,
        data: ByteArray,
        offset: Int,
        length: Int,
        timeoutMillis: Int,
        transferred: IntArray,
    ): Int

    /** Bytes moved in the data stage, or a libusb error. */
    @JvmStatic external fun controlTransfer(
        session: Long,
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        data: ByteArray?,
        length: Int,
        timeoutMillis: Int,
    ): Int

    @JvmStatic external fun clearHalt(session: Long, endpoint: Int): Int

    @JvmStatic external fun close(session: Long)

    @JvmStatic external fun errorName(code: Int): String

    @JvmStatic external fun version(): String
}
