package be.discrip

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import be.discrip.ui.navigation.DiscRipRoot
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container: AppContainer get() = (application as DiscRipApplication).container

    /** Counts the times a notification asked for the rip screen. */
    private val ripScreenRequests = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { DiscRipRoot(container, ripScreenRequests.intValue) }
        if (savedInstanceState == null) handleIntent(intent)
        // The drive is polled while the app is on screen; the connection survives in between.
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { container.drives.watch() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            // Android starts DiscRip for an attached drive when the user picked it, with permission.
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> container.drives.deviceAttached()
            ACTION_SHOW_RIP -> ripScreenRequests.intValue++
        }
    }

    companion object {
        /** From the rip notifications: show the rip screen. */
        const val ACTION_SHOW_RIP = "be.discrip.action.SHOW_RIP"
    }
}
