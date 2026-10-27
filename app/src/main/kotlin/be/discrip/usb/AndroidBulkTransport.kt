package be.discrip.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.SystemClock
import be.discrip.core.usb.Endpoint
import be.discrip.core.usb.TransferFailure
import be.discrip.core.usb.UsbTransferException

/**
 * Bulk-Only Transport pipes through the Java USB host API, the fallback when libusb is not
 * available. Android reports every failure as -1, so failures are classified by elapsed time and
 * by whether the device is still attached, and bytes received before a stall are lost.
 * Owns the connection: closing the transport releases the interface and closes it.
 */
class AndroidBulkTransport(
    private val connection: UsbDeviceConnection,
    private val storage: MassStorageInterface,
    private val isAttached: () -> Boolean,
) : MassStorageTransport {

    override val kind = TransportKind.ANDROID

    override fun bulkOut(buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int =
        transfer(storage.outEndpoint, buffer, offset, length, timeoutMillis)

    override fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int =
        transfer(storage.inEndpoint, buffer, offset, length, timeoutMillis)

    /**
     * CLEAR_FEATURE(ENDPOINT_HALT) as a plain control request. Unlike libusb's ioctl this leaves
     * the host's data toggle alone, which is one reason the libusb transport is preferred.
     */
    override fun clearHalt(endpoint: Endpoint) {
        val address = if (endpoint == Endpoint.IN) storage.inEndpoint.address else storage.outEndpoint.address
        control(
            MassStorageRequests.CLEAR_FEATURE_REQUEST_TYPE,
            MassStorageRequests.CLEAR_FEATURE_REQUEST,
            MassStorageRequests.ENDPOINT_HALT,
            address,
            null,
            "Clearing the halt on endpoint 0x%02X".format(address),
        )
    }

    override fun massStorageReset() {
        control(MassStorageRequests.RESET_REQUEST_TYPE, MassStorageRequests.RESET_REQUEST, 0, storage.number, null, "Mass storage reset")
    }

    override fun maxLun(): Int {
        val answer = ByteArray(1)
        val result = connection.controlTransfer(
            MassStorageRequests.MAX_LUN_REQUEST_TYPE,
            MassStorageRequests.MAX_LUN_REQUEST,
            0,
            storage.number,
            answer,
            1,
            MassStorageRequests.CONTROL_TIMEOUT_MILLIS,
        )
        // Single-LUN devices may stall this request (section 3.2).
        return if (result == 1) answer[0].toInt() and 0x0F else 0
    }

    override fun close() {
        connection.releaseInterface(storage.usbInterface)
        connection.close()
    }

    private fun transfer(endpoint: UsbEndpoint, buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size) { "Bad buffer range" }
        val started = SystemClock.elapsedRealtime()
        val result = connection.bulkTransfer(endpoint, buffer, offset, length, timeoutMillis)
        if (result >= 0) return result
        throw UsbTransferException(classify(started, timeoutMillis), "Bulk transfer on endpoint 0x%02X failed".format(endpoint.address))
    }

    private fun control(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray?, what: String) {
        val started = SystemClock.elapsedRealtime()
        val timeout = MassStorageRequests.CONTROL_TIMEOUT_MILLIS
        val result = connection.controlTransfer(requestType, request, value, index, data, data?.size ?: 0, timeout)
        if (result < 0) throw UsbTransferException(classify(started, timeout), "$what failed")
    }

    private fun classify(started: Long, timeoutMillis: Int): TransferFailure = when {
        !isAttached() -> TransferFailure.DISCONNECTED
        SystemClock.elapsedRealtime() - started >= timeoutMillis - TIMEOUT_SLACK_MILLIS -> TransferFailure.TIMEOUT
        else -> TransferFailure.STALL_OR_ERROR
    }

    private companion object {
        /** A failure this close to the timeout counts as one. */
        const val TIMEOUT_SLACK_MILLIS = 50
    }
}
