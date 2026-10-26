package be.discrip.core.settings

import be.discrip.core.model.MediaKind
import be.discrip.core.model.QualityPreset
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppSettingsTest {

    @Test
    fun `defaults follow the preset table`() {
        val settings = AppSettings()

        assertEquals(QualityPreset.LOSSLESS, settings.defaultPreset)
        assertNull(settings.videoBitrateKbps(QualityPreset.LOSSLESS))
        assertEquals(2_500, settings.videoBitrateKbps(QualityPreset.BALANCED))
        assertEquals(1_200, settings.videoBitrateKbps(QualityPreset.MAX_COMPRESSION))
        assertEquals(3_000, settings.videoBitrateKbps(QualityPreset.COMPATIBLE))
    }

    @Test
    fun `configured bitrates are clamped`() {
        val settings = AppSettings(videoBitratesKbps = mapOf(QualityPreset.BALANCED to 50))

        assertEquals(QualityPreset.MIN_VIDEO_BITRATE_KBPS, settings.videoBitrateKbps(QualityPreset.BALANCED))
    }

    @Test
    fun `home videos and other share the home video folder`() {
        val nas = NasSettings(filmsFolder = "Films", homeVideosFolder = "Home Videos")
        val local = LocalFolderSettings(filmsFolder = "", homeVideosFolder = "Thuis")

        assertEquals("Films", nas.baseFolder(MediaKind.FILM))
        assertEquals("Home Videos", nas.baseFolder(MediaKind.HOME_VIDEO))
        assertEquals("Home Videos", nas.baseFolder(MediaKind.OTHER))
        assertEquals("", local.baseFolder(MediaKind.FILM))
        assertEquals("Thuis", local.baseFolder(MediaKind.OTHER))
    }

    @Test
    fun `plex sections are chosen per kind`() {
        val films = PlexSectionRef("1", "Films")
        val home = PlexSectionRef("4", "Thuisvideo's")
        val plex = PlexSettings("http://nas:32400", films, home)

        assertEquals(films, plex.section(MediaKind.FILM))
        assertEquals(home, plex.section(MediaKind.HOME_VIDEO))
        assertEquals(home, plex.section(MediaKind.OTHER))
    }

    @Test
    fun `folder settings are normalized`() {
        assertEquals("video/Home Videos", normalizeFolderSetting(" /video\\Home Videos/ "))
        assertEquals("", normalizeFolderSetting("/"))
    }
}
