package be.discrip.core.naming

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileNameSanitizerTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "Mission: Impossible | Mission - Impossible",
            "10:30 | 10-30",
            "Face/Off | Face-Off",
            "AC\\DC | AC-DC",
            "M*A*S*H | M-A-S-H",
            "What If? | What If",
            "<Untitled> | Untitled",
            "Say \"Cheese\" | Say 'Cheese'",
            "'A|B' | A-B",
            "Title {special} | Title (special)",
            "  spaced    out  | spaced out",
            "...hidden | hidden",
            "Ends with dot. | Ends with dot",
            "Amélie | Amélie",
        ],
    )
    fun `replaces characters that are illegal somewhere`(input: String, expected: String) {
        assertEquals(expected, FileNameSanitizer.sanitizeComponent(input))
    }

    @Test
    fun `control characters are dropped`() {
        assertEquals("AB", FileNameSanitizer.sanitizeComponent("A\u0000\u001F\u0085B"))
    }

    @Test
    fun `decomposed accents are composed`() {
        val decomposed = "Amélie"

        assertEquals("Amélie", FileNameSanitizer.sanitizeComponent(decomposed))
    }

    @Test
    fun `reserved windows names get a suffix`() {
        assertEquals("CON_", FileNameSanitizer.sanitizeComponent("CON"))
        assertEquals("nul.txt_", FileNameSanitizer.sanitizeComponent("nul.txt"))
        assertEquals("Console", FileNameSanitizer.sanitizeComponent("Console"))
    }

    @Test
    fun `long names are cut on a code point boundary`() {
        val title = "Ü".repeat(300)

        val result = FileNameSanitizer.sanitizeComponent(title)

        assertTrue(result.toByteArray(Charsets.UTF_8).size <= FileNameSanitizer.MAX_COMPONENT_BYTES)
        assertEquals(FileNameSanitizer.MAX_COMPONENT_BYTES / 2, result.length)
    }

    @Test
    fun `surrogate pairs are never split`() {
        val result = FileNameSanitizer.truncateUtf8("ab🎬", 5)

        assertEquals("ab", result)
    }

    @Test
    fun `relative paths drop empty and parent segments`() {
        assertEquals(listOf("video", "Home Videos"), FileNameSanitizer.sanitizeRelativePath("/video//Home Videos/"))
        assertEquals(listOf("Films"), FileNameSanitizer.sanitizeRelativePath("\\..\\Films\\.\\"))
        assertEquals(emptyList(), FileNameSanitizer.sanitizeRelativePath("   "))
    }
}
