package be.discrip.core.naming

import java.text.Normalizer

/**
 * Turns titles into path components that are valid on every place a rip can land: Android's
 * ext4/f2fs/FUSE storage, FAT32 or exFAT USB sticks and SD cards, and SMB shares on a Synology
 * (which apply Windows naming rules for SMB clients).
 */
object FileNameSanitizer {

    /** Room for " (2024) {tmdb-1234567}.mkv" inside the 255-byte component limit of ext4/f2fs. */
    const val MAX_COMPONENT_BYTES = 200

    private val colonBeforeSpace = Regex("""\s*:\s+""")
    private val whitespaceRun = Regex("""\s+""")
    private val reservedWindowsNames = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        for (i in 1..9) {
            add("COM$i")
            add("LPT$i")
        }
    }

    /**
     * Sanitizes one path component. Returns an empty string when nothing usable is left; callers
     * treat that as a validation error instead of inventing a name.
     */
    fun sanitizeComponent(raw: String, maxBytes: Int = MAX_COMPONENT_BYTES): String {
        val nfc = Normalizer.normalize(raw, Normalizer.Form.NFC)
        // "Star Wars: Episode IV" reads best as "Star Wars - Episode IV"; a bare ':' becomes '-'.
        val colonsHandled = colonBeforeSpace.replace(nfc, " - ").replace(':', '-')

        val mapped = StringBuilder(colonsHandled.length)
        for (ch in colonsHandled) {
            when {
                ch.code < 0x20 || ch.code in 0x7F..0x9F -> Unit
                ch == '/' || ch == '\\' || ch == '|' || ch == '*' -> mapped.append('-')
                ch == '?' || ch == '<' || ch == '>' -> Unit
                ch == '"' -> mapped.append('\'')
                // Plex reads {tmdb-…} and {imdb-…} hints from braces; keep braces out of titles.
                ch == '{' -> mapped.append('(')
                ch == '}' -> mapped.append(')')
                else -> mapped.append(ch)
            }
        }

        var result = whitespaceRun.replace(mapped, " ").trim(' ', '.')
        result = truncateUtf8(result, maxBytes).trim(' ', '.')

        val stem = result.substringBefore('.').uppercase()
        if (stem in reservedWindowsNames) result += "_"
        return result
    }

    /**
     * Splits a user-typed folder such as `video/Home Videos`, `\Films\` or `Films//2024` into
     * sanitized segments. Empty segments and `.`/`..` are dropped, so a setting can never escape the
     * chosen root folder or share.
     */
    fun sanitizeRelativePath(raw: String): List<String> =
        raw.split('/', '\\')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .map { sanitizeComponent(it) }
            .filter { it.isNotEmpty() }

    /** Cuts [text] to at most [maxBytes] UTF-8 bytes without splitting a code point. */
    internal fun truncateUtf8(text: String, maxBytes: Int): String {
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val size = when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
            if (bytes + size > maxBytes) break
            bytes += size
            index += Character.charCount(codePoint)
        }
        return text.substring(0, index)
    }
}
