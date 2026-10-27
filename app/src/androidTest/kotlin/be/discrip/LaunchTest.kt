package be.discrip

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import be.discrip.usb.LibusbTransport
import be.discrip.usb.NativeUsb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Starts the app on a device without a drive: Home and the native USB library must come up. */
@RunWith(AndroidJUnit4::class)
class LaunchTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeShowsTheDriveSection() {
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("No drive connected") or hasText("No USB host")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("DRIVE")).assertExists()
    }

    @Test
    fun nativeUsbLibraryLoads() {
        assertTrue(NativeUsb.isAvailable)
        assertEquals("1.0.30", LibusbTransport.version)
    }
}
