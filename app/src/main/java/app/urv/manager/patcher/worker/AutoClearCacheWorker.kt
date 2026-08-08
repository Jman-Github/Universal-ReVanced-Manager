package app.urv.manager.patcher.worker

import android.app.Notification
import android.app.Notification.BigTextStyle
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.PowerManager
import android.text.format.Formatter
import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import app.universal.revanced.manager.R
import app.urv.manager.MainActivity
import app.urv.manager.domain.storage.CacheCleanupGuard
import app.urv.manager.domain.storage.clearManagerCache
import app.urv.manager.domain.worker.Worker
import app.urv.manager.util.permission.hasNotificationPermission

class AutoClearCacheWorker(
    context: Context,
    parameters: WorkerParameters
) : Worker<AutoClearCacheWorker.Args>(context, parameters) {
    class Args

    private val cacheNotificationChannel = NotificationChannel(
        CHANNEL_ID,
        applicationContext.getString(R.string.notification_channel_cache_cleanup_name),
        NotificationManager.IMPORTANCE_DEFAULT
    )

    override suspend fun doWork(): Result {
        if (cacheCleanupBlocked()) {
            Log.d("AutoClearCacheWorker", "Skipped cache cleanup because cache-dependent work is active.")
            return Result.success()
        }

        val wakeLock = runCatching {
            val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }.getOrNull()

        return try {
            val clearedBytes = clearManagerCache(applicationContext)
            val notificationManager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            cacheNotificationChannel.description =
                applicationContext.getString(R.string.notification_channel_cache_cleanup_description)
            notificationManager.createNotificationChannel(cacheNotificationChannel)

            if (applicationContext.hasNotificationPermission()) {
                notificationManager.notify(
                    CACHE_CLEANUP_NOTIFICATION_ID,
                    buildNotification(clearedBytes)
                )
            } else {
                notificationManager.cancel(CACHE_CLEANUP_NOTIFICATION_ID)
            }

            Result.success()
        } catch (error: Exception) {
            Log.d("AutoClearCacheWorker", "Error during cache cleanup: ${error.message}")
            Result.failure()
        } finally {
            runCatching {
                if (wakeLock?.isHeld == true) wakeLock.release()
            }
        }
    }

    private fun buildNotification(clearedBytes: Long): Notification {
        val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val description = applicationContext.getString(
            R.string.cache_cleanup_notification_description,
            Formatter.formatShortFileSize(applicationContext, clearedBytes)
        )
        return Notification.Builder(applicationContext, cacheNotificationChannel.id)
            .setContentTitle(applicationContext.getString(R.string.cache_cleanup_notification_title))
            .setContentText(description)
            .setStyle(BigTextStyle().bigText(description))
            .setSmallIcon(Icon.createWithResource(applicationContext, R.drawable.ic_notification_status))
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(false)
            .setAutoCancel(true)
            .build()
    }

    private fun cacheCleanupBlocked(): Boolean {
        if (CacheCleanupGuard.isCacheInUse) return true

        return runCatching {
            WorkManager.getInstance(applicationContext)
                .getWorkInfosForUniqueWork(PatcherWorker.UNIQUE_WORK_NAME)
                .get()
                .any { info ->
                    info.state == WorkInfo.State.ENQUEUED ||
                        info.state == WorkInfo.State.RUNNING ||
                        info.state == WorkInfo.State.BLOCKED
                }
        }.getOrDefault(false)
    }

    companion object {
        private const val CHANNEL_ID = "cache-cleanup-notification-channel"
        const val CACHE_CLEANUP_NOTIFICATION_ID = 9005
        private const val WAKE_LOCK_TAG = "urv:auto_cache_cleanup_worker"
        private const val WAKE_LOCK_TIMEOUT_MS = 10L * 60L * 1000L
    }
}
