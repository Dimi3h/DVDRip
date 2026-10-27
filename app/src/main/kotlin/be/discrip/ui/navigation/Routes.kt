package be.discrip.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data object HistoryRoute

@Serializable
data object SettingsRoute

/** [volumeLabel] seeds the TMDB search; null when the screen is opened without a disc. */
@Serializable
data class MetadataRoute(val volumeLabel: String? = null)

/** A DVD picked in the document UI: an ISO image, or a VIDEO_TS folder when [folder] is true. */
@Serializable
data class SourceRoute(val folder: Boolean, val uri: String)

/** The running or last rip. */
@Serializable
data object RipRoute

@Serializable
data object QualitySettingsRoute

@Serializable
data object MetadataSettingsRoute

@Serializable
data object StorageSettingsRoute

@Serializable
data object NasSettingsRoute

@Serializable
data object PlexSettingsRoute
