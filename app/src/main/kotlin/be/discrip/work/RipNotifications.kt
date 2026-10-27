package be.discrip.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import be.discrip.MainActivity
import be.discrip.R
import be.discrip.core.dvd.RipFailure
import be.discrip.core.format.Formats
import be.discrip.rip.DvdSource
import be.discrip.rip.NasOutcome
import be.discrip.rip.Retry
import be.discrip.rip.RipJob
import be.discrip.rip.RipStage
import be.discrip.rip.RipState
import be.discrip.ui.rip.failureTextRes
import be.discrip.ui.rip.percent
import java.util.Locale

/**
 * The notifications of [RipService]: the ongoing one with the progress of a rip or an upload,
 * and one when a rip has ended. Without the POST_NOTIFICATIONS permission Android does not show
 * them; the work runs all the same.
 */
class RipNotifications(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    init {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.notification_channel_progress), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, context.getString(R.string.notification_channel_result), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** One line on where the work stands: "Lezen en muxen · 42 %", "Lezen en encoderen · 42 % · nog 1:10:00". */
    fun progressText(running: RipState.Running?, retry: Retry?): String {
        val locale = locale()
        return when {
            running == null -> context.getString(R.string.notification_retry_upload, percent(retry?.progress?.fraction ?: 0f, locale))
            running.cancelling -> context.getString(R.string.rip_cancelling)
            running.stage == RipStage.PREPARING -> context.getString(R.string.rip_stage_preparing)
            running.stage == RipStage.PLEX -> context.getString(R.string.rip_stage_plex)
            running.stage == RipStage.UPLOADING ->
                context.getString(R.string.rip_status_uploading, percent(running.upload?.fraction ?: 0f, locale))
            running.progress == null -> context.getString(R.string.rip_status_starting)
            else -> {
                val progress = running.progress
                val phase = if (running.job.quality.isLossy) R.string.notification_encoding else R.string.notification_reading
                val done = context.getString(phase, percent(progress.fraction, locale))
                progress.remainingMs?.let { context.getString(R.string.notification_left, done, Formats.duration(it / 1000)) } ?: done
            }
        }
    }

    fun progress(running: RipState.Running?, retry: Retry?): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(running?.job?.let(::filmName) ?: context.getString(R.string.notification_retry_title))
            .setContentText(progressText(running, retry))
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        val fraction = when {
            running == null -> retry?.progress?.fraction
            running.stage == RipStage.RIPPING -> running.progress?.fraction
            running.stage == RipStage.UPLOADING -> running.upload?.fraction
            else -> null
        }
        if (fraction != null) {
            builder.setProgress(PROGRESS_MAX, (fraction * PROGRESS_MAX).toInt().coerceIn(0, PROGRESS_MAX), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        // Waiting for Plex has its own "stop waiting" on the rip screen; a retried upload runs to its end.
        if (running != null && !running.cancelling && running.stage != RipStage.PLEX) {
            val stop = PendingIntent.getService(
                context,
                0,
                Intent(context, RipService::class.java).setAction(RipService.ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, context.getString(if (running.stage == RipStage.UPLOADING) R.string.rip_cancel_upload else R.string.rip_cancel), stop)
        }
        return builder.build()
    }

    @Suppress("MissingPermission") // Without the permission Android drops it, which is fine.
    fun update(notification: Notification) {
        if (manager.areNotificationsEnabled()) manager.notify(PROGRESS_ID, notification)
    }

    /** How the rip ended, unless the user stopped it. */
    @Suppress("MissingPermission")
    fun ended(state: RipState) {
        if (!manager.areNotificationsEnabled()) return
        val (title, text) = when (state) {
            is RipState.Succeeded -> context.getString(R.string.notification_done, filmName(state.job)) to when {
                state.nas is NasOutcome.Failed -> context.getString(R.string.rip_status_done_upload_failed)
                state.job.source is DvdSource.Drive && state.discEjected -> context.getString(R.string.rip_next_disc_insert)
                else -> context.getString(R.string.rip_status_done)
            }
            is RipState.Failed -> {
                if (state.reason == RipFailure.CANCELLED) return
                context.getString(R.string.notification_failed, filmName(state.job)) to context.getString(failureTextRes(state.reason))
            }
            else -> return
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        manager.notify(RESULT_ID, notification)
    }

    /** Opens the app on the rip screen. */
    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_SHOW_RIP)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun filmName(job: RipJob): String = job.metadata.year?.let { "${job.metadata.title} ($it)" } ?: job.metadata.title

    private fun locale(): Locale = context.resources.configuration.locales[0]

    companion object {
        const val PROGRESS_ID = 1
        const val RESULT_ID = 2
        private const val CHANNEL_PROGRESS = "rip_progress"
        private const val CHANNEL_RESULT = "rip_result"
        private const val PROGRESS_MAX = 1000
    }
}
