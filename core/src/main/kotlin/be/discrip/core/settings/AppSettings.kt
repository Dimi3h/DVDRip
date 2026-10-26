package be.discrip.core.settings

import be.discrip.core.model.MediaKind
import be.discrip.core.model.MetadataMode
import be.discrip.core.model.QualityPreset
import be.discrip.core.model.StorageTarget
import be.discrip.core.naming.FileNameSanitizer

/**
 * Everything the user configures, except secrets (TMDB key, NAS password, Plex token), which the
 * app keeps in encrypted storage.
 */
data class AppSettings(
    val defaultPreset: QualityPreset = QualityPreset.DEFAULT,
    val videoBitratesKbps: Map<QualityPreset, Int> = emptyMap(),
    val convertLpcmToFlac: Boolean = false,
    val defaultStorage: StorageTarget = StorageTarget.LOCAL,
    val metadataMode: MetadataMode = MetadataMode.AUTOMATIC,
    val local: LocalFolderSettings = LocalFolderSettings(),
    val nas: NasSettings = NasSettings(),
    val plex: PlexSettings = PlexSettings(),
) {
    /** Video bitrate for a lossy preset, or null for the lossless copy preset. */
    fun videoBitrateKbps(preset: QualityPreset): Int? {
        val default = preset.defaultVideoBitrateKbps ?: return null
        return QualityPreset.clampBitrate(videoBitratesKbps[preset] ?: default)
    }
}

/**
 * The folder picked with ACTION_OPEN_DOCUMENT_TREE plus the sub folders (inside it) for films and
 * for home videos and "other". An empty sub folder means "straight into the picked folder".
 */
data class LocalFolderSettings(
    val treeUri: String? = null,
    val displayName: String? = null,
    val filmsFolder: String = DEFAULT_FILMS_FOLDER,
    val homeVideosFolder: String = DEFAULT_HOME_VIDEOS_FOLDER,
) {
    val isConfigured: Boolean get() = !treeUri.isNullOrBlank()

    fun baseFolder(kind: MediaKind): String = if (kind.usesHomeVideoFolder) homeVideosFolder else filmsFolder

    companion object {
        const val DEFAULT_FILMS_FOLDER = "Films"
        const val DEFAULT_HOME_VIDEOS_FOLDER = "Home Videos"
    }
}

/** SMB target on the Synology. Folders are relative to the share. */
data class NasSettings(
    val host: String = "",
    val share: String = DEFAULT_SHARE,
    val filmsFolder: String = DEFAULT_FILMS_FOLDER,
    val homeVideosFolder: String = DEFAULT_HOME_VIDEOS_FOLDER,
    val username: String = "",
    val domain: String = "",
    /** Write the MKV straight to the share instead of ripping locally first (default off). */
    val writeDirectly: Boolean = false,
) {
    val isConfigured: Boolean get() = host.isNotBlank() && share.isNotBlank()

    fun baseFolder(kind: MediaKind): String = if (kind.usesHomeVideoFolder) homeVideosFolder else filmsFolder

    companion object {
        /** Synology DSM creates a "video" shared folder by default. */
        const val DEFAULT_SHARE = "video"
        const val DEFAULT_FILMS_FOLDER = "Films"
        const val DEFAULT_HOME_VIDEOS_FOLDER = "Home Videos"
    }
}

/** Plex server and the library sections that receive films and home videos. */
data class PlexSettings(
    val serverUrl: String = "",
    val filmsSection: PlexSectionRef? = null,
    val homeVideosSection: PlexSectionRef? = null,
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank()

    fun section(kind: MediaKind): PlexSectionRef? = if (kind.usesHomeVideoFolder) homeVideosSection else filmsSection
}

data class PlexSectionRef(val key: String, val title: String)

/** Normalizes a folder setting as typed by the user into `a/b/c` form. */
fun normalizeFolderSetting(raw: String): String = FileNameSanitizer.sanitizeRelativePath(raw).joinToString("/")
