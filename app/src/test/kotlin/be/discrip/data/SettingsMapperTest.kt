package be.discrip.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import be.discrip.core.model.MetadataMode
import be.discrip.core.model.QualityPreset
import be.discrip.core.model.StorageTarget
import be.discrip.core.settings.AppSettings
import be.discrip.core.settings.LocalFolderSettings
import be.discrip.core.settings.NasSettings
import be.discrip.core.settings.PlexSectionRef
import be.discrip.core.settings.PlexSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsMapperTest {

    @Test
    fun `empty preferences give the defaults`() {
        assertEquals(AppSettings(), SettingsMapper.read(mutablePreferencesOf()))
    }

    @Test
    fun `every field survives a round trip`() {
        val settings = AppSettings(
            defaultPreset = QualityPreset.COMPATIBLE,
            videoBitratesKbps = mapOf(QualityPreset.BALANCED to 2_000, QualityPreset.COMPATIBLE to 3_500),
            convertLpcmToFlac = true,
            defaultStorage = StorageTarget.BOTH,
            metadataMode = MetadataMode.MANUAL,
            local = LocalFolderSettings(
                treeUri = "content://com.android.externalstorage.documents/tree/primary%3AMovies",
                displayName = "Interne opslag › Movies",
                filmsFolder = "",
                homeVideosFolder = "Thuis",
            ),
            nas = NasSettings(
                host = "nas.local",
                share = "media",
                filmsFolder = "Films",
                homeVideosFolder = "video/Home Videos",
                username = "dimi",
                domain = "WORKGROUP",
                writeDirectly = true,
            ),
            plex = PlexSettings(
                serverUrl = "http://192.168.1.10:32400",
                filmsSection = PlexSectionRef("1", "Films"),
                homeVideosSection = PlexSectionRef("4", "Thuisvideo's"),
            ),
        )
        val prefs = mutablePreferencesOf()

        SettingsMapper.write(settings, prefs)

        assertEquals(settings, SettingsMapper.read(prefs))
    }

    @Test
    fun `cleared values are removed`() {
        val prefs = mutablePreferencesOf()
        SettingsMapper.write(
            AppSettings(
                videoBitratesKbps = mapOf(QualityPreset.BALANCED to 2_000),
                plex = PlexSettings("http://x", PlexSectionRef("1", "Films"), null),
            ),
            prefs,
        )

        SettingsMapper.write(AppSettings(), prefs)

        assertEquals(AppSettings(), SettingsMapper.read(prefs))
    }

    @Test
    fun `unknown enum names fall back to defaults`() {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("default_preset") to "ULTRA",
            stringPreferencesKey("default_storage") to "CLOUD",
        )

        val settings = SettingsMapper.read(prefs)

        assertEquals(QualityPreset.LOSSLESS, settings.defaultPreset)
        assertEquals(StorageTarget.LOCAL, settings.defaultStorage)
    }
}
