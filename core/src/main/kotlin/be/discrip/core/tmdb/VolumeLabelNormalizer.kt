package be.discrip.core.tmdb

/** A search query derived from a disc's volume label, plus a year when the label ends with one. */
data class TitleGuess(val query: String, val year: Int?) {
    val isEmpty: Boolean get() = query.isEmpty()

    companion object {
        val EMPTY = TitleGuess("", null)
    }
}

/**
 * Guesses a film title from an ISO 9660/UDF volume label such as `LORD_OF_THE_RINGS_DISC1` or
 * `AMELIE_SPECIAL_EDITION`: separators become spaces, disc and edition markers and trailing format
 * words are dropped, a trailing year is split off and the result is title-cased.
 */
object VolumeLabelNormalizer {

    private val camelBoundary = Regex("""(?<=\p{Ll})(?=\p{Lu})""")
    private val separators = Regex("""[_.\-]+""")
    private val whitespace = Regex("""\s+""")
    private val discMarker = Regex("""^(DISC|DISK|DVD|CD|D)\d{1,2}$""")
    private val sideMarker = Regex("""^SIDE[AB12]$""")
    private val discWords = setOf("DISC", "DISK", "DVD", "CD", "SIDE")
    private val discNumbers = Regex("""^(\d{1,2}|ONE|TWO|THREE|FOUR|A|B)$""")
    private val wordWithNumber = Regex("""^(\p{L}{3,})(\d{1,2})$""")
    private val year = Regex("""^(19|20)\d{2}$""")
    private val romanNumeral = Regex("""^(X{0,3})(IX|IV|V?I{0,3})$""")

    /** Only dropped right before `EDITION`: "Blood Diamond" and "Fool's Gold" must survive. */
    private val editionQualifiers = setOf(
        "SPECIAL", "COLLECTORS", "COLLECTOR", "ULTIMATE", "LIMITED", "DELUXE", "ANNIVERSARY",
        "PLATINUM", "DEFINITIVE", "DIAMOND", "GOLD", "ANNIV", "SE", "CE", "2DISC",
    )

    /**
     * Format, region and language markers that only ever trail a title. Words that also end real
     * titles ("All of Us", "Let It Be", "The Final Cut") are deliberately absent.
     */
    private val trailingNoise = setOf(
        "WS", "FS", "WIDESCREEN", "FULLSCREEN", "PAL", "NTSC", "R1", "R2", "R3", "R4", "R5", "R6",
        "REGION", "EXTENDED", "UNRATED", "REMASTERED", "THEATRICAL", "SE", "CE", "16X9", "4X3",
        "DVD", "DVD5", "DVD9", "NL", "FR", "DE", "EN", "UK", "NLD", "DUT", "FRA", "ENG", "GER",
    )
    private val numberWords = Regex("""^(\d{1,2}|ONE|TWO|THREE|FOUR)$""")
    private val genericLabels = setOf(
        "DVD", "DVDVIDEO", "DVD VIDEO", "VIDEO", "VIDEO TS", "VIDEOTS", "DVDROM", "DVD ROM", "DISC",
        "VOLUME", "DVDVOLUME", "DVD VOLUME", "NO NAME", "NONAME", "UNTITLED", "NEW VOLUME", "CDROM",
        "MOVIE", "FILM",
    )
    private val smallWords = setOf(
        "A", "AN", "THE", "AND", "OR", "OF", "IN", "ON", "AT", "TO", "FOR", "BY", "FROM", "WITH",
        "DE", "HET", "EEN", "VAN", "EN", "OP", "LA", "LE", "LES", "DES", "DU", "DER", "DIE", "DAS",
    )

    fun guess(volumeLabel: String?): TitleGuess {
        if (volumeLabel.isNullOrBlank()) return TitleGuess.EMPTY

        val hasMixedCase = volumeLabel.any { it.isLowerCase() } && volumeLabel.any { it.isUpperCase() }
        val spaced = if (hasMixedCase) camelBoundary.replace(volumeLabel, " ") else volumeLabel
        var tokens = separators.replace(spaced, " ")
            .trim()
            .split(whitespace)
            .filter { it.isNotEmpty() }
            .map { it.uppercase() }
            .flatMap { splitWordAndNumber(it) }

        tokens = removeDiscMarkers(tokens)
        tokens = removeEditions(tokens)
        tokens = stripTrailingNoise(tokens)

        var foundYear: Int? = null
        if (tokens.size > 1 && year.matches(tokens.last())) {
            foundYear = tokens.last().toInt()
            tokens = stripTrailingNoise(tokens.dropLast(1))
        }

        if (tokens.isEmpty() || tokens.joinToString(" ") in genericLabels) return TitleGuess("", foundYear)
        return TitleGuess(titleCase(tokens), foundYear)
    }

    /** `TERMINATOR2` becomes `TERMINATOR 2`, but disc markers such as `DISC1` stay intact. */
    private fun splitWordAndNumber(token: String): List<String> {
        if (discMarker.matches(token)) return listOf(token)
        val match = wordWithNumber.matchEntire(token) ?: return listOf(token)
        return listOf(match.groupValues[1], match.groupValues[2])
    }

    private fun removeDiscMarkers(tokens: List<String>): List<String> {
        val out = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val next = tokens.getOrNull(i + 1)
            when {
                discMarker.matches(token) || sideMarker.matches(token) -> i += 1
                token in discWords && next != null && discNumbers.matches(next) -> i += 2
                else -> {
                    out += token
                    i += 1
                }
            }
        }
        return out
    }

    /**
     * Drops `EDITION` with the qualifiers right before it (`2 DISC SPECIAL EDITION` as a whole), and
     * `DIRECTORS CUT`.
     */
    private fun removeEditions(tokens: List<String>): List<String> {
        val out = ArrayList<String>(tokens.size)
        for (token in tokens) {
            when {
                token == "EDITION" -> popEditionQualifiers(out)
                token == "CUT" && out.lastOrNull().let { it == "DIRECTORS" || it == "DIRECTOR" } -> out.removeAt(out.lastIndex)
                else -> out += token
            }
        }
        return out
    }

    private fun popEditionQualifiers(out: MutableList<String>) {
        while (out.isNotEmpty()) {
            val last = out.last()
            when {
                last in editionQualifiers -> out.removeAt(out.lastIndex)
                last == "DISC" || last == "DISK" -> {
                    out.removeAt(out.lastIndex)
                    if (out.isNotEmpty() && numberWords.matches(out.last())) out.removeAt(out.lastIndex)
                }
                else -> return
            }
        }
    }

    private fun stripTrailingNoise(tokens: List<String>): List<String> {
        var end = tokens.size
        // Keep at least one token: a film called "Extended" should not vanish.
        while (end > 1 && tokens[end - 1] in trailingNoise) end--
        return tokens.subList(0, end)
    }

    private fun titleCase(tokens: List<String>): String =
        tokens.mapIndexed { index, token ->
            when {
                index > 0 && token in smallWords -> token.lowercase()
                romanNumeral.matches(token) && token.isNotEmpty() -> token
                token.all { it.isDigit() } -> token
                else -> token.lowercase().replaceFirstChar { it.titlecase() }
            }
        }.joinToString(" ")
}
