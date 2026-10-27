package be.discrip.rip

import be.discrip.core.model.MovieMetadata
import be.discrip.core.model.StorageTarget
import be.discrip.core.naming.PlexNaming
import be.discrip.core.naming.RipPath
import be.discrip.core.nas.NasTarget
import be.discrip.core.settings.AppSettings
import be.discrip.core.settings.NasSettings

/** Where a rip's file goes ("Bewaren op"). */
data class RipDestination(
    val storage: StorageTarget,
    /** The local folder; null for a rip to the NAS only. */
    val local: LocalTarget? = null,
    /** The share on the NAS; null for a rip that stays local. */
    val nas: NasDestination? = null,
    /** The Plex library that gets the film once it is on the NAS; null when Plex is not set up. */
    val plex: PlexDestination? = null,
) {
    /** The engine writes to the share itself: a NAS-only rip with "write directly" on. */
    val writesDirectly: Boolean get() = local == null && nas?.settings?.writeDirectly == true

    /** The film's folder and file: in the local folder, or else on the share. */
    val path: RipPath get() = checkNotNull(local?.path ?: nas?.path) { "A destination without a place" }

    companion object {
        /**
         * The destination for [storage] with the current [settings]; null when a part it needs is
         * not set up (the local folder, or the NAS host and share).
         */
        fun plan(settings: AppSettings, storage: StorageTarget, metadata: MovieMetadata): RipDestination? {
            val local = if (storage.usesLocalFolder) {
                val treeUri = settings.local.treeUri ?: return null
                LocalTarget(treeUri, settings.local.displayName, PlexNaming.plan(metadata, settings.local.baseFolder(metadata.kind)))
            } else {
                null
            }
            val nas = if (storage.usesNas) {
                if (!settings.nas.isConfigured) return null
                NasDestination(settings.nas, PlexNaming.plan(metadata, settings.nas.baseFolder(metadata.kind)))
            } else {
                null
            }
            val section = settings.plex.section(metadata.kind)
            val plex = if (nas != null && settings.plex.isConfigured && section != null) {
                PlexDestination(settings.plex.serverUrl, section.key)
            } else {
                null
            }
            return RipDestination(storage, local, nas, plex)
        }
    }
}

/** The folder picked with ACTION_OPEN_DOCUMENT_TREE, and the file's path below it. */
data class LocalTarget(val treeUri: String, val folderName: String?, val path: RipPath) {
    /** The picked folder's name, then the path inside it: "Movies/Films/…/….mkv". */
    val displayPath: String get() = listOfNotNull(folderName, path.relativeFile).joinToString("/")
}

/** The share and the file's path on it. The password comes from the secret store when the rip runs. */
data class NasDestination(val settings: NasSettings, val path: RipPath) {

    fun target(password: String): NasTarget = NasTarget.of(settings.host, settings.share, settings.username, password, settings.domain)

    val shareName: String get() = settings.share.trim().trim('/', '\\')

    /** `smb://nas.local/video/Films/…/….mkv`, as file managers write it. */
    val location: String get() = "smb://${settings.host.trim()}/$shareName/${path.relativeFile}"
}

data class PlexDestination(val serverUrl: String, val sectionKey: String)
