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
        val openApp = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openApp,
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Bluetooth Logger may be missing events")
            .setContentText("Tap to fix setup so connections are captured.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        if (manager.areNotificationsEnabled()) {
            manager.notify(NOTIFICATION_ID, notification)
        }
    }
}
