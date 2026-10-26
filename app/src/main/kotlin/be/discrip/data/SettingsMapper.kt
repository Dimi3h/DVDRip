package be.discrip.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import be.discrip.core.model.QualityPreset
import be.discrip.core.settings.AppSettings
import be.discrip.core.settings.LocalFolderSettings
import be.discrip.core.settings.NasSettings
import be.discrip.core.settings.PlexSectionRef
import be.discrip.core.settings.PlexSettings

/** Maps [AppSettings] to and from DataStore preferences. Unknown or missing values fall back to defaults. */
object SettingsMapper {

    private val defaultPreset = stringPreferencesKey("default_preset")
    private fun bitrateKey(preset: QualityPreset) = intPreferencesKey("bitrate_kbps_${preset.name}")
    private val lpcmToFlac = booleanPreferencesKey("lpcm_to_flac")
    private val defaultStorage = stringPreferencesKey("default_storage")
    private val metadataMode = stringPreferencesKey("metadata_mode")

    private val localTreeUri = stringPreferencesKey("local_tree_uri")
    private val localDisplayName = stringPreferencesKey("local_display_name")
    private val localFilmsFolder = stringPreferencesKey("local_films_folder")
    private val localHomeVideosFolder = stringPreferencesKey("local_home_videos_folder")

    private val nasHost = stringPreferencesKey("nas_host")
    private val nasShare = stringPreferencesKey("nas_share")
    private val nasFilmsFolder = stringPreferencesKey("nas_films_folder")
    private val nasHomeVideosFolder = stringPreferencesKey("nas_home_videos_folder")
    private val nasUsername = stringPreferencesKey("nas_username")
    private val nasDomain = stringPreferencesKey("nas_domain")
    private val nasWriteDirectly = booleanPreferencesKey("nas_write_directly")

    private val plexUrl = stringPreferencesKey("plex_url")
    private val plexFilmsKey = stringPreferencesKey("plex_films_section_key")
    private val plexFilmsTitle = stringPreferencesKey("plex_films_section_title")
    private val plexHomeKey = stringPreferencesKey("plex_home_section_key")
    private val plexHomeTitle = stringPreferencesKey("plex_home_section_title")

    fun read(prefs: Preferences): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            defaultPreset = enumOrDefault(prefs[defaultPreset], defaults.defaultPreset),
            videoBitratesKbps = QualityPreset.lossy.mapNotNull { preset -> prefs[bitrateKey(preset)]?.let { preset to it } }.toMap(),
            convertLpcmToFlac = prefs[lpcmToFlac] ?: defaults.convertLpcmToFlac,
            defaultStorage = enumOrDefault(prefs[defaultStorage], defaults.defaultStorage),
            metadataMode = enumOrDefault(prefs[metadataMode], defaults.metadataMode),
            local = LocalFolderSettings(
                treeUri = prefs[localTreeUri],
                displayName = prefs[localDisplayName],
                filmsFolder = prefs[localFilmsFolder] ?: LocalFolderSettings.DEFAULT_FILMS_FOLDER,
                homeVideosFolder = prefs[localHomeVideosFolder] ?: LocalFolderSettings.DEFAULT_HOME_VIDEOS_FOLDER,
            ),
            nas = NasSettings(
                host = prefs[nasHost].orEmpty(),
                share = prefs[nasShare] ?: NasSettings.DEFAULT_SHARE,
                filmsFolder = prefs[nasFilmsFolder] ?: NasSettings.DEFAULT_FILMS_FOLDER,
                homeVideosFolder = prefs[nasHomeVideosFolder] ?: NasSettings.DEFAULT_HOME_VIDEOS_FOLDER,
                username = prefs[nasUsername].orEmpty(),
                domain = prefs[nasDomain].orEmpty(),
                writeDirectly = prefs[nasWriteDirectly] ?: false,
            ),
            plex = PlexSettings(
                serverUrl = prefs[plexUrl].orEmpty(),
                filmsSection = sectionRef(prefs[plexFilmsKey], prefs[plexFilmsTitle]),
                homeVideosSection = sectionRef(prefs[plexHomeKey], prefs[plexHomeTitle]),
            ),
        )
    }

    fun write(settings: AppSettings, prefs: MutablePreferences) {
        prefs[defaultPreset] = settings.defaultPreset.name
        QualityPreset.lossy.forEach { preset ->
            val kbps = settings.videoBitratesKbps[preset]
            if (kbps == null) prefs.remove(bitrateKey(preset)) else prefs[bitrateKey(preset)] = kbps
        }
        prefs[lpcmToFlac] = settings.convertLpcmToFlac
        prefs[defaultStorage] = settings.defaultStorage.name
        prefs[metadataMode] = settings.metadataMode.name

        prefs.putOrRemove(localTreeUri, settings.local.treeUri)
        prefs.putOrRemove(localDisplayName, settings.local.displayName)
        prefs[localFilmsFolder] = settings.local.filmsFolder
        prefs[localHomeVideosFolder] = settings.local.homeVideosFolder

        prefs[nasHost] = settings.nas.host
        prefs[nasShare] = settings.nas.share
        prefs[nasFilmsFolder] = settings.nas.filmsFolder
        prefs[nasHomeVideosFolder] = settings.nas.homeVideosFolder
        prefs[nasUsername] = settings.nas.username
        prefs[nasDomain] = settings.nas.domain
        prefs[nasWriteDirectly] = settings.nas.writeDirectly

        prefs[plexUrl] = settings.plex.serverUrl
        prefs.putOrRemove(plexFilmsKey, settings.plex.filmsSection?.key)
        prefs.putOrRemove(plexFilmsTitle, settings.plex.filmsSection?.title)
        prefs.putOrRemove(plexHomeKey, settings.plex.homeVideosSection?.key)
        prefs.putOrRemove(plexHomeTitle, settings.plex.homeVideosSection?.title)
    }

    private fun sectionRef(key: String?, title: String?): PlexSectionRef? =
        if (key.isNullOrBlank()) null else PlexSectionRef(key, title.orEmpty())

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    private fun MutablePreferences.putOrRemove(key: Preferences.Key<String>, value: String?) {
        if (value == null) remove(key) else this[key] = value
    }
}
