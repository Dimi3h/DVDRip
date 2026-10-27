package be.discrip.core.css

/**
 * The cipher of CSS authentication between host and drive. Both sides turn the challenges they
 * exchange into key 1, key 2 and the bus key with it, in one of 32 variants the drive picks.
 *
 * Ported from CryptKey in libdvdcss's css.c (GPL-2.0-or-later, like DiscRip as a whole, which
 * links libdvdcss); the tables are libdvdcss's csstables.h. The tests check the port against
 * results printed by libdvdcss itself (`css_tool vectors`).
 */
object CssCipher {

    const val KEY1 = 0
    const val KEY2 = 1
    const val BUS_KEY = 2

    /** [challenge] is 10 bytes; the key is 5. [variant] is 0 to 31. */
    fun cryptKey(keyType: Int, variant: Int, challenge: ByteArray): ByteArray {
        require(keyType in KEY1..BUS_KEY) { "Key type $keyType" }
        require(variant in 0..31) { "Variant $variant" }
        require(challenge.size == 10) { "A challenge is 10 bytes" }

        val scratch = IntArray(10) { challenge[PERM_CHALLENGE[keyType][it]].toInt() and 0xFF }
        val cssVariant = if (keyType == KEY1) variant else PERM_VARIANT[keyType - 1][variant]

        // Two LFSRs seeded from the second half of the challenge give 30 bytes of key stream.
        val seed = IntArray(5) { scratch[5 + it] xor SECRET[it] xor TAB2[it] }
        var lfsr0 = (seed[0] shl 17) or (seed[1] shl 9) or ((seed[2] and 0xF8) shl 1) or 8 or (seed[2] and 7)
        var lfsr1 = (seed[3] shl 9) or 0x100 or seed[4]
        val bits = IntArray(30)
        var carry = 0
        for (index in 29 downTo 0) {
            var value = 0
            for (bit in 0 until 8) {
                val out0 = ((lfsr0 ushr 24) xor (lfsr0 ushr 21) xor (lfsr0 ushr 20) xor (lfsr0 ushr 12)) and 1
                lfsr0 = (lfsr0 shl 1) or out0
                val out1 = ((lfsr1 ushr 16) xor (lfsr1 ushr 2)) and 1
                lfsr1 = (lfsr1 shl 1) or out1
                val combined = (1 - out1) + carry + (1 - out0)
                carry = (combined ushr 1) and 1
                value = value or ((combined and 1) shl bit)
            }
            bits[index] = value
        }

        // Six rounds over the first half of the challenge.
        val cse = VARIANTS[cssVariant] xor TAB2[cssVariant]
        val tmp1 = IntArray(5)
        val tmp2 = IntArray(5)
        val key = IntArray(5)
        round(scratch, bits, 25, cse, substitute = false, tmp1)
        round(tmp1, bits, 20, cse, substitute = false, tmp2)
        round(tmp2, bits, 15, cse, substitute = true, tmp1)
        round(tmp1, bits, 10, cse, substitute = true, tmp2)
        round(tmp2, bits, 5, cse, substitute = false, tmp1)
        round(tmp1, bits, 0, cse, substitute = false, key, last = true)
        return ByteArray(5) { key[it].toByte() }
    }

    private fun round(input: IntArray, bits: IntArray, base: Int, cse: Int, substitute: Boolean, output: IntArray, last: Boolean = false) {
        var term = 0
        for (i in 4 downTo 0) {
            var index = bits[base + i] xor input[i]
            index = (TAB1[index] xor (TAB2[index] xor 0xFF) xor cse) and 0xFF
            output[i] = if (substitute) {
                index = (TAB2[index] xor TAB3[index] xor term) and 0xFF
                TAB0[index] xor TAB2[index]
            } else {
                (TAB2[index] xor TAB3[index] xor term) and 0xFF
            }
            term = input[i]
        }
        if (!last) output[4] = output[4] xor output[0]
    }

    private val PERM_CHALLENGE = arrayOf(
        intArrayOf(1, 3, 0, 7, 5, 2, 9, 6, 4, 8),
        intArrayOf(6, 1, 9, 3, 8, 5, 7, 4, 0, 2),
        intArrayOf(4, 0, 3, 5, 7, 2, 8, 6, 1, 9),
    )

    private val PERM_VARIANT = arrayOf(
        intArrayOf(
            0x0a, 0x08, 0x0e, 0x0c, 0x0b, 0x09, 0x0f, 0x0d, 0x1a, 0x18, 0x1e, 0x1c, 0x1b, 0x19, 0x1f, 0x1d,
            0x02, 0x00, 0x06, 0x04, 0x03, 0x01, 0x07, 0x05, 0x12, 0x10, 0x16, 0x14, 0x13, 0x11, 0x17, 0x15,
        ),
        intArrayOf(
            0x12, 0x1a, 0x16, 0x1e, 0x02, 0x0a, 0x06, 0x0e, 0x10, 0x18, 0x14, 0x1c, 0x00, 0x08, 0x04, 0x0c,
            0x13, 0x1b, 0x17, 0x1f, 0x03, 0x0b, 0x07, 0x0f, 0x11, 0x19, 0x15, 0x1d, 0x01, 0x09, 0x05, 0x0d,
        ),
    )

    private val VARIANTS = intArrayOf(
        0xB7, 0x74, 0x85, 0xD0, 0xCC, 0xDB, 0xCA, 0x73, 0x03, 0xFE, 0x31, 0x03, 0x52, 0xE0, 0xB7, 0x42,
        0x63, 0x16, 0xF2, 0x2A, 0x79, 0x52, 0xFF, 0x1B, 0x7A, 0x11, 0xCA, 0x1A, 0x9B, 0x40, 0xAD, 0x01,
    )

    /** The "secret" key. */
    private val SECRET = intArrayOf(0x55, 0xD6, 0xC4, 0xC5, 0x28)

    private val TAB0 = table(
        "b7f48257da4ddbe22f521aa8685a8afffb0e6d35f75c7612ce25792939620824a5857b56012368cf0ae25aed3d59b0a9"
        + "b02cf2b8ef32a9408071af1ede8f5888b83ad0fcc41eb5a0bb3b0f017e1f9fd9aab83d9d741e25db37568f16ba492bac"
        + "d0bd9520be7a28d05164631c7f6610bbc4561a046e0aec9cd6e89a7acf8cdbb1ef71de31ff543e5e076996b0cfdd9e47"
        + "c7968fe42b59c6eeb9869a648472e25ba29658995003f5384d027de77d75a7b86787843f1d11e5fc1ed38316a529f6c7"
        + "1561291a434f9bafc587346c0f3ba81d455825dca8a33bd1791b48f2e9931ffcdb2a90a98a3d3918a38e586ce012bb25"
        + "cd7122a264c6e7fbad9477049a39cf7c"
    )
    private val TAB1 = table(
        "8c47b0e1ebfceb5610e52c1a5defbe4f0875974b0e258e6e395a8753c41ff45c4ee69930e04288abe585bc8fd83c54c9"
        + "534718d6065b412c671e417433e2b4e0232942ea550f25b4242c9913eb0a0bc9f96367432dc77d076089d1cce7947774"
        + "9b7ed7e6ffbb68141ea325de3aa3547b879d50ca27c3a4509127d4b0824197799482acc78ea54eaa789ee042ba28eab7"
        + "74ad35da92607ed20eb9245e394f5e6309b5fabff122551ce225dbc5d8500398c4ac2e11b4384dd0b9fc2d3c08045aef"
        + "ce32fb4c921e4bfb1ad0e23eda6e7c4d56c33f42b13a234d6e845668f40e0364d0a9922f8bbc399cac095eeee597bfa5"
        + "cefa282c6d4fef77aa1b798e97b4c3f4"
    )
    private val TAB2 = table(
        "b77581d5dccade6623df152662d18377e39776afe9c36b8edab06ebf2bf119b4953448e437945d7b365f655307e28911"
        + "9885d912c19d84eca4d488b8fc2c7928d8dbb31ea2f9d044d7d660ef14f4f631d24146670ae1582743a3f8e0c8ba5a5c"
        + "806cc6f2e8ad7d040db93cc225bd49638c9f51ce20c5a150922dddbc8d4f9a712f301d733913fb1acb2459fe0596570f"
        + "1fcf54bef5061bb26dd34d325621330b52e7abeba674004cb17f8299870e5ec08fee6f55f37e0890fab66470474a17a7"
        + "b5408a38e5683e8b69aa9b42a5100135fd619ee6169c86edcd2effc45ba0aecc4b3b03bb1c2aac0c3f93c7727a09223d"
        + "4578a9a8eac96af72991f002183a4e7c"
    )
    private val TAB3 = table(
        "735195e112e4c058eef2081ba9fa984ca733e21ba76df530971df302605a820f91d09c10397a83853bb2b8ae0c0952ea"
        + "1ce18d664ff3da9229b9d5c57747225314f7af2264dfc67212f375dad7d7e5029eeddadb4c47ce9106066d558b19c9ef"
        + "8c801a0eee4babf2085ce937265e9a9000f30db2a6a3f726174888c90e2cc902e718054bf339e120020d40c7cab94830"
        + "5767cc06bfac8108247ad48b198eacb45a0f7313ac9edab6b8965b6088e1813f0786372d791452ea73df3d09c82548d8"
        + "75609a08274a2cb9a88b8a7362371602bdc10e56543e145f8c8f6e751c07397b4bdbd34b1ec87efe3e7216837deef5ca"
        + "c518f9d868ab3885a8f0a1739f5d190b00000000000000003372392567266d7136773c2062236874c382c91557165d81"
    )

    private fun table(hex: String) = IntArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16) }
}
