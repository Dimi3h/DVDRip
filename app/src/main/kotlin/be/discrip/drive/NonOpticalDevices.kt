package be.discrip.drive

import android.content.Context
import android.hardware.usb.UsbDevice
import androidx.core.content.edit

/**
 * USB devices that turned out not to be optical drives. Claiming a USB stick detaches it from
 * Android's storage until it is plugged in again, so such a device is claimed at most once, and
 * again only when the user asks.
 */
class NonOpticalDevices(context: Context) {

    private val preferences = context.getSharedPreferences("usb_devices", Context.MODE_PRIVATE)

    fun contains(device: UsbDevice, serial: String?): Boolean = key(device, serial) in remembered()

    fun remember(device: UsbDevice, serial: String?) {
        val keys = (remembered() + key(device, serial)).toList().takeLast(MAX_DEVICES).toSet()
        preferences.edit { putStringSet(KEY, keys) }
    }

    fun forget(device: UsbDevice, serial: String?) {
        preferences.edit { putStringSet(KEY, remembered() - key(device, serial)) }
    }

    private fun remembered(): Set<String> = preferences.getStringSet(KEY, null).orEmpty()

    private fun key(device: UsbDevice, serial: String?) = "%04x:%04x:%s".format(device.vendorId, device.productId, serial.orEmpty())

    private companion object {
        const val KEY = "non_optical"
        const val MAX_DEVICES = 16
    }
}
