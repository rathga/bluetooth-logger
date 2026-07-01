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

    private fun post(context: Context, id: Int, title: String, text: String) {
        val manager = NotificationManagerCompat.from(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
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
