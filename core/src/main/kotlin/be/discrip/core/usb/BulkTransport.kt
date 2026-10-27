package be.discrip.core.usb

import java.io.Closeable
import java.io.IOException

/** The two bulk endpoints of a mass-storage interface. */
enum class Endpoint { IN, OUT }

/**
 * Why a transfer failed. libusb says so precisely; Android's UsbDeviceConnection only reports
 * "-1", so that transport classifies failures itself (elapsed time, device still attached).
 */
enum class TransferFailure { STALL_OR_ERROR, TIMEOUT, DISCONNECTED }

/** A transfer failed; [transferred] bytes had moved before it did (a device may stall mid-way). */
class UsbTransferException(
    val failure: TransferFailure,
    message: String,
    val transferred: Int = 0,
) : IOException(message)

/**
 * The raw pipes and class requests of one USB mass-storage interface (Bulk-Only Transport). On
 * Android this is libusb or UsbDeviceConnection; tests use a simulated drive.
 */
interface BulkTransport : Closeable {
    /** Sends [length] bytes on the bulk OUT endpoint; returns the number sent. */
    fun bulkOut(buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int

    /** Receives up to [length] bytes on the bulk IN endpoint; a short packet ends the transfer early. */
    fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int

    /** CLEAR_FEATURE(ENDPOINT_HALT) on [endpoint]. */
    fun clearHalt(endpoint: Endpoint)

    /** The Bulk-Only Mass Storage Reset class request. */
    fun massStorageReset()
}
