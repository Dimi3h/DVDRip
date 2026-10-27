package be.discrip.core.disc

import be.discrip.core.usb.Bytes

enum class FileSystemType { ISO9660, UDF }

/** What the volume descriptors and root directory say about a disc. */
data class VolumeInfo(
    /** UDF volume identifier (what DVD players and libdvdread use), else the ISO 9660 one. */
    val label: String?,
    val udfLabel: String?,
    val isoLabel: String?,
    val fileSystems: Set<FileSystemType>,
    /** Directory names in the root of the UDF and ISO 9660 trees; null when neither could be read. */
    val rootDirectories: List<String>?,
) {
    fun hasRootDirectory(name: String): Boolean = rootDirectories.orEmpty().any { it.equals(name, ignoreCase = true) }

    companion object {
        val EMPTY = VolumeInfo(null, null, null, emptySet(), null)
    }
}

private data class Extent(val lba: Long, val length: Long)

/** A UDF long_ad: an extent in one of the logical volume's partitions. */
private data class LongAd(val length: Long, val block: Long, val partition: Int)

/** What it takes to walk a UDF tree: where each partition starts and the root directory's ICB. */
private data class UdfVolume(val partitionStarts: List<Long?>, val rootIcb: LongAd)

/**
 * Reads the volume recognition area: ISO 9660 descriptors from sector 16 on (ECMA-119), the UDF
 * volume recognition sequence after them, and the UDF anchor at sector 256 (ECMA-167, OSTA UDF).
 * DVD-Video discs are UDF 1.02 discs that usually carry an ISO 9660 bridge as well.
 */
object VolumeReader {

    private const val SECTOR = BlockDevice.BLOCK_SIZE
    private const val FIRST_DESCRIPTOR_LBA = 16L
    private const val MAX_RECOGNITION_SECTORS = 64
    private const val ANCHOR_LBA = 256L
    private const val MAX_VDS_SECTORS = 64
    private const val MAX_DIRECTORY_BYTES = 64 * 1024

    private const val TAG_PRIMARY_VOLUME_DESCRIPTOR = 1
    private const val TAG_ANCHOR = 2
    private const val TAG_PARTITION_DESCRIPTOR = 5
    private const val TAG_LOGICAL_VOLUME_DESCRIPTOR = 6
    private const val TAG_TERMINATING = 8
    private const val TAG_FILE_SET_DESCRIPTOR = 256
    private const val TAG_FILE_IDENTIFIER = 257
    private const val TAG_FILE_ENTRY = 261
    private const val TAG_EXTENDED_FILE_ENTRY = 266

    private const val FILE_TYPE_DIRECTORY = 4

    fun read(device: BlockDevice): VolumeInfo {
        val sector = ByteArray(SECTOR)
        var isoLabel: String? = null
        var isoRoot: Extent? = null
        var hasIso = false
        var hasUdfSequence = false

        val end = minOf(device.blockCount, FIRST_DESCRIPTOR_LBA + MAX_RECOGNITION_SECTORS)
        var lba = FIRST_DESCRIPTOR_LBA
        scan@ while (lba < end) {
            device.read(lba, 1, sector)
            when (String(sector, 1, 5, Charsets.US_ASCII)) {
                "CD001" -> {
                    hasIso = true
                    if ((sector[0].toInt() and 0xFF) == 1 && isoRoot == null) {
                        isoLabel = isoText(sector, 40, 32)
                        isoRoot = Extent(Bytes.uintLe(sector, 158), Bytes.uintLe(sector, 166))
                    }
                }
                "NSR02", "NSR03" -> hasUdfSequence = true
                "BEA01", "TEA01", "BOOT2", "CDW02" -> Unit
                else -> break@scan
            }
            lba++
        }

        val udf = if (hasUdfSequence) readUdf(device, sector) else null
        val fileSystems = buildSet {
            if (hasIso) add(FileSystemType.ISO9660)
            if (udf != null) add(FileSystemType.UDF)
        }
        val udfLabel = udf?.volumeIdentifier ?: udf?.logicalVolumeIdentifier
        val udfDirectories = udf?.volume?.let { udfRootDirectories(device, it) }
        val isoDirectories = isoRoot?.let { isoRootDirectories(device, it) }
        val rootDirectories = if (udfDirectories == null && isoDirectories == null) {
            null
        } else {
            (udfDirectories.orEmpty() + isoDirectories.orEmpty()).distinctBy { it.uppercase() }
        }
        return VolumeInfo(
            label = udfLabel ?: isoLabel,
            udfLabel = udfLabel,
            isoLabel = isoLabel,
            fileSystems = fileSystems,
            rootDirectories = rootDirectories,
        )
    }

    private fun isoRootDirectories(device: BlockDevice, root: Extent): List<String>? {
        val sectors = ((root.length + SECTOR - 1) / SECTOR)
            .coerceIn(1, (MAX_DIRECTORY_BYTES / SECTOR).toLong())
            .coerceAtMost(device.blockCount - root.lba)
        if (root.lba < 0 || sectors <= 0) return null
        val buffer = ByteArray(sectors.toInt() * SECTOR)
        device.read(root.lba, sectors.toInt(), buffer)

        val names = mutableListOf<String>()
        for (s in 0 until sectors.toInt()) {
            var position = s * SECTOR
            val sectorEnd = position + SECTOR
            while (position + 33 < sectorEnd) {
                val recordLength = buffer[position].toInt() and 0xFF
                if (recordLength < 34 || position + recordLength > sectorEnd) break
                val isDirectory = buffer[position + 25].toInt() and 0x02 != 0
                val nameLength = buffer[position + 32].toInt() and 0xFF
                // Identifiers 0x00 and 0x01 are "." and "..".
                if (isDirectory && nameLength > 1 && 33 + nameLength <= recordLength) {
                    names += String(buffer, position + 33, nameLength, Charsets.US_ASCII).substringBefore(';')
                }
                position += recordLength
            }
        }
        return names
    }

    private class UdfInfo(val volumeIdentifier: String?, val logicalVolumeIdentifier: String?, val volume: UdfVolume?)

    private fun readUdf(device: BlockDevice, sector: ByteArray): UdfInfo? {
        val candidates = listOf(ANCHOR_LBA, device.blockCount - 256, device.blockCount - 1)
            .filter { it in ANCHOR_LBA until device.blockCount }
            .distinct()
        for (anchorLba in candidates) {
            device.read(anchorLba, 1, sector)
            if (tagIdentifier(sector, 0, anchorLba) != TAG_ANCHOR) continue
            val main = Extent(Bytes.uintLe(sector, 20), Bytes.uintLe(sector, 16))
            val reserve = Extent(Bytes.uintLe(sector, 28), Bytes.uintLe(sector, 24))
            val sequence = readVolumeDescriptorSequence(device, main, sector)
                .takeIf { it.logicalVolume != null }
                ?: readVolumeDescriptorSequence(device, reserve, sector)
            return UdfInfo(
                volumeIdentifier = sequence.volumeIdentifier,
                logicalVolumeIdentifier = sequence.logicalVolume?.identifier,
                volume = sequence.logicalVolume?.let { resolveVolume(device, it, sequence.partitions, sector) },
            )
        }
        return null
    }

    private class LogicalVolume(
        val identifier: String?,
        val blockSize: Long,
        val fileSetDescriptor: LongAd,
        /** Partition number for each partition reference; null for map types this reader skips. */
        val partitionMaps: List<Int?>,
    )

    private class DescriptorSequence(
        val volumeIdentifier: String?,
        val logicalVolume: LogicalVolume?,
        /** Partition number to starting sector. */
        val partitions: Map<Int, Long>,
    )

    private fun readVolumeDescriptorSequence(device: BlockDevice, extent: Extent, sector: ByteArray): DescriptorSequence {
        var volumeIdentifier: String? = null
        var logicalVolume: LogicalVolume? = null
        val partitions = mutableMapOf<Int, Long>()
        val count = (extent.length / SECTOR).coerceAtMost(MAX_VDS_SECTORS.toLong())
        for (i in 0 until count) {
            val lba = extent.lba + i
            if (lba >= device.blockCount) break
            device.read(lba, 1, sector)
            when (tagIdentifier(sector, 0, lba)) {
                TAG_PRIMARY_VOLUME_DESCRIPTOR -> volumeIdentifier = volumeIdentifier ?: dString(sector, 24, 32)
                TAG_PARTITION_DESCRIPTOR -> partitions.putIfAbsent(Bytes.ushortLe(sector, 22), Bytes.uintLe(sector, 188))
                TAG_LOGICAL_VOLUME_DESCRIPTOR -> if (logicalVolume == null) logicalVolume = parseLogicalVolume(sector)
                // The sequence ends at a terminating descriptor or at a sector without a valid one.
                TAG_TERMINATING, null -> break
            }
        }
        return DescriptorSequence(volumeIdentifier, logicalVolume, partitions)
    }

    private fun parseLogicalVolume(sector: ByteArray): LogicalVolume {
        val mapCount = Bytes.uintLe(sector, 268).coerceAtMost(16).toInt()
        val maps = ArrayList<Int?>(mapCount)
        var position = 440
        for (i in 0 until mapCount) {
            if (position + 2 > SECTOR) break
            val type = sector[position].toInt() and 0xFF
            val length = sector[position + 1].toInt() and 0xFF
            if (length < 2 || position + length > SECTOR) break
            // Type 1 maps point straight at a partition; type 2 (virtual, sparable, metadata) are not followed.
            maps += if (type == 1 && length == 6) Bytes.ushortLe(sector, position + 4) else null
            position += length
        }
        return LogicalVolume(
            identifier = dString(sector, 84, 128),
            blockSize = Bytes.uintLe(sector, 212),
            fileSetDescriptor = longAd(sector, 248),
            partitionMaps = maps,
        )
    }

    private fun resolveVolume(device: BlockDevice, lv: LogicalVolume, partitions: Map<Int, Long>, sector: ByteArray): UdfVolume? {
        if (lv.blockSize != SECTOR.toLong()) return null
        val starts: List<Long?> = if (lv.partitionMaps.isEmpty()) {
            listOfNotNull(partitions.values.singleOrNull())
        } else {
            lv.partitionMaps.map { number -> number?.let(partitions::get) }
        }
        val fsdStart = starts.getOrNull(lv.fileSetDescriptor.partition) ?: return null
        val fsdLba = fsdStart + lv.fileSetDescriptor.block
        if (fsdLba !in 0 until device.blockCount) return null
        device.read(fsdLba, 1, sector)
        if (tagIdentifier(sector, 0, lv.fileSetDescriptor.block) != TAG_FILE_SET_DESCRIPTOR) return null
        return UdfVolume(starts, longAd(sector, 400))
    }

    private class FileEntry(val fileType: Int, val allocationType: Int, val informationLength: Long, val descriptors: ByteArray)

    private fun readFileEntry(device: BlockDevice, volume: UdfVolume, icb: LongAd): FileEntry? {
        val start = volume.partitionStarts.getOrNull(icb.partition) ?: return null
        val lba = start + icb.block
        if (lba !in 0 until device.blockCount) return null
        val sector = ByteArray(SECTOR)
        device.read(lba, 1, sector)
        // Extended attributes come first, then the allocation descriptors; each length is a Uint32.
        val lengthsAt = when (tagIdentifier(sector, 0, icb.block)) {
            TAG_FILE_ENTRY -> 168
            TAG_EXTENDED_FILE_ENTRY -> 208
            else -> return null
        }
        val descriptorsStart = lengthsAt + 8 + Bytes.uintLe(sector, lengthsAt)
        val descriptorsLength = Bytes.uintLe(sector, lengthsAt + 4)
        if (descriptorsStart + descriptorsLength > SECTOR) return null
        return FileEntry(
            fileType = sector[27].toInt() and 0xFF,
            allocationType = Bytes.ushortLe(sector, 34) and 0x07,
            informationLength = Bytes.longLe(sector, 56),
            descriptors = sector.copyOfRange(descriptorsStart.toInt(), (descriptorsStart + descriptorsLength).toInt()),
        )
    }

    /** The first [limit] bytes of a file's data, or null for layouts this reader does not follow. */
    private fun readFileData(device: BlockDevice, volume: UdfVolume, icb: LongAd, entry: FileEntry, limit: Int): ByteArray? {
        val wanted = entry.informationLength.coerceAtMost(limit.toLong()).toInt()
        if (entry.allocationType == 3) return entry.descriptors.copyOf(minOf(wanted, entry.descriptors.size))

        val size = when (entry.allocationType) {
            0 -> 8
            1 -> 16
            else -> return null
        }
        val out = ByteArray(wanted)
        var filled = 0
        var position = 0
        while (filled < wanted && position + size <= entry.descriptors.size) {
            val raw = Bytes.uintLe(entry.descriptors, position)
            val length = raw and 0x3FFF_FFFF
            val type = (raw ushr 30).toInt()
            if (length == 0L || type == 3) break
            val block = Bytes.uintLe(entry.descriptors, position + 4)
            val partition = if (size == 16) Bytes.ushortLe(entry.descriptors, position + 8) else icb.partition
            val take = minOf(length, (wanted - filled).toLong()).toInt()
            if (type == 0) {
                val start = volume.partitionStarts.getOrNull(partition) ?: return null
                val sectors = (take + SECTOR - 1) / SECTOR
                val lba = start + block
                if (lba < 0 || lba + sectors > device.blockCount) return null
                val buffer = ByteArray(sectors * SECTOR)
                device.read(lba, sectors, buffer)
                buffer.copyInto(out, filled, 0, take)
            }
            // Allocated but unrecorded extents read as zeros, which the array already holds.
            filled += take
            position += size
        }
        return if (filled == wanted) out else out.copyOf(filled)
    }

    private fun udfRootDirectories(device: BlockDevice, volume: UdfVolume): List<String>? {
        val entry = readFileEntry(device, volume, volume.rootIcb) ?: return null
        if (entry.fileType != FILE_TYPE_DIRECTORY) return null
        val data = readFileData(device, volume, volume.rootIcb, entry, MAX_DIRECTORY_BYTES) ?: return null

        val names = mutableListOf<String>()
        var position = 0
        while (position + 38 <= data.size) {
            if (tagIdentifier(data, position, null) != TAG_FILE_IDENTIFIER) break
            val characteristics = data[position + 18].toInt() and 0xFF
            val identifierLength = data[position + 19].toInt() and 0xFF
            val implementationUseLength = Bytes.ushortLe(data, position + 36)
            val identifierStart = position + 38 + implementationUseLength
            if (identifierStart + identifierLength > data.size) break
            val isDirectory = characteristics and 0x02 != 0
            val isDeletedOrParent = characteristics and 0x0C != 0
            if (isDirectory && !isDeletedOrParent && identifierLength > 1) {
                compressedUnicode(data, identifierStart, identifierLength)?.let(names::add)
            }
            position += (38 + implementationUseLength + identifierLength + 3) and 3.inv()
        }
        return names
    }

    /**
     * The descriptor tag identifier at [offset] when the tag checksum is right and, if
     * [expectedLocation] is given, the tag says it sits there.
     */
    private fun tagIdentifier(bytes: ByteArray, offset: Int, expectedLocation: Long?): Int? {
        if (offset + 16 > bytes.size) return null
        var sum = 0
        for (i in 0 until 16) if (i != 4) sum += bytes[offset + i].toInt() and 0xFF
        if ((sum and 0xFF) != (bytes[offset + 4].toInt() and 0xFF)) return null
        if (expectedLocation != null && Bytes.uintLe(bytes, offset + 12) != expectedLocation) return null
        return Bytes.ushortLe(bytes, offset)
    }

    private fun longAd(bytes: ByteArray, offset: Int) = LongAd(
        length = Bytes.uintLe(bytes, offset) and 0x3FFF_FFFF,
        block = Bytes.uintLe(bytes, offset + 4),
        partition = Bytes.ushortLe(bytes, offset + 8),
    )

    private fun isoText(bytes: ByteArray, offset: Int, length: Int): String? =
        String(bytes, offset, length, Charsets.US_ASCII).trim { it <= ' ' }.ifEmpty { null }

    /**
     * OSTA compressed Unicode: compression ID 8 (one byte per character) or 16 (UTF-16BE), then
     * the characters; [length] includes the compression ID.
     */
    internal fun compressedUnicode(bytes: ByteArray, offset: Int, length: Int): String? {
        if (length < 1) return null
        return when (bytes[offset].toInt() and 0xFF) {
            8, 254 -> String(bytes, offset + 1, length - 1, Charsets.ISO_8859_1)
            16, 255 -> String(bytes, offset + 1, (length - 1) and 1.inv(), Charsets.UTF_16BE)
            else -> null
        }
    }

    /** A dstring: compressed Unicode whose used length sits in the field's last byte. */
    internal fun dString(bytes: ByteArray, offset: Int, fieldLength: Int): String? {
        val used = bytes[offset + fieldLength - 1].toInt() and 0xFF
        if (used < 2 || used > fieldLength - 1) return null
        return compressedUnicode(bytes, offset, used)?.trim { it <= ' ' }?.ifEmpty { null }
    }
}
