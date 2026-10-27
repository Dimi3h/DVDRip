package be.discrip.core.scsi

import be.discrip.core.usb.Bytes

/** Standard INQUIRY data (SPC-4, 6.6.2). */
data class InquiryData(
    /** Peripheral device type; 0x05 is a CD/DVD device. */
    val deviceType: Int,
    val removable: Boolean,
    val vendor: String,
    val product: String,
    val revision: String,
) {
    val isOpticalDrive: Boolean get() = deviceType == OPTICAL_DEVICE_TYPE

    /** "ASUS SDRW-08U9M-U" */
    val displayName: String get() = listOf(vendor, product).filter { it.isNotBlank() }.joinToString(" ")

    companion object {
        const val OPTICAL_DEVICE_TYPE = 0x05

        fun parse(bytes: ByteArray, length: Int): InquiryData? {
            if (length < 5) return null
            fun ascii(from: Int, to: Int): String =
                if (length < to) "" else String(bytes, from, to - from, Charsets.US_ASCII).trim { it <= ' ' }
            return InquiryData(
                deviceType = bytes[0].toInt() and 0x1F,
                removable = bytes[1].toInt() and 0x80 != 0,
                vendor = ascii(8, 16),
                product = ascii(16, 32),
                revision = ascii(32, 36),
            )
        }
    }
}

/** READ CAPACITY(10) data. */
data class Capacity(val lastLba: Long, val blockLength: Int) {
    val blockCount: Long get() = lastLba + 1

    companion object {
        fun parse(bytes: ByteArray, length: Int): Capacity? {
            if (length < 8) return null
            return Capacity(Bytes.uintBe(bytes, 0), Bytes.uintBe(bytes, 4).toInt())
        }
    }
}

enum class SenseCategory {
    NO_ERROR,
    NO_MEDIUM,
    BECOMING_READY,
    NOT_READY,
    MEDIUM_CHANGED,
    RESET,
    MEDIUM_ERROR,
    HARDWARE_ERROR,
    /** RPC-2 drive refuses a disc from another region (ASC 6Fh, ASCQ 04h/05h). */
    REGION_MISMATCH,
    /** CSS authentication problem (ASC 6Fh, other ASCQs). */
    COPY_PROTECTION,
    LBA_OUT_OF_RANGE,
    INVALID_COMMAND,
    INCOMPATIBLE_MEDIUM,
    REMOVAL_PREVENTED,
    ILLEGAL_REQUEST,
    ABORTED,
    OTHER,
}

/** Sense key, additional sense code and qualifier from REQUEST SENSE (fixed or descriptor format). */
data class SenseData(val senseKey: Int, val asc: Int, val ascq: Int) {

    val category: SenseCategory
        get() = when (senseKey) {
            KEY_NO_SENSE, KEY_RECOVERED_ERROR -> SenseCategory.NO_ERROR
            KEY_NOT_READY -> when {
                asc == 0x3A -> SenseCategory.NO_MEDIUM
                asc == 0x04 && ascq == 0x01 -> SenseCategory.BECOMING_READY
                asc == 0x30 -> SenseCategory.INCOMPATIBLE_MEDIUM
                else -> SenseCategory.NOT_READY
            }
            KEY_MEDIUM_ERROR -> if (asc == 0x30) SenseCategory.INCOMPATIBLE_MEDIUM else SenseCategory.MEDIUM_ERROR
            KEY_HARDWARE_ERROR -> SenseCategory.HARDWARE_ERROR
            KEY_ILLEGAL_REQUEST -> when (asc) {
                0x6F -> if (ascq == 0x04 || ascq == 0x05) SenseCategory.REGION_MISMATCH else SenseCategory.COPY_PROTECTION
                0x21 -> SenseCategory.LBA_OUT_OF_RANGE
                0x20 -> SenseCategory.INVALID_COMMAND
                0x30, 0x64 -> SenseCategory.INCOMPATIBLE_MEDIUM
                0x53 -> SenseCategory.REMOVAL_PREVENTED
                else -> SenseCategory.ILLEGAL_REQUEST
            }
            KEY_UNIT_ATTENTION -> when (asc) {
                0x28 -> SenseCategory.MEDIUM_CHANGED
                0x29 -> SenseCategory.RESET
                else -> SenseCategory.MEDIUM_CHANGED
            }
            KEY_ABORTED_COMMAND -> SenseCategory.ABORTED
            else -> SenseCategory.OTHER
        }

    /** Worth repeating a READ for: transient or possibly recoverable conditions. */
    val isRetryable: Boolean
        get() = category in setOf(
            SenseCategory.MEDIUM_ERROR,
            SenseCategory.HARDWARE_ERROR,
            SenseCategory.BECOMING_READY,
            SenseCategory.NOT_READY,
            SenseCategory.MEDIUM_CHANGED,
            SenseCategory.RESET,
            SenseCategory.ABORTED,
        )

    override fun toString(): String = "%X/%02X/%02X".format(senseKey, asc, ascq)

    companion object {
        const val KEY_NO_SENSE = 0x0
        const val KEY_RECOVERED_ERROR = 0x1
        const val KEY_NOT_READY = 0x2
        const val KEY_MEDIUM_ERROR = 0x3
        const val KEY_HARDWARE_ERROR = 0x4
        const val KEY_ILLEGAL_REQUEST = 0x5
        const val KEY_UNIT_ATTENTION = 0x6
        const val KEY_ABORTED_COMMAND = 0xB

        val NONE = SenseData(KEY_NO_SENSE, 0, 0)

        fun parse(bytes: ByteArray, length: Int): SenseData? {
            if (length < 1) return null
            return when (bytes[0].toInt() and 0x7F) {
                0x70, 0x71 -> if (length < 3) {
                    null
                } else {
                    SenseData(
                        senseKey = bytes[2].toInt() and 0x0F,
                        asc = if (length > 12) bytes[12].toInt() and 0xFF else 0,
                        ascq = if (length > 13) bytes[13].toInt() and 0xFF else 0,
                    )
                }
                0x72, 0x73 -> if (length < 4) {
                    null
                } else {
                    SenseData(bytes[1].toInt() and 0x0F, bytes[2].toInt() and 0xFF, bytes[3].toInt() and 0xFF)
                }
                else -> null
            }
        }
    }
}

enum class MediaEvent { NO_CHANGE, EJECT_REQUEST, NEW_MEDIA, MEDIA_REMOVAL, MEDIA_CHANGED, OTHER }

/** The media event descriptor of GET EVENT STATUS NOTIFICATION (MMC-6, 6.6.2.6). */
data class MediaEventStatus(
    val event: MediaEvent,
    val trayOpen: Boolean,
    val mediaPresent: Boolean,
) {
    companion object {
        private const val MEDIA_NOTIFICATION_CLASS = 0x04

        /** Null when no media event descriptor is present (NEA set or another class reported). */
        fun parse(bytes: ByteArray, length: Int): MediaEventStatus? {
            if (length < 8) return null
            val noEventAvailable = bytes[2].toInt() and 0x80 != 0
            val notificationClass = bytes[2].toInt() and 0x07
            if (noEventAvailable || notificationClass != MEDIA_NOTIFICATION_CLASS) return null
            val event = when (bytes[4].toInt() and 0x0F) {
                0 -> MediaEvent.NO_CHANGE
                1 -> MediaEvent.EJECT_REQUEST
                2 -> MediaEvent.NEW_MEDIA
                3 -> MediaEvent.MEDIA_REMOVAL
                4 -> MediaEvent.MEDIA_CHANGED
                else -> MediaEvent.OTHER
            }
            val status = bytes[5].toInt()
            return MediaEventStatus(event, trayOpen = status and 0x01 != 0, mediaPresent = status and 0x02 != 0)
        }
    }
}

enum class DiscType { NONE, CD, DVD, BLU_RAY, HD_DVD, OTHER }

/** MMC profile from the GET CONFIGURATION feature header. */
data class DiscProfile(val code: Int) {

    val type: DiscType
        get() = when (code) {
            0x0000 -> DiscType.NONE
            in 0x0008..0x000A -> DiscType.CD
            in 0x0010..0x002B -> DiscType.DVD
            in 0x0040..0x0043 -> DiscType.BLU_RAY
            in 0x0050..0x005A -> DiscType.HD_DVD
            else -> DiscType.OTHER
        }

    val name: String
        get() = NAMES[code] ?: "0x%04X".format(code)

    companion object {
        private val NAMES = mapOf(
            0x0008 to "CD-ROM",
            0x0009 to "CD-R",
            0x000A to "CD-RW",
            0x0010 to "DVD-ROM",
            0x0011 to "DVD-R",
            0x0012 to "DVD-RAM",
            0x0013 to "DVD-RW",
            0x0014 to "DVD-RW",
            0x0015 to "DVD-R DL",
            0x0016 to "DVD-R DL",
            0x0017 to "DVD-RW DL",
            0x001A to "DVD+RW",
            0x001B to "DVD+R",
            0x002A to "DVD+RW DL",
            0x002B to "DVD+R DL",
            0x0040 to "BD-ROM",
            0x0041 to "BD-R",
            0x0042 to "BD-R",
            0x0043 to "BD-RE",
        )

        /** Null when the header is too short; profile 0 means "no medium". */
        fun parseHeader(bytes: ByteArray, length: Int): DiscProfile? =
            if (length < 8) null else DiscProfile(Bytes.ushortBe(bytes, 6))
    }
}

/** Regions 1..8 whose bit is clear in a region mask (a set bit means "may not be played"). */
internal fun playableRegions(mask: Int): Set<Int> = (1..8).filterTo(sortedSetOf()) { mask and (1 shl (it - 1)) == 0 }

/** READ DVD STRUCTURE format 01h: copyright information of one layer. */
data class CopyrightInfo(
    /** Copy protection system type: 0 none, 1 CSS/CPPM, 2 CPRM. */
    val protectionType: Int,
    /** Region management information; only meaningful for CSS discs. */
    val regionMask: Int,
) {
    val isCss: Boolean get() = protectionType == PROTECTION_CSS

    /** Regions the disc may be played in; empty when the disc is not CSS protected. */
    val playableRegions: Set<Int> get() = if (isCss) playableRegions(regionMask) else emptySet()

    companion object {
        const val PROTECTION_NONE = 0
        const val PROTECTION_CSS = 1

        fun parse(bytes: ByteArray, length: Int): CopyrightInfo? =
            if (length < 6) null else CopyrightInfo(bytes[4].toInt() and 0xFF, bytes[5].toInt() and 0xFF)
    }
}

/** REPORT KEY format 08h: the drive's regional playback control state. */
data class RpcState(
    /** 0 no region set, 1 region set, 2 last change left, 3 region permanent. */
    val typeCode: Int,
    val vendorResetsLeft: Int,
    val userChangesLeft: Int,
    /** A set bit means the drive refuses that region. */
    val regionMask: Int,
    /** 0 RPC-I (no enforcement), 1 RPC-II (the drive enforces the region). */
    val scheme: Int,
) {
    val enforcesRegion: Boolean get() = scheme == SCHEME_RPC2
    val regionSet: Boolean get() = typeCode != TYPE_NONE
    val playableRegions: Set<Int> get() = playableRegions(regionMask)

    companion object {
        const val TYPE_NONE = 0
        const val SCHEME_RPC2 = 1

        fun parse(bytes: ByteArray, length: Int): RpcState? {
            if (length < 7) return null
            val state = bytes[4].toInt() and 0xFF
            return RpcState(
                typeCode = state shr 6,
                vendorResetsLeft = (state shr 3) and 0x07,
                userChangesLeft = state and 0x07,
                regionMask = bytes[5].toInt() and 0xFF,
                scheme = bytes[6].toInt() and 0xFF,
            )
        }
    }
}
