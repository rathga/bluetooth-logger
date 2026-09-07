package com.nestegg.btlogger.setup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nestegg.btlogger.R
import com.nestegg.btlogger.sync.SyncHealth
import com.nestegg.btlogger.ui.MainActivity

object SetupNotifier {

    private const val CHANNEL_ID = "setup-health"

    internal enum class Alert(val id: Int, val title: String, val text: String) {
        SETUP_HEALTH(
            id = 1,
            title = "Bluetooth Logger may be missing events",
            text = "Tap to fix setup so connections are captured.",
        ),
        AUTH_NEEDED(
            id = 2,
            title = "Bluetooth Logger can't reach Google Drive",
            text = "Tap to sign in again so syncing resumes.",
        ),
        SYNC_STALLED(
            id = 3,
            title = "Bluetooth Logger has stopped syncing",
            text = "Tap to open the app and sync manually.",
        ),
        SYNC_OFFLINE(
            id = 4,
            title = "Bluetooth Logger is waiting for a connection",
            text = "Captured events will reach Google Drive once the phone is back online.",
        ),
    }

    private val SYNC_ALERTS: List<Alert> = Alert.entries - Alert.SETUP_HEALTH

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
        if (status.isHealthy) {
            NotificationManagerCompat.from(context).cancel(Alert.SETUP_HEALTH.id)
            return
        }
        post(context, Alert.SETUP_HEALTH)
    }

    internal fun notifyAuthNeeded(context: Context) {
        showAlone(context, Alert.AUTH_NEEDED)
    }

    internal fun notifySyncAlert(context: Context, health: SyncHealth) {
        showAlone(context, alertFor(health) ?: return)
    }

    internal fun retireSyncAlertsContradicting(context: Context, health: SyncHealth) {
        cancelSyncAlertsOtherThan(context, keep = alertFor(health))
    }

    internal fun clearSyncAlerts(context: Context) {
        cancelSyncAlertsOtherThan(context, keep = null)
    }

    private fun alertFor(health: SyncHealth): Alert? = when (health) {
        SyncHealth.HEALTHY -> null
        SyncHealth.AUTH_EXPIRED -> Alert.AUTH_NEEDED
        SyncHealth.STALLED -> Alert.SYNC_STALLED
        SyncHealth.OFFLINE -> Alert.SYNC_OFFLINE
    }

    private fun showAlone(context: Context, alert: Alert) {
        cancelSyncAlertsOtherThan(context, keep = alert)
        post(context, alert)
    }

    private fun cancelSyncAlertsOtherThan(context: Context, keep: Alert?) {
        val manager = NotificationManagerCompat.from(context)
        SYNC_ALERTS.filter { it != keep }.forEach { manager.cancel(it.id) }
    }

    private fun post(context: Context, alert: Alert) {
        val manager = NotificationManagerCompat.from(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        if (manager.areNotificationsEnabled()) {
            manager.notify(alert.id, notification)
        }
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val openApp = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, 0, openApp, PendingIntent.FLAG_IMMUTABLE)
    }
}
