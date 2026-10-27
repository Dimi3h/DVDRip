package be.discrip.ui

import android.net.Uri
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import be.discrip.DiscRipApplication
import be.discrip.rip.DvdSource
import be.discrip.ui.source.SourceScreen
import be.discrip.ui.theme.HigTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The source screen for the test DVD image, with the app's own rip manager and engine. */
@RunWith(AndroidJUnit4::class)
class SourceScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (target.applicationContext as DiscRipApplication).container
    private val iso = File(target.cacheDir, "source-screen-test.iso")
    private val source = DvdSource.Image(Uri.fromFile(iso))

    @Before
    fun copyImage() {
        InstrumentationRegistry.getInstrumentation().context.assets.open("test-dvd.iso").use { input ->
            iso.outputStream().use { input.copyTo(it) }
        }
    }

    @After
    fun closeImage() = runBlocking {
        container.rips.close(source)?.join()
        iso.delete()
        Unit
    }

    @Test
    fun listsTheTitlesWithTheMainFeatureChosenAndTheAudioInOutputOrder() {
        compose.setContent {
            HigTheme {
                SourceScreen(
                    container = container,
                    source = source,
                    backLabel = "DiscRip",
                    onBack = {},
                    onOpenMetadata = {},
                    onOpenStorageSettings = {},
                    onRipStarted = {},
                )
            }
        }
        compose.waitUntil(60_000) { compose.onAllNodes(hasText("Title 1")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("DISCRIP_TEST_DVD").assertExists()
        compose.onNodeWithText("0:12 · 3 chapters · 2 audio tracks").assertExists()
        compose.onNodeWithText("0:03 · 1 chapter · 2 audio tracks").assertExists()
        compose.onNodeWithText("Main feature").assertExists()
        compose.onNodeWithText("Dutch").assertExists()
        compose.onNodeWithText("LPCM 2.0").assertExists()
        compose.onNodeWithText("English").assertExists()
        compose.onNodeWithText("Dolby Digital 2.0").assertExists()
        compose.onNodeWithText("Choose metadata").assertExists()
    }
}
