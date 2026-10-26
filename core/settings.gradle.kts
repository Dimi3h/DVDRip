// Standalone build for the platform-independent logic. The Android app consumes it as an included
// build (see the root settings file); it only needs Maven Central, so `./gradlew -p core test`
// works on machines without the Android SDK.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "core"
