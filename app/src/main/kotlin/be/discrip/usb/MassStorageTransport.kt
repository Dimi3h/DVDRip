package be.discrip.usb

import be.discrip.core.usb.BulkTransport

enum class TransportKind { LIBUSB, ANDROID }

/** A [BulkTransport] on a real USB device, which can also say how many LUNs the device has. */
interface MassStorageTransport : BulkTransport {
    val kind: TransportKind

    /** Highest logical unit number (Get Max LUN); 0 for single-LUN devices. */
    fun maxLun(): Int
}
