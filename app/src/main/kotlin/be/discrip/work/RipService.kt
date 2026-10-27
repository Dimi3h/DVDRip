package be.discrip.work

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import be.discrip.DiscRipApplication
import be.discrip.rip.DvdSource
import be.discrip.rip.Retry
import be.discrip.rip.RipState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps DiscRip going while a rip or an upload runs, also with the screen off or the app in the
 * background: a foreground service with a progress notification, a partial wake lock, and a
 * Wi-Fi lock while files go to the NAS. The work itself runs in [be.discrip.rip.RipManager]; the
 * service follows it and stops once nothing runs.
 *
 * A rip from the drive makes it a connectedDevice service (the USB permission DiscRip holds is
 * the prerequisite) and dataSync for the upload after it, both from the start, while the app is in
 * the foreground: a type changed later, from the background, might not be allowed. Other work is
 * dataSync only, which Android 15 limits to 6 hours a day in the background.
 */
class RipService : Service() {

    private val container get() = (application as DiscRipApplication).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notifications: RipNotifications
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var foregroundType = 0

    /** Android refused connectedDevice (no USB permission after all); dataSync it is. */
    private var driveTypeRefused = false
    private var following = false

    /** The rip seen running, to tell how it ended. */
    private var lastRunning: RipState.Running? = null
    private var lastUpdate = 0L
    private var lastText: String? = null
    private var lastStage: Any? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = RipNotifications(this)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DiscRip:rip")
            .apply { setReferenceCounted(false) }
        // Replaced by a low-latency lock on Android 14 and later, which only helps while the screen
        // is on; on older versions it keeps Wi-Fi out of power save for the transfer.
        @Suppress("DEPRECATION")
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DiscRip:nas")
            ?.apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) container.rips.cancel()
        // A service started for the foreground must get there at once, even when the work has
        // just ended; following then stops it.
        show(container.rips.rip.value as? RipState.Running, container.rips.retry.value, force = true)
        if (!following) {
            following = true
            scope.launch {
                combine(container.rips.rip, container.rips.retry, ::Pair).collect { (rip, retry) -> follow(rip, retry) }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 ends dataSync services after 6 hours a day in the background. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground time used up; the work goes on without the service")
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun follow(rip: RipState, retry: Retry?) {
        val running = rip as? RipState.Running
        val ended = lastRunning
        if (running == null && ended != null && ended.job == jobOf(rip)) notifications.ended(rip)
        lastRunning = running
        if (running == null && retry == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        show(running, retry, force = false)
    }

    /** Puts the service in the foreground with the right type, updates the notification and the locks. */
    private fun show(running: RipState.Running?, retry: Retry?, force: Boolean) {
        val drive = running?.job?.source is DvdSource.Drive && !driveTypeRefused
        val type = if (drive) DRIVE_TYPES else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        val text = notifications.progressText(running, retry)
        val stage = running?.stage ?: retry?.stage
        val now = SystemClock.elapsedRealtime()
        // A rip from the drive keeps its types to the end; only the next job may change them.
        val typeChanged = type != foregroundType && !(running == null && foregroundType == DRIVE_TYPES)
        // Progress at most once a second (Android drops faster updates anyway); a new stage at once.
        val due = text != lastText && (stage != lastStage || now - lastUpdate >= MIN_UPDATE_MILLIS)
        if (!force && !typeChanged && !due) return
        lastText = text
        lastStage = stage
        lastUpdate = now
        val notification = notifications.progress(running, retry)
        if (force || typeChanged) {
            foregroundType = try {
                try {
                    ServiceCompat.startForeground(this, RipNotifications.PROGRESS_ID, notification, type)
                    type
                } catch (e: SecurityException) {
                    // The USB permission went away: carry on as data transfer.
                    driveTypeRefused = true
                    ServiceCompat.startForeground(this, RipNotifications.PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
            } catch (e: IllegalStateException) {
                // Not allowed now (on Android 15, dataSync time used up): the work goes on without it.
                Log.w(TAG, "The rip service cannot go to the foreground", e)
                stopSelf()
                return
            }
        } else {
            notifications.update(notification)
        }
        wakeLock?.acquire(WAKE_LOCK_MILLIS)
        val network = retry != null || running?.job?.destination?.nas != null || running?.job?.destination?.plex != null
        wifiLock?.let { lock ->
            if (network && !lock.isHeld) lock.acquire()
            if (!network && lock.isHeld) lock.release()
        }
    }

    private fun jobOf(state: RipState) = when (state) {
        is RipState.Succeeded -> state.job
        is RipState.Failed -> state.job
        else -> null
    }

    companion object {
        private const val TAG = "DiscRip"
        const val ACTION_STOP = "be.discrip.action.STOP_RIP"

        private const val DRIVE_TYPES = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

        /** Notification updates at most this often, unless the stage changes. */
        private const val MIN_UPDATE_MILLIS = 1_000L

        /** Renewed with every update; only a hung rip outlives it. */
        private const val WAKE_LOCK_MILLIS = 30 * 60 * 1000L

        /** Starts the service; call it when a rip or an upload starts, from the app in the foreground. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, RipService::class.java))
            } catch (e: IllegalStateException) {
                // Not allowed from the background (Android 12+): the work runs on without it.
                Log.w(TAG, "The rip service could not start", e)
            }
        }

    }
}
