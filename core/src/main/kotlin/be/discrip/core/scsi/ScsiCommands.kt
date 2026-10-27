package be.discrip.core.scsi

import be.discrip.core.usb.Bytes

/**
 * Command descriptor blocks for the SCSI Primary Commands and MultiMedia Commands a DVD-ROM drive
 * needs. Field positions follow SPC-4 and MMC-6.
 */
object ScsiCommands {

    const val TEST_UNIT_READY = 0x00
    const val REQUEST_SENSE = 0x03
    const val INQUIRY = 0x12
    const val START_STOP_UNIT = 0x1B
    const val PREVENT_ALLOW_MEDIUM_REMOVAL = 0x1E
    const val READ_CAPACITY_10 = 0x25
    const val READ_10 = 0x28
    const val GET_CONFIGURATION = 0x46
    const val GET_EVENT_STATUS_NOTIFICATION = 0x4A
    const val SEND_KEY = 0xA3
    const val REPORT_KEY = 0xA4
    const val READ_12 = 0xA8
    const val READ_DVD_STRUCTURE = 0xAD

    /** GESN notification class request bit for the media class. */
    const val MEDIA_EVENT_CLASS = 0x10

    /** READ DVD STRUCTURE format for copyright information (CPST and region mask). */
    const val DVD_STRUCTURE_COPYRIGHT = 0x01

    /** READ DVD STRUCTURE format for the disc key, encrypted with the bus key (needs an AGID). */
    const val DVD_STRUCTURE_DISC_KEY = 0x02

    /** REPORT KEY and SEND KEY formats of CSS authentication (MMC-6, 6.29 and 6.39). */
    const val KEY_FORMAT_AGID = 0x00
    const val KEY_FORMAT_CHALLENGE = 0x01
    const val KEY_FORMAT_KEY1 = 0x02
    const val KEY_FORMAT_KEY2 = 0x03
    const val KEY_FORMAT_ASF = 0x05
    const val KEY_FORMAT_INVALIDATE_AGID = 0x3F

    /** REPORT KEY format for the drive's regional playback control state. */
    const val KEY_FORMAT_RPC_STATE = 0x08

    fun testUnitReady(): ByteArray = ByteArray(6)

    /** Fixed-format sense; 18 bytes is the standard length. */
    fun requestSense(allocationLength: Int = 18): ByteArray =
        byteArrayOf(REQUEST_SENSE.toByte(), 0, 0, 0, allocationLength.toByte(), 0)

    /** Standard INQUIRY data; 36 bytes is the length every device supports. */
    fun inquiry(allocationLength: Int = 36): ByteArray {
        val cdb = ByteArray(6)
        cdb[0] = INQUIRY.toByte()
        Bytes.putShortBe(cdb, 3, allocationLength)
        return cdb
    }

    fun readCapacity10(): ByteArray = ByteArray(10).also { it[0] = READ_CAPACITY_10.toByte() }

    fun read10(lba: Long, blocks: Int): ByteArray {
        require(lba in 0..0xFFFF_FFFFL) { "LBA out of range" }
        require(blocks in 0..0xFFFF) { "READ(10) transfers at most 65535 blocks" }
        val cdb = ByteArray(10)
        cdb[0] = READ_10.toByte()
        Bytes.putIntBe(cdb, 2, lba)
        Bytes.putShortBe(cdb, 7, blocks)
        return cdb
    }

    fun read12(lba: Long, blocks: Int): ByteArray {
        require(lba in 0..0xFFFF_FFFFL) { "LBA out of range" }
        require(blocks >= 0) { "Negative block count" }
        val cdb = ByteArray(12)
        cdb[0] = READ_12.toByte()
        Bytes.putIntBe(cdb, 2, lba)
        Bytes.putIntBe(cdb, 6, blocks.toLong())
        return cdb
    }

    fun preventAllowMediumRemoval(prevent: Boolean): ByteArray =
        byteArrayOf(PREVENT_ALLOW_MEDIUM_REMOVAL.toByte(), 0, 0, 0, if (prevent) 1 else 0, 0)

    /**
     * START STOP UNIT. With [loadEject] set, [start] false ejects the medium and true loads it
     * (closes the tray).
     */
    fun startStopUnit(start: Boolean, loadEject: Boolean, immediate: Boolean = false): ByteArray {
        var flags = 0
        if (loadEject) flags = flags or 0x02
        if (start) flags = flags or 0x01
        return byteArrayOf(START_STOP_UNIT.toByte(), if (immediate) 1 else 0, 0, 0, flags.toByte(), 0)
    }

    /** Polled GET EVENT STATUS NOTIFICATION for the media event class (MMC-6, 6.6). */
    fun getEventStatusNotification(classRequest: Int = MEDIA_EVENT_CLASS, allocationLength: Int = 8): ByteArray {
        val cdb = ByteArray(10)
        cdb[0] = GET_EVENT_STATUS_NOTIFICATION.toByte()
        cdb[1] = 0x01 // Polled
        cdb[4] = classRequest.toByte()
        Bytes.putShortBe(cdb, 7, allocationLength)
        return cdb
    }

    /** GET CONFIGURATION for the feature header only, which carries the current profile. */
    fun getConfiguration(requestType: Int = 0x02, startingFeature: Int = 0, allocationLength: Int = 8): ByteArray {
        val cdb = ByteArray(10)
        cdb[0] = GET_CONFIGURATION.toByte()
        cdb[1] = (requestType and 0x03).toByte()
        Bytes.putShortBe(cdb, 2, startingFeature)
        Bytes.putShortBe(cdb, 7, allocationLength)
        return cdb
    }

    /** READ DVD STRUCTURE (MMC: READ DISC STRUCTURE) for DVD media. */
    fun readDvdStructure(format: Int, layer: Int = 0, address: Long = 0, allocationLength: Int, agid: Int = 0): ByteArray {
        val cdb = ByteArray(12)
        cdb[0] = READ_DVD_STRUCTURE.toByte()
        Bytes.putIntBe(cdb, 2, address)
        cdb[6] = layer.toByte()
        cdb[7] = format.toByte()
        Bytes.putShortBe(cdb, 8, allocationLength)
        cdb[10] = ((agid and 0x03) shl 6).toByte()
        return cdb
    }

    /** SEND KEY for the CSS/CPPM key class; [parameterLength] bytes follow in the data-out phase. */
    fun sendKey(keyFormat: Int, parameterLength: Int, agid: Int = 0): ByteArray {
        val cdb = ByteArray(12)
        cdb[0] = SEND_KEY.toByte()
        Bytes.putShortBe(cdb, 8, parameterLength)
        cdb[10] = (((agid and 0x03) shl 6) or (keyFormat and 0x3F)).toByte()
        return cdb
    }

    /** REPORT KEY for the CSS/CPPM key class. */
    fun reportKey(keyFormat: Int, allocationLength: Int, agid: Int = 0, lba: Long = 0): ByteArray {
        val cdb = ByteArray(12)
        cdb[0] = REPORT_KEY.toByte()
        Bytes.putIntBe(cdb, 2, lba)
        Bytes.putShortBe(cdb, 8, allocationLength)
        cdb[10] = (((agid and 0x03) shl 6) or (keyFormat and 0x3F)).toByte()
        return cdb
    }
}
