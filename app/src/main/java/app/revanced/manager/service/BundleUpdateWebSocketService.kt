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
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.domain.manager.SearchForUpdatesBackgroundInterval
import kotlinx.coroutines.runBlocking

class BundleUpdateWebSocketService : Service() {
    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val (listenBundle, listenManager) = resolveListenTargets(intent)
        if (!listenBundle && !listenManager) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification(listenBundle, listenManager))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(listenBundle: Boolean, listenManager: Boolean): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val body = when {
            listenBundle && listenManager -> getString(
                R.string.bundle_update_websocket_notification_description_bundle_and_manager
            )
            listenBundle -> getString(
                R.string.bundle_update_websocket_notification_description_bundle_only
            )
            listenManager -> getString(
                R.string.bundle_update_websocket_notification_description_manager_only
            )
            else -> ""
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.bundle_update_websocket_notification_title))
            .setContentText(body)
            .setSmallIcon(Icon.createWithResource(this, R.drawable.ic_notification))
            .setLargeIcon(Icon.createWithResource(this, R.drawable.ic_notification))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

    private fun resolveListenTargets(intent: Intent?): Pair<Boolean, Boolean> {
        val bundleFromIntent = intent?.getBooleanExtra(EXTRA_LISTEN_BUNDLE_UPDATES, false)
        val managerFromIntent = intent?.getBooleanExtra(EXTRA_LISTEN_MANAGER_UPDATES, false)
        val hasBundleExtra = intent?.hasExtra(EXTRA_LISTEN_BUNDLE_UPDATES) == true
        val hasManagerExtra = intent?.hasExtra(EXTRA_LISTEN_MANAGER_UPDATES) == true

        if (hasBundleExtra || hasManagerExtra) {
            return (bundleFromIntent == true) to (managerFromIntent == true)
        }

        val prefs = PreferencesManager(applicationContext)
        val listenBundle = runBlocking {
            prefs.searchForUpdatesBackgroundInterval.get() != SearchForUpdatesBackgroundInterval.NEVER
        }
        val listenManager = runBlocking {
            prefs.searchForManagerUpdatesBackgroundInterval.get() != SearchForUpdatesBackgroundInterval.NEVER
        }
        return listenBundle to listenManager
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
        const val EXTRA_LISTEN_BUNDLE_UPDATES = "listen_bundle_updates"
        const val EXTRA_LISTEN_MANAGER_UPDATES = "listen_manager_updates"
        private const val NOTIFICATION_ID = 9002
    }
}
