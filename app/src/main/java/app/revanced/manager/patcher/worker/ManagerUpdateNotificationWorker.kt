package app.revanced.manager.patcher.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.PowerManager
import android.util.Log
import androidx.work.WorkerParameters
import app.universal.revanced.manager.R
import app.revanced.manager.MainActivity
import app.revanced.manager.data.platform.NetworkInfo
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.domain.worker.Worker
import app.revanced.manager.network.api.ReVancedAPI
import app.revanced.manager.util.ManagerUpdateDeepLinkIntent
import app.revanced.manager.util.permission.hasNotificationPermission
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ManagerUpdateNotificationWorker(
    context: Context,
    parameters: WorkerParameters
) : Worker<ManagerUpdateNotificationWorker.Args>(context, parameters), KoinComponent {
    private val reVancedAPI: ReVancedAPI by inject()
    private val prefs: PreferencesManager by inject()
    private val networkInfo: NetworkInfo by inject()

    class Args

    private val managerNotificationChannel = NotificationChannel(
        "background-manager-update-channel",
        applicationContext.getString(R.string.notification_channel_manager_updates_name),
        NotificationManager.IMPORTANCE_HIGH
    )

    override suspend fun doWork(): Result {
        val wakeLock = runCatching {
            val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }.getOrNull()

        return try {
            val notificationManager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            managerNotificationChannel.description =
                applicationContext.getString(R.string.notification_channel_manager_updates_description)
            notificationManager.createNotificationChannel(managerNotificationChannel)

            if (!prefs.managerAutoUpdates.get()) {
                clearPendingState(notificationManager)
                return Result.success()
            }

            if (!networkInfo.isConnected()) {
                return Result.success()
            }

            val update = reVancedAPI.getAppUpdate()
            if (update == null) {
                clearPendingState(notificationManager)
                return Result.success()
            }

            if (!applicationContext.hasNotificationPermission()) {
                return Result.success()
            }

            val versionMarker = update.version.trim().lowercase().hashCode()
            if (prefs.pendingManagerUpdateVersionCode.get() == versionMarker) {
                return Result.success()
            }

            val notification = buildNotification(
                title = applicationContext.getString(R.string.manager_updates_notification_title),
                description = applicationContext.getString(
                    R.string.manager_updates_notification_available,
                    update.version
                ),
                pendingIntent = buildManagerUpdatePendingIntent()
            )
            notificationManager.notify(MANAGER_NOTIFICATION_ID, notification)
            prefs.pendingManagerUpdateVersionCode.update(versionMarker)
            Result.success()
        } catch (e: Exception) {
            Log.d("ManagerUpdateWorker", "Error during manager update check: ${e.message}")
            Result.failure()
        } finally {
            runCatching {
                if (wakeLock?.isHeld == true) wakeLock.release()
            }
        }
    }

    private suspend fun clearPendingState(notificationManager: NotificationManager) {
        if (prefs.pendingManagerUpdateVersionCode.get() != -1) {
            prefs.pendingManagerUpdateVersionCode.update(-1)
        }
        notificationManager.cancel(MANAGER_NOTIFICATION_ID)
    }

    private fun buildManagerUpdatePendingIntent(): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            ManagerUpdateDeepLinkIntent.addOpenManagerUpdate(this)
        }
        return PendingIntent.getActivity(
            applicationContext,
            MANAGER_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(
        title: String,
        description: String,
        pendingIntent: PendingIntent
    ): Notification {
        return Notification.Builder(applicationContext, managerNotificationChannel.id)
            .setContentTitle(title)
            .setContentText(description)
            .setLargeIcon(Icon.createWithResource(applicationContext, R.drawable.ic_notification))
            .setSmallIcon(Icon.createWithResource(applicationContext, R.drawable.ic_notification))
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
    }

    private companion object {
        private const val MANAGER_NOTIFICATION_ID = 9003
        private const val WAKE_LOCK_TAG = "urv:manager_update_worker"
        private const val WAKE_LOCK_TIMEOUT_MS = 10L * 60L * 1000L
    }
}
