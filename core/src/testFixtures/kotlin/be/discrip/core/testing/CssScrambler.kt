package be.discrip.core.testing

import java.io.File
import java.io.RandomAccessFile

/**
 * CSS scrambling as a protected disc carries it: the inverse of libdvdcss's dvdcss_unscramble
 * (css.c), with its tables (csstables.h). Tests turn the clear test DVD into a protected one with
 * it; the rip engine's libdvdcss then has to recover the title key from the sectors, as on a
 * real disc, and decrypt them.
 */
object CssScrambler {

    const val BLOCK_SIZE = 2048

    /**
     * Scrambles the 2048-byte sector at [offset] of [data] in place with the 5-byte [titleKey],
     * when it is an audio or video pack: its PES header then says so and its bytes from 0x80 on
     * are scrambled. Navigation packs (system header, private stream 2) and padding stay clear.
     * Returns whether the sector was scrambled.
     */
    fun scramble(data: ByteArray, offset: Int, titleKey: ByteArray): Boolean {
        require(titleKey.size == 5) { "A title key has 5 bytes" }
        fun byte(i: Int) = data[offset + i].toInt() and 0xFF
        if (byte(0) != 0 || byte(1) != 0 || byte(2) != 1 || byte(3) != 0xBA) return false
        if (byte(0x0E) != 0 || byte(0x0F) != 0 || byte(0x10) != 1) return false
        if (byte(0x11) == 0xBB || byte(0x11) == 0xBE || byte(0x11) == 0xBF) return false
        data[offset + 0x14] = ((byte(0x14) and 0x30.inv()) or 0x10).toByte()

        fun key(i: Int) = titleKey[i].toInt() and 0xFF
        var t1 = (key(0) xor byte(0x54)) or 0x100
        var t2 = key(1) xor byte(0x55)
        var t3 = (key(2) or (key(3) shl 8) or (key(4) shl 16)) xor (byte(0x56) or (byte(0x57) shl 8) or (byte(0x58) shl 16))
        var t4 = t3 and 7
        t3 = t3 * 2 + 8 - t4
        var t5 = 0
        for (i in 0x80 until BLOCK_SIZE) {
            t4 = TAB2[t2] xor TAB3[t1]
            t2 = t1 ushr 1
            t1 = ((t1 and 1) shl 8) xor t4
            t4 = TAB5[t4]
            var t6 = (((((((t3 ushr 3) xor t3) ushr 1) xor t3) ushr 8) xor t3) ushr 5) and 0xFF
            t3 = (t3 shl 8) or t6
            t6 = TAB4[t6]
            t5 += t6 + t4
            // dvdcss_unscramble turns c into TAB1[c] xor the key stream; this undoes it.
            data[offset + i] = INVERSE_TAB1[byte(i) xor (t5 and 0xFF)].toByte()
            t5 = t5 ushr 8
        }
        return true
    }

    /** Scrambles every audio and video pack of [image] with [titleKey]; returns how many. */
    fun scrambleImage(image: File, titleKey: ByteArray): Int {
        var scrambled = 0
        RandomAccessFile(image, "rw").use { file ->
            val buffer = ByteArray(BLOCK_SIZE * 64)
            var position = 0L
            while (position < file.length()) {
                file.seek(position)
                val read = file.read(buffer)
                if (read <= 0) break
                val blocks = read / BLOCK_SIZE
                var changed = false
                for (block in 0 until blocks) {
                    if (scramble(buffer, block * BLOCK_SIZE, titleKey)) {
                        scrambled++
                        changed = true
                    }
                }
                if (changed) {
                    file.seek(position)
                    file.write(buffer, 0, blocks * BLOCK_SIZE)
                }
                position += blocks.toLong() * BLOCK_SIZE
                if (blocks == 0) break
            }
        }
        return scrambled
    }

    private val TAB1 = table(
            "33733b2663236b763e7e362b6e2e667bd393db0643034b96de9ed60b4e0e469b57175f82c787cf125a1a528fca8ac21fd999d10049094190d898d00148084091" +
            "3d7d35246d2d65743c7c34256c2c6475dd9dd5044d0d4594dc9cd4054c0c449559195180c989c11058185081c888c011d797df0247074f92da9ad20f4a0a429f" +
            "53135b86c383cb165e1e568bce8ec61bb3f3bba6e3a3ebf6befeb6abeeaee6fb37773f2267276f723a7a322f6a2a627fb9f9b1a0e9a9e1f0b8f8b0a1e8a8e0f1" +
            "5d1d5584cd8dc5145c1c5485cc8cc415bdfdb5a4edade5f4bcfcb4a5ecace4f539793120692961703878302168286071b7f7bfa2e7a7eff2bafab2afeaaae2ff",
    )

    private val INVERSE_TAB1 = IntArray(256).also { inverse -> TAB1.forEachIndexed { index, value -> inverse[value] = index } }

    private val TAB2 = table(
            "000102030405060709080b0a0d0c0f0e12131011161714151b1a19181f1e1d1c24252627202122232d2c2f2e29282b2a36373435323330313f3e3d3c3b3a3938" +
            "49484b4a4d4c4f4e40414243444546475b5a59585f5e5d5c52535051565754556d6c6f6e69686b6a64656667606162637f7e7d7c7b7a79787677747572737071" +
            "92939091969794959b9a99989f9e9d9c808182838485868789888b8a8d8c8f8eb6b7b4b5b2b3b0b1bfbebdbcbbbab9b8a4a5a6a7a0a1a2a3adacafaea9a8abaa" +
            "dbdad9d8dfdedddcd2d3d0d1d6d7d4d5c9c8cbcacdcccfcec0c1c2c3c4c5c6c7fffefdfcfbfaf9f8f6f7f4f5f2f3f0f1edecefeee9e8ebeae4e5e6e7e0e1e2e3",
    )

    private val TAB3 = table(
            "0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff" +
            "0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff" +
            "0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff" +
            "0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff" +
            "0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff" +
            "0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff" +
            "0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff" +
            "0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff0024496d92b6dbff",
    )

    private val TAB4 = table(
            "008040c020a060e0109050d030b070f0088848c828a868e8189858d838b878f8048444c424a464e4149454d434b474f40c8c4ccc2cac6cec1c9c5cdc3cbc7cfc" +
            "028242c222a262e2129252d232b272f20a8a4aca2aaa6aea1a9a5ada3aba7afa068646c626a666e6169656d636b676f60e8e4ece2eae6eee1e9e5ede3ebe7efe" +
            "018141c121a161e1119151d131b171f1098949c929a969e9199959d939b979f9058545c525a565e5159555d535b575f50d8d4dcd2dad6ded1d9d5ddd3dbd7dfd" +
            "038343c323a363e3139353d333b373f30b8b4bcb2bab6beb1b9b5bdb3bbb7bfb078747c727a767e7179757d737b777f70f8f4fcf2faf6fef1f9f5fdf3fbf7fff",
    )

    private val TAB5 = table(
            "ff7fbf3fdf5f9f1fef6faf2fcf4f8f0ff777b737d7579717e767a727c7478707fb7bbb3bdb5b9b1beb6bab2bcb4b8b0bf373b333d3539313e363a323c3438303" +
            "fd7dbd3ddd5d9d1ded6dad2dcd4d8d0df575b535d5559515e565a525c5458505f979b939d9599919e969a929c9498909f171b131d1519111e161a121c1418101" +
            "fe7ebe3ede5e9e1eee6eae2ece4e8e0ef676b636d6569616e666a626c6468606fa7aba3ada5a9a1aea6aaa2aca4a8a0af272b232d2529212e262a222c2428202" +
            "fc7cbc3cdc5c9c1cec6cac2ccc4c8c0cf474b434d4549414e464a424c4448404f878b838d8589818e868a828c8488808f070b030d0509010e060a020c0408000",
    )

    private fun table(hex: String) = IntArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16) }
}
