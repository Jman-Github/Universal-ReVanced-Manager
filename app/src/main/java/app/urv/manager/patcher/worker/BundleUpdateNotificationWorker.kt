package app.urv.manager.patcher.worker

import android.app.Notification
import android.app.Notification.InboxStyle
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
import app.urv.manager.MainActivity
import app.urv.manager.domain.bundles.PatchBundleSource
import app.urv.manager.domain.bundles.RemotePatchBundle
import app.urv.manager.domain.repository.PatchBundleRepository
import app.urv.manager.domain.worker.Worker
import app.urv.manager.receiver.BundleUpdateNotificationDismissReceiver
import app.urv.manager.util.BundleDeepLinkIntent
import app.urv.manager.util.permission.hasNotificationPermission
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
            val updatedBundles = LinkedHashMap<Int, BundleUpdateNotificationEntry>()
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
                            title = bundleNotificationTitle(totalAutoUpdates.coerceAtLeast(1)),
                            description = progressText,
                            pendingIntent = buildPendingIntent(bundle.uid),
                            ongoing = true,
                            progress = ProgressInfo(bytesRead, bytesTotal)
                        )
                        notificationManager.notify(BUNDLE_PROGRESS_NOTIFICATION_ID, notification)
                    },
                    onBundleUpdated = { bundle, updatedName, updatedVersion ->
                        val resolvedName = bundle.displayName
                            ?.takeIf { it.isNotBlank() }
                            ?: updatedName?.takeIf { it.isNotBlank() }
                            ?: bundle.displayTitle
                        updatedBundles[bundle.uid] = BundleUpdateNotificationEntry(
                            uid = bundle.uid,
                            name = resolvedName,
                            version = updatedVersion
                        )
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

            val manualUpdates = LinkedHashMap<Int, BundleUpdateNotificationEntry>()
            if (canNotify) {
                patchBundleRepository.fetchUpdatesAndNotify(
                    applicationContext,
                    predicate = { bundle -> !bundle.autoUpdate },
                    onAlreadyNotified = { bundle, bundleVersion ->
                        if (!isManualUpdateDismissed(bundle.uid, bundleVersion)) {
                            manualUpdates[bundle.uid] = BundleUpdateNotificationEntry(
                                uid = bundle.uid,
                                name = bundle.displayTitle,
                                version = bundleVersion
                            )
                        }
                    }
                ) { bundle, bundleVersion ->
                    manualUpdateDismissalMarker(bundle.uid, bundleVersion)?.let { marker ->
                        BundleUpdateNotificationDismissReceiver.clearDismissedMarkers(
                            applicationContext,
                            setOf(marker)
                        )
                    }
                    manualUpdates[bundle.uid] = BundleUpdateNotificationEntry(
                        uid = bundle.uid,
                        name = bundle.displayTitle,
                        version = bundleVersion
                    )
                    true
                }
            }

            if (canNotify) {
                val sourceOrder = patchBundleRepository.sources.first().map { it.uid }
                val orderedUpdatedBundles = updatedBundles.values.orderBySource(sourceOrder)
                val orderedManualUpdates = manualUpdates.values.orderBySource(sourceOrder)
                if (orderedManualUpdates.isNotEmpty()) {
                    val sections = listOf(
                        BundleNotificationSection(
                            header = bundleNotificationAvailable(orderedManualUpdates.size),
                            entries = orderedManualUpdates
                        )
                    )
                    val notification = buildNotification(
                        channelId = bundleNotificationChannel.id,
                        title = bundleNotificationTitle(orderedManualUpdates.size),
                        description = sections.toNotificationText(),
                        pendingIntent = buildPendingIntent(
                            if (orderedManualUpdates.size == 1) orderedManualUpdates.first().uid else null
                        ),
                        ongoing = false,
                        progress = null,
                        sections = sections,
                        dismissalMarkers = orderedManualUpdates.dismissalMarkers()
                    )
                    notificationManager.notify(BUNDLE_MANUAL_UPDATE_NOTIFICATION_ID, notification)
                } else {
                    notificationManager.cancel(BUNDLE_MANUAL_UPDATE_NOTIFICATION_ID)
                }

                when {
                    updatedAny -> {
                        val sections = if (orderedUpdatedBundles.isNotEmpty()) {
                            listOf(
                                BundleNotificationSection(
                                    header = bundleNotificationUpdated(orderedUpdatedBundles.size),
                                    entries = orderedUpdatedBundles
                                )
                            )
                        } else {
                            emptyList()
                        }
                        val description = sections.toNotificationText()
                            .ifBlank {
                                applicationContext.getString(R.string.bundle_updates_notification_completed)
                            }
                        val notification = buildNotification(
                            channelId = bundleNotificationChannel.id,
                            title = bundleNotificationTitle(orderedUpdatedBundles.size.coerceAtLeast(1)),
                            description = description,
                            pendingIntent = buildPendingIntent(
                                if (orderedUpdatedBundles.size == 1) orderedUpdatedBundles.first().uid else null
                            ),
                            ongoing = false,
                            progress = null,
                            sections = sections
                        )
                        notificationManager.cancel(BUNDLE_PROGRESS_NOTIFICATION_ID)
                        notificationManager.notify(BUNDLE_AUTO_RESULT_NOTIFICATION_ID, notification)
                    }
                    progressNotified -> {
                        val description = if (downloadStarted) {
                            applicationContext.getString(R.string.bundle_updates_notification_failed)
                        } else {
                            applicationContext.getString(R.string.bundle_updates_notification_completed)
                        }
                        val notification = buildNotification(
                            channelId = bundleNotificationChannel.id,
                            title = bundleNotificationTitle(seenUids.size.coerceAtLeast(1)),
                            description = description,
                            pendingIntent = buildPendingIntent(seenUids.firstOrNull()),
                            ongoing = false,
                            progress = null,
                            sections = emptyList()
                        )
                        notificationManager.notify(BUNDLE_PROGRESS_NOTIFICATION_ID, notification)
                    }
                    else -> {
                        notificationManager.cancel(BUNDLE_PROGRESS_NOTIFICATION_ID)
                    }
                }
            } else {
                notificationManager.cancel(BUNDLE_PROGRESS_NOTIFICATION_ID)
                notificationManager.cancel(BUNDLE_AUTO_RESULT_NOTIFICATION_ID)
                notificationManager.cancel(BUNDLE_MANUAL_UPDATE_NOTIFICATION_ID)
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
        private const val BUNDLE_PROGRESS_NOTIFICATION_ID = 9001
        private const val BUNDLE_AUTO_RESULT_NOTIFICATION_ID = 9100
        private const val BUNDLE_MANUAL_UPDATE_NOTIFICATION_ID = 9101
        private const val WAKE_LOCK_TAG = "urv:bundle_update_worker"
        private const val WAKE_LOCK_TIMEOUT_MS = 20L * 60L * 1000L
    }

    private data class ProgressInfo(
        val bytesRead: Long,
        val bytesTotal: Long?
    )

    private data class BundleUpdateNotificationEntry(
        val uid: Int,
        val name: String,
        val version: String
    ) {
        val displayLine: String
            get() = listOf(
                name.trim(),
                version.trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { version -> if (version.startsWith("v", ignoreCase = true)) version else "v$version" }
            )
                .filterNotNull()
                .filter { it.isNotBlank() }
                .joinToString(" ")
    }

    private data class BundleNotificationSection(
        val header: String,
        val entries: List<BundleUpdateNotificationEntry>
    )

    private fun buildNotification(
        channelId: String,
        title: String,
        description: String,
        pendingIntent: PendingIntent,
        ongoing: Boolean,
        progress: ProgressInfo?,
        sections: List<BundleNotificationSection> = emptyList(),
        dismissalMarkers: Array<String> = emptyArray()
    ): Notification {
        val builder = Notification.Builder(applicationContext, channelId)
            .setContentTitle(title)
            .setContentText(description)
            .setLargeIcon(Icon.createWithResource(applicationContext, R.drawable.ic_notification))
            .setSmallIcon(Icon.createWithResource(applicationContext, R.drawable.ic_notification_status))
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)

        buildDismissPendingIntent(dismissalMarkers)?.let(builder::setDeleteIntent)

        if (progress != null) {
            val total = progress.bytesTotal?.takeIf { it > 0L }
            if (total == null) {
                builder.setProgress(0, 0, true)
            } else {
                val max = min(total, Int.MAX_VALUE.toLong()).toInt()
                val current = min(progress.bytesRead, max.toLong()).toInt()
                builder.setProgress(max, current, false)
            }
        } else {
            builder.setProgress(0, 0, false)
        }

        if (sections.isNotEmpty()) {
            val style = InboxStyle()
                .setBigContentTitle(title)
            sections.forEach { section ->
                style.addLine(section.header)
                section.entries.forEach { entry ->
                    style.addLine("- ${entry.displayLine}")
                }
            }
            builder.setStyle(style)
        }

        return builder.build()
    }

    private fun bundleNotificationTitle(count: Int): String =
        applicationContext.resources.getQuantityString(
            R.plurals.bundle_updates_notification_title_found_quantity,
            count,
            count
        )

    private fun bundleNotificationAvailable(count: Int): String =
        applicationContext.resources.getQuantityString(
            R.plurals.bundle_updates_notification_available_quantity,
            count,
            count
        )

    private fun bundleNotificationUpdated(count: Int): String =
        applicationContext.resources.getQuantityString(
            R.plurals.bundle_updates_notification_updated_quantity,
            count,
            count
        )

    private fun Collection<BundleUpdateNotificationEntry>.orderBySource(
        sourceOrder: List<Int>
    ): List<BundleUpdateNotificationEntry> {
        val order = sourceOrder.withIndex().associate { it.value to it.index }
        return sortedWith(
            compareBy<BundleUpdateNotificationEntry> { order[it.uid] ?: Int.MAX_VALUE }
                .thenBy { it.name.lowercase() }
                .thenBy { it.uid }
        )
    }

    private fun buildDismissPendingIntent(markers: Array<String>): PendingIntent? {
        if (markers.isEmpty()) return null
        val intent = Intent(
            applicationContext,
            BundleUpdateNotificationDismissReceiver::class.java
        ).apply {
            action = BundleUpdateNotificationDismissReceiver.ACTION_BUNDLE_UPDATE_NOTIFICATION_DISMISSED
            putExtra(BundleUpdateNotificationDismissReceiver.EXTRA_DISMISSAL_MARKERS, markers)
        }
        return PendingIntent.getBroadcast(
            applicationContext,
            BUNDLE_MANUAL_UPDATE_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun List<BundleUpdateNotificationEntry>.dismissalMarkers(): Array<String> =
        mapNotNull { manualUpdateDismissalMarker(it.uid, it.version) }.distinct().toTypedArray()

    private fun isManualUpdateDismissed(uid: Int, version: String): Boolean {
        val marker = manualUpdateDismissalMarker(uid, version) ?: return false
        return BundleUpdateNotificationDismissReceiver.dismissedMarkers(applicationContext).contains(marker)
    }

    private fun List<BundleNotificationSection>.toNotificationText(): String =
        flatMap { section ->
            buildList {
                add(section.header)
                section.entries.forEach { entry -> add("- ${entry.displayLine}") }
            }
        }.joinToString("\n")

    private fun manualUpdateDismissalMarker(uid: Int, version: String): String? {
        val normalizedVersion = version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('+')
            .trim()
            .lowercase()
            .takeIf { it.isNotBlank() } ?: return null
        return "$uid:$normalizedVersion"
    }
}
