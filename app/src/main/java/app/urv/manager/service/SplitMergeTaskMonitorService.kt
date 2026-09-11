package app.urv.manager.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import app.urv.manager.util.SplitMergeNotification

/** Keeps the merge notification tied to a service that Android removes with the process. */
class SplitMergeTaskMonitorService : Service() {
    override fun onCreate() {
        super.onCreate()
        synchronized(lock) { instance = this }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(lock) {
            // Fulfil a pending foreground start even if the merge finished before delivery.
            @Suppress("DEPRECATION")
            val initial = intent?.getParcelableExtra<Notification>(EXTRA_NOTIFICATION)
            (notification ?: initial)?.let { startForeground(SplitMergeNotification.NOTIFICATION_ID, it) }
            if (notification == null) stopForeground(STOP_FOREGROUND_REMOVE)
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val callback = synchronized(lock) { onTaskClosed }
        callback?.invoke()
        SplitMergeNotification.clear(this)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val callback = synchronized(lock) {
            if (instance === this) instance = null
            onTaskClosed.takeIf { notification != null }
        }
        callback?.invoke()
        SplitMergeNotification.clear(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val EXTRA_NOTIFICATION = "notification"
        private val lock = Any()
        private var instance: SplitMergeTaskMonitorService? = null
        private var notification: Notification? = null
        private var onTaskClosed: (() -> Unit)? = null

        fun register(callback: () -> Unit) = synchronized(lock) {
            onTaskClosed = callback
        }

        fun unregister(context: Context, callback: () -> Unit) {
            synchronized(lock) {
                if (onTaskClosed !== callback) return
                onTaskClosed = null
                notification = null
                instance?.stopForeground(STOP_FOREGROUND_REMOVE)
            }
            context.stopService(Intent(context, SplitMergeTaskMonitorService::class.java))
        }

        fun show(context: Context, value: Notification): Boolean = synchronized(lock) {
            if (onTaskClosed == null) return@synchronized false
            val needsStart = notification == null || instance == null
            notification = value
            try {
                if (needsStart) {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, SplitMergeTaskMonitorService::class.java)
                            .putExtra(EXTRA_NOTIFICATION, value)
                    )
                }
                true
            } catch (error: Exception) {
                notification = null
                throw error
            }
        }

        fun clear() = synchronized(lock) {
            notification = null
            instance?.stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }
}
