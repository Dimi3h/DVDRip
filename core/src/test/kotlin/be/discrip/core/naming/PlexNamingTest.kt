package be.discrip.core.naming

import be.discrip.core.model.MediaKind
import be.discrip.core.model.MovieMetadata
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class PlexNamingTest {

    private val matrix = MovieMetadata(title = "The Matrix", year = 1999, tmdbId = 603)

    @Test
    fun `film with tmdb id gets the id in folder and file name`() {
        val path = PlexNaming.plan(matrix, baseFolder = "Films")

        assertEquals(listOf("Films"), path.baseSegments)
        assertEquals("The Matrix (1999) {tmdb-603}", path.folderName)
        assertEquals("The Matrix (1999) {tmdb-603}.mkv", path.fileName)
        assertEquals("Films/The Matrix (1999) {tmdb-603}/The Matrix (1999) {tmdb-603}.mkv", path.relativeFile)
        assertEquals("Films/The Matrix (1999) {tmdb-603}", path.relativeFolder)
    }

    @Test
    fun `film without id is named title and year`() {
        val path = PlexNaming.plan(matrix.copy(tmdbId = null), baseFolder = "Films")

        assertEquals("Films/The Matrix (1999)/The Matrix (1999).mkv", path.relativeFile)
    }

    @Test
    fun `film with only an imdb id uses the imdb hint`() {
        val name = PlexNaming.baseName(matrix.copy(tmdbId = null, imdbId = "tt0133093"))

        assertEquals("The Matrix (1999) {imdb-tt0133093}", name)
    }

    @Test
    fun `tmdb id wins over imdb id`() {
        assertEquals("The Matrix (1999) {tmdb-603}", PlexNaming.baseName(matrix.copy(imdbId = "tt0133093")))
    }

    @Test
    fun `home video goes to its own base folder without ids`() {
        val video = MovieMetadata(
            title = "Kerstmis bij oma",
            year = 2003,
            kind = MediaKind.HOME_VIDEO,
            tmdbId = 42,
            imdbId = "tt0000042",
        )

        val path = PlexNaming.plan(video, baseFolder = "video/Home Videos")

        assertEquals(listOf("video", "Home Videos"), path.baseSegments)
        assertEquals("video/Home Videos/Kerstmis bij oma (2003)/Kerstmis bij oma (2003).mkv", path.relativeFile)
    }

    @Test
    fun `home video without a year has no year part`() {
        val video = MovieMetadata(title = "Trouw Jan en An", year = null, kind = MediaKind.HOME_VIDEO)

        assertEquals("Trouw Jan en An.mkv", PlexNaming.fileName(video))
    }

    @Test
    fun `other kind behaves like a home video`() {
        val other = MovieMetadata(title = "Concert", year = 2010, kind = MediaKind.OTHER, tmdbId = 7)

        assertEquals("Concert (2010)", PlexNaming.baseName(other))
    }

    @Test
    fun `empty base folder puts the movie folder in the root`() {
        val path = PlexNaming.plan(matrix, baseFolder = "  ")

        assertEquals("The Matrix (1999) {tmdb-603}/The Matrix (1999) {tmdb-603}.mkv", path.relativeFile)
    }

    @Test
    fun `titles are sanitized for every file system`() {
        val starWars = MovieMetadata(title = "Star Wars: Episode IV - A New Hope", year = 1977, tmdbId = 11)

        assertEquals("Star Wars - Episode IV - A New Hope (1977) {tmdb-11}", PlexNaming.baseName(starWars))
    }

    @Test
    fun `unusable title is rejected`() {
        assertThrows<IllegalArgumentException> { PlexNaming.baseName(matrix.copy(title = "???")) }
    }
}
