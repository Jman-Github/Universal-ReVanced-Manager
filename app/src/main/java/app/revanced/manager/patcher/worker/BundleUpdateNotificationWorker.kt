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
import app.revanced.manager.domain.bundles.PatchBundleSource
import app.revanced.manager.domain.bundles.RemotePatchBundle
import app.revanced.manager.domain.repository.PatchBundleRepository
import app.revanced.manager.domain.worker.Worker
import app.revanced.manager.util.BundleDeepLinkIntent
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

    private val bundleNotificationChannel = NotificationChannel(
        "background-bundle-update-channel",
        applicationContext.getString(R.string.notification_channel_bundle_updates_name),
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
            bundleNotificationChannel.description =
                applicationContext.getString(R.string.notification_channel_bundle_updates_description)

            val canNotify = applicationContext.hasNotificationPermission()
            val notificationManager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(bundleNotificationChannel)

            fun buildPendingIntent(bundleUid: Int?): PendingIntent {
                val intent = Intent(applicationContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    BundleDeepLinkIntent.addBundleUid(this, bundleUid)
                }
                val requestCode = bundleUid?.plus(1) ?: 0
                return PendingIntent.getActivity(
                    applicationContext,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }

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
            val updatedBundleNames = LinkedHashSet<String>()
            val updatedBundleUids = LinkedHashSet<Int>()
            var progressNotified = false
            var downloadStarted = false

            val updatedAny = if (totalAutoUpdates > 0) {
                patchBundleRepository.updateNow(
                    allowUnsafeNetwork = false,
                    onPerBundleProgress = { bundle, bytesRead, bytesTotal ->
                        val shouldNotify = bytesRead > 0L || (bytesTotal ?: 0L) > 0L
                        if (!shouldNotify) return@updateNow
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
                            channelId = bundleNotificationChannel.id,
                            title = applicationContext.getString(R.string.bundle_update_banner_title),
                            description = progressText,
                            pendingIntent = buildPendingIntent(bundle.uid),
                            ongoing = true,
                            progress = ProgressInfo(bytesRead, bytesTotal)
                        )
                        notificationManager.notify(BUNDLE_NOTIFICATION_ID, notification)
                    },
                    onBundleUpdated = { bundle, updatedName ->
                        val resolvedName = updatedName?.takeIf { it.isNotBlank() } ?: bundle.displayTitle
                        updatedBundleNames.add(resolvedName)
                        updatedBundleUids.add(bundle.uid)
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

            data class ManualUpdateEntry(val uid: Int, val name: String, val version: String)
            val manualUpdates = mutableListOf<ManualUpdateEntry>()
            if (canNotify) {
                patchBundleRepository.fetchUpdatesAndNotify(
                    applicationContext,
                    predicate = { bundle -> !bundle.autoUpdate }
                ) { bundle, bundleVersion ->
                    manualUpdates += ManualUpdateEntry(bundle.uid, bundle.displayTitle, bundleVersion)
                    true
                }
            }

            if (canNotify) {
                val deepLinkUid = when {
                    updatedBundleUids.size == 1 -> updatedBundleUids.first()
                    manualUpdates.size == 1 -> manualUpdates.first().uid
                    else -> null
                }
                when {
                    updatedAny -> {
                        val updatedNames = formatUpdatedBundleNames(updatedBundleNames)
                        val description = when {
                            updatedNames != null && manualUpdates.isNotEmpty() ->
                                applicationContext.getString(
                                    R.string.bundle_updates_notification_completed_named_with_available,
                                    updatedNames,
                                    manualUpdates.size
                                )
                            updatedNames != null ->
                                applicationContext.getString(
                                    R.string.bundle_updates_notification_completed_named,
                                    updatedNames
                                )
                            manualUpdates.isNotEmpty() ->
                                applicationContext.getString(
                                    R.string.bundle_updates_notification_completed_with_available,
                                    manualUpdates.size
                                )
                            else ->
                                applicationContext.getString(R.string.bundle_updates_notification_completed)
                        }
                        val notification = buildNotification(
                            channelId = bundleNotificationChannel.id,
                            title = applicationContext.getString(R.string.bundle_update_banner_title),
                            description = description,
                            pendingIntent = buildPendingIntent(deepLinkUid),
                            ongoing = false,
                            progress = null
                        )
                        notificationManager.notify(BUNDLE_NOTIFICATION_ID, notification)
                    }
                    manualUpdates.isNotEmpty() -> {
                        val notification = buildNotification(
                            channelId = bundleNotificationChannel.id,
                            title = applicationContext.getString(R.string.bundle_update_banner_title),
                            description = applicationContext.getString(
                                R.string.bundle_updates_notification_available,
                                manualUpdates.size
                            ),
                            pendingIntent = buildPendingIntent(
                                if (manualUpdates.size == 1) manualUpdates.first().uid else null
                            ),
                            ongoing = false,
                            progress = null
                        )
                        notificationManager.notify(BUNDLE_NOTIFICATION_ID, notification)
                    }
                    progressNotified -> {
                        val description = if (downloadStarted) {
                            applicationContext.getString(R.string.bundle_updates_notification_failed)
                        } else {
                            applicationContext.getString(R.string.bundle_updates_notification_completed)
                        }
                        val notification = buildNotification(
                            channelId = bundleNotificationChannel.id,
                            title = applicationContext.getString(R.string.bundle_update_banner_title),
                            description = description,
                            pendingIntent = buildPendingIntent(seenUids.firstOrNull()),
                            ongoing = false,
                            progress = null
                        )
                        notificationManager.notify(BUNDLE_NOTIFICATION_ID, notification)
                    }
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.d("BundleAutoUpdateWorker", "Error during work: ${e.message}")
            Result.failure()
        } finally {
            runCatching {
                if (wakeLock?.isHeld == true) wakeLock.release()
            }
        }
    }

    private companion object {
        private const val BUNDLE_NOTIFICATION_ID = 9001
        private const val WAKE_LOCK_TAG = "urv:bundle_update_worker"
        private const val WAKE_LOCK_TIMEOUT_MS = 20L * 60L * 1000L
    }

    private data class ProgressInfo(
        val bytesRead: Long,
        val bytesTotal: Long?
    )

    private fun buildNotification(
        channelId: String,
        title: String,
        description: String,
        pendingIntent: PendingIntent,
        ongoing: Boolean,
        progress: ProgressInfo?
    ): Notification {
        val builder = Notification.Builder(applicationContext, channelId)
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

    private fun formatUpdatedBundleNames(names: Collection<String>): String? {
        val cleaned = names.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (cleaned.isEmpty()) return null
        val maxVisible = 3
        val visible = cleaned.take(maxVisible)
        val extra = cleaned.size - visible.size
        val joined = visible.joinToString(", ")
        return if (extra > 0) "$joined +$extra" else joined
    }
}
