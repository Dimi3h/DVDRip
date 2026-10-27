package be.discrip.core.usb

enum class DataDirection { NONE, IN, OUT }

/** Command Block Wrapper, USB Mass Storage Class Bulk-Only Transport 1.0, section 5.1. */
class CommandBlockWrapper(
    val tag: Int,
    val dataTransferLength: Int,
    val direction: DataDirection,
    val lun: Int,
    val commandBlock: ByteArray,
) {
    init {
        require(commandBlock.size in 1..MAX_COMMAND_LENGTH) { "Command block must be 1 to 16 bytes" }
        require(lun in 0..15) { "LUN must be 0 to 15" }
        require(dataTransferLength >= 0) { "Negative transfer length" }
        require(direction != DataDirection.NONE || dataTransferLength == 0) { "No data phase but a length was given" }
    }

    fun encode(): ByteArray {
        val bytes = ByteArray(SIZE)
        Bytes.putIntLe(bytes, 0, SIGNATURE)
        Bytes.putIntLe(bytes, 4, tag)
        Bytes.putIntLe(bytes, 8, dataTransferLength)
        bytes[12] = if (direction == DataDirection.IN) 0x80.toByte() else 0
        bytes[13] = lun.toByte()
        bytes[14] = commandBlock.size.toByte()
        commandBlock.copyInto(bytes, 15)
        return bytes
    }

    companion object {
        const val SIZE = 31
        const val SIGNATURE = 0x43425355 // "USBC"
        const val MAX_COMMAND_LENGTH = 16

        fun decode(bytes: ByteArray, length: Int = bytes.size): CommandBlockWrapper? {
            if (length != SIZE || Bytes.intLe(bytes, 0) != SIGNATURE) return null
            val commandLength = bytes[14].toInt() and 0x1F
            if (commandLength !in 1..MAX_COMMAND_LENGTH) return null
            val transferLength = Bytes.intLe(bytes, 8)
            val direction = when {
                transferLength == 0 -> DataDirection.NONE
                bytes[12].toInt() and 0x80 != 0 -> DataDirection.IN
                else -> DataDirection.OUT
            }
            return CommandBlockWrapper(
                tag = Bytes.intLe(bytes, 4),
                dataTransferLength = transferLength,
                direction = direction,
                lun = bytes[13].toInt() and 0x0F,
                commandBlock = bytes.copyOfRange(15, 15 + commandLength),
            )
        }
    }
}

enum class CommandStatus { PASSED, FAILED, PHASE_ERROR }

/** Command Status Wrapper, Bulk-Only Transport 1.0, section 5.2. */
data class CommandStatusWrapper(
    val tag: Int,
    val dataResidue: Int,
    val status: CommandStatus,
) {
    fun encode(): ByteArray {
        val bytes = ByteArray(SIZE)
        Bytes.putIntLe(bytes, 0, SIGNATURE)
        Bytes.putIntLe(bytes, 4, tag)
        Bytes.putIntLe(bytes, 8, dataResidue)
        bytes[12] = status.ordinal.toByte()
        return bytes
    }

    companion object {
        const val SIZE = 13
        const val SIGNATURE = 0x53425355 // "USBS"

        /** Null when [length] or the signature is wrong, or the status byte is reserved. */
        fun decode(bytes: ByteArray, length: Int = bytes.size): CommandStatusWrapper? {
            if (length != SIZE || Bytes.intLe(bytes, 0) != SIGNATURE) return null
            val status = when (bytes[12].toInt() and 0xFF) {
                0 -> CommandStatus.PASSED
                1 -> CommandStatus.FAILED
                2 -> CommandStatus.PHASE_ERROR
                else -> return null
            }
            return CommandStatusWrapper(Bytes.intLe(bytes, 4), Bytes.intLe(bytes, 8), status)
        }
    }
}

/** Little- and big-endian helpers for wire formats. */
internal object Bytes {
    fun putIntLe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }

    fun intLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    fun uintLe(bytes: ByteArray, offset: Int): Long = intLe(bytes, offset).toLong() and 0xFFFF_FFFFL

    fun ushortLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    fun longLe(bytes: ByteArray, offset: Int): Long = uintLe(bytes, offset) or (uintLe(bytes, offset + 4) shl 32)

    fun uintBe(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    fun ushortBe(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    fun putIntBe(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    fun putShortBe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }
}
