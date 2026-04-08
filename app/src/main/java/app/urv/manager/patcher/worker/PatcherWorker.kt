package app.urv.manager.patcher.worker

import android.app.Activity
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Parcelable
import android.os.PowerManager
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.urv.manager.MainActivity
import app.universal.revanced.manager.R
import app.urv.manager.data.platform.Filesystem
import app.urv.manager.data.room.apps.installed.InstallType
import app.urv.manager.domain.installer.RootInstaller
import app.urv.manager.domain.manager.KeystoreManager
import app.urv.manager.domain.manager.PreferencesManager
import app.urv.manager.domain.repository.DownloadResult
import app.urv.manager.domain.repository.DownloadedAppRepository
import app.urv.manager.domain.repository.DownloaderPluginRepository
import app.urv.manager.domain.repository.InstalledAppRepository
import app.urv.manager.domain.repository.PatchBundleRepository
import app.urv.manager.domain.worker.Worker
import app.urv.manager.domain.worker.WorkerRepository
import app.urv.manager.network.downloader.LoadedDownloaderPlugin
import app.urv.manager.patcher.ProgressEvent
import app.urv.manager.patcher.RemoteError
import app.urv.manager.patcher.StepId
import app.urv.manager.patcher.toSafeRemoteError
import app.urv.manager.patcher.logger.Logger
import app.urv.manager.patcher.logger.LogLevel
import app.urv.manager.patcher.split.SplitApkPreparer
import app.urv.manager.patcher.runtime.CoroutineRuntime
import app.urv.manager.patcher.runtime.ProcessRuntime
import app.urv.manager.patcher.runtime.MemoryLimitConfig
import app.urv.manager.patcher.ample.AmpleBridgeFailureException
import app.urv.manager.patcher.morphe.MorpheBridgeFailureException
import app.urv.manager.patcher.revanced.Revanced22BridgeFailureException
import app.urv.manager.patcher.runtime.ample.AmpleBridgeRuntime
import app.urv.manager.patcher.runtime.ample.AmpleProcessRuntime
import app.urv.manager.patcher.runtime.morphe.MorpheBridgeRuntime
import app.urv.manager.patcher.runtime.morphe.MorpheProcessRuntime
import app.urv.manager.patcher.runtime.Revanced22BridgeRuntime
import app.urv.manager.patcher.runtime.Revanced22ProcessRuntime
import app.urv.manager.patcher.runCancellableBlockingIo
import app.urv.manager.patcher.runStep
import app.urv.manager.patcher.toRemoteError
import app.urv.manager.patcher.patch.PatchBundleType
import app.urv.manager.plugin.downloader.GetScope
import app.urv.manager.plugin.downloader.PluginHostApi
import app.urv.manager.plugin.downloader.UserInteractionException
import app.urv.manager.ui.model.SelectedApp
import app.urv.manager.util.AppForeground
import app.urv.manager.util.Options
import app.urv.manager.util.PM
import app.urv.manager.util.PatchSelection
import app.urv.manager.util.tag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(PluginHostApi::class)
class PatcherWorker(
    context: Context,
    parameters: WorkerParameters
) : Worker<PatcherWorker.Args>(context, parameters), KoinComponent {
    private val workerRepository: WorkerRepository by inject()
    private val prefs: PreferencesManager by inject()
    private val keystoreManager: KeystoreManager by inject()
    private val downloaderPluginRepository: DownloaderPluginRepository by inject()
    private val downloadedAppRepository: DownloadedAppRepository by inject()
    private val pm: PM by inject()
    private val fs: Filesystem by inject()
    private val installedAppRepository: InstalledAppRepository by inject()
    private val rootInstaller: RootInstaller by inject()
    private val patchBundleRepository: PatchBundleRepository by inject()
    private var activeRuntime: app.urv.manager.patcher.runtime.Runtime? = null
    private var activeMorpheRuntime: app.urv.manager.patcher.runtime.morphe.MorpheRuntime? = null
    private var activeAmpleRuntime: app.urv.manager.patcher.runtime.ample.AmpleRuntime? = null
    @Volatile
    private var patchNotificationSteps: List<String> = emptyList()
    @Volatile
    private var foregroundStarted: Boolean = false
    @Volatile
    private var lastNotificationProgressCurrent: Int = 0
    @Volatile
    private var notificationSplitPreparationSeen: Boolean = false
    @Volatile
    private var lastWriteApkNotificationPhaseIndex: Int = -1
    @Volatile
    private var lastWriteApkNotificationDetail: String? = null
    private val notificationStateLock = Any()
    private val workerProgressScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val workerProgressMutex = Mutex()
    private val progressSequence = AtomicLong(0L)
    private var progressPersistenceClosed: Boolean = false
    private var lastPersistedProgressSequence: Long = Long.MIN_VALUE
    private val cachedExpandableSubSteps = ConcurrentHashMap<StepId, List<String>>()
    private val notificationExpandableSubSteps = ConcurrentHashMap<StepId, List<String>>()
    private val dexCompilePattern =
        Regex("(Compiling|Compiled)\\s+(classes\\d*\\.dex)", RegexOption.IGNORE_CASE)
    private val dexWritePattern =
        Regex("Write\\s+\\[[^\\]]+\\]\\s+(classes\\d*\\.dex)", RegexOption.IGNORE_CASE)
    private val notificationManager by lazy {
        applicationContext.getSystemService(NotificationManager::class.java)
    }
    private val patchingServiceType by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
    }

    class Args(
        val input: SelectedApp,
        val output: String,
        val selectedPatches: PatchSelection,
        val options: Options,
        val logger: Logger,
        val handleStartActivityRequest: suspend (LoadedDownloaderPlugin, Intent) -> ActivityResult,
        val setInputFile: suspend (File, Boolean, Boolean) -> Unit,
        val onEvent: (ProgressEvent) -> Unit
    ) {
        val packageName get() = input.packageName
    }

    override suspend fun getForegroundInfo() = createForegroundInfo(event = null, totalPatchCount = 0)

    private fun createForegroundInfo(
        event: ProgressEvent?,
        totalPatchCount: Int
    ): ForegroundInfo = createForegroundInfo(createNotification(event, totalPatchCount))

    private fun createForegroundInfo(notification: Notification): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID,
        notification,
        patchingServiceType
    )

    private fun createNotification(
        event: ProgressEvent?,
        totalPatchCount: Int
    ): Notification {
        val notificationIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val channel = NotificationChannel(
            PATCHING_NOTIFICATION_CHANNEL_ID,
            applicationContext.getString(R.string.notification_channel_patching_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description =
            applicationContext.getString(R.string.notification_channel_patching_description)
        notificationManager.createNotificationChannel(channel)
        val progress = normalizeNotificationProgress(notificationProgress(event, totalPatchCount))
        val contentText = notificationContentText(event, totalPatchCount)
        return Notification.Builder(applicationContext, channel.id)
            .setContentTitle(applicationContext.getText(R.string.patcher_notification_title))
            .setContentText(contentText)
            .setSmallIcon(Icon.createWithResource(applicationContext, R.drawable.ic_notification_status))
            .setContentIntent(pendingIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (progress != null) {
                    setProgress(progress.max, progress.current, progress.indeterminate)
                } else {
                    setProgress(0, 0, false)
                }
            }
            .build()
    }

    private fun notificationContentText(
        event: ProgressEvent?,
        totalPatchCount: Int
    ): CharSequence {
        val stepText = event?.stepId?.let { step -> notificationStepTitle(step, totalPatchCount) }

        val detail = when (event) {
            is ProgressEvent.Progress -> normalizeNotificationDetail(event.stepId, event.message)
            else -> null
        }

        return when {
            stepText != null && detail != null -> "$stepText • $detail"
            stepText != null -> stepText
            else -> applicationContext.getText(R.string.patcher_notification_text)
        }
    }

    private fun normalizeNotificationDetail(stepId: StepId?, message: String?): String? {
        val detail = message?.takeIf { it.isNotBlank() } ?: return null
        if (stepId != StepId.WriteAPK) return detail
        return detail.trim()
    }

    private fun notificationStepTitle(step: StepId, totalPatchCount: Int): String = when (step) {
        StepId.DownloadAPK -> applicationContext.getString(R.string.download_apk)
        StepId.LoadPatches -> applicationContext.getString(R.string.patcher_step_load_patches)
        StepId.PrepareSplitApk -> applicationContext.getString(R.string.patcher_step_prepare_split_apk)
        StepId.ReadAPK -> applicationContext.getString(R.string.patcher_step_unpack)
        StepId.ExecutePatches -> applicationContext.getString(R.string.execute_patches)
        is StepId.ExecutePatch -> {
            patchNotificationSteps.getOrNull(step.index)?.takeIf { it.isNotBlank() } ?: run {
                if (totalPatchCount > 0) {
                    val current = (step.index + 1).coerceIn(1, totalPatchCount)
                    "${applicationContext.getString(R.string.execute_patches)} ($current/$totalPatchCount)"
                } else {
                    applicationContext.getString(R.string.execute_patches)
                }
            }
        }
        StepId.WriteAPK -> applicationContext.getString(R.string.patcher_step_write_patched)
        StepId.SignAPK -> applicationContext.getString(R.string.patcher_step_sign_apk)
    }

    private data class NotificationProgress(
        val max: Int,
        val current: Int,
        val indeterminate: Boolean
    )


    private fun notificationProgress(
        event: ProgressEvent?,
        totalPatchCount: Int
    ): NotificationProgress? {
        if (event == null) return NotificationProgress(max = 0, current = 0, indeterminate = true)
        return when (event) {
            is ProgressEvent.Started -> if (event.stepId == StepId.DownloadAPK) {
                NotificationProgress(max = 0, current = 0, indeterminate = true)
            } else {
                notificationStageProgress(event.stepId, totalPatchCount, 0f)
            }
            is ProgressEvent.Progress -> {
                val total = event.total?.takeIf { it > 0L }
                val current = event.current
                if (event.stepId == StepId.DownloadAPK) {
                    if (total != null && current != null) {
                        val maxInt = min(total, Int.MAX_VALUE.toLong()).toInt()
                        val curInt = min(current, maxInt.toLong()).toInt()
                        NotificationProgress(max = maxInt, current = curInt, indeterminate = false)
                    } else {
                        NotificationProgress(max = 0, current = 0, indeterminate = true)
                    }
                } else {
                    notificationStageProgress(
                        stepId = event.stepId,
                        totalPatchCount = totalPatchCount,
                        fraction = when {
                            total != null && current != null ->
                                (current.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            event.stepId == StepId.WriteAPK -> notificationWriteApkFraction(event)
                            else -> 0.5f
                        }
                    )
                }
            }
            is ProgressEvent.Completed -> if (event.stepId == StepId.DownloadAPK) {
                NotificationProgress(max = 0, current = 0, indeterminate = true)
            } else {
                notificationStageProgress(event.stepId, totalPatchCount, 1f)
            }
            is ProgressEvent.Failed -> null
        }
    }

    private fun normalizeNotificationProgress(progress: NotificationProgress?): NotificationProgress? {
        if (progress == null) return null
        if (progress.indeterminate || progress.max <= 0) return progress

        val current = progress.current
            .coerceAtLeast(lastNotificationProgressCurrent)
            .coerceAtMost(progress.max)
        lastNotificationProgressCurrent = current
        return if (current == progress.current) progress else progress.copy(current = current)
    }

    private fun notificationStageProgress(
        stepId: StepId,
        totalPatchCount: Int,
        fraction: Float
    ): NotificationProgress {
        val normalized = fraction.coerceIn(0f, 1f)
        val current = when (stepId) {
            StepId.DownloadAPK -> 0
            StepId.LoadPatches -> {
                val (start, end) = loadPatchesNotificationRange()
                progressInRange(start, end, normalized)
            }
            StepId.PrepareSplitApk -> {
                val (start, end) = prepareSplitNotificationRange()
                progressInRange(start, end, normalized)
            }
            StepId.ReadAPK -> progressInRange(READ_APK_START, READ_APK_END, normalized)
            StepId.ExecutePatches -> progressInRange(
                EXECUTE_PATCHES_START,
                EXECUTE_PATCHES_END,
                normalized
            )
            is StepId.ExecutePatch -> notificationExecutePatchProgress(stepId, totalPatchCount, normalized)
            StepId.WriteAPK -> progressInRange(WRITE_APK_START, WRITE_APK_END, normalized)
            StepId.SignAPK -> progressInRange(SIGN_APK_START, SIGN_APK_END, normalized)
        }
        return NotificationProgress(
            max = NOTIFICATION_PROGRESS_MAX,
            current = current,
            indeterminate = false
        )
    }

    private fun loadPatchesNotificationRange(): Pair<Int, Int> {
        return if (notificationSplitPreparationSeen) {
            LOAD_PATCHES_WITH_SPLIT_START to LOAD_PATCHES_WITH_SPLIT_END
        } else {
            LOAD_PATCHES_START to LOAD_PATCHES_END
        }
    }

    private fun prepareSplitNotificationRange(): Pair<Int, Int> {
        return if (notificationSplitPreparationSeen) {
            PREPARE_SPLIT_WITH_SPLIT_START to PREPARE_SPLIT_WITH_SPLIT_END
        } else {
            PREPARE_SPLIT_START to PREPARE_SPLIT_END
        }
    }

    private fun notificationExecutePatchProgress(
        stepId: StepId.ExecutePatch,
        totalPatchCount: Int,
        fraction: Float
    ): Int {
        if (totalPatchCount <= 0) {
            return progressInRange(EXECUTE_PATCHES_START, EXECUTE_PATCHES_END, fraction)
        }
        val currentPatch = stepId.index.coerceAtLeast(0).coerceAtMost(totalPatchCount)
        val overallFraction =
            ((currentPatch.toFloat() + fraction.coerceIn(0f, 1f)) / totalPatchCount.toFloat())
                .coerceIn(0f, 1f)
        return progressInRange(EXECUTE_PATCHES_START, EXECUTE_PATCHES_END, overallFraction)
    }

    private fun notificationWriteApkFraction(event: ProgressEvent.Progress): Float {
        val detail = normalizeNotificationDetail(event.stepId, event.message)?.trim()
        return when {
            detail == null -> 0.5f
            detail.equals("Preparing output APK", ignoreCase = true) -> 0.05f
            detail.equals("Copying base APK", ignoreCase = true) -> 0.15f
            detail.equals("Applying patched changes", ignoreCase = true) -> 0.28f
            detail.equals("Compiling patched dex files", ignoreCase = true) -> 0.4f
            detail.equals("Compiling modified resources", ignoreCase = true) -> 0.65f
            detail.startsWith("Compiling ", ignoreCase = true) -> 0.4f
            detail.startsWith("Compiled ", ignoreCase = true) -> 0.4f
            detail.equals("Writing output APK", ignoreCase = true) -> 0.82f
            detail.equals("Finalizing output", ignoreCase = true) -> 0.92f
            detail.equals("Stripping native libraries", ignoreCase = true) -> 0.97f
            !event.subSteps.isNullOrEmpty() -> 0.25f
            else -> 0.5f
        }
    }

    private fun progressInRange(start: Int, end: Int, fraction: Float): Int {
        val normalized = fraction.coerceIn(0f, 1f)
        return (start + ((end - start) * normalized)).roundToInt()
            .coerceIn(start, end)
    }

    private fun cancelActiveRuntimes() {
        activeRuntime?.cancel()
        activeMorpheRuntime?.cancel()
        activeAmpleRuntime?.cancel()
    }

    private fun isAppTaskPresent(): Boolean {
        val activityManager = applicationContext.getSystemService(ActivityManager::class.java) ?: return true
        return runCatching {
            activityManager.appTasks.isNotEmpty()
        }.onFailure { error ->
            Log.d(tag, "Failed to inspect app task state", error)
        }.getOrDefault(true)
    }

    private fun cancelForAppClosed(reason: String) {
        if (!workerRepository.isActiveUniqueWork(UNIQUE_WORK_NAME, id)) return

        Log.d(tag, "$reason; cancelling active patching work")
        clearForegroundNotificationIfOwned()
        workerRepository.cancelUniqueWork(UNIQUE_WORK_NAME)
        cancelActiveRuntimes()
    }

    private fun stopForegroundUpdates() {
        cancelActiveRuntimes()
        synchronized(notificationStateLock) {
            patchNotificationSteps = emptyList()
            resetNotificationProgressTrackingLocked()
            foregroundStarted = false
        }
        clearForegroundNotificationIfOwned()
    }

    private fun shouldSkipForegroundUpdates(): Boolean {
        if (!isStopped) return false
        stopForegroundUpdates()
        return true
    }

    private fun updateForegroundNotification(event: ProgressEvent?, totalPatchCount: Int) {
        if (shouldSkipForegroundUpdates()) return
        synchronized(notificationStateLock) {
            val notificationEvent = normalizeNotificationEvent(event)
            val notification = createNotification(notificationEvent, totalPatchCount)
            try {
                if (!foregroundStarted) {
                    runBlocking {
                        setForeground(createForegroundInfo(notification))
                    }
                    foregroundStarted = true
                }
            } catch (e: Exception) {
                Log.d(tag, "Failed to set foreground notification:", e)
            }

            try {
                notificationManager.notify(NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                Log.d(tag, "Failed to refresh foreground notification:", e)
            }
        }
    }

    private fun normalizeNotificationEvent(event: ProgressEvent?): ProgressEvent? {
        when (event?.stepId) {
            StepId.PrepareSplitApk -> notificationSplitPreparationSeen = true
            null,
            StepId.DownloadAPK,
            StepId.LoadPatches,
            StepId.ReadAPK,
            StepId.ExecutePatches,
            StepId.WriteAPK,
            StepId.SignAPK -> Unit
            is StepId.ExecutePatch -> Unit
        }
        return when (event) {
            is ProgressEvent.Started -> {
                if (event.stepId == StepId.LoadPatches) {
                    lastNotificationProgressCurrent = 0
                    resetWriteApkNotificationPhase()
                }
                if (event.stepId == StepId.WriteAPK || event.stepId == StepId.SignAPK) {
                    resetWriteApkNotificationPhase()
                }
                event
            }
            is ProgressEvent.Progress -> normalizeWriteApkNotificationPhase(event)
            is ProgressEvent.Completed -> {
                if (event.stepId == StepId.WriteAPK || event.stepId == StepId.SignAPK) {
                    resetWriteApkNotificationPhase()
                }
                event
            }
            is ProgressEvent.Failed -> {
                if (event.stepId == StepId.WriteAPK || event.stepId == StepId.SignAPK) {
                    resetWriteApkNotificationPhase()
                }
                event
            }
            null -> null
        }
    }

    private fun normalizeWriteApkNotificationPhase(
        event: ProgressEvent.Progress
    ): ProgressEvent.Progress {
        if (event.stepId != StepId.WriteAPK) return event
        val detail = normalizeNotificationDetail(event.stepId, event.message)
            ?.trim()
            ?.let(::normalizeWriteApkNotificationProgressDetail)
            ?: return lastWriteApkNotificationDetail?.let { detail ->
                event.copy(message = detail)
            } ?: event
        val phaseIndex = writeApkNotificationPhaseIndex(detail)
        if (phaseIndex == -1) {
            return lastWriteApkNotificationDetail?.let { lastDetail ->
                event.copy(message = lastDetail)
            } ?: event
        }
        if (phaseIndex < lastWriteApkNotificationPhaseIndex) {
            return event.copy(message = lastWriteApkNotificationDetail)
        }
        if (shouldRetainWriteApkNotificationDetail(detail, phaseIndex)) {
            return event.copy(message = lastWriteApkNotificationDetail)
        }
        lastWriteApkNotificationPhaseIndex = phaseIndex
        lastWriteApkNotificationDetail = detail
        return event.copy(message = detail)
    }

    private fun resetWriteApkNotificationPhase() {
        lastWriteApkNotificationPhaseIndex = -1
        lastWriteApkNotificationDetail = null
    }

    private fun resetNotificationProgressTracking() {
        synchronized(notificationStateLock) {
            resetNotificationProgressTrackingLocked()
        }
    }

    private fun resetNotificationProgressTrackingLocked() {
        lastNotificationProgressCurrent = 0
        notificationSplitPreparationSeen = false
        resetWriteApkNotificationPhase()
    }

    private fun primeNotificationSplitFlow(input: SelectedApp) {
        if (input !is SelectedApp.Local) return
        if (!SplitApkPreparer.isSplitArchive(input.file)) return
        synchronized(notificationStateLock) {
            notificationSplitPreparationSeen = true
        }
    }

    private fun writeApkNotificationPhaseIndex(detail: String): Int = when {
        detail.equals("Preparing output APK", ignoreCase = true) -> 0
        detail.equals("Copying base APK", ignoreCase = true) -> 1
        detail.equals("Applying patched changes", ignoreCase = true) -> 2
        detail.equals("Compiling patched dex files", ignoreCase = true) -> 3
        detail.equals("Compiling modified resources", ignoreCase = true) -> 4
        detail.startsWith("Compiling ", ignoreCase = true) -> 3
        detail.startsWith("Compiled ", ignoreCase = true) -> 3
        detail.equals("Writing output APK", ignoreCase = true) -> 5
        detail.equals("Finalizing output", ignoreCase = true) -> 6
        detail.equals("Stripping native libraries", ignoreCase = true) -> 7
        else -> -1
    }

    private fun normalizeWriteApkNotificationProgressDetail(detail: String): String {
        val trimmed = detail.trim()
        return if (trimmed.startsWith("Compiled ", ignoreCase = true)) {
            "Compiling ${trimmed.removePrefix("Compiled ").trim()}"
        } else {
            trimmed
        }
    }

    private fun shouldRetainWriteApkNotificationDetail(detail: String, phaseIndex: Int): Boolean {
        val lastDetail = lastWriteApkNotificationDetail ?: return false
        if (phaseIndex != lastWriteApkNotificationPhaseIndex) return false
        if (phaseIndex != 3) return false

        val lastIsDexChild = isWriteApkDexNotificationTitle(lastDetail)
        val nextIsDexChild = isWriteApkDexNotificationTitle(detail)
        if (lastIsDexChild && !nextIsDexChild) return true
        if (!lastIsDexChild || !nextIsDexChild) return false

        val lastDexName = lastDetail.removePrefix("Compiling ").trim()
        val nextDexName = detail.removePrefix("Compiling ").trim()
        return writeApkDexSortKey(nextDexName) < writeApkDexSortKey(lastDexName)
    }

    private fun clearForegroundNotification() {
        try {
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.d(tag, "Failed to clear foreground notification:", e)
        }
    }

    private fun clearForegroundNotificationIfOwned() {
        if (!ownsCurrentPatchingNotification()) return
        clearForegroundNotification()
        workerRepository.clearActiveUniqueWork(UNIQUE_WORK_NAME, id)
    }

    private fun ownsCurrentPatchingNotification(): Boolean {
        val activeWorkId = workerRepository.activeUniqueWorkId(UNIQUE_WORK_NAME)
        if (activeWorkId == id) return true
        if (activeWorkId != null) return false

        return try {
            runBlocking {
                workerRepository.workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME).first()
            }.none { workInfo ->
                workInfo.id != id && !workInfo.state.isFinished
            }
        } catch (e: Exception) {
            Log.d(tag, "Failed to resolve current patching work ownership:", e)
            true
        }
    }

    private suspend fun updateWorkerProgressState(
        active: Boolean,
        snapshot: PatcherWorkerProgressSnapshot? = null
    ): Boolean {
        return runCatching {
            setProgress(PatcherWorkerProgressState.toWorkData(active, snapshot))
            true
        }.onFailure { error ->
            Log.d(tag, "Failed to update active patching state", error)
        }.getOrDefault(false)
    }

    private suspend fun persistWorkerProgressSnapshot(snapshot: PatcherWorkerProgressSnapshot) {
        workerProgressMutex.withLock {
            if (progressPersistenceClosed) return
            if (snapshot.sequence <= lastPersistedProgressSequence) return
            if (updateWorkerProgressState(active = true, snapshot = snapshot)) {
                lastPersistedProgressSequence = snapshot.sequence
            }
        }
    }

    private suspend fun deactivateWorkerProgressState() {
        workerProgressMutex.withLock {
            progressPersistenceClosed = true
            updateWorkerProgressState(active = false)
        }
    }

    private fun persistProgressSnapshot(event: ProgressEvent) {
        if (event is ProgressEvent.Failed) return

        cacheExpandableSubSteps(event)
        val snapshotEvent = when (event) {
            is ProgressEvent.Progress -> {
                val cachedSubSteps = if (isExpandableStep(event.stepId)) {
                    event.subSteps ?: cachedExpandableSubSteps[event.stepId]
                } else {
                    null
                }
                event.copy(subSteps = cachedSubSteps)
            }
            else -> event
        }
        val snapshot = PatcherWorkerProgressSnapshot(
            sequence = progressSequence.incrementAndGet(),
            event = snapshotEvent
        )

        workerProgressScope.launch {
            persistWorkerProgressSnapshot(snapshot)
        }
    }

    private fun cacheExpandableSubSteps(event: ProgressEvent) {
        when (event) {
            is ProgressEvent.Started -> {
                if (isExpandableStep(event.stepId)) {
                    cachedExpandableSubSteps.remove(event.stepId)
                    notificationExpandableSubSteps.remove(event.stepId)
                    if (!event.subSteps.isNullOrEmpty()) {
                        cachedExpandableSubSteps[event.stepId] = event.subSteps
                        notificationExpandableSubSteps[event.stepId] = event.subSteps
                    }
                }
            }
            is ProgressEvent.Progress -> {
                if (isExpandableStep(event.stepId) && !event.subSteps.isNullOrEmpty()) {
                    cachedExpandableSubSteps[event.stepId] = event.subSteps
                    notificationExpandableSubSteps[event.stepId] = event.subSteps
                }
            }
            is ProgressEvent.Completed,
            is ProgressEvent.Failed -> {
                event.stepId?.takeIf(::isExpandableStep)?.let {
                    cachedExpandableSubSteps.remove(it)
                    notificationExpandableSubSteps.remove(it)
                }
            }
        }
    }

    private fun handleWorkerLogProgress(
        message: String,
        totalPatchCount: Int,
        onEvent: (ProgressEvent) -> Unit
    ) {
        if (shouldSkipForegroundUpdates()) return
        val event = buildWriteApkLogEvent(message) ?: return
        forwardWriteApkLogProgressForUi(event, onEvent)
        val notificationEvent = normalizeWriteApkNotificationEvent(event)
        updateForegroundNotification(notificationEvent, totalPatchCount)
    }

    private fun normalizeWriteApkNotificationEvent(
        event: ProgressEvent.Progress
    ): ProgressEvent.Progress {
        if (event.stepId != StepId.WriteAPK) return event
        return event.copy(subSteps = null)
    }

    private fun forwardWriteApkLogProgressForUi(
        event: ProgressEvent.Progress,
        onEvent: (ProgressEvent) -> Unit
    ) {
        if (event.stepId != StepId.WriteAPK) return

        val uiEvent = normalizeEarlyWriteApkUiEvent(event)
        runCatching {
            onEvent(uiEvent)
        }.onFailure { error ->
            Log.d(tag, "Failed to forward write APK log progress to UI", error)
        }
        val snapshotEvent = normalizeEarlyWriteApkUiEvent(event).copy(
            subSteps = cachedExpandableSubSteps[StepId.WriteAPK] ?: event.subSteps
        )
        val snapshot = PatcherWorkerProgressSnapshot(
            sequence = progressSequence.incrementAndGet(),
            event = snapshotEvent
        )

        workerProgressScope.launch {
            persistWorkerProgressSnapshot(snapshot)
        }
    }

    private fun normalizeEarlyWriteApkUiEvent(event: ProgressEvent.Progress): ProgressEvent.Progress {
        if (event.stepId != StepId.WriteAPK) return event
        if (event.message.equals("Applying patched changes", ignoreCase = true)) {
            return event.copy(message = null, subSteps = null)
        }
        return event.copy(subSteps = null)
    }

    private fun buildWriteApkLogEvent(rawMessage: String): ProgressEvent.Progress? {
        val message = rawMessage.trim()
        val rawDetail = when {
            message.contains("Writing patched files", ignoreCase = true) ->
                "Applying patched changes"
            message.contains("Compiling modified resources", ignoreCase = true) ||
                message.contains("Compiling patched resources", ignoreCase = true) ||
                message.contains("Compiled modified resources", ignoreCase = true) ||
                message.contains("Compiled patched resources", ignoreCase = true) ->
                "Compiling modified resources"
            message.contains("Writing output APK", ignoreCase = true) ->
                "Writing output APK"
            message.contains("Finalizing output", ignoreCase = true) ->
                "Finalizing output"
            message.contains("Patched apk saved to", ignoreCase = true) ->
                "Writing output APK"
            isDexCompilePhaseTitle(message) -> message
            else -> {
                val match = dexCompilePattern.find(message) ?: dexWritePattern.find(message)
                val dexName =
                    match?.groupValues?.lastOrNull()?.takeIf { it.endsWith(".dex", ignoreCase = true) }
                        ?: return null
                val completionKeyword = match.groupValues.getOrNull(1)
                if (completionKeyword.equals("Compiled", ignoreCase = true)) {
                    "Compiled $dexName"
                } else {
                    "Compiling $dexName"
                }
            }
        }

        updateCachedWriteApkNotificationSubSteps(rawDetail)
        val detail = rawDetail

        return ProgressEvent.Progress(
            stepId = StepId.WriteAPK,
            message = detail,
            subSteps = notificationExpandableSubSteps[StepId.WriteAPK]
        )
    }

    private fun updateCachedWriteApkNotificationSubSteps(detail: String) {
        val normalized = normalizeWriteApkNotificationCacheTitle(detail)
        if (normalized.isBlank()) return

        val existing = notificationExpandableSubSteps[StepId.WriteAPK].orEmpty()
        val updated = existing.toMutableList()
        if (updated.isEmpty()) {
            updated += defaultWriteApkNotificationSubSteps()
        }

        if (isWriteApkDexNotificationTitle(normalized)) {
            ensureWriteApkDexNotificationTitle(updated, normalized)
        } else {
            ensureWriteApkPhaseNotificationTitle(updated, normalized)
        }

        if (updated != existing) {
            notificationExpandableSubSteps[StepId.WriteAPK] = updated
        }
    }

    private fun normalizeWriteApkNotificationCacheTitle(detail: String): String {
        val trimmed = detail.trim()
        if (trimmed.startsWith("Compiled ", ignoreCase = true)) {
            return "Compiling ${trimmed.removePrefix("Compiled ").trim()}"
        }
        return trimmed
    }

    private fun isWriteApkDexNotificationTitle(title: String): Boolean {
        if (!title.startsWith("Compiling ", ignoreCase = true)) return false
        return title.removePrefix("Compiling ").trim().endsWith(".dex", ignoreCase = true)
    }

    private fun ensureWriteApkDexNotificationTitle(
        subSteps: MutableList<String>,
        title: String
    ) {
        if (subSteps.any { it.equals(title, ignoreCase = true) }) return

        val resourceIndex = subSteps.indexOfFirst {
            it.equals("Compiling modified resources", ignoreCase = true)
        }.takeIf { it != -1 }
            ?: subSteps.indexOfFirst {
                it.equals("Writing output APK", ignoreCase = true)
            }.takeIf { it != -1 }
            ?: subSteps.size

        val targetDexName = title.removePrefix("Compiling ").trim()
        val targetSortKey = writeApkDexSortKey(targetDexName)
        val insertIndex = (0 until resourceIndex)
            .firstOrNull { index ->
                val existing = subSteps[index]
                isWriteApkDexNotificationTitle(existing) &&
                    writeApkDexSortKey(existing.removePrefix("Compiling ").trim()) > targetSortKey
            }
            ?: resourceIndex
        subSteps.add(insertIndex, title)
    }

    private fun ensureWriteApkPhaseNotificationTitle(
        subSteps: MutableList<String>,
        title: String
    ) {
        if (subSteps.any { it.equals(title, ignoreCase = true) }) return

        val phaseOrder = listOf(
            "Copying base APK",
            "Applying patched changes",
            "Compiling patched dex files",
            "Compiling modified resources",
            "Writing output APK",
            "Finalizing output",
            "Stripping native libraries"
        )
        val targetOrder = phaseOrder.indexOfFirst { it.equals(title, ignoreCase = true) }
        if (targetOrder == -1) {
            subSteps += title
            return
        }

        val insertIndex = subSteps.indexOfFirst { existing ->
            val existingOrder = phaseOrder.indexOfFirst { it.equals(existing, ignoreCase = true) }
            existingOrder != -1 && existingOrder > targetOrder
        }.takeIf { it != -1 } ?: subSteps.size
        subSteps.add(insertIndex, title)
    }

    private fun defaultWriteApkNotificationSubSteps(): List<String> = listOf(
        "Copying base APK",
        "Applying patched changes",
        "Compiling modified resources",
        "Writing output APK",
        "Finalizing output"
    )

    private fun writeApkDexSortKey(name: String): Int {
        val base = name.removeSuffix(".dex")
        if (base == "classes") return 1
        val suffix = base.removePrefix("classes")
        return suffix.toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun isDexCompilePhaseTitle(message: String): Boolean {
        return message.equals("Compiling patched dex files", ignoreCase = true) ||
            message.equals("Applying patched changes", ignoreCase = true)
    }

    private fun isExpandableStep(stepId: StepId) = when (stepId) {
        StepId.PrepareSplitApk,
        StepId.WriteAPK -> true
        else -> false
    }

    override suspend fun doWork(): Result {
        resetNotificationProgressTracking()
        val workerFinished = AtomicBoolean(false)
        val stopMonitor = CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            var missingTaskSamples = 0
            while (!workerFinished.get()) {
                if (isStopped) {
                    cancelActiveRuntimes()
                } else if (AppForeground.isMainTaskClosed) {
                    cancelForAppClosed("Main activity destroyed")
                } else if (isAppTaskPresent()) {
                    missingTaskSamples = 0
                } else {
                    missingTaskSamples++
                    if (missingTaskSamples >= 4) {
                        cancelForAppClosed("App task removed")
                    }
                }
                delay(250)
            }
        }
        if (runAttemptCount > 0) {
            Log.d(tag, "Android requested retrying but retrying is disabled.".logFmt())
            return Result.failure()
        }

        val wakeLock: PowerManager.WakeLock =
            (applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$tag::Patcher")
                .apply {
                    acquire(10 * 60 * 1000L)
                    Log.d(tag, "Acquired wakelock.")
                }

        try {
            val initialForegroundInfo = createForegroundInfo(event = null, totalPatchCount = 0)
            setForeground(initialForegroundInfo)
            foregroundStarted = true
        } catch (e: Exception) {
            Log.d(tag, "Failed to set initial foreground info:", e)
        }

        return try {
            val args = workerRepository.claimInput(this)
            val totalPatchCount = args.selectedPatches.values.sumOf { it.size }
            primeNotificationSplitFlow(args.input)

            try {
                updateForegroundNotification(event = null, totalPatchCount = totalPatchCount)
            } catch (e: Exception) {
                Log.d(tag, "Failed to publish initial patching notification:", e)
            }

            updateWorkerProgressState(active = true)
            val result = runPatcher(args, totalPatchCount)

            if (result is Result.Success && args.input is SelectedApp.Local && args.input.temporary) {
                args.input.file.delete()
            }

            result
        } finally {
            workerFinished.set(true)
            stopMonitor.cancel()
            withContext(NonCancellable) {
                deactivateWorkerProgressState()
            }
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
            stopForegroundUpdates()
            workerProgressScope.cancel()
        }
    }

    private suspend fun runPatcher(args: Args, totalPatchCount: Int): Result {
        cleanupTemporarySplitArtifacts()
        val patchedApk = fs.tempDir.resolve("patched.apk")
        var downloadCleanup: (() -> Unit)? = null
        patchNotificationSteps = args.selectedPatches.values
            .asSequence()
            .flatten()
            .sorted()
            .toList()
        val workerLogger = object : Logger() {
            override fun log(level: LogLevel, message: String) {
                args.logger.log(level, message)
                handleWorkerLogProgress(message, totalPatchCount, args.onEvent)
            }
        }
        val eventDispatcher: (ProgressEvent) -> Unit = eventDispatcher@{ event ->
            if (shouldSkipForegroundUpdates()) return@eventDispatcher
            args.onEvent(event)
            persistProgressSnapshot(event)
            updateForegroundNotification(event, totalPatchCount)
        }

        return try {
            val startTime = System.currentTimeMillis()
            val autoSaveDownloads = prefs.autoSaveDownloaderApks.get()

            if (args.input is SelectedApp.Installed) {
                installedAppRepository.get(args.packageName)?.let {
                    if (it.installType == InstallType.MOUNT) {
                        rootInstaller.unmount(args.packageName)
                    }
                }
            }

            suspend fun download(plugin: LoadedDownloaderPlugin, data: Parcelable) =
                downloadedAppRepository.download(
                    plugin,
                    data,
                    args.packageName,
                    args.input.version,
                    prefs.suggestedVersionSafeguard.get(),
                    !prefs.disablePatchVersionCompatCheck.get(),
                    onDownload = run {
                        var lastProgressAt = 0L
                        var lastProgressBytes = 0L
                        progressHandler@{ progress ->
                            val current = progress.first
                            val total = progress.second
                            val now = System.currentTimeMillis()
                            val isFinal = total != null && total > 0L && current >= total
                            val shouldDispatch =
                                isFinal ||
                                    lastProgressAt == 0L ||
                                    (now - lastProgressAt) >= DOWNLOAD_PROGRESS_MIN_INTERVAL_MS ||
                                    (current - lastProgressBytes) >= DOWNLOAD_PROGRESS_MIN_BYTES

                            if (!shouldDispatch) return@progressHandler

                            lastProgressAt = now
                            lastProgressBytes = current
                            eventDispatcher(
                                ProgressEvent.Progress(
                                    stepId = StepId.DownloadAPK,
                                    current = current,
                                    total = total
                                )
                            )
                        }
                    },
                    persistDownload = autoSaveDownloads
                ).also { result ->
                    args.setInputFile(result.file, result.needsSplit, result.merged)
                }

            val downloadResult = when (val selectedApp = args.input) {
                is SelectedApp.Download -> runStep(StepId.DownloadAPK, eventDispatcher) {
                    val (plugin, data) = downloaderPluginRepository.unwrapParceledData(selectedApp.data)
                    download(plugin, data)
                }

                is SelectedApp.Search -> runStep(StepId.DownloadAPK, eventDispatcher) {
                    var lastUserInteractionFailure: UserInteractionException? = null
                    for (plugin in downloaderPluginRepository.loadedPluginsFlow.first()) {
                        val interactionFailure = AtomicReference<UserInteractionException?>(null)
                        try {
                            val getScope = object : GetScope {
                                override val pluginPackageName = plugin.packageName
                                override val hostPackageName = applicationContext.packageName
                                override suspend fun requestStartActivity(intent: Intent): Intent? {
                                    interactionFailure.get()?.let { error -> throw error }
                                    val result = try {
                                        args.handleStartActivityRequest(plugin, intent)
                                    } catch (e: UserInteractionException) {
                                        interactionFailure.compareAndSet(null, e)
                                        throw e
                                    }
                                    interactionFailure.get()?.let { error -> throw error }
                                    return when (result.resultCode) {
                                        Activity.RESULT_OK -> result.data
                                        Activity.RESULT_CANCELED -> {
                                            val error = UserInteractionException.Activity.Cancelled()
                                            interactionFailure.compareAndSet(null, error)
                                            throw error
                                        }
                                        else -> {
                                            val error = UserInteractionException.Activity.NotCompleted(
                                                result.resultCode,
                                                result.data
                                            )
                                            interactionFailure.compareAndSet(null, error)
                                            throw error
                                        }
                                    }
                                }
                            }
                            val result = runInterruptiblePluginGet(interactionFailure) {
                                plugin.get(
                                    getScope,
                                    selectedApp.packageName,
                                    selectedApp.version
                                )
                            }?.takeIf { (_, version) ->
                                selectedApp.version == null || version == selectedApp.version
                            }
                            if (result != null) {
                                val (data, _) = result
                                return@runStep download(plugin, data)
                            }
                        } catch (e: UserInteractionException.Activity.NotCompleted) {
                            throw e
                        } catch (e: UserInteractionException) {
                            lastUserInteractionFailure = e
                        }
                    }
                    throw (lastUserInteractionFailure ?: Exception("App is not available."))
                }

                is SelectedApp.Local -> {
                    val needsSplit = SplitApkPreparer.isSplitArchive(selectedApp.file)
                    args.setInputFile(selectedApp.file, needsSplit, false)
                    DownloadResult(selectedApp.file, needsSplit)
                }

                is SelectedApp.Installed -> {
                    val input = prepareInstalledInput(selectedApp.packageName)
                    args.setInputFile(input.file, input.needsSplit, false)
                    input
                }
            }
            downloadCleanup = downloadResult.cleanup
            val inputFile = downloadResult.file

            val bundleType = patchBundleRepository.selectionBundleType(args.selectedPatches)
                ?: throw IllegalStateException("Cannot patch with mixed ReVanced, Morphe, or Ample bundles.")
            if (
                bundleType == PatchBundleType.REVANCED &&
                patchBundleRepository.selectionHasMixedRevancedPatcherVersions(args.selectedPatches)
            ) {
                throw IllegalStateException(
                    "Cannot patch with mixed ReVanced patcher versions. " +
                        "Select either ReVanced v21 or v22 patches."
                )
            }
            val stripNativeLibs = prefs.stripUnusedNativeLibs.get()
            val skipUnneededSplits = prefs.skipUnneededSplitApks.get()
            val inputIsSplitArchive = SplitApkPreparer.isSplitArchive(inputFile)
            val inputHasProblematicEmbeddedApks =
                !inputIsSplitArchive && hasProblematicEmbeddedApkPayloads(inputFile)
            val selectedCount = totalPatchCount
            val processRuntimeRequested = prefs.useProcessRuntime.get()
            val processRuntimeSupported = Build.VERSION.SDK_INT > Build.VERSION_CODES.Q
            val useRevancedPatcher22 =
                bundleType == PatchBundleType.REVANCED &&
                    patchBundleRepository.selectionUsesRevancedPatcher22(args.selectedPatches)
            val forceProcessRuntimeReason =
                if (!processRuntimeRequested && processRuntimeSupported) {
                    when (bundleType) {
                        PatchBundleType.AMPLE -> when {
                            inputIsSplitArchive -> "legacy split APK input"
                            inputHasProblematicEmbeddedApks -> "embedded APK payloads in legacy input"
                            else -> null
                        }
                        PatchBundleType.REVANCED -> when {
                            useRevancedPatcher22 -> null
                            inputIsSplitArchive -> "legacy split APK input"
                            inputHasProblematicEmbeddedApks -> "embedded APK payloads in legacy input"
                            else -> null
                        }
                        PatchBundleType.MORPHE -> null
                    }
                } else {
                    null
                }
            val forceProcessRuntimeForLegacyInput = forceProcessRuntimeReason != null
            val useProcessRuntime =
                (processRuntimeRequested && processRuntimeSupported) || forceProcessRuntimeForLegacyInput
            val memoryOverrideActive = useProcessRuntime && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            val requestedLimit = prefs.patcherProcessMemoryLimit.get()
            val aggressiveLimit = prefs.patcherProcessMemoryAggressive.get()
            val effectiveLimit = MemoryLimitConfig.clampLimitMb(
                applicationContext,
                if (aggressiveLimit) {
                    MemoryLimitConfig.maxLimitMb(applicationContext)
                } else {
                    requestedLimit
                }
            )

            workerLogger.info(
                "Patching started at ${System.currentTimeMillis()} " +
                        "pkg=${args.packageName} version=${args.input.version} " +
                        "input=${inputFile.absolutePath} size=${inputFile.length()} " +
                        "split=$inputIsSplitArchive patches=$selectedCount"
            )
            workerLogger.info(
                "Patcher runtime: bundle=$bundleType experimental=$processRuntimeRequested " +
                    "memoryLimit=${if (memoryOverrideActive) "${effectiveLimit}MB" else "disabled"} " +
                    "(requested=${requestedLimit}MB${if (aggressiveLimit) ", aggressive" else ""})"
            )
            if (processRuntimeRequested && !processRuntimeSupported) {
                workerLogger.warn(
                    "Process runtime requested but unsupported on Android ${Build.VERSION.SDK_INT}; using in-process runtime"
                )
            }
            if (forceProcessRuntimeReason != null) {
                workerLogger.warn(
                    "Forcing process runtime for legacy input because the in-process legacy runtime " +
                        "cannot reliably initialize $forceProcessRuntimeReason"
                )
            }
            workerLogger.info("Runtime mode: ${if (useProcessRuntime) "process" else "in-process"}")
            workerLogger.info("Memory override: ${if (memoryOverrideActive) "enabled" else "disabled"}")
            when (bundleType) {
                PatchBundleType.MORPHE -> {
                    val runtime = if (useProcessRuntime) {
                        MorpheProcessRuntime(applicationContext, useMemoryOverride = memoryOverrideActive)
                    } else {
                        MorpheBridgeRuntime(applicationContext)
                    }
                    activeMorpheRuntime = runtime
                    runtime.execute(
                        inputFile.absolutePath,
                        patchedApk.absolutePath,
                        args.packageName,
                        args.selectedPatches,
                        args.options,
                        workerLogger,
                        eventDispatcher,
                        stripNativeLibs,
                        skipUnneededSplits
                    )
                }
                PatchBundleType.AMPLE -> {
                    val runtime = if (useProcessRuntime) {
                        AmpleProcessRuntime(applicationContext, useMemoryOverride = memoryOverrideActive)
                    } else {
                        AmpleBridgeRuntime(applicationContext)
                    }
                    activeAmpleRuntime = runtime
                    runtime.execute(
                        inputFile.absolutePath,
                        patchedApk.absolutePath,
                        args.packageName,
                        args.selectedPatches,
                        args.options,
                        workerLogger,
                        eventDispatcher,
                        stripNativeLibs,
                        skipUnneededSplits
                    )
                }
                PatchBundleType.REVANCED -> {
                    val runtime: app.urv.manager.patcher.runtime.Runtime =
                        if (useRevancedPatcher22) {
                            if (useProcessRuntime) {
                                Revanced22ProcessRuntime(
                                    applicationContext,
                                    useMemoryOverride = memoryOverrideActive
                                )
                            } else {
                                Revanced22BridgeRuntime(applicationContext)
                            }
                        } else if (useProcessRuntime) {
                            ProcessRuntime(applicationContext)
                        } else {
                            CoroutineRuntime(applicationContext)
                        }
                    activeRuntime = runtime
                    runtime.execute(
                        inputFile.absolutePath,
                        patchedApk.absolutePath,
                        args.packageName,
                        args.selectedPatches,
                        args.options,
                        workerLogger,
                        eventDispatcher,
                        stripNativeLibs,
                        skipUnneededSplits
                    )
                }
            }

            runStep(StepId.SignAPK, eventDispatcher) {
                keystoreManager.sign(patchedApk, File(args.output))
            }

            val elapsed = System.currentTimeMillis() - startTime
            val rt = Runtime.getRuntime()
            val usedMem = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val totalMem = rt.totalMemory() / (1024 * 1024)

            workerLogger.info(
                "Patching succeeded: output=${args.output} size=${File(args.output).length()} " +
                        "elapsed=${elapsed}ms memory=${usedMem}MB/${totalMem}MB"
            )

            Log.i(tag, "Patching succeeded".logFmt())
            Result.success()
        } catch (e: CancellationException) {
            Log.i(tag, "Patching cancelled".logFmt())
            throw e
        } catch (e: ProcessRuntime.ProcessExitException) {
            Log.e(
                tag,
                "Patcher process exited with code ${e.exitCode}".logFmt(),
                e
            )
            val message = applicationContext.getString(
                R.string.patcher_process_exit_message,
                e.exitCode
            )
            eventDispatcher(ProgressEvent.Failed(null, Exception(message).toRemoteError()))
            val previousLimit = prefs.patcherProcessMemoryLimit.get()
            Result.failure(
                workDataOf(
                    PROCESS_EXIT_CODE_KEY to e.exitCode,
                    PROCESS_PREVIOUS_LIMIT_KEY to previousLimit,
                    PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(message)
                )
            )
        } catch (e: ProcessRuntime.RemoteFailureException) {
            Log.e(
                tag,
                "An exception occurred in the remote process while patching. ${e.originalStackTrace}".logFmt()
            )
            eventDispatcher(
                ProgressEvent.Failed(
                    null,
                    RemoteError(
                        type = e::class.java.name,
                        message = e.message,
                        stackTrace = e.originalStackTrace
                    )
                )
            )
            Result.failure(
                workDataOf(PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(e.originalStackTrace))
            )
        } catch (e: MorpheProcessRuntime.ProcessExitException) {
            Log.e(
                tag,
                "Morphe patcher process exited with code ${e.exitCode}".logFmt(),
                e
            )
            val message = applicationContext.getString(
                R.string.patcher_process_exit_message,
                e.exitCode
            )
            eventDispatcher(ProgressEvent.Failed(null, Exception(message).toRemoteError()))
            val previousLimit = prefs.patcherProcessMemoryLimit.get()
            Result.failure(
                workDataOf(
                    PROCESS_EXIT_CODE_KEY to e.exitCode,
                    PROCESS_PREVIOUS_LIMIT_KEY to previousLimit,
                    PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(message)
                )
            )
        } catch (e: MorpheProcessRuntime.RemoteFailureException) {
            Log.e(
                tag,
                "An exception occurred in the Morphe remote process while patching. ${e.originalStackTrace}".logFmt()
            )
            eventDispatcher(
                ProgressEvent.Failed(
                    null,
                    RemoteError(
                        type = e::class.java.name,
                        message = e.message,
                        stackTrace = e.originalStackTrace
                    )
                )
            )
            Result.failure(
                workDataOf(PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(e.originalStackTrace))
            )
        } catch (e: MorpheBridgeFailureException) {
            Log.e(
                tag,
                "An exception occurred in the Morphe bridge runtime while patching. ${e.originalStackTrace}".logFmt()
            )
            eventDispatcher(
                ProgressEvent.Failed(
                    null,
                    RemoteError(
                        type = e::class.java.name,
                        message = e.message,
                        stackTrace = e.originalStackTrace
                    )
                )
            )
            Result.failure(
                workDataOf(PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(e.originalStackTrace))
            )
        } catch (e: AmpleProcessRuntime.ProcessExitException) {
            Log.e(
                tag,
                "Ample patcher process exited with code ${e.exitCode}".logFmt(),
                e
            )
            val message = applicationContext.getString(
                R.string.patcher_process_exit_message,
                e.exitCode
            )
            eventDispatcher(ProgressEvent.Failed(null, Exception(message).toRemoteError()))
            val previousLimit = prefs.patcherProcessMemoryLimit.get()
            Result.failure(
                workDataOf(
                    PROCESS_EXIT_CODE_KEY to e.exitCode,
                    PROCESS_PREVIOUS_LIMIT_KEY to previousLimit,
                    PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(message)
                )
            )
        } catch (e: AmpleProcessRuntime.RemoteFailureException) {
            Log.e(
                tag,
                "An exception occurred in the Ample remote process while patching. ${e.originalStackTrace}".logFmt()
            )
            eventDispatcher(
                ProgressEvent.Failed(
                    null,
                    RemoteError(
                        type = e::class.java.name,
                        message = e.message,
                        stackTrace = e.originalStackTrace
                    )
                )
            )
            Result.failure(
                workDataOf(PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(e.originalStackTrace))
            )
        } catch (e: AmpleBridgeFailureException) {
            Log.e(
                tag,
                "An exception occurred in the Ample bridge runtime while patching. ${e.originalStackTrace}".logFmt()
            )
            eventDispatcher(
                ProgressEvent.Failed(
                    null,
                    RemoteError(
                        type = e::class.java.name,
                        message = e.message,
                        stackTrace = e.originalStackTrace
                    )
                )
            )
            Result.failure(
                workDataOf(PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(e.originalStackTrace))
            )
        } catch (e: Revanced22ProcessRuntime.ProcessExitException) {
            Log.e(
                tag,
                "ReVanced v22 patcher process exited with code ${e.exitCode}".logFmt(),
                e
            )
            val message = applicationContext.getString(
                R.string.patcher_process_exit_message,
                e.exitCode
            )
            eventDispatcher(ProgressEvent.Failed(null, Exception(message).toRemoteError()))
            val previousLimit = prefs.patcherProcessMemoryLimit.get()
            Result.failure(
                workDataOf(
                    PROCESS_EXIT_CODE_KEY to e.exitCode,
                    PROCESS_PREVIOUS_LIMIT_KEY to previousLimit,
                    PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(message)
                )
            )
        } catch (e: Revanced22ProcessRuntime.RemoteFailureException) {
            Log.e(
                tag,
                "An exception occurred in the ReVanced v22 remote process while patching. ${e.originalStackTrace}".logFmt()
            )
            eventDispatcher(
                ProgressEvent.Failed(
                    null,
                    RemoteError(
                        type = e::class.java.name,
                        message = e.message,
                        stackTrace = e.originalStackTrace
                    )
                )
            )
            Result.failure(
                workDataOf(PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(e.originalStackTrace))
            )
        } catch (e: Revanced22BridgeFailureException) {
            Log.e(
                tag,
                "An exception occurred in the ReVanced v22 bridge runtime while patching. ${e.originalStackTrace}".logFmt()
            )
            eventDispatcher(
                ProgressEvent.Failed(
                    null,
                    RemoteError(
                        type = e::class.java.name,
                        message = e.message,
                        stackTrace = e.originalStackTrace
                    )
                )
            )
            Result.failure(
                workDataOf(PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(e.originalStackTrace))
            )
        } catch (e: UserInteractionException) {
            Log.i(tag, "User cancelled downloader interaction".logFmt(), e)
            eventDispatcher(ProgressEvent.Failed(null, e.toRemoteError()))
            Result.failure(
                workDataOf(
                    PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(
                        e.message ?: "Downloader interaction cancelled by user"
                    )
                )
            )
        } catch (e: OutOfMemoryError) {
            Log.e(tag, "Patching ran out of memory".logFmt(), e)
            val safeError = e.toSafeRemoteError()
            eventDispatcher(ProgressEvent.Failed(null, safeError))
            Result.failure(
                workDataOf(
                    PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(safeError.stackTrace)
                )
            )
        } catch (e: Exception) {
            Log.e(tag, "An exception occurred while patching".logFmt(), e)
            eventDispatcher(ProgressEvent.Failed(null, e.toRemoteError()))
            Result.failure(
                workDataOf(PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(e.stackTraceToString()))
            )
        } finally {
            activeRuntime = null
            activeMorpheRuntime = null
            activeAmpleRuntime = null
            patchNotificationSteps = emptyList()
            foregroundStarted = false
            patchedApk.delete()
            downloadCleanup?.invoke()
            cleanupTemporarySplitArtifacts()
        }
    }

    companion object {
        private const val LOG_PREFIX = "[Worker]"
        private fun String.logFmt() = "$LOG_PREFIX $this"
        const val UNIQUE_WORK_NAME = "patching"
        internal const val PATCHING_NOTIFICATION_CHANNEL_ID = "revanced-patcher-patching"
        internal const val NOTIFICATION_ID = 1
        const val PROCESS_EXIT_CODE_KEY = "process_exit_code"
        const val PROCESS_PREVIOUS_LIMIT_KEY = "process_previous_limit"
        const val PROCESS_FAILURE_MESSAGE_KEY = "process_failure_message"
        const val PATCHING_ACTIVE_KEY = "patching_active"
        private const val WORK_DATA_MAX_BYTES = 9000
        private const val DOWNLOAD_PROGRESS_MIN_INTERVAL_MS = 150L
        private const val DOWNLOAD_PROGRESS_MIN_BYTES = 256 * 1024L
        private const val NOTIFICATION_PROGRESS_MAX = 1000
        private const val LOAD_PATCHES_START = 0
        private const val LOAD_PATCHES_END = 220
        private const val PREPARE_SPLIT_START = LOAD_PATCHES_START
        private const val PREPARE_SPLIT_END = LOAD_PATCHES_END
        private const val PREPARE_SPLIT_WITH_SPLIT_START = 0
        private const val PREPARE_SPLIT_WITH_SPLIT_END = 120
        private const val LOAD_PATCHES_WITH_SPLIT_START = PREPARE_SPLIT_WITH_SPLIT_END
        private const val LOAD_PATCHES_WITH_SPLIT_END = 220
        private const val READ_APK_START = PREPARE_SPLIT_END
        private const val READ_APK_END = 320
        private const val EXECUTE_PATCHES_START = READ_APK_END
        private const val EXECUTE_PATCHES_END = 820
        private const val WRITE_APK_START = EXECUTE_PATCHES_END
        private const val WRITE_APK_END = 970
        private const val SIGN_APK_START = WRITE_APK_END
        private const val SIGN_APK_END = NOTIFICATION_PROGRESS_MAX
    }

    private fun trimForWorkData(message: String?): String? {
        if (message.isNullOrEmpty()) return message
        val utf8 = Charsets.UTF_8
        if (message.toByteArray(utf8).size <= WORK_DATA_MAX_BYTES) return message
        var end = message.length
        while (end > 0) {
            val candidate = message.substring(0, end)
            if (candidate.toByteArray(utf8).size <= WORK_DATA_MAX_BYTES) {
                return candidate + "\n[truncated]"
            }
            end -= 1
        }
        return message.take(512) + "\n[truncated]"
    }

    private suspend fun <T> runInterruptiblePluginGet(
        interactionFailure: AtomicReference<UserInteractionException?>,
        block: suspend () -> T
    ): T = runCancellableBlockingIo(
        checkCancelled = {
            interactionFailure.get()?.let { error -> throw error }
        }
    ) {
        runBlocking { block() }
    }.also {
        interactionFailure.get()?.let { error -> throw error }
    }

    private fun cleanupTemporarySplitArtifacts() {
        fs.tempDir.listFiles()?.forEach { child ->
            val shouldDelete = child.name == "patched.apk" ||
                child.name.startsWith("split-") ||
                child.name.startsWith("installed-splits-")
            if (!shouldDelete) return@forEach
            runCatching {
                if (child.isDirectory) {
                    child.deleteRecursively()
                } else {
                    child.delete()
                }
            }
        }
    }

    private fun hasProblematicEmbeddedApkPayloads(file: File): Boolean {
        if (!file.exists() || !file.extension.equals("apk", ignoreCase = true)) return false
        return runCatching {
            ZipFile(file).use { zip ->
                zip.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .any { entry ->
                        val name = entry.name
                        name.endsWith(".apk", ignoreCase = true) &&
                            !SplitApkPreparer.isLikelySplitApkEntryName(name) &&
                            isProblematicEmbeddedApkEntryName(name)
                    }
            }
        }.getOrDefault(false)
    }

    private fun isProblematicEmbeddedApkEntryName(entryName: String): Boolean {
        val normalized = entryName.replace('\\', '/')
        val fileName = normalized.substringAfterLast('/')
        if (!fileName.endsWith(".apk", ignoreCase = true)) return false

        val stem = fileName.removeSuffix(".apk").lowercase(Locale.ROOT)
        val parts = stem.split('.')
        if (parts.size < 3) return false
        return parts.all { part ->
            part.isNotBlank() && part.all { ch -> ch.isLowerCase() || ch.isDigit() || ch == '_' }
        }
    }

    private suspend fun prepareInstalledInput(packageName: String): DownloadResult = withContext(Dispatchers.IO) {
        val packageInfo = pm.getPackageInfo(packageName)
            ?: throw IllegalStateException("Installed package not found: $packageName")
        val appInfo = packageInfo.applicationInfo
            ?: throw IllegalStateException("ApplicationInfo missing for package: $packageName")
        val basePath = appInfo.sourceDir
            ?: throw IllegalStateException("sourceDir missing for package: $packageName")

        val baseApk = File(basePath)
        if (!baseApk.exists()) {
            throw IllegalStateException("Base APK not found for package: $packageName")
        }
        val splitApks = appInfo.splitSourceDirs
            ?.map(::File)
            ?.filter(File::exists)
            ?.sortedBy { it.name }
            .orEmpty()

        if (splitApks.isEmpty()) {
            return@withContext DownloadResult(baseApk, needsSplit = false)
        }

        val archiveDir = fs.tempDir.resolve("installed-splits-${System.currentTimeMillis()}").apply { mkdirs() }
        val archiveFile = archiveDir.resolve("${packageName.replace('.', '_')}.apks")

        buildInstalledSplitArchive(
            apkFiles = listOf(baseApk) + splitApks,
            output = archiveFile
        )

        DownloadResult(
            file = archiveFile,
            needsSplit = true,
            cleanup = { archiveDir.deleteRecursively() }
        )
    }

    private fun buildInstalledSplitArchive(apkFiles: List<File>, output: File) {
        output.parentFile?.mkdirs()
        val usedNames = LinkedHashSet<String>()
        var writtenEntries = 0
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            apkFiles.forEachIndexed { index, apk ->
                if (!apk.exists()) return@forEachIndexed
                val entryName = uniqueSplitEntryName(apk.name, index, usedNames)
                zip.putNextEntry(ZipEntry(entryName).apply { time = apk.lastModified() })
                apk.inputStream().buffered().use { input -> input.copyTo(zip) }
                zip.closeEntry()
                writtenEntries++
            }
        }
        if (writtenEntries == 0) {
            throw IllegalStateException("Failed to build installed split archive: no APK entries written.")
        }
    }

    private fun uniqueSplitEntryName(originalName: String, index: Int, usedNames: MutableSet<String>): String {
        val normalized = if (originalName.endsWith(".apk", ignoreCase = true)) originalName else "$originalName.apk"
        if (usedNames.add(normalized)) return normalized

        val dot = normalized.lastIndexOf('.')
        val base = if (dot >= 0) normalized.substring(0, dot) else normalized
        val ext = if (dot >= 0) normalized.substring(dot) else ".apk"
        var counter = 1
        while (true) {
            val candidate = "${base}_${index}_$counter$ext"
            if (usedNames.add(candidate)) return candidate
            counter++
        }
    }
}
