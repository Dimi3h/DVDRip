package be.discrip.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/** A USB mass-storage interface that speaks Bulk-Only Transport, with its two bulk endpoints. */
class MassStorageInterface(
    val usbInterface: UsbInterface,
    val inEndpoint: UsbEndpoint,
    val outEndpoint: UsbEndpoint,
    /** USB Attached SCSI capable devices offer UAS as alternate setting 1 next to Bulk-Only. */
    val hasAlternateSettings: Boolean,
) {
    val number: Int get() = usbInterface.id

    /** The MMC subclass is what ATAPI CD/DVD drives report; disks and sticks use SCSI. */
    val looksOptical: Boolean get() = usbInterface.interfaceSubclass == SUBCLASS_MMC

    companion object {
        const val SUBCLASS_MMC = 0x02
        const val SUBCLASS_SCSI = 0x06
        const val PROTOCOL_BULK_ONLY = 0x50

        /** The first Bulk-Only mass-storage interface of [device], if it has one. */
        fun find(device: UsbDevice): MassStorageInterface? {
            for (i in 0 until device.interfaceCount) {
                val candidate = device.getInterface(i)
                if (candidate.interfaceClass != UsbConstants.USB_CLASS_MASS_STORAGE) continue
                if (candidate.interfaceSubclass != SUBCLASS_MMC && candidate.interfaceSubclass != SUBCLASS_SCSI) continue
                if (candidate.interfaceProtocol != PROTOCOL_BULK_ONLY) continue
                var bulkIn: UsbEndpoint? = null
                var bulkOut: UsbEndpoint? = null
                for (e in 0 until candidate.endpointCount) {
                    val endpoint = candidate.getEndpoint(e)
                    if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (endpoint.direction == UsbConstants.USB_DIR_IN) bulkIn = bulkIn ?: endpoint else bulkOut = bulkOut ?: endpoint
                }
                if (bulkIn != null && bulkOut != null) {
                    val alternates = (0 until device.interfaceCount).any {
                        val other = device.getInterface(it)
                        other.id == candidate.id && other.alternateSetting != candidate.alternateSetting
                    }
                    return MassStorageInterface(candidate, bulkIn, bulkOut, alternates)
                }
            }
            return null
        }
    }
}

/** Class requests of the Bulk-Only Transport and the standard request that clears a halt. */
internal object MassStorageRequests {
    const val CONTROL_TIMEOUT_MILLIS = 5_000

    /** Host to device, class, interface: Bulk-Only Mass Storage Reset (section 3.1). */
    const val RESET_REQUEST_TYPE = 0x21
    const val RESET_REQUEST = 0xFF

    /** Device to host, class, interface: Get Max LUN (section 3.2). */
    const val MAX_LUN_REQUEST_TYPE = 0xA1
    const val MAX_LUN_REQUEST = 0xFE

    /** Host to device, standard, endpoint: CLEAR_FEATURE(ENDPOINT_HALT). */
    const val CLEAR_FEATURE_REQUEST_TYPE = 0x02
    const val CLEAR_FEATURE_REQUEST = 0x01
    const val ENDPOINT_HALT = 0x00
}
