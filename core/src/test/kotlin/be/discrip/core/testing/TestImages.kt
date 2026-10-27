package be.discrip.core.testing

import be.discrip.core.disc.BlockDevice
import java.io.IOException
import java.util.zip.GZIPInputStream

/** A disc image held in memory. Counts reads so tests can tell whether a disc was read again. */
class MemoryBlockDevice(private val bytes: ByteArray) : BlockDevice {
    override val blockCount: Long = (bytes.size / BlockDevice.BLOCK_SIZE).toLong()

    var readCalls = 0
        private set

    override fun read(lba: Long, blocks: Int, buffer: ByteArray, offset: Int) {
        if (lba < 0 || lba + blocks > blockCount) throw IOException("Out of range: $lba+$blocks")
        readCalls++
        System.arraycopy(bytes, (lba * BlockDevice.BLOCK_SIZE).toInt(), buffer, offset, blocks * BlockDevice.BLOCK_SIZE)
    }

    override fun close() = Unit
}

/**
 * Disc images for tests: fixtures made with genisoimage and mkudffs (see
 * `src/test/resources/images/generate.sh`) and synthetic images built here, which can be bent in
 * ways real tools refuse to.
 */
object TestImages {

    private const val SECTOR = BlockDevice.BLOCK_SIZE

    /** Unpacks `images/<name>.gz` from the test resources. */
    fun fixture(name: String): ByteArray {
        val stream = TestImages::class.java.getResourceAsStream("/images/$name.gz") ?: error("No fixture $name")
        return GZIPInputStream(stream).use { it.readBytes() }
    }

    /**
     * A DVD-Video style disc: ISO 9660 and/or UDF 1.02 trees whose root holds [directories].
     * [breakMainSequence] damages the main UDF volume descriptor sequence so readers must use the
     * reserve copy; without [anchor] the UDF structures are there but cannot be found.
     */
    fun synthetic(
        isoLabel: String? = "SYNTHETIC_DVD",
        udfLabel: String? = "Synthetic DVD",
        directories: List<String> = listOf("AUDIO_TS", "VIDEO_TS"),
        blocks: Int = 400,
        breakMainSequence: Boolean = false,
        anchor: Boolean = true,
    ): ByteArray {
        val image = ByteArray(blocks * SECTOR)
        var vrsStart = 16
        if (isoLabel != null) {
            writeIso(image, isoLabel, directories)
            vrsStart = 18
        }
        if (udfLabel != null) {
            writeUdf(image, vrsStart, udfLabel, directories, blocks)
            if (breakMainSequence) image[sectorOffset(MAIN_VDS + 2) + 4] = (image[sectorOffset(MAIN_VDS + 2) + 4] + 1).toByte()
            if (!anchor) image.fill(0, sectorOffset(ANCHOR), sectorOffset(ANCHOR + 1))
        }
        return image
    }

    // ISO 9660

    private const val ISO_ROOT = 100

    private fun writeIso(image: ByteArray, label: String, directories: List<String>) {
        val pvd = sectorOffset(16)
        image[pvd] = 1
        ascii(image, pvd + 1, "CD001")
        image[pvd + 6] = 1
        padded(image, pvd + 8, 32, "")
        padded(image, pvd + 40, 32, label)
        bothEndian32(image, pvd + 80, image.size / SECTOR.toLong())
        bothEndian16(image, pvd + 120, 1)
        bothEndian16(image, pvd + 124, 1)
        bothEndian16(image, pvd + 128, SECTOR)
        directoryRecord(image, pvd + 156, byteArrayOf(0), ISO_ROOT.toLong(), SECTOR.toLong(), directory = true)
        image[pvd + 881] = 1

        val terminator = sectorOffset(17)
        image[terminator] = 0xFF.toByte()
        ascii(image, terminator + 1, "CD001")
        image[terminator + 6] = 1

        var position = sectorOffset(ISO_ROOT)
        position += directoryRecord(image, position, byteArrayOf(0), ISO_ROOT.toLong(), SECTOR.toLong(), true)
        position += directoryRecord(image, position, byteArrayOf(1), ISO_ROOT.toLong(), SECTOR.toLong(), true)
        directories.sorted().forEachIndexed { i, name ->
            val extent = (ISO_ROOT + 1 + i).toLong()
            position += directoryRecord(image, position, name.toByteArray(Charsets.US_ASCII), extent, SECTOR.toLong(), true)
            var child = sectorOffset(extent.toInt())
            child += directoryRecord(image, child, byteArrayOf(0), extent, SECTOR.toLong(), true)
            directoryRecord(image, child, byteArrayOf(1), ISO_ROOT.toLong(), SECTOR.toLong(), true)
        }
    }

    private fun directoryRecord(image: ByteArray, at: Int, name: ByteArray, extent: Long, length: Long, directory: Boolean): Int {
        val size = (33 + name.size + 1) and 1.inv()
        image[at] = size.toByte()
        bothEndian32(image, at + 2, extent)
        bothEndian32(image, at + 10, length)
        image[at + 25] = if (directory) 0x02 else 0x00
        bothEndian16(image, at + 28, 1)
        image[at + 32] = name.size.toByte()
        name.copyInto(image, at + 33)
        return size
    }

    // UDF 1.02

    private const val MAIN_VDS = 32
    private const val RESERVE_VDS = 48
    private const val ANCHOR = 256
    private const val PARTITION_START = 260

    private fun writeUdf(image: ByteArray, vrsStart: Int, label: String, directories: List<String>, blocks: Int) {
        listOf("BEA01", "NSR02", "TEA01").forEachIndexed { i, id ->
            val at = sectorOffset(vrsStart + i)
            ascii(image, at + 1, id)
            image[at + 6] = 1
        }

        for (start in listOf(MAIN_VDS, RESERVE_VDS)) {
            primaryVolumeDescriptor(image, start, label)
            partitionDescriptor(image, start + 1, blocks - PARTITION_START)
            logicalVolumeDescriptor(image, start + 2, label)
            descriptor(image, start + 3, TAG_TERMINATING, (start + 3).toLong(), 512)
        }

        val anchor = sectorOffset(ANCHOR)
        le32(image, anchor + 16, 16L * SECTOR)
        le32(image, anchor + 20, MAIN_VDS.toLong())
        le32(image, anchor + 24, 16L * SECTOR)
        le32(image, anchor + 28, RESERVE_VDS.toLong())
        descriptor(image, ANCHOR, TAG_ANCHOR, ANCHOR.toLong(), 512)

        // Partition blocks: 0 file set descriptor, 1 root file entry, 2 root directory data,
        // then one file entry and one data block per subdirectory.
        val fsd = sectorOffset(PARTITION_START)
        dString(image, fsd + 112, 128, label)
        dString(image, fsd + 304, 32, label)
        longAd(image, fsd + 400, SECTOR.toLong(), 1)
        descriptor(image, PARTITION_START, TAG_FILE_SET_DESCRIPTOR, 0, 512)

        val rootData = ArrayList<ByteArray>()
        rootData += fileIdentifier(characteristics = 0x0A, name = null, icbBlock = 1, location = 2)
        directories.forEachIndexed { i, name ->
            val entryBlock = 3 + i * 2
            rootData += fileIdentifier(characteristics = 0x02, name = name, icbBlock = entryBlock.toLong(), location = 2)
            val childData = fileIdentifier(characteristics = 0x0A, name = null, icbBlock = 1, location = (entryBlock + 1).toLong())
            childData.copyInto(image, sectorOffset(PARTITION_START + entryBlock + 1))
            directoryEntry(image, entryBlock, (entryBlock + 1).toLong(), childData.size.toLong())
        }
        val root = rootData.fold(ByteArray(0)) { all, fid -> all + fid }
        root.copyInto(image, sectorOffset(PARTITION_START + 2))
        directoryEntry(image, 1, 2, root.size.toLong())
    }

    private fun primaryVolumeDescriptor(image: ByteArray, sector: Int, label: String) {
        val at = sectorOffset(sector)
        dString(image, at + 24, 32, label)
        le16(image, at + 56, 1)
        le16(image, at + 58, 1)
        le16(image, at + 60, 2)
        le16(image, at + 62, 2)
        dString(image, at + 72, 128, label)
        descriptor(image, sector, TAG_PRIMARY_VOLUME, sector.toLong(), 512)
    }

    private fun partitionDescriptor(image: ByteArray, sector: Int, length: Int) {
        val at = sectorOffset(sector)
        le16(image, at + 20, 1)
        le16(image, at + 22, 0)
        ascii(image, at + 25, "+NSR02")
        le32(image, at + 184, 1)
        le32(image, at + 188, PARTITION_START.toLong())
        le32(image, at + 192, length.toLong())
        descriptor(image, sector, TAG_PARTITION, sector.toLong(), 512)
    }

    private fun logicalVolumeDescriptor(image: ByteArray, sector: Int, label: String) {
        val at = sectorOffset(sector)
        dString(image, at + 84, 128, label)
        le32(image, at + 212, SECTOR.toLong())
        ascii(image, at + 217, "*OSTA UDF Compliant")
        longAd(image, at + 248, SECTOR.toLong(), 0)
        le32(image, at + 264, 6)
        le32(image, at + 268, 1)
        image[at + 440] = 1
        image[at + 441] = 6
        le16(image, at + 442, 1)
        le16(image, at + 444, 0)
        descriptor(image, sector, TAG_LOGICAL_VOLUME, sector.toLong(), 446)
    }

    /** A directory file entry at partition [block] whose data is one short_ad extent. */
    private fun directoryEntry(image: ByteArray, block: Int, dataBlock: Long, dataLength: Long) {
        val at = sectorOffset(PARTITION_START + block)
        le16(image, at + 20, 4)
        le16(image, at + 24, 1)
        image[at + 27] = 4
        le16(image, at + 48, 1)
        le32(image, at + 56, dataLength)
        le32(image, at + 64, 1)
        le32(image, at + 108, 1)
        le32(image, at + 172, 8)
        le32(image, at + 176, dataLength)
        le32(image, at + 180, dataBlock)
        descriptor(image, PARTITION_START + block, TAG_FILE_ENTRY, block.toLong(), 184)
    }

    private fun fileIdentifier(characteristics: Int, name: String?, icbBlock: Long, location: Long): ByteArray {
        val identifier = name?.let { byteArrayOf(8) + it.toByteArray(Charsets.ISO_8859_1) } ?: ByteArray(0)
        val length = 38 + identifier.size
        val fid = ByteArray((length + 3) and 3.inv())
        le16(fid, 16, 1)
        fid[18] = characteristics.toByte()
        fid[19] = identifier.size.toByte()
        longAd(fid, 20, SECTOR.toLong(), icbBlock)
        identifier.copyInto(fid, 38)
        tag(fid, 0, TAG_FILE_IDENTIFIER, location, length)
        return fid
    }

    private fun descriptor(image: ByteArray, sector: Int, id: Int, location: Long, length: Int) =
        tag(image, sectorOffset(sector), id, location, length)

    /** Writes a descriptor tag with its CRC (over [length] - 16 body bytes) and checksum. */
    private fun tag(bytes: ByteArray, at: Int, id: Int, location: Long, length: Int) {
        le16(bytes, at, id)
        le16(bytes, at + 2, 2)
        le16(bytes, at + 8, crc16(bytes, at + 16, length - 16))
        le16(bytes, at + 10, length - 16)
        le32(bytes, at + 12, location)
        var sum = 0
        for (i in 0 until 16) if (i != 4) sum += bytes[at + i].toInt() and 0xFF
        bytes[at + 4] = sum.toByte()
    }

    /** CRC-ITU-T as ECMA-167 uses it: polynomial 1021h, initial value 0. */
    private fun crc16(bytes: ByteArray, from: Int, length: Int): Int {
        var crc = 0
        for (i in from until from + length) {
            crc = crc xor ((bytes[i].toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
            crc = crc and 0xFFFF
        }
        return crc
    }

    private fun dString(bytes: ByteArray, at: Int, field: Int, text: String) {
        val encoded = byteArrayOf(8) + text.toByteArray(Charsets.ISO_8859_1)
        require(encoded.size < field) { "Text too long for a dstring[$field]" }
        encoded.copyInto(bytes, at)
        bytes[at + field - 1] = encoded.size.toByte()
    }

    private fun longAd(bytes: ByteArray, at: Int, length: Long, block: Long) {
        le32(bytes, at, length)
        le32(bytes, at + 4, block)
        le16(bytes, at + 8, 0)
    }

    private const val TAG_PRIMARY_VOLUME = 1
    private const val TAG_ANCHOR = 2
    private const val TAG_PARTITION = 5
    private const val TAG_LOGICAL_VOLUME = 6
    private const val TAG_TERMINATING = 8
    private const val TAG_FILE_SET_DESCRIPTOR = 256
    private const val TAG_FILE_IDENTIFIER = 257
    private const val TAG_FILE_ENTRY = 261

    private fun sectorOffset(sector: Int) = sector * SECTOR

    private fun ascii(bytes: ByteArray, at: Int, text: String) = text.toByteArray(Charsets.US_ASCII).copyInto(bytes, at)

    private fun padded(bytes: ByteArray, at: Int, length: Int, text: String) = ascii(bytes, at, text.padEnd(length, ' '))

    private fun le16(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = value.toByte()
        bytes[at + 1] = (value ushr 8).toByte()
    }

    private fun le32(bytes: ByteArray, at: Int, value: Long) {
        for (i in 0 until 4) bytes[at + i] = (value ushr (8 * i)).toByte()
    }

    private fun be16(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value ushr 8).toByte()
        bytes[at + 1] = value.toByte()
    }

    private fun be32(bytes: ByteArray, at: Int, value: Long) {
        for (i in 0 until 4) bytes[at + i] = (value ushr (24 - 8 * i)).toByte()
    }

    private fun bothEndian16(bytes: ByteArray, at: Int, value: Int) {
        le16(bytes, at, value)
        be16(bytes, at + 2, value)
    }

    private fun bothEndian32(bytes: ByteArray, at: Int, value: Long) {
        le32(bytes, at, value)
        be32(bytes, at + 4, value)
    }
}
