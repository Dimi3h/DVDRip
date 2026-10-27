package be.discrip.rip

import be.discrip.core.model.MediaKind
import be.discrip.core.model.MovieMetadata
import be.discrip.core.model.StorageTarget
import be.discrip.core.settings.AppSettings
import be.discrip.core.settings.LocalFolderSettings
import be.discrip.core.settings.NasSettings
import be.discrip.core.settings.PlexSectionRef
import be.discrip.core.settings.PlexSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RipDestinationTest {

    private val film = MovieMetadata(title = "The Matrix", year = 1999, tmdbId = 603)
    private val settings = AppSettings(
        local = LocalFolderSettings(treeUri = "content://tree", displayName = "Movies", filmsFolder = "Films"),
        nas = NasSettings(host = "nas.local", share = "/video/", filmsFolder = "Films/Sci-fi", homeVideosFolder = "Thuis", username = "u"),
        plex = PlexSettings(
            serverUrl = "http://nas.local:32400",
            filmsSection = PlexSectionRef("1", "Films"),
            homeVideosSection = PlexSectionRef("4", "Thuisvideo's"),
        ),
    )

    @Test
    fun `local rips stay out of the NAS and Plex`() {
        val destination = RipDestination.plan(settings, StorageTarget.LOCAL, film)!!

        assertEquals("Movies/Films/The Matrix (1999) {tmdb-603}/The Matrix (1999) {tmdb-603}.mkv", destination.local!!.displayPath)
        assertNull(destination.nas)
        assertNull(destination.plex)
        assertFalse(destination.writesDirectly)
    }

    @Test
    fun `NAS rips use the NAS folders and the Plex library of their kind`() {
        val destination = RipDestination.plan(settings, StorageTarget.NAS, film)!!

        assertNull(destination.local)
        val nas = destination.nas!!
        assertEquals("Films/Sci-fi/The Matrix (1999) {tmdb-603}/The Matrix (1999) {tmdb-603}.mkv", nas.path.relativeFile)
        assertEquals("video", nas.shareName)
        assertEquals("smb://nas.local/video/${nas.path.relativeFile}", nas.location)
        assertEquals(PlexDestination("http://nas.local:32400", "1"), destination.plex)

        val home = RipDestination.plan(settings, StorageTarget.NAS, MovieMetadata(title = "Kerst", year = null, kind = MediaKind.HOME_VIDEO))!!
        assertEquals("Thuis/Kerst/Kerst.mkv", home.nas!!.path.relativeFile)
        assertEquals("4", home.plex!!.sectionKey)
    }

    @Test
    fun `only NAS-only rips write straight to the share`() {
        val direct = settings.copy(nas = settings.nas.copy(writeDirectly = true))

        assertTrue(RipDestination.plan(direct, StorageTarget.NAS, film)!!.writesDirectly)
        val both = RipDestination.plan(direct, StorageTarget.BOTH, film)!!
        assertFalse(both.writesDirectly)
        assertEquals(both.local!!.path, both.path)
    }

    @Test
    fun `a part that is not set up gives no destination`() {
        assertNull(RipDestination.plan(settings.copy(local = LocalFolderSettings()), StorageTarget.BOTH, film))
        assertNull(RipDestination.plan(settings.copy(nas = NasSettings(host = "")), StorageTarget.NAS, film))
        // Without Plex the rip still goes to the NAS.
        assertNull(RipDestination.plan(settings.copy(plex = PlexSettings()), StorageTarget.NAS, film)!!.plex)
    }
}
