package be.discrip.core.model

/** Where a finished rip ends up ("Bewaren op"). */
enum class StorageTarget {
    LOCAL,
    NAS,
    BOTH;

    val usesLocalFolder: Boolean get() = this != NAS
    val usesNas: Boolean get() = this != LOCAL
}

/** Which path the Metadata screen opens on. */
enum class MetadataMode {
    AUTOMATIC,
    MANUAL,
}
