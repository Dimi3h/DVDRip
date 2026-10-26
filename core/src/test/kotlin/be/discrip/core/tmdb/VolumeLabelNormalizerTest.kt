package be.discrip.core.tmdb

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

class VolumeLabelNormalizerTest {

    @ParameterizedTest(name = "{0} -> \"{1}\" {2}")
    @CsvSource(
        delimiter = '|',
        nullValues = ["null"],
        value = [
            "THE_MATRIX | The Matrix | null",
            "LORD_OF_THE_RINGS_DISC1 | Lord of the Rings | null",
            "SHREK_2_D1 | Shrek 2 | null",
            "AMELIE_SPECIAL_EDITION | Amelie | null",
            "GLADIATOR_EXTENDED_D1 | Gladiator | null",
            "STAR_WARS_EPISODE_IV_DISC_1 | Star Wars Episode IV | null",
            "BLADE_RUNNER_1982 | Blade Runner | 1982",
            "TERMINATOR2_WS | Terminator 2 | null",
            "de_leeuwenkoning | De Leeuwenkoning | null",
            "TheMatrixReloaded | The Matrix Reloaded | null",
            "FINDING.NEMO-D2 | Finding Nemo | null",
            "HARRY_POTTER_COLLECTORS_EDITION_PAL_R2 | Harry Potter | null",
            "TITANIC_2_DISC_SPECIAL_EDITION | Titanic | null",
            "ALIEN_DIRECTORS_CUT | Alien | null",
            "THE_FINAL_CUT | The Final Cut | null",
            "BLOOD_DIAMOND | Blood Diamond | null",
            "ALL_OF_US | All of Us | null",
            "LET_IT_BE | Let It Be | null",
            "IT_D1 | It | null",
            "MALCOLM_X | Malcolm X | null",
            "ROCKY_II_NL | Rocky II | null",
            "2012 | 2012 | null",
            "EXTENDED | Extended | null",
            "NEMO_SIDE_A | Nemo | null",
            "UP_SIDEB | Up | null",
        ],
    )
    fun `guesses titles from volume labels`(label: String, title: String, year: Int?) {
        assertEquals(TitleGuess(title, year), VolumeLabelNormalizer.guess(label))
    }

    @Test
    fun `generic labels give no query`() {
        listOf("DVD_VIDEO", "VIDEO_TS", "DVDVOLUME", "NO_NAME", "DISC_1", "DVD").forEach { label ->
            val guess = VolumeLabelNormalizer.guess(label)
            assertEquals("", guess.query, "label $label")
        }
    }

    @Test
    fun `blank and null labels give an empty guess`() {
        assertEquals(TitleGuess.EMPTY, VolumeLabelNormalizer.guess(null))
        assertEquals(TitleGuess.EMPTY, VolumeLabelNormalizer.guess("   "))
    }
}
