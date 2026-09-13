/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.urv.manager.patcher.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.universal.revanced.manager.R
import app.urv.manager.MainActivity
import app.urv.manager.domain.batch.BatchInstallOutcome
import app.urv.manager.domain.batch.BatchInstallPolicy
import app.urv.manager.domain.batch.BatchPatchCoordinator
import app.urv.manager.domain.batch.BatchPhase
import app.urv.manager.domain.batch.BatchRunState
import app.urv.manager.domain.batch.BatchPlanResolver
import app.urv.manager.domain.installer.InstallerManager
import app.urv.manager.domain.manager.PreferencesManager
import app.urv.manager.domain.manager.SearchForUpdatesBackgroundInterval
import app.urv.manager.domain.repository.PatchBundleRepository
import app.urv.manager.domain.worker.WorkerRepository
import app.urv.manager.util.BatchPatchIntents
import app.urv.manager.util.permission.hasNotificationPermission
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

internal suspend fun reconcileAutoPatchNotificationPermission(
    context: Context,
    prefs: PreferencesManager
): Boolean {
    if (context.hasNotificationPermission()) return true
    if (prefs.autoPatchEnabled.get()) {
        prefs.updateAutoPatchEnabled(false)
    }
    AutoPatchWorker.cancel(context)
    return false
}

// Code adapted from Morphe, see third-party/NOTICE for more information
// https://github.com/MorpheApp/morphe-manager/pull/795
class AutoPatchWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {
    private val prefs: PreferencesManager by inject()
    private val resolver: BatchPlanResolver by inject()
    private val patchBundleRepository: PatchBundleRepository by inject()
    private val coordinator: BatchPatchCoordinator by inject()
    private val installerManager: InstallerManager by inject()
    private val workerRepository: WorkerRepository by inject()

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(notification(running = true, count = 0))

    private fun foregroundInfo(notification: Notification) = ForegroundInfo(
        NOTIFICATION_ID_RUNNING,
        notification,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
    )

    override suspend fun doWork(): Result {
        if (!prefs.autoPatchEnabled.get()) return Result.success()
        if (!reconcileAutoPatchNotificationPermission(applicationContext, prefs)) {
            return Result.success()
        }

        if (workerRepository.hasActiveUniqueWork(PatcherWorker.UNIQUE_WORK_NAME)) {
            return Result.retry()
        }

        val powerManager = applicationContext.getSystemService(PowerManager::class.java)
        if (powerManager?.isIgnoringBatteryOptimizations(applicationContext.packageName) != true) {
            showBlockedNotification(R.string.auto_patch_blocked_battery)
            return Result.success()
        }

        try {
            patchBundleRepository.updateCheck()
        } catch (error: Exception) {
            Log.w("URV AutoPatch", "Unable to refresh patch bundles", error)
            return Result.retry()
        }

        val packages = resolver.findOutdatedPackages(onlyAutoPatchEnabled = true)
        if (packages.isEmpty()) return Result.success()

        try {
            setForeground(
                foregroundInfo(
                    notification(
                        running = true,
                        count = packages.size,
                        packageNames = packages
                    )
                )
            )
        } catch (error: Exception) {
            Log.w("URV AutoPatch", "Unable to promote automatic patching to foreground", error)
            showBlockedNotification(R.string.auto_patch_blocked_foreground)
            return Result.success()
        }
        var ownsCoordinator = false
        var completed = false
        try {
            ownsCoordinator = coordinator.plan(
                packageNames = packages,
                policy = BatchInstallPolicy.SAVE_ONLY,
                scheduled = true
            )
            if (!ownsCoordinator) return Result.retry()

            val planned = coordinator.state.first {
                it?.phase == BatchPhase.PREFLIGHT || it?.phase == BatchPhase.FINISHED
            } ?: return Result.retry()
            if (planned.phase == BatchPhase.FINISHED) {
                showResultNotification(
                    state = planned,
                    textOverride = applicationContext.resources.getQuantityString(
                        R.plurals.auto_patch_preparation_failed,
                        planned.failed.coerceAtLeast(1),
                        planned.failed.coerceAtLeast(1)
                    )
                )
                completed = true
                return Result.success()
            }
            if (planned.runnable.isEmpty()) {
                val finished = coordinator.finishPreflight() ?: planned
                showResultNotification(
                    state = finished,
                    textOverride = applicationContext.resources.getQuantityString(
                        R.plurals.auto_patch_needs_attention,
                        finished.skipped.coerceAtLeast(1),
                        finished.skipped.coerceAtLeast(1)
                    )
                )
                completed = true
                return Result.success()
            }

            coordinator.start()
            var finished = coordinator.state.first {
                it?.phase == BatchPhase.FINISHED
            } ?: return Result.retry()

            var installBlockedByShizuku = false
            if (
                prefs.autoPatchInstallWithShizuku.get() &&
                finished.patchedItems.isNotEmpty()
            ) {
                val shizukuStatus = installerManager.shizukuStatus(
                    InstallerManager.InstallTarget.PATCHER
                )
                if (shizukuStatus.availability.available) {
                    coordinator.installAll(forceShizuku = true)
                    finished = coordinator.state.first {
                        it?.phase == BatchPhase.FINISHED &&
                            it.patchedItems.all { item -> item.installOutcome != null }
                    } ?: finished
                } else {
                    installBlockedByShizuku = true
                }
            }

            showResultNotification(
                state = finished,
                textOverride = if (installBlockedByShizuku) {
                    applicationContext.getString(
                        R.string.auto_patch_install_skipped_shizuku
                    )
                } else {
                    null
                }
            )
            completed = true
            return Result.success()
        } finally {
            withContext(NonCancellable) {
                if (ownsCoordinator && !completed) {
                    coordinator.cancel()
                }
                coordinator.shutdown()
            }
        }
    }

    private fun showResultNotification(
        state: BatchRunState,
        textOverride: String? = null
    ) {
        applicationContext.getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID_FINISHED,
            notification(
                running = false,
                count = state.succeeded,
                installed = state.patchedItems.count {
                    it.installOutcome == BatchInstallOutcome.INSTALLED
                },
                textOverride = textOverride,
                packageNames = state.items.map { it.packageName }
            )
        )
    }

    private fun showBlockedNotification(message: Int) {
        applicationContext.getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID_FINISHED,
            notification(
                running = false,
                count = 0,
                textOverride = applicationContext.getString(message)
            )
        )
    }

    private fun notification(
        running: Boolean,
        count: Int,
        installed: Int = 0,
        textOverride: String? = null,
        packageNames: List<String> = emptyList()
    ): Notification {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.batch_patch_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val intent = BatchPatchIntents.markInternal(
            applicationContext,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (!running) {
                    action = BatchPatchIntents.ACTION_SHOW_RESULT
                    putExtra(BatchPatchIntents.EXTRA_SCHEDULED, true)
                    if (packageNames.isNotEmpty()) {
                        putExtra(BatchPatchIntents.EXTRA_PACKAGES, packageNames.toTypedArray())
                    }
                }
            }
        )
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            795,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = textOverride ?: if (running) {
            applicationContext.resources.getQuantityString(
                R.plurals.batch_patch_notification_running,
                count,
                count
            )
        } else if (installed > 0) {
            applicationContext.resources.getQuantityString(
                R.plurals.batch_patch_notification_finished_installed,
                count,
                count,
                installed
            )
        } else {
            applicationContext.resources.getQuantityString(
                R.plurals.batch_patch_notification_finished,
                count,
                count
            )
        }
        return Notification.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(
                Icon.createWithResource(
                    applicationContext,
                    R.drawable.ic_notification_status
                )
            )
            .setContentTitle(applicationContext.getString(R.string.batch_patch_title))
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(running)
            .setAutoCancel(!running)
            .build()
    }

    companion object {
        const val WORK_NAME = "urv_auto_patch"
        private const val CHANNEL_ID = "urv-batch-patching"
        private const val NOTIFICATION_ID_RUNNING = 795
        private const val NOTIFICATION_ID_FINISHED = 796

        suspend fun schedule(
            context: Context,
            prefs: PreferencesManager,
            interval: SearchForUpdatesBackgroundInterval,
            requiresCharging: Boolean
        ) {
            if (interval == SearchForUpdatesBackgroundInterval.NEVER) {
                if (prefs.autoPatchEnabled.get()) {
                    prefs.updateAutoPatchEnabled(false)
                }
                cancel(context)
                return
            }
            if (!reconcileAutoPatchNotificationPermission(context, prefs)) {
                cancel(context)
                return
            }
            val request = PeriodicWorkRequestBuilder<AutoPatchWorker>(
                interval.value,
                TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .setRequiresCharging(requiresCharging)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
