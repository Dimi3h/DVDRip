import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import javax.inject.Inject

// AGP 9 compiles Kotlin itself (built-in Kotlin); the root build pins the Kotlin Gradle plugin
// version so the app and the core build use the same compiler.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Optional release signing: keystore.properties (git-ignored) with storeFile, storePassword,
// keyAlias and keyPassword. Without it, release builds are signed with the debug key, which is fine
// for a personal app that is sideloaded and never published.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

android {
    namespace = "be.discrip"
    // Current AndroidX, Compose and OkHttp releases require compiling against API 37. The app
    // still targets API 35 as specified; compileSdk only decides which APIs are visible.
    compileSdk = 37
    // NDK r30. With r28 and later, native libraries are 16 KB page aligned by default.
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "be.discrip"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = false
        // targetSdk 35 and arm64-v8a only are project requirements, not oversights.
        disable += setOf("OldTargetApi", "ChromeOsAbiSupport")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// The native build compiles third-party sources that are not in git. The script fetches them,
// pinned by version and SHA-256, and returns at once when they are already in place.
val fetchNativeSources by tasks.registering(Exec::class) {
    description = "Downloads the pinned third-party sources of the native build."
    val script = rootProject.file("native/scripts/fetch-sources.sh")
    inputs.file(script)
    commandLine("bash", script.absolutePath)
}

// FFmpeg is configured and built by its own script with the NDK the app uses (CMake then links
// the result). The script keeps a fingerprint and returns at once when nothing changed.
val buildFfmpeg by tasks.registering(Exec::class) {
    description = "Builds FFmpeg for arm64-v8a from the pinned sources."
    dependsOn(fetchNativeSources)
    val script = rootProject.file("native/scripts/build-ffmpeg.sh")
    val ndk = androidComponents.sdkComponents.ndkDirectory
    inputs.file(script)
    commandLine("bash", script.absolutePath, "android")
    doFirst { environment("ANDROID_NDK_HOME", ndk.get().asFile.absolutePath) }
}

tasks.named("preBuild") {
    dependsOn(fetchNativeSources, buildFfmpeg)
}

/**
 * Authors the small unencrypted DVD the instrumented rip tests use, as an image and as a VIDEO_TS
 * folder (native/scripts/make-test-dvd.sh; needs ffmpeg, dvdauthor and genisoimage).
 */
abstract class GenerateTestDvd : DefaultTask() {
    @get:InputFile
    abstract val script: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun generate() {
        val output = outputDir.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        execOperations.exec {
            commandLine("bash", script.get().asFile.absolutePath, output.resolve("test-dvd.iso").path, output.resolve("folder").path)
        }
    }
}

val generateTestDvd by tasks.registering(GenerateTestDvd::class) {
    description = "Authors the test DVD for the instrumented rip tests."
    script = rootProject.layout.projectDirectory.file("native/scripts/make-test-dvd.sh")
    outputDir = layout.buildDirectory.dir("generated/testDvd")
}

androidComponents {
    onVariants { variant ->
        variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(generateTestDvd, GenerateTestDvd::outputDir)
    }
}

dependencies {
    implementation(libs.discrip.core)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.documentfile)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.okhttp.mockwebserver)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
