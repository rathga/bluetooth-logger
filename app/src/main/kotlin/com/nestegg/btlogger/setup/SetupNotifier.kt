package com.nestegg.btlogger.setup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nestegg.btlogger.R
import com.nestegg.btlogger.ui.MainActivity

object SetupNotifier {

    private const val CHANNEL_ID = "setup-health"
    private const val NOTIFICATION_ID = 1
    private const val AUTH_NOTIFICATION_ID = 2

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Setup warnings",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Warns when the logger cannot capture Bluetooth events"
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun update(context: Context, status: SetupStatus) {
        val manager = NotificationManagerCompat.from(context)
        if (status.isHealthy) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        post(
            context,
            NOTIFICATION_ID,
            title = "Bluetooth Logger may be missing events",
            text = "Tap to fix setup so connections are captured.",
        )
    }

    /** Sync failed because the Drive token needs the user to sign in again. */
    fun notifyAuthNeeded(context: Context) {
        post(
            context,
            AUTH_NOTIFICATION_ID,
            title = "Bluetooth Logger can't reach Google Drive",
            text = "Tap to sign in again so syncing resumes.",
        )
    }

    fun clearAuthNeeded(context: Context) {
        NotificationManagerCompat.from(context).cancel(AUTH_NOTIFICATION_ID)
    }

    fun notifySyncStalled(context: Context) {
        postSyncAlert(context, SyncAlert.STALLED)
    }

    fun notifySyncOffline(context: Context) {
        postSyncAlert(context, SyncAlert.OFFLINE)
    }

    fun clearSyncAlert(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        SyncAlert.entries.forEach { manager.cancel(it.id) }
    }

    /**
     * One sync alert stands at a time, and the two carry different ids so that swapping one for
     * the other is a fresh notification rather than a silent rewrite: `setOnlyAlertOnce` suppresses
     * sound and heads-up when an id that is still showing is re-posted, which would let the benign
     * "waiting for a connection" escalate to the actionable "stopped syncing" with no signal.
     */
    private fun postSyncAlert(context: Context, alert: SyncAlert) {
        val manager = NotificationManagerCompat.from(context)
        SyncAlert.entries.filter { it != alert }.forEach { manager.cancel(it.id) }
        post(context, alert.id, title = alert.title, text = alert.text)
    }

    private enum class SyncAlert(val id: Int, val title: String, val text: String) {
        /**
         * Says nothing about forcing a sync: by the time this posts the watchdog has already
         * forced one and stamped it, so the grace window makes a tap's own force a no-op. What
         * the tap does reach is the in-app "Sync now" button.
         */
        STALLED(
            id = 3,
            title = "Bluetooth Logger has stopped syncing",
            text = "Tap to open the app and sync manually.",
        ),
        OFFLINE(
            id = 4,
            title = "Bluetooth Logger is waiting for a connection",
            text = "Captured events will reach Google Drive once the phone is back online.",
        ),
    }

    private fun post(context: Context, id: Int, title: String, text: String) {
        val manager = NotificationManagerCompat.from(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        if (manager.areNotificationsEnabled()) {
            manager.notify(id, notification)
        }
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val openApp = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, 0, openApp, PendingIntent.FLAG_IMMUTABLE)
    }
}
