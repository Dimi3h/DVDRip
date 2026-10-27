package be.discrip.drive

import android.hardware.usb.UsbDevice
import be.discrip.core.disc.DiscState
import be.discrip.core.scsi.InquiryData
import be.discrip.usb.TransportKind

/** A USB device with a Bulk-Only mass-storage interface, as far as Android describes it unopened. */
data class UsbCandidate(
    /** usbfs path such as /dev/bus/usb/001/004; changes when the device is plugged in again. */
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val productName: String?,
    val manufacturerName: String?,
) {
    /** "ASUSTeK SDRW-08U9M-U", falling back to the USB ids. */
    val displayName: String
        get() = listOfNotNull(manufacturerName?.trim(), productName?.trim())
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" ")
            .ifEmpty { "USB %04x:%04x".format(vendorId, productId) }

    companion object {
        fun of(device: UsbDevice) = UsbCandidate(
            deviceName = device.deviceName,
            vendorId = device.vendorId,
            productId = device.productId,
            productName = device.productName,
            manufacturerName = device.manufacturerName,
        )
    }
}

/** An optical drive DiscRip has claimed. */
data class DriveInfo(
    val device: UsbCandidate,
    val inquiry: InquiryData,
    val transport: TransportKind,
    /** libusb version when [transport] is libusb. */
    val libusbVersion: String?,
) {
    val displayName: String get() = inquiry.displayName.ifEmpty { device.displayName }
}

enum class DriveFailure {
    /** UsbManager could not open the device. */
    OPEN_FAILED,

    /** Another driver or app holds the interface. */
    CLAIM_FAILED,

    /** The drive did not answer INQUIRY. */
    NO_ANSWER,

    /** The drive stopped answering while connected. */
    UNRESPONSIVE,
}

sealed interface DriveStatus {
    /** The phone has no USB host mode (OTG). */
    data object UsbHostUnsupported : DriveStatus

    /** No USB mass-storage device is attached. */
    data object NoDrive : DriveStatus

    /** A device is attached; DiscRip needs the user's permission to use it. */
    data class NeedsPermission(val device: UsbCandidate) : DriveStatus

    /** The system permission dialog is showing. */
    data class AwaitingPermission(val device: UsbCandidate) : DriveStatus

    data class PermissionDenied(val device: UsbCandidate) : DriveStatus

    data class Connecting(val device: UsbCandidate) : DriveStatus

    /** The device answered INQUIRY but is not a CD/DVD drive (a USB stick, a disk). */
    data class NotOptical(val device: UsbCandidate, val inquiry: InquiryData?) : DriveStatus

    data class Failed(val device: UsbCandidate, val reason: DriveFailure, val detail: String?) : DriveStatus

    /** [inUse]: the disc is lent to the rip engine; the drive is not polled meanwhile. */
    data class Connected(val drive: DriveInfo, val disc: DiscState, val inUse: Boolean = false) : DriveStatus
}

/** One-off outcomes of user actions, shown as alerts. */
enum class DriveMessage { EJECT_FAILED, CLOSE_TRAY_FAILED }
