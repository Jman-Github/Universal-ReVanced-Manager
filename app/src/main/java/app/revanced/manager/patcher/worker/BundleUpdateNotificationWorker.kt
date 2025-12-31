package app.revanced.manager.patcher.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Log
import androidx.work.WorkerParameters
import app.universal.revanced.manager.R
import app.revanced.manager.MainActivity
import app.revanced.manager.domain.bundles.PatchBundleSource
import app.revanced.manager.domain.bundles.RemotePatchBundle
import app.revanced.manager.domain.repository.PatchBundleRepository
import app.revanced.manager.domain.worker.Worker
import app.revanced.manager.util.permission.hasNotificationPermission
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.math.min

class BundleUpdateNotificationWorker(
    context: Context,
    parameters: WorkerParameters
) : Worker<BundleUpdateNotificationWorker.Args>(context, parameters), KoinComponent {
    private val patchBundleRepository: PatchBundleRepository by inject()

    class Args

    private val notificationChannel = NotificationChannel(
        "background-bundle-update-channel",
        applicationContext.getString(R.string.notification_channel_bundle_updates_name),
        NotificationManager.IMPORTANCE_HIGH
    )

    override suspend fun doWork(): Result {
        return try {
            notificationChannel.description =
                applicationContext.getString(R.string.notification_channel_bundle_updates_description)

            val canNotify = applicationContext.hasNotificationPermission()
            val notificationManager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(notificationChannel)

            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                0,
                Intent(applicationContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_IMMUTABLE
            )

            val autoUpdateTargets = patchBundleRepository.sources.first()
                .filterIsInstance<RemotePatchBundle>()
                .filter { bundle ->
                    bundle.autoUpdate &&
                        bundle.searchUpdate &&
                        bundle.enabled &&
                        bundle.state is PatchBundleSource.State.Available
                }

            val totalAutoUpdates = autoUpdateTargets.size
            val seenUids = LinkedHashSet<Int>()
            var progressNotified = false
            var downloadStarted = false

            val updatedAny = if (totalAutoUpdates > 0) {
                patchBundleRepository.updateNow(
                    allowUnsafeNetwork = false,
                    onPerBundleProgress = { bundle, bytesRead, bytesTotal ->
                        downloadStarted = true
                        if (!canNotify) return@updateNow

                        if (seenUids.add(bundle.uid)) {
                            progressNotified = true
                        }
                        val currentIndex = seenUids.size.coerceAtMost(totalAutoUpdates)
                        val progressText = applicationContext.getString(
                            R.string.bundle_updates_notification_progress,
                            currentIndex,
                            totalAutoUpdates,
                            bundle.displayTitle
                        )
                        val notification = buildNotification(
                            title = applicationContext.getString(R.string.bundle_update_banner_title),
                            description = progressText,
                            pendingIntent = pendingIntent,
                            ongoing = true,
                            progress = ProgressInfo(bytesRead, bytesTotal)
                        )
                        notificationManager.notify(NOTIFICATION_ID, notification)
                    },
                    predicate = { bundle ->
                        bundle.autoUpdate &&
                            bundle.searchUpdate &&
                            bundle.enabled &&
                            bundle.state is PatchBundleSource.State.Available
                    }
                )
            } else {
                false
            }

            val manualUpdates = mutableListOf<Pair<String, String>>()
            if (canNotify) {
                patchBundleRepository.fetchUpdatesAndNotify(
                    applicationContext,
                    predicate = { bundle -> !bundle.autoUpdate }
                ) { bundleName, bundleVersion ->
                    manualUpdates += bundleName to bundleVersion
                    true
                }
            }

            if (canNotify) {
                when {
                    updatedAny -> {
                        val description = if (manualUpdates.isNotEmpty()) {
                            applicationContext.getString(
                                R.string.bundle_updates_notification_completed_with_available,
                                manualUpdates.size
                            )
                        } else {
                            applicationContext.getString(R.string.bundle_updates_notification_completed)
                        }
                        val notification = buildNotification(
                            title = applicationContext.getString(R.string.bundle_update_banner_title),
                            description = description,
                            pendingIntent = pendingIntent,
                            ongoing = false,
                            progress = null
                        )
                        notificationManager.notify(NOTIFICATION_ID, notification)
                    }
                    manualUpdates.isNotEmpty() -> {
                        val notification = buildNotification(
                            title = applicationContext.getString(R.string.bundle_update_banner_title),
                            description = applicationContext.getString(
                                R.string.bundle_updates_notification_available,
                                manualUpdates.size
                            ),
                            pendingIntent = pendingIntent,
                            ongoing = false,
                            progress = null
                        )
                        notificationManager.notify(NOTIFICATION_ID, notification)
                    }
                    progressNotified -> {
                        val description = if (downloadStarted) {
                            applicationContext.getString(R.string.bundle_updates_notification_failed)
                        } else {
                            applicationContext.getString(R.string.bundle_updates_notification_completed)
                        }
                        val notification = buildNotification(
                            title = applicationContext.getString(R.string.bundle_update_banner_title),
                            description = description,
                            pendingIntent = pendingIntent,
                            ongoing = false,
                            progress = null
                        )
                        notificationManager.notify(NOTIFICATION_ID, notification)
                    }
                }
            }
            Result.success()
        } catch (e: Exception) {
            Log.d("BundleAutoUpdateWorker", "Error during work: ${e.message}")
            Result.failure()
        }
    }

    private companion object {
        private const val NOTIFICATION_ID = 9001
    }

    private data class ProgressInfo(
        val bytesRead: Long,
        val bytesTotal: Long?
    )

    private fun buildNotification(
        title: String,
        description: String,
        pendingIntent: PendingIntent,
        ongoing: Boolean,
        progress: ProgressInfo?
    ): Notification {
        val builder = Notification.Builder(applicationContext, notificationChannel.id)
            .setContentTitle(title)
            .setContentText(description)
            .setLargeIcon(Icon.createWithResource(applicationContext, R.drawable.ic_notification))
            .setSmallIcon(Icon.createWithResource(applicationContext, R.drawable.ic_notification))
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)

        if (progress != null) {
            val total = progress.bytesTotal?.takeIf { it > 0L }
            if (total == null) {
                builder.setProgress(0, 0, true)
            } else {
                val max = min(total, Int.MAX_VALUE.toLong()).toInt()
                val current = min(progress.bytesRead, max.toLong()).toInt()
                builder.setProgress(max, current, false)
            }
        }

        return builder.build()
    }
}
