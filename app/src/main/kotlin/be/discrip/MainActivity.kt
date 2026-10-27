package be.discrip

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import be.discrip.ui.navigation.DiscRipRoot
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container: AppContainer get() = (application as DiscRipApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { DiscRipRoot(container) }
        if (savedInstanceState == null) handleUsbIntent(intent)
        // The drive is polled while the app is on screen; the connection survives in between.
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { container.drives.watch() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    /** Android starts DiscRip for an attached drive when the user picked it, with permission. */
    private fun handleUsbIntent(intent: Intent) {
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) container.drives.deviceAttached()
    }
}
