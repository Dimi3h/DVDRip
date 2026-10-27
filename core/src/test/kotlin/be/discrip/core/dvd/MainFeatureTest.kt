package be.discrip.core.dvd

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainFeatureTest {

    private val video = DvdVideo(2, "PAL", "16:9", 720, 576, letterboxed = false, film = true)

    /** [count] cells of [seconds] each, back to back from [startSector], at [mbps] megabit per second. */
    private fun cells(count: Int, seconds: Int, mbps: Double = 6.0, startSector: Long = 0): List<DvdCell> {
        val sectorsPerCell = (seconds * mbps * 1_000_000 / 8 / 2048).toLong()
        return (0 until count).map { i ->
            val first = startSector + i * sectorsPerCell
            DvdCell(first, first + sectorsPerCell - 1, seconds * 1000L)
        }
    }

    private fun title(number: Int, playback: List<DvdCell>, chapters: Int = 1) = DvdTitle(
        number = number,
        titleSet = 1,
        angles = 1,
        chapters = chapters,
        cells = playback.size,
        sectors = playback.sumOf { it.sectors },
        durationMs = playback.sumOf { it.durationMs },
        video = video,
        audio = emptyList(),
        subtitles = emptyList(),
        chapterStartsMs = emptyList(),
        playback = playback,
    )

    @Test
    fun `a plain disc gets its longest title`() {
        val titles = listOf(
            title(1, cells(1, 30)),
            title(2, cells(24, 280), chapters = 24),
            title(3, cells(1, 180, startSector = 9_000_000)),
        )

        assertEquals(2, MainFeature.choose(titles)?.number)
        assertNull(MainFeature.choose(emptyList()))
    }

    @Test
    fun `among decoys the title that plays its cells in order wins`() {
        val film = cells(20, 336)
        val random = Random(7)
        val titles = (1..30).map { number ->
            if (number == 17) {
                title(number, film, chapters = 20)
            } else {
                var shuffled = film.shuffled(random)
                while (shuffled == film) shuffled = film.shuffled(random)
                title(number, shuffled, chapters = 20)
            }
        }

        assertEquals(17, MainFeature.choose(titles)?.number)
        assertTrue(MainFeature.assess(titles[0]).looksScrambled)
        assertFalse(MainFeature.assess(titles[16]).looksScrambled)
    }

    @Test
    fun `a longer decoy that plays cells twice loses`() {
        val film = cells(20, 300)
        val decoy = film + film.subList(4, 10)

        assertEquals(1, MainFeature.choose(listOf(title(1, film), title(2, decoy)))?.number)
        assertEquals(6, MainFeature.assess(title(2, decoy)).repeatedCells)
    }

    @Test
    fun `a decoy whose cells claim more data than a DVD can play loses`() {
        val film = cells(20, 300)
        val stuffed = film.toMutableList().apply {
            // Twelve minutes of sectors in a 30 second cell: 72 Mbit/s.
            this[5] = DvdCell(film[5].first, film[5].first + film[5].sectors * 12 / 5, 30_000)
        }

        assertEquals(1, MainFeature.assess(title(2, stuffed)).overfullCells)
        assertEquals(1, MainFeature.choose(listOf(title(1, film), title(2, stuffed)))?.number)
    }

    @Test
    fun `a feature with an oddity still beats the extras`() {
        val feature = cells(30, 240).toMutableList().apply { add(4, removeAt(9)) }
        val titles = listOf(title(1, cells(1, 600, startSector = 20_000_000)), title(2, feature))

        assertEquals(1, MainFeature.assess(titles[1]).backwardJumps)
        assertEquals(2, MainFeature.choose(titles)?.number)
    }

    @Test
    fun `seamless branching versions both look clean and the longer cut wins`() {
        val opening = cells(10, 300)
        val theatricalBranch = cells(2, 300, startSector = opening.last().last + 1)
        val extendedBranch = cells(5, 300, startSector = theatricalBranch.last().last + 1)
        val ending = cells(10, 300, startSector = extendedBranch.last().last + 1)
        val theatrical = title(1, opening + theatricalBranch + ending)
        val extended = title(2, opening + extendedBranch + ending)

        assertFalse(MainFeature.assess(theatrical).looksScrambled)
        assertFalse(MainFeature.assess(extended).looksScrambled)
        assertEquals(2, MainFeature.choose(listOf(theatrical, extended))?.number)
    }

    @Test
    fun `identical copies go to the lowest number, equal lengths to the most chapters`() {
        val film = cells(20, 300)
        assertEquals(3, MainFeature.choose(listOf(title(5, film), title(3, film)))?.number)
        assertEquals(5, MainFeature.choose(listOf(title(3, film, chapters = 12), title(5, film, chapters = 28)))?.number)
    }

    @Test
    fun `the list shows the film and real extras, not decoys or menus`() {
        val film = cells(20, 336)
        val decoy = title(2, film.reversed())
        val titles = listOf(
            title(1, cells(1, 15)),
            decoy,
            title(3, film, chapters = 20),
            title(4, cells(4, 300, startSector = 30_000_000)),
        )

        assertEquals(listOf(3, 4), MainFeature.listed(titles, chosen = 3).map { it.number })
        // A title the user picked stays listed, short or not.
        assertEquals(listOf(1, 3, 4), MainFeature.listed(titles, chosen = 1).map { it.number })
    }

    @Test
    fun `cuts into tiny cells count against a title only as a tie breaker`() {
        val film = cells(20, 300)
        val sliced = film.flatMap { cell ->
            val half = cell.sectors / 2
            listOf(DvdCell(cell.first, cell.first + half - 1, 500), DvdCell(cell.first + half, cell.last, cell.durationMs - 500))
        }

        assertEquals(20, MainFeature.assess(title(2, sliced)).tinyCells)
        assertEquals(1, MainFeature.choose(listOf(title(2, sliced), title(1, film)))?.number)
    }
}
