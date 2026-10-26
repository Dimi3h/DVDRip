package be.discrip.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data object SettingsRoute

/** [volumeLabel] seeds the TMDB search; null when the screen is opened without a disc. */
@Serializable
data class MetadataRoute(val volumeLabel: String? = null)

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
