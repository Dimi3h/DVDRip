pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DiscRip"

// Platform-independent logic (naming, presets, TMDB/Plex clients, SCSI, scoring). Kept as its own
// build so it compiles and tests without the Android SDK; Gradle substitutes be.discrip:core.
includeBuild("core")
include(":app")
