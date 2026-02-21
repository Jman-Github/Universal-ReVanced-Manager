package app.revanced.manager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.IBinder
import app.universal.revanced.manager.R
import app.revanced.manager.MainActivity

class BundleUpdateWebSocketService : Service() {
    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.bundle_update_websocket_notification_title))
            .setContentText(getString(R.string.bundle_update_websocket_notification_description))
            .setSmallIcon(Icon.createWithResource(this, R.drawable.ic_notification))
            .setLargeIcon(Icon.createWithResource(this, R.drawable.ic_notification))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_bundle_websocket_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_bundle_websocket_description)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "bundle-update-websocket-channel"
        private const val NOTIFICATION_ID = 9002
    }
}
