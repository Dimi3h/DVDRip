package be.discrip.ui

import androidx.annotation.StringRes
import be.discrip.R
import be.discrip.core.model.MediaKind
import be.discrip.core.model.MetadataMode
import be.discrip.core.model.QualityPreset
import be.discrip.core.model.StorageTarget

/** Full UI label, including "(verliesvrij)" or "(met kwaliteitsverlies)". */
@StringRes
fun QualityPreset.labelRes(): Int = when (this) {
    QualityPreset.LOSSLESS -> R.string.preset_lossless_label
    QualityPreset.BALANCED -> R.string.preset_balanced_label
    QualityPreset.MAX_COMPRESSION -> R.string.preset_max_compression_label
    QualityPreset.COMPATIBLE -> R.string.preset_compatible_label
}

@StringRes
fun QualityPreset.shortLabelRes(): Int = when (this) {
    QualityPreset.LOSSLESS -> R.string.preset_lossless_short
    QualityPreset.BALANCED -> R.string.preset_balanced_short
    QualityPreset.MAX_COMPRESSION -> R.string.preset_max_compression_short
    QualityPreset.COMPATIBLE -> R.string.preset_compatible_short
}

@StringRes
fun QualityPreset.descriptionRes(): Int = when (this) {
    QualityPreset.LOSSLESS -> R.string.preset_lossless_description
    QualityPreset.BALANCED -> R.string.preset_balanced_description
    QualityPreset.MAX_COMPRESSION -> R.string.preset_max_compression_description
    QualityPreset.COMPATIBLE -> R.string.preset_compatible_description
}

@StringRes
fun StorageTarget.labelRes(): Int = when (this) {
    StorageTarget.LOCAL -> R.string.storage_local
    StorageTarget.NAS -> R.string.storage_nas
    StorageTarget.BOTH -> R.string.storage_both
}

@StringRes
fun StorageTarget.descriptionRes(): Int = when (this) {
    StorageTarget.LOCAL -> R.string.storage_local_description
    StorageTarget.NAS -> R.string.storage_nas_description
    StorageTarget.BOTH -> R.string.storage_both_description
}

@StringRes
fun MediaKind.labelRes(): Int = when (this) {
    MediaKind.FILM -> R.string.kind_film
    MediaKind.HOME_VIDEO -> R.string.kind_home_video
    MediaKind.OTHER -> R.string.kind_other
}

@StringRes
fun MetadataMode.labelRes(): Int = when (this) {
    MetadataMode.AUTOMATIC -> R.string.metadata_mode_automatic
    MetadataMode.MANUAL -> R.string.metadata_mode_manual
}
