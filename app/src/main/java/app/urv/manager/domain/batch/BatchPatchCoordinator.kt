/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.urv.manager.domain.batch

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.work.WorkInfo
import app.universal.revanced.manager.R
import app.urv.manager.data.platform.Filesystem
import app.urv.manager.data.room.apps.installed.InstallType
import app.urv.manager.data.room.apps.installed.InstalledApp
import app.urv.manager.domain.installer.InstallResult
import app.urv.manager.domain.installer.InstallerManager
import app.urv.manager.domain.installer.installerTokenMatchesPatchMode
import app.urv.manager.domain.installer.RootInstaller
import app.urv.manager.domain.installer.SessionInstaller
import app.urv.manager.domain.installer.ShizukuInstaller
import app.urv.manager.domain.installer.root.RootMountOperation
import app.urv.manager.domain.installer.root.RootMountRequest
import app.urv.manager.domain.installer.root.RootMountResult
import app.urv.manager.domain.installer.root.RootMountTransactionCoordinator
import app.urv.manager.domain.installer.root.RootRecoveryState
import app.urv.manager.domain.installer.root.describeRecovery
import app.urv.manager.domain.manager.PreferencesManager
import app.urv.manager.domain.repository.InstalledAppRepository
import app.urv.manager.domain.repository.PendingHistoricalSavedEntry
import app.urv.manager.domain.repository.PatchBundleRepository
import app.urv.manager.domain.worker.UniqueWorkAlreadyRunningException
import app.urv.manager.domain.worker.WorkerRepository
import app.urv.manager.patcher.updatedFromLog
import app.urv.manager.patcher.logger.LogLevel
import app.urv.manager.patcher.logger.Logger
import app.urv.manager.patcher.logger.isVerbosePatcherExportLog
import app.urv.manager.patcher.split.SplitApkPreparer
import app.urv.manager.patcher.worker.PatcherWorker
import app.urv.manager.ui.model.SelectedApp
import app.urv.manager.util.PM
import app.urv.manager.util.buildSavedAppEntryKey
import app.urv.manager.util.buildSavedAppVariantIdentity
import app.urv.manager.util.isSavedAppEntryForPackage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class BatchActivityRequest(
    val requestId: String,
    val intent: Intent,
    val completion: CompletableDeferred<ActivityResult>
)

internal data class BatchRootDowngradeRequest(
    val appName: String,
    val reason: String,
    val completion: CompletableDeferred<Boolean>
)

internal data class BatchFallbackInstallRequest(
    val failureMessage: String,
    val fallbackLabel: String,
    val completion: CompletableDeferred<Boolean>
)

private data class BatchInstallAttempt(
    val failure: String? = null,
    val allowFallback: Boolean = true
) {
    val succeeded: Boolean get() = failure == null
}

private class PendingExternalInstallerLease(
    val requestId: String,
    val completion: CompletableDeferred<ActivityResult>,
    val plan: InstallerManager.InstallPlan.External
) {
    val timedOut = AtomicBoolean(false)
    val released = CompletableDeferred<Unit>()
}

internal fun canLaunchExternalBatchInstaller(pending: Boolean): Boolean = !pending

internal fun shouldWaitForExternalBatchInstaller(
    pending: Boolean,
    timedOut: Boolean
): Boolean = pending && timedOut

internal fun canDeleteReplacedSavedEntry(targetMigrationSucceeded: Boolean): Boolean =
    targetMigrationSucceeded

internal fun batchForcedUseMount(
    scheduled: Boolean,
    autoInstallWithShizuku: Boolean
): Boolean? = false.takeIf { scheduled && autoInstallWithShizuku }

internal fun batchInstallPlanToken(
    plan: InstallerManager.InstallPlan
): InstallerManager.Token = when (plan) {
    is InstallerManager.InstallPlan.Internal -> InstallerManager.Token.Internal
    is InstallerManager.InstallPlan.Mount -> InstallerManager.Token.AutoSaved
    is InstallerManager.InstallPlan.Shizuku -> plan.token
    is InstallerManager.InstallPlan.External -> plan.token
}

internal fun batchInstallerTokensEqual(
    first: InstallerManager.Token,
    second: InstallerManager.Token
): Boolean = when {
    first === second -> true
    first is InstallerManager.Token.Component && second is InstallerManager.Token.Component ->
        first.componentName == second.componentName
    else -> false
}

internal fun shouldOfferBatchFallback(
    scheduled: Boolean,
    chooseInstallerPerInstall: Boolean,
    explicitInstaller: Boolean,
    attemptedToken: InstallerManager.Token,
    primaryToken: InstallerManager.Token,
    fallbackToken: InstallerManager.Token
): Boolean = !scheduled &&
    !chooseInstallerPerInstall &&
    !explicitInstaller &&
    batchInstallerTokensEqual(attemptedToken, primaryToken) &&
    fallbackToken != InstallerManager.Token.None &&
    !batchInstallerTokensEqual(primaryToken, fallbackToken)

internal fun rootMountAllowsBatchFallback(result: RootMountResult): Boolean = when (result) {
    is RootMountResult.Success -> false
    is RootMountResult.RequiresDowngradeConfirmation -> false
    is RootMountResult.RequiresRepatch -> true
    is RootMountResult.Busy -> false
    is RootMountResult.RecoveredToPreviousMount -> false
    is RootMountResult.RecoveredToStock -> true
    is RootMountResult.Failure -> result.recoveryState == RootRecoveryState.STOCK
}

// Code adapted from Morphe, see third-party/NOTICE for more information
// https://github.com/MorpheApp/morphe-manager/pull/795
class BatchPatchCoordinator(
    private val app: Application,
    private val resolver: BatchPlanResolver,
    private val workerRepository: WorkerRepository,
    private val fs: Filesystem,
    private val pm: PM,
    private val prefs: PreferencesManager,
    private val installerManager: InstallerManager,
    private val rootInstaller: RootInstaller,
    private val rootMountCoordinator: RootMountTransactionCoordinator,
    private val sessionInstaller: SessionInstaller,
    private val installedAppRepository: InstalledAppRepository,
    private val patchBundleRepository: PatchBundleRepository,
    private val executionGate: BatchExecutionGate,
    private val json: Json
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val executionOwner = Any()
    private val closed = AtomicBoolean(false)
    private val mutableState = MutableStateFlow<BatchRunState?>(null)
    val state = mutableState.asStateFlow()
    private val activityRequestChannel = Channel<BatchActivityRequest>()
    internal val activityRequests = activityRequestChannel.receiveAsFlow()
    private val rootDowngradeRequestChannel = Channel<BatchRootDowngradeRequest>()
    internal val rootDowngradeRequests = rootDowngradeRequestChannel.receiveAsFlow()
    private val fallbackInstallRequestChannel = Channel<BatchFallbackInstallRequest>()
    internal val fallbackInstallRequests = fallbackInstallRequestChannel.receiveAsFlow()
    private var job: Job? = null
    private var installJob: Job? = null
    @Volatile
    private var activeWorkerId: UUID? = null
    private val pendingExternalInstaller =
        AtomicReference<PendingExternalInstallerLease?>(null)
    @Volatile
    private var liveScheduledExecution = false

    private fun tryAcquireExecution(): Boolean =
        !closed.get() && executionGate.tryAcquire(executionOwner)

    private fun releaseExecution() {
        executionGate.release(executionOwner)
    }

    @Synchronized
    fun plan(
        packageNames: List<String>,
        policy: BatchInstallPolicy = BatchInstallPolicy.SAVE_ONLY,
        scheduled: Boolean = false,
        requestId: String? = null
    ): Boolean {
        if (
            job?.isActive == true ||
            installJob?.isActive == true ||
            !tryAcquireExecution()
        ) return false
        liveScheduledExecution = scheduled
        job = scope.launch {
            mutableState.value = BatchRunState(
                items = emptyList(),
                phase = BatchPhase.PLANNING,
                policy = policy,
                scheduled = scheduled,
                requestId = requestId
            )
            var nextPhase = BatchPhase.PREFLIGHT
            val items = try {
                resolver.resolve(
                    packageNames,
                    forcedUseMount = batchForcedUseMount(
                        scheduled,
                        prefs.autoPatchInstallWithShizuku.get()
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                nextPhase = BatchPhase.FINISHED
                packageNames.distinct().map { packageName ->
                    BatchPatchItem(
                        packageName = packageName,
                        appName = packageName,
                        version = null,
                        versionCode = null,
                        input = null,
                        selection = emptyMap(),
                        options = emptyMap(),
                        bundles = emptyList(),
                        state = BatchItemState.FAILED,
                        message = error.message ?: "Unable to prepare batch patch"
                    )
                }
            }
            val plannedState = BatchRunState(
                items = items,
                phase = nextPhase,
                policy = policy,
                scheduled = scheduled,
                requestId = requestId
            )
            if (nextPhase == BatchPhase.FINISHED) {
                finish(plannedState)
            } else {
                mutableState.value = plannedState
            }
        }
        return true
    }

    @Synchronized
    fun planManual(
        entries: List<ManualBatchPatchEntry>,
        policy: BatchInstallPolicy = BatchInstallPolicy.SAVE_ONLY,
        requestId: String? = null
    ): Boolean {
        if (
            job?.isActive == true ||
            installJob?.isActive == true ||
            !tryAcquireExecution()
        ) return false
        liveScheduledExecution = false
        job = scope.launch {
            mutableState.value = BatchRunState(
                items = emptyList(),
                phase = BatchPhase.PLANNING,
                policy = policy,
                requestId = requestId
            )
            var nextPhase = BatchPhase.PREFLIGHT
            val items = try {
                resolver.resolveManual(entries)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                nextPhase = BatchPhase.FINISHED
                entries.distinctBy { it.input.packageName }.map { entry ->
                    BatchPatchItem(
                        packageName = entry.input.packageName,
                        appName = entry.input.packageName,
                        version = entry.input.version,
                        versionCode = entry.input.versionCode,
                        input = entry.input,
                        selection = entry.selection,
                        options = entry.options,
                        bundles = emptyList(),
                        state = BatchItemState.FAILED,
                        message = error.message ?: "Unable to prepare batch patch"
                    )
                }
            }
            val plannedState = BatchRunState(
                items = items,
                phase = nextPhase,
                policy = policy,
                requestId = requestId
            )
            if (nextPhase == BatchPhase.FINISHED) {
                finish(plannedState)
            } else {
                mutableState.value = plannedState
            }
        }
        return true
    }

    @Synchronized
    fun retryFailed(
        manualEntries: List<ManualBatchPatchEntry>? = null
    ): Boolean {
        val snapshot = mutableState.value ?: return false
        if (
            snapshot.phase != BatchPhase.FINISHED ||
            job?.isActive == true ||
            installJob?.isActive == true
        ) return false

        val retryPackages = snapshot.items.filter {
            it.state == BatchItemState.FAILED || it.state == BatchItemState.CANCELLED
        }.map { it.packageName }.distinct()
        if (retryPackages.isEmpty() || !tryAcquireExecution()) return false

        job = scope.launch {
            mutableState.value = snapshot.copy(
                phase = BatchPhase.PLANNING,
                activeIndex = null,
                progress = 0f,
                detail = null,
                restored = false
            )
            val replacements = try {
                if (manualEntries == null) {
                    resolver.resolve(
                        retryPackages,
                        forcedUseMount = batchForcedUseMount(
                            snapshot.scheduled,
                            prefs.autoPatchInstallWithShizuku.get()
                        )
                    )
                } else {
                    val retrySet = retryPackages.toSet()
                    resolver.resolveManual(
                        manualEntries.filter { it.input.packageName in retrySet }
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                retryPackages.map { packageName ->
                    snapshot.items.first { it.packageName == packageName }.copy(
                        state = BatchItemState.FAILED,
                        message = error.message ?: "Unable to prepare retry",
                        progressEvents = emptyList(),
                        memoryUsageSamples = emptyList()
                    )
                }
            }.associateBy { it.packageName }

            val items = snapshot.items.map { item ->
                if (item.packageName !in retryPackages) {
                    item
                } else {
                    replacements[item.packageName] ?: item.copy(
                        state = BatchItemState.FAILED,
                        message = "The retry source is no longer available",
                        progressEvents = emptyList(),
                        memoryUsageSamples = emptyList()
                    )
                }
            }
            mutableState.value = snapshot.copy(
                items = items,
                phase = BatchPhase.PREFLIGHT,
                activeIndex = null,
                progress = 0f,
                detail = null,
                restored = false
            )
        }
        return true
    }

    fun start() {
        val snapshot = mutableState.value ?: return
        if (!snapshot.canStartBatchPatch() || !tryAcquireExecution()) return
        if (job?.isActive == true) job?.cancel()
        job = scope.launch { runQueue() }
    }

    fun reorder(packageNames: List<String>) {
        mutableState.update { current ->
            current?.takeIf { it.phase == BatchPhase.PREFLIGHT }?.let { snapshot ->
                snapshot.copy(items = reorderBatchItems(snapshot.items, packageNames))
            } ?: current
        }
    }

    private suspend fun runQueue() {
        val initial = mutableState.value ?: return
        mutableState.value = initial.copy(phase = BatchPhase.RUNNING, progress = 0f)
        val indexes = initial.items.indices.filter { initial.items[it].state == BatchItemState.READY }

        try {
            indexes.forEachIndexed { queueIndex, itemIndex ->
                val item = mutableState.value?.items?.getOrNull(itemIndex) ?: return@forEachIndexed
                updateItem(itemIndex) { it.copy(state = BatchItemState.RUNNING, message = null) }
                mutableState.update {
                    it?.copy(
                        activeIndex = itemIndex,
                        progress = queueIndex.toFloat() / indexes.size.coerceAtLeast(1),
                        detail = item.appName
                    )
                }

                try {
                    val output = fs.createBatchPatchOutputFile(item.packageName)
                    var keepOutput = false
                    try {
                        val succeeded = runPatcher(
                            item,
                            output,
                            itemIndex,
                            queueIndex,
                            indexes.size
                        )
                        if (succeeded) {
                            val finalOutput = if (
                                shouldPersistBatchOutputImmediately(initial.scheduled)
                            ) {
                                persistPatchedItem(item, output)
                            } else {
                                keepOutput = true
                                output
                            }
                            updateItem(itemIndex) {
                                it.copy(
                                    state = BatchItemState.SUCCEEDED,
                                    patchedFile = finalOutput,
                                    savedForLater = initial.scheduled,
                                    message = null
                                )
                            }
                        } else {
                            updateItem(itemIndex) {
                                it.copy(
                                    state = BatchItemState.FAILED,
                                    message = it.message ?: "Patching failed"
                                )
                            }
                        }
                    } finally {
                        if (!keepOutput) output.delete()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    updateItem(itemIndex) {
                        it.copy(
                            state = BatchItemState.FAILED,
                            message = error.message ?: "Patching failed"
                        )
                    }
                }
            }

            val afterPatch = mutableState.value ?: return
            if (afterPatch.policy == BatchInstallPolicy.INSTALL_AFTER) {
                installAllItems(afterPatch)
            } else {
                finish(
                    afterPatch.copy(
                        phase = BatchPhase.FINISHED,
                        activeIndex = null,
                        progress = 1f,
                        detail = null
                    )
                )
            }
        } catch (cancelled: CancellationException) {
            mutableState.update { current ->
                current?.copy(
                    items = current.items.map {
                        if (it.state == BatchItemState.RUNNING || it.state == BatchItemState.READY) {
                            it.copy(state = BatchItemState.CANCELLED)
                        } else {
                            it.copy(installing = false)
                        }
                    },
                    phase = BatchPhase.FINISHED,
                    activeIndex = null,
                    detail = null
                )
            }
            throw cancelled
        }
    }

    private suspend fun runPatcher(
        item: BatchPatchItem,
        output: File,
        itemIndex: Int,
        queueIndex: Int,
        queueSize: Int
    ): Boolean {
        val input = item.input ?: return false
        val backgroundExecution = mutableState.value?.scheduled == true
        output.parentFile?.mkdirs()
        val logger = object : Logger() {
            override fun log(level: LogLevel, message: String) {
                if (level.ordinal < LogLevel.INFO.ordinal) return
                val line = "[${level.name}]: $message"
                val retainInLog = !isVerbosePatcherExportLog(level, message)
                mutableState.update { state ->
                    state?.copy(
                        detail = message.takeLast(180),
                        items = state.items.mapIndexed { index, currentItem ->
                            if (index != itemIndex) {
                                currentItem
                            } else {
                                currentItem.copy(
                                    patcherSessionInfo =
                                        currentItem.patcherSessionInfo.updatedFromLog(message),
                                    logLines = if (retainInLog) {
                                        (currentItem.logLines + line).takeLast(MAX_LOG_LINES)
                                    } else {
                                        currentItem.logLines
                                    }
                                )
                            }
                        }
                    )
                }
            }
        }
        val args = PatcherWorker.Args(
            input = input,
            output = output.path,
            selectedPatches = item.selection,
            options = item.options,
            skipApkSigning = prefs.skipApkSigning.get(),
            logger = logger,
            preparedInput = null,
            splitSelection = null,
            handleStartActivityRequest = { _, intent ->
                if (allowsInteractiveBatchActivity(mutableState.value?.scheduled == true)) {
                    requestActivityResult(intent)
                } else {
                    ActivityResult(Activity.RESULT_CANCELED, null)
                }
            },
            setInputFile = { _, _, _ -> },
            queuePosition = queueIndex + 1,
            queueSize = queueSize,
            appName = item.appName,
            allowBackgroundExecution = backgroundExecution,
            onEvent = { update ->
                val current = update.notificationProgressCurrent
                val max = update.notificationProgressMax
                mutableState.update { state ->
                    state?.copy(
                        items = state.items.mapIndexed { index, currentItem ->
                            if (index != itemIndex) {
                                currentItem
                            } else {
                                currentItem.copy(
                                    progressEvents = update.event?.let { event ->
                                        (currentItem.progressEvents + event)
                                            .takeLast(MAX_PROGRESS_EVENTS)
                                    } ?: currentItem.progressEvents,
                                    memoryUsageSamples = update.memoryUsage
                                        ?.takeIf { update.isMemorySample }
                                        ?.let { sample ->
                                            val normalized = sample.copy(
                                                usedMb = sample.usedMb.coerceAtLeast(0L),
                                                maxMb = sample.maxMb.coerceAtLeast(1L),
                                                requestedMaxMb = sample.requestedMaxMb.coerceAtLeast(1L)
                                            )
                                            if (
                                                currentItem.memoryUsageSamples.lastOrNull()
                                                    ?.sampledAtElapsedRealtimeMs
                                                    ?.let {
                                                        it >= normalized.sampledAtElapsedRealtimeMs
                                                    } == true
                                            ) {
                                                currentItem.memoryUsageSamples
                                            } else {
                                                (currentItem.memoryUsageSamples + normalized)
                                                    .takeLast(MAX_MEMORY_SAMPLES)
                                            }
                                        } ?: currentItem.memoryUsageSamples
                                )
                            }
                        },
                        progress = if (current != null && max != null && max > 0) {
                            val itemProgress = current.toFloat() / max
                            (queueIndex + itemProgress) / queueSize.coerceAtLeast(1)
                        } else {
                            state.progress
                        }
                    )
                }
            }
        )

        if (backgroundExecution) {
            PatcherWorker.markBackgroundExecutionRequested()
        }
        val id = try {
            workerRepository.launchExpedited<PatcherWorker, PatcherWorker.Args>(
                PatcherWorker.UNIQUE_WORK_NAME,
                args
            )
        } catch (error: UniqueWorkAlreadyRunningException) {
            if (backgroundExecution) {
                PatcherWorker.clearBackgroundExecutionRequested()
            }
            throw IllegalStateException(
                app.getString(R.string.patcher_already_running),
                error
            )
        } catch (error: Throwable) {
            if (backgroundExecution) {
                PatcherWorker.clearBackgroundExecutionRequested()
            }
            throw error
        }
        activeWorkerId = id
        PatcherWorker.showInitialNotification(
            context = app,
            appName = item.appName,
            queuePosition = queueIndex + 1,
            queueSize = queueSize
        )
        return try {
            var info: WorkInfo? = null
            while (info?.state?.isFinished != true) {
                info = withContext(Dispatchers.IO) {
                    workerRepository.workManager.getWorkInfoById(id).get()
                }
                if (info?.state?.isFinished != true) delay(250)
            }
            info.state == WorkInfo.State.SUCCEEDED && output.isFile && output.length() > 0L
        } finally {
            if (activeWorkerId == id) activeWorkerId = null
            if (backgroundExecution) {
                PatcherWorker.clearBackgroundExecutionRequested()
            }
        }
    }

    private suspend fun requestActivityResult(intent: Intent): ActivityResult {
        val completion = CompletableDeferred<ActivityResult>()
        val cancelledResult = ActivityResult(Activity.RESULT_CANCELED, null)
        return awaitBatchRequest(
            timeoutMs = INTERACTIVE_ACTIVITY_TIMEOUT_MS,
            completion = completion,
            timeoutResult = cancelledResult
        ) {
            activityRequestChannel.send(
                BatchActivityRequest(
                    requestId = UUID.randomUUID().toString(),
                    intent = intent,
                    completion = completion
                )
            )
        }
    }

    private suspend fun requestFallbackInstall(
        failureMessage: String,
        fallbackLabel: String
    ): Boolean {
        val completion = CompletableDeferred<Boolean>()
        return awaitBatchRequest(
            timeoutMs = INTERACTIVE_ACTIVITY_TIMEOUT_MS,
            completion = completion,
            timeoutResult = false
        ) {
            fallbackInstallRequestChannel.send(
                BatchFallbackInstallRequest(
                    failureMessage = failureMessage,
                    fallbackLabel = fallbackLabel,
                    completion = completion
                )
            )
        }
    }

    private suspend fun persistPatchedItem(item: BatchPatchItem, output: File): File {
        val info = pm.getPackageInfo(output)
        val version = info?.versionName?.takeIf(String::isNotBlank)
            ?: item.version
            ?: "unknown"
        val finalPackageName = info?.packageName ?: item.packageName
        val selectionPayload = item.selectionPayload
            ?: patchBundleRepository.snapshotSelection(
                item.selection,
                item.options
            )
        val identity = buildSavedAppVariantIdentity(version, selectionPayload, item.selection)
        val overwriteDisabled = prefs.disableSavedAppOverwrite.get()
        val sourceSavedEntry = item.sourceEntryKey
            ?.let { installedAppRepository.get(it) }
            ?.takeIf { sourceEntry ->
                sourceEntry.installType == InstallType.SAVED &&
                    (
                        isSavedAppEntryForPackage(
                            sourceEntry.currentPackageName,
                            finalPackageName
                        ) ||
                            sourceEntry.originalPackageName == item.packageName ||
                            sourceEntry.originalPackageName == finalPackageName
                    )
            }
        val replacementSourceSavedEntry = sourceSavedEntry
            ?.takeUnless { overwriteDisabled }
        val matchingEntryKey = if (overwriteDisabled) {
            null
        } else {
            replacementSourceSavedEntry?.currentPackageName
                ?: installedAppRepository.getByInstallType(InstallType.SAVED)
                    .firstOrNull { savedEntry ->
                        isSavedAppEntryForPackage(
                            savedEntry.currentPackageName,
                            finalPackageName
                        ) && savedVariantIdentity(savedEntry) == identity
                    }
                    ?.currentPackageName
        }
        val savedEntryKey = selectBatchSavedEntryKey(
            packageName = finalPackageName,
            variantIdentity = identity,
            overwriteDisabled = overwriteDisabled,
            matchingEntryKey = matchingEntryKey,
            uniqueSuffix = UUID.randomUUID().toString().replace("-", "").take(8)
        )
        val savedOutput = fs.getPatchedAppFile(savedEntryKey, version)
        val savedOutputDirectory = requireNotNull(savedOutput.parentFile)
        val copiedOutput = output.absolutePath != savedOutput.absolutePath
        val stagingOutput = savedOutputDirectory.resolve(
            ".${savedOutput.name}.${UUID.randomUUID()}.tmp"
        )
        val backupOutput = savedOutputDirectory.resolve(
            ".${savedOutput.name}.${UUID.randomUUID()}.bak"
        )
        var replacementStarted = false
        var keepBackup = false
        try {
            if (copiedOutput) {
                savedOutputDirectory.mkdirs()
                output.copyTo(stagingOutput, overwrite = true)
                check(stagingOutput.isFile && stagingOutput.length() == output.length()) {
                    "Failed to verify the patched APK staging copy"
                }
                if (savedOutput.isFile) {
                    savedOutput.copyTo(backupOutput, overwrite = true)
                    check(backupOutput.length() == savedOutput.length()) {
                        "Failed to verify the previous saved APK backup"
                    }
                }
                replacementStarted = true
                stagingOutput.copyTo(savedOutput, overwrite = true)
                check(savedOutput.isFile && savedOutput.length() == output.length()) {
                    "Failed to verify the saved patched APK"
                }
            }
            installedAppRepository.addOrUpdate(
                currentPackageName = savedEntryKey,
                originalPackageName = item.packageName,
                version = version,
                installType = InstallType.SAVED,
                patchSelection = item.selection,
                selectionPayload = selectionPayload,
                resetCreatedAt = true
            )
        } catch (error: Throwable) {
            if (copiedOutput && replacementStarted) {
                val restored = runCatching {
                    if (backupOutput.isFile) {
                        backupOutput.copyTo(savedOutput, overwrite = true)
                        check(savedOutput.isFile && savedOutput.length() == backupOutput.length()) {
                            "Failed to verify the restored saved APK"
                        }
                    } else {
                        check(savedOutput.delete() || !savedOutput.exists()) {
                            "Failed to remove the uncommitted saved APK"
                        }
                    }
                }.onFailure { restoreError ->
                    Log.e(TAG, "Failed to restore the previous saved APK", restoreError)
                }.isSuccess
                keepBackup = !restored && backupOutput.isFile
            }
            throw error
        } finally {
            stagingOutput.delete()
            if (!keepBackup) backupOutput.delete()
        }

        if (copiedOutput) {
            runPostCommitStep("Failed to remove the temporary batch patched APK") {
                check(output.delete() || !output.exists()) {
                    "The temporary batch patched APK could not be removed"
                }
            }
        }
        var sourceMigrationSucceeded = true
        sourceSavedEntry?.let { sourceEntry ->
            sourceMigrationSucceeded = runPostCommitStep(
                "Failed to migrate automatic patch target from ${sourceEntry.currentPackageName} to $savedEntryKey"
            ) {
                installedAppRepository.migrateAutoPatchTarget(
                    sourceEntry.currentPackageName,
                    savedEntryKey
                )
            }
        }
        replacementSourceSavedEntry
            ?.takeIf { canDeleteReplacedSavedEntry(sourceMigrationSucceeded) }
            ?.let { sourceEntry ->
                runPostCommitStep(
                    "Failed to clean up replaced saved entry ${sourceEntry.currentPackageName}"
                ) {
                    if (sourceEntry.currentPackageName != savedEntryKey) {
                        installedAppRepository.delete(sourceEntry)
                    }
                    if (
                        sourceEntry.currentPackageName != savedEntryKey ||
                        sourceEntry.version != version
                    ) {
                        fs.getPatchedAppFile(
                            sourceEntry.currentPackageName,
                            sourceEntry.version
                        ).takeIf { oldFile ->
                            oldFile.exists() &&
                                !oldFile.absolutePath.equals(
                                    savedOutput.absolutePath,
                                    ignoreCase = true
                                )
                        }?.let { oldFile ->
                            check(oldFile.delete() || !oldFile.exists()) {
                                "The replaced saved APK could not be removed"
                            }
                        }
                    }
                }
            }
        runPostCommitStep("Failed to prune retained original APKs") {
            installedAppRepository.pruneRetainedOriginals()
        }
        return savedOutput
    }

    suspend fun saveForLater(packageName: String): Boolean =
        saveForLater(setOf(packageName))

    suspend fun saveAllForLater(): Boolean {
        val packageNames = mutableState.value?.unsavedPatchedItems
            .orEmpty()
            .mapTo(mutableSetOf()) { it.packageName }
        return packageNames.isEmpty() || saveForLater(packageNames)
    }

    private suspend fun saveForLater(packageNames: Set<String>): Boolean {
        val snapshot = mutableState.value ?: return false
        if (
            snapshot.phase != BatchPhase.FINISHED ||
            installJob?.isActive == true ||
            snapshot.items.any { it.saving } ||
            packageNames.isEmpty() ||
            !tryAcquireExecution()
        ) return false

        mutableState.update { current ->
            current?.copy(
                items = current.items.map { item ->
                    item.copy(saving = item.packageName in packageNames && item.needsSaveBeforeLeaving)
                }
            )
        }
        var allSaved = true
        try {
            packageNames.forEach { packageName ->
                val currentItem = mutableState.value?.items
                    ?.firstOrNull { it.packageName == packageName }
                    ?: return@forEach
                if (!currentItem.needsSaveBeforeLeaving) return@forEach
                val sourceFile = currentItem.patchedFile?.takeIf(File::isFile)
                if (sourceFile == null) {
                    allSaved = false
                    updateItemByPackage(packageName) { it.copy(saving = false) }
                    return@forEach
                }
                try {
                    val savedFile = persistPatchedItem(currentItem, sourceFile)
                    updateItemByPackage(packageName) {
                        it.copy(
                            patchedFile = savedFile,
                            savedForLater = true,
                            saving = false
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    allSaved = false
                    Log.e(TAG, "Failed to save batch patched APK for $packageName", error)
                    updateItemByPackage(packageName) { it.copy(saving = false) }
                }
            }
            mutableState.value?.let { persistResult(it) }
            return allSaved
        } finally {
            mutableState.update { current ->
                current?.copy(items = current.items.map { it.copy(saving = false) })
            }
            releaseExecution()
        }
    }

    fun discardUnsavedPatchedFiles() {
        val snapshot = mutableState.value ?: return
        snapshot.unsavedPatchedItems.forEach { item ->
            item.patchedFile?.takeIf(File::isFile)?.delete()
        }
        mutableState.update { current ->
            current?.copy(
                items = current.items.map { item ->
                    if (item.needsSaveBeforeLeaving) {
                        item.copy(patchedFile = null, saving = false)
                    } else {
                        item.copy(saving = false)
                    }
                }
            )
        }
    }

    fun setPolicy(policy: BatchInstallPolicy) {
        mutableState.update { it?.copy(policy = policy) }
    }

    fun toggleExcluded(packageName: String) {
        mutableState.update { state ->
            state?.copy(items = state.items.map { item ->
                if (item.packageName != packageName) item
                else if (item.state == BatchItemState.EXCLUDED) {
                    item.copy(
                        state = item.restoreState ?: BatchItemState.READY,
                        restoreState = null
                    )
                } else if (item.state == BatchItemState.READY || item.state.needsAttention) {
                    item.copy(state = BatchItemState.EXCLUDED, restoreState = item.state)
                } else item
            })
        }
    }

    fun forceVersion(packageName: String) {
        mutableState.update { state ->
            state?.copy(items = state.items.map { item ->
                if (item.packageName == packageName &&
                    item.state == BatchItemState.VERSION_MISMATCH
                ) {
                    item.copy(
                        state = if (item.selection.values.any { it.isNotEmpty() }) {
                            BatchItemState.READY
                        } else {
                            BatchItemState.NO_PATCHES
                        },
                        forceVersionMismatch = true
                    )
                } else item
            })
        }
    }

    fun updateConfiguration(
        packageName: String,
        selection: app.urv.manager.util.PatchSelection,
        options: app.urv.manager.util.Options
    ) {
        val normalizedSelection = selection.filterValues { it.isNotEmpty() }
        mutableState.update { state ->
            state?.copy(items = state.items.map { item ->
                if (item.packageName != packageName) item
                else item.copy(
                    selection = normalizedSelection,
                    options = options,
                    patcherEngine = null,
                    state = if (
                        item.state == BatchItemState.VERSION_MISMATCH &&
                        !item.forceVersionMismatch
                    ) {
                        BatchItemState.VERSION_MISMATCH
                    } else if (normalizedSelection.isEmpty()) {
                        BatchItemState.NO_PATCHES
                    } else {
                        BatchItemState.READY
                    }
                )
            })
        }
        scope.launch {
            val patcherEngine = runCatching {
                resolver.resolvePatcherEngine(normalizedSelection)
            }.getOrNull()
            mutableState.update { state ->
                state?.copy(items = state.items.map { item ->
                    if (
                        item.packageName == packageName &&
                        item.selection == normalizedSelection
                    ) {
                        item.copy(patcherEngine = patcherEngine)
                    } else {
                        item
                    }
                })
            }
        }
    }

    suspend fun attachApk(packageName: String, file: File): BatchPatchItem? {
        val current = mutableState.value ?: return null
        val index = current.items.indexOfFirst { it.packageName == packageName }
        if (index < 0) return null
        val resolved = resolver.reattach(current.items[index], file)
        updateItem(index) { resolved }
        return resolved
    }

    suspend fun attachSource(
        packageName: String,
        input: app.urv.manager.ui.model.SelectedApp
    ): BatchPatchItem? {
        val current = mutableState.value ?: return null
        val index = current.items.indexOfFirst { it.packageName == packageName }
        if (index < 0) return null
        val resolved = resolver.reattach(current.items[index], input)
        updateItem(index) { resolved }
        return resolved
    }

    @Synchronized
    fun installAll(forceShizuku: Boolean = false) {
        val snapshot = mutableState.value ?: return
        if (snapshot.phase != BatchPhase.FINISHED || installJob?.isActive == true) return
        val packages = snapshot.patchedItems
            .filter { it.installOutcome != BatchInstallOutcome.INSTALLED }
            .map { it.packageName }
        if (packages.isEmpty() || !tryAcquireExecution()) return
        mutableState.value = snapshot.copy(
            phase = BatchPhase.INSTALLING,
            items = snapshot.items.map { item ->
                item.copy(installing = item.packageName in packages)
            }
        )
        installJob = launchInstall {
            installAllItems(forceShizuku = forceShizuku)
        }
    }

    private suspend fun installAllItems(
        initial: BatchRunState? = null,
        forceShizuku: Boolean = false
    ) {
        val snapshot = initial ?: mutableState.value ?: return
        val packages = snapshot.patchedItems
            .filter { it.installOutcome != BatchInstallOutcome.INSTALLED }
            .map { it.packageName }
        mutableState.value = snapshot.copy(
            phase = BatchPhase.INSTALLING,
            activeIndex = null,
            detail = null,
            items = snapshot.items.map { item ->
                item.copy(installing = item.packageName in packages)
            }
        )
        for (packageName in packages) {
            installOne(packageName, forceShizuku = forceShizuku)
        }
        val finished = mutableState.value?.copy(
            phase = BatchPhase.FINISHED,
            activeIndex = null,
            detail = null,
            items = mutableState.value?.items.orEmpty().map { it.copy(installing = false) }
        ) ?: return
        finish(finished)
    }

    @Synchronized
    fun install(
        packageName: String,
        installerToken: InstallerManager.Token? = null
    ) {
        val snapshot = mutableState.value ?: return
        val item = snapshot.items.firstOrNull { it.packageName == packageName } ?: return
        if (
            snapshot.phase != BatchPhase.FINISHED ||
            item.installing ||
            item.installOutcome == BatchInstallOutcome.INSTALLED ||
            item.patchedFile?.exists() != true ||
            !tryAcquireExecution()
        ) return
        mutableState.value = snapshot.copy(
            phase = BatchPhase.INSTALLING,
            activeIndex = snapshot.items.indexOf(item),
            items = snapshot.items.map {
                if (it.packageName == packageName) it.copy(installing = true) else it
            }
        )
        installJob = launchInstall {
            installOne(packageName, installerToken = installerToken)
            val finished = mutableState.value?.copy(
                phase = BatchPhase.FINISHED,
                activeIndex = null,
                detail = null,
                items = mutableState.value?.items.orEmpty().map { it.copy(installing = false) }
            ) ?: return@launchInstall
            finish(finished)
        }
    }

    private fun launchInstall(block: suspend () -> Unit): Job = scope.launch {
        try {
            block()
        } catch (cancelled: CancellationException) {
            finishInterruptedInstall(cancelled)
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "Batch installation stopped unexpectedly", error)
            finishInterruptedInstall(error)
        }
    }

    private suspend fun finishInterruptedInstall(error: Throwable) {
        withContext(NonCancellable) {
            val current = mutableState.value ?: return@withContext
            if (
                current.phase == BatchPhase.CANCELLING ||
                current.phase == BatchPhase.FINISHED
            ) return@withContext

            val message = if (error is CancellationException) {
                app.getString(R.string.installer_hint_aborted)
            } else {
                error.message?.takeIf(String::isNotBlank)
                    ?: app.getString(R.string.installer_hint_aborted)
            }
            finish(finishInterruptedInstallState(current, message))
        }
    }

    private suspend fun installOne(
        packageName: String,
        forceShizuku: Boolean = false,
        installerToken: InstallerManager.Token? = null
    ) {
        val state = mutableState.value ?: return
        val allowAutomaticUninstall = state.scheduled &&
            forceShizuku &&
            prefs.autoPatchUninstallOnConflictWithShizuku.get()
        val index = state.items.indexOfFirst { it.packageName == packageName }
        val item = state.items.getOrNull(index) ?: return
        mutableState.update { current ->
            current?.copy(activeIndex = index)
        }
        val file = item.patchedFile?.takeIf { it.exists() }
        if (file == null) {
            updateItem(index) {
                it.copy(
                    installOutcome = BatchInstallOutcome.FAILED,
                    installMessage = "Patched APK is unavailable",
                    installing = false
                )
            }
            return
        }
        val patchedPackageInfo = pm.getPackageInfo(file)
        val targetPackage = patchedPackageInfo?.packageName ?: item.packageName
        var successfulInstallType = InstallType.DEFAULT

        suspend fun attemptShizukuInstall(
            installerPackageNameOverride: String?
        ): ShizukuInstaller.OperationResult =
            try {
                installerManager.installWithShizuku(
                    file,
                    targetPackage,
                    installerPackageNameOverride
                )
            } catch (error: ShizukuInstaller.InstallerOperationException) {
                ShizukuInstaller.OperationResult(
                    error.status,
                    error.message
                )
            }

        val explicitToken = when {
            installerToken != null -> installerToken
            forceShizuku -> installerManager.withPlayStoreSource(
                InstallerManager.Token.Shizuku,
                prefs.shizukuInstallAsPlayStore.get()
            )
            item.profileInstallerToken != null ->
                installerManager.withPlayStoreSource(
                    installerManager.parseToken(item.profileInstallerToken),
                    prefs.shizukuInstallAsPlayStore.get()
                )
            else -> null
        }
        val resolvedToken = explicitToken
            ?: InstallerManager.Token.AutoSaved.takeIf { item.useMount }
        if (item.useMount && targetPackage != item.packageName) {
            updateItem(index) {
                it.copy(
                    installOutcome = BatchInstallOutcome.FAILED,
                    installMessage = app.getString(
                        R.string.root_mount_renamed_package_not_supported
                    ),
                    installedPackageName = targetPackage,
                    installing = false
                )
            }
            return
        }
        if (
            resolvedToken != null &&
            !installerTokenMatchesPatchMode(resolvedToken, item.useMount)
        ) {
            updateItem(index) {
                it.copy(
                    installOutcome = BatchInstallOutcome.FAILED,
                    installMessage = app.getString(R.string.installer_patch_mode_mismatch),
                    installedPackageName = targetPackage,
                    installing = false
                )
            }
            return
        }
        val explicitInstaller = explicitToken != null || item.useMount
        val plan = try {
            if (resolvedToken != null) {
                installerManager.resolvePlanForToken(
                    token = resolvedToken,
                    target = InstallerManager.InstallTarget.PATCHER,
                    sourceFile = file,
                    expectedPackage = targetPackage,
                    sourceLabel = item.appName,
                    allowMount = item.useMount && targetPackage == item.packageName
                )
            } else {
                installerManager.resolvePlan(
                    target = InstallerManager.InstallTarget.PATCHER,
                    sourceFile = file,
                    expectedPackage = targetPackage,
                    sourceLabel = item.appName,
                    allowMount = item.useMount && targetPackage == item.packageName
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            updateItem(index) {
                it.copy(
                    installOutcome = BatchInstallOutcome.FAILED,
                    installMessage = error.message ?: "Unable to prepare installer",
                    installedPackageName = targetPackage,
                    installing = false
                )
            }
            return
        }
        if (plan == null) {
            updateItem(index) {
                it.copy(
                    installOutcome = BatchInstallOutcome.FAILED,
                    installMessage = "The selected installer is unavailable",
                    installedPackageName = targetPackage,
                    installing = false
                )
            }
            return
        }

        var installed = false
        var result: String? = null

        val attempt = try {
            when (plan) {
                    is InstallerManager.InstallPlan.Internal -> {
                        when (val install = sessionInstaller.install(file, targetPackage)) {
                            InstallResult.Success -> {
                                successfulInstallType = InstallType.DEFAULT
                                BatchInstallAttempt()
                            }
                            is InstallResult.Conflict -> BatchInstallAttempt(
                                install.message ?: "Install conflict"
                            )
                            is InstallResult.Failure -> BatchInstallAttempt(
                                install.message ?: "Install failed"
                            )
                        }
                    }

                    is InstallerManager.InstallPlan.Shizuku -> {
                        var operation = attemptShizukuInstall(
                            plan.installerPackageNameOverride
                        )
                        var automaticUninstallBlockedMessage: String? = null
                        if (
                            operation.status != PackageInstaller.STATUS_SUCCESS &&
                            allowAutomaticUninstall &&
                            installerManager.isSignatureMismatch(operation.message)
                        ) {
                            val installedPackageInfo = pm.getPackageInfo(targetPackage)
                            val downgradeWouldOccur = installedPackageInfo != null &&
                                patchedPackageInfo != null &&
                                pm.getVersionCode(patchedPackageInfo) <
                                pm.getVersionCode(installedPackageInfo)
                            if (downgradeWouldOccur) {
                                automaticUninstallBlockedMessage = app.getString(
                                    R.string.auto_patch_shizuku_downgrade_blocked
                                )
                            } else {
                                val uninstall = installerManager.uninstallWithShizuku(
                                    targetPackage
                                )
                                if (uninstall.status == PackageInstaller.STATUS_SUCCESS) {
                                    operation = if (waitUntilPackageRemoved(targetPackage)) {
                                        attemptShizukuInstall(
                                            plan.installerPackageNameOverride
                                        )
                                    } else {
                                        ShizukuInstaller.OperationResult(
                                            PackageInstaller.STATUS_FAILURE_TIMEOUT,
                                            "Timed out waiting for the package to be removed"
                                        )
                                    }
                                }
                            }
                        }
                        if (operation.status == PackageInstaller.STATUS_SUCCESS) {
                            successfulInstallType = InstallType.SHIZUKU
                            BatchInstallAttempt()
                        } else {
                            BatchInstallAttempt(
                                automaticUninstallBlockedMessage
                                    ?: installerManager.formatShizukuFailure(
                                        operation.status,
                                        operation.message
                                    )
                            )
                        }
                    }

                    is InstallerManager.InstallPlan.Mount -> {
                        installWithRootMount(
                            item = item,
                            patchedFile = file,
                            targetPackage = targetPackage
                        ).also { mountAttempt ->
                            if (mountAttempt.succeeded) {
                                successfulInstallType = InstallType.MOUNT
                            }
                        }
                    }

                    is InstallerManager.InstallPlan.External ->
                        installWithExternalInstaller(
                            plan = plan,
                            targetPackage = targetPackage
                        ).also { externalAttempt ->
                            if (externalAttempt.succeeded) {
                                successfulInstallType = InstallType.CUSTOM
                            }
                        }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            BatchInstallAttempt(error.message ?: "Install failed")
        }

        installed = attempt.succeeded
        result = attempt.failure
        if (!installed && attempt.allowFallback) {
            val primaryToken = installerManager.getPrimaryToken()
            val fallbackToken = installerManager.getFallbackToken()
            val canOfferFallback = shouldOfferBatchFallback(
                scheduled = state.scheduled,
                chooseInstallerPerInstall = prefs.chooseInstallerPerInstall.get(),
                explicitInstaller = explicitInstaller,
                attemptedToken = batchInstallPlanToken(plan),
                primaryToken = primaryToken,
                fallbackToken = fallbackToken
            ) && installerTokenMatchesPatchMode(
                fallbackToken,
                item.useMount
            ) && !(
                fallbackToken == InstallerManager.Token.AutoSaved &&
                    targetPackage != item.packageName
                )
            val fallbackEntry = fallbackToken
                .takeIf { canOfferFallback }
                ?.let {
                    installerManager.describeEntry(
                        it,
                        InstallerManager.InstallTarget.PATCHER
                    )
                }
                ?.takeIf { it.availability.available }
            if (
                fallbackEntry != null &&
                requestFallbackInstall(
                    failureMessage = result ?: "Install failed",
                    fallbackLabel = fallbackEntry.label
                )
            ) {
                installOne(
                    packageName = packageName,
                    installerToken = fallbackToken
                )
                return
            }
        }
        if (!installed && result == null) {
            result = if (forceShizuku) "Shizuku is unavailable" else "Install failed"
        }

        if (installed) {
            withContext(NonCancellable) {
                var metadataWarning: String? = null
                val installedSavedFile = try {
                    persistInstalledPatchedItem(
                        item = item,
                        sourceFile = file,
                        targetPackage = targetPackage,
                        installType = successfulInstallType
                    )
                } catch (error: Exception) {
                    Log.e(
                        TAG,
                        "Installed ${item.packageName}, but failed to update its saved app metadata",
                        error
                    )
                    metadataWarning = app.getString(
                        R.string.batch_patch_metadata_save_failed,
                        error.message ?: error.javaClass.simpleName
                    )
                    file
                }
                updateItem(index) {
                    it.copy(
                        installOutcome = BatchInstallOutcome.INSTALLED,
                        installMessage = metadataWarning,
                        installedPackageName = targetPackage,
                        patchedFile = installedSavedFile,
                        installing = false
                    )
                }
            }
        } else {
            updateItem(index) {
                it.copy(
                    installOutcome = BatchInstallOutcome.FAILED,
                    installMessage = result,
                    installedPackageName = targetPackage,
                    patchedFile = file,
                    installing = false
                )
            }
        }
    }

    private suspend fun installWithRootMount(
        item: BatchPatchItem,
        patchedFile: File,
        targetPackage: String
    ): BatchInstallAttempt {
        if (targetPackage != item.packageName) {
            return BatchInstallAttempt(
                app.getString(R.string.root_mount_renamed_package_not_supported)
            )
        }
        if (!withContext(Dispatchers.IO) { rootInstaller.hasRootAccess() }) {
            return BatchInstallAttempt(app.getString(R.string.installer_status_requires_root))
        }

        val patchedInfo = pm.getPackageInfo(patchedFile)
            ?: return BatchInstallAttempt(app.getString(R.string.installer_hint_invalid))
        val versionName = patchedInfo.versionName.orEmpty()
        val versionCode = pm.getVersionCode(patchedInfo)
        val retainedOriginal = fs.findOriginalAppFile(
            packageName = targetPackage,
            version = versionName,
            versionCode = versionCode
        )
        val retainedStock = retainedOriginal
            ?.takeUnless(SplitApkPreparer::isSplitArchive)
        val retainedInfo = retainedStock?.let(pm::getPackageInfo)
        val verifiedRetainedStock = retainedStock?.takeIf {
            retainedInfo?.packageName == targetPackage &&
                retainedInfo.versionName == patchedInfo.versionName &&
                pm.getVersionCode(retainedInfo) == versionCode
        }
        val installedInfo = pm.getPackageInfo(targetPackage)
        val stockNeedsReplacement = installedInfo == null ||
            installedInfo.versionName != patchedInfo.versionName ||
            pm.getVersionCode(installedInfo) != versionCode
        val appMounted = rootInstaller.isAppMounted(targetPackage)
        val stockFile = when {
            stockNeedsReplacement -> verifiedRetainedStock
            appMounted -> null
            else -> installedInfo?.applicationInfo?.sourceDir
                ?.let(::File)
                ?.takeIf(File::isFile)
                ?: verifiedRetainedStock
        }
        if (stockFile == null && (stockNeedsReplacement || !appMounted)) {
            return BatchInstallAttempt(app.getString(R.string.install_app_fail_missing_stock))
        }

        val request = RootMountRequest(
            packageName = targetPackage,
            userId = android.os.Process.myUid() / 100_000,
            operation = if (stockNeedsReplacement) {
                RootMountOperation.REPLACE_STOCK_AND_MOUNT
            } else {
                RootMountOperation.SWITCH_PATCHED_BUILD
            },
            patchedApk = patchedFile,
            stockApks = listOfNotNull(stockFile),
            expectedVersionName = patchedInfo.versionName,
            expectedVersionCode = versionCode,
            label = item.appName
        )

        var mountResult = executeRootMount(request, item)
        val downgradeRequest =
            mountResult as? RootMountResult.RequiresDowngradeConfirmation
        if (downgradeRequest != null) {
            val reason = downgradeRequest.reason
            val completion = CompletableDeferred<Boolean>()
            val confirmed = awaitBatchRequest(
                timeoutMs = INTERACTIVE_ACTIVITY_TIMEOUT_MS,
                completion = completion,
                timeoutResult = false
            ) {
                rootDowngradeRequestChannel.send(
                    BatchRootDowngradeRequest(
                        appName = item.appName,
                        reason = reason,
                        completion = completion
                    )
                )
            }
            if (!confirmed) {
                return BatchInstallAttempt(
                    failure = reason,
                    allowFallback = false
                )
            }
            mountResult = executeRootMount(
                request.copy(downgradeFallbackConfirmed = true),
                item
            )
        }
        return BatchInstallAttempt(
            failure = rootMountFailureMessage(mountResult),
            allowFallback = rootMountAllowsBatchFallback(mountResult)
        )
    }

    private suspend fun executeRootMount(
        request: RootMountRequest,
        item: BatchPatchItem
    ): RootMountResult = rootMountCoordinator.execute(request) { phase ->
        mutableState.update { state ->
            state?.copy(
                detail = "${item.appName}: " +
                    phase.name.lowercase().replace('_', ' ')
            )
        }
    }

    private fun rootMountFailureMessage(result: RootMountResult): String? = when (result) {
        is RootMountResult.Success -> null
        is RootMountResult.RequiresDowngradeConfirmation -> result.reason
        is RootMountResult.RequiresRepatch -> result.reason
        is RootMountResult.Busy -> result.reason
            ?: "Root mount is busy (${result.phase?.name?.lowercase() ?: "preparing"})"
        is RootMountResult.RecoveredToPreviousMount -> buildString {
            append(result.reason.orEmpty())
            if (isNotEmpty()) append(' ')
            append("The previous patched build was restored. Diagnostic ${result.diagnosticId}.")
        }
        is RootMountResult.RecoveredToStock -> buildString {
            append(result.reason.orEmpty())
            if (isNotEmpty()) append(' ')
            append("The stock app was restored. Diagnostic ${result.diagnosticId}.")
        }
        is RootMountResult.Failure ->
            "${result.message} ${result.recoveryState.describeRecovery()} " +
                "Diagnostic ${result.diagnosticId}."
    }

    private suspend fun acquireExternalInstallerLease(
        lease: PendingExternalInstallerLease
    ): Boolean {
        while (true) {
            val existing = pendingExternalInstaller.get()
            if (existing == null) {
                if (pendingExternalInstaller.compareAndSet(null, lease)) return true
                continue
            }
            if (
                !shouldWaitForExternalBatchInstaller(
                    pending = true,
                    timedOut = existing.timedOut.get()
                )
            ) return false

            val released = withTimeoutOrNull(EXTERNAL_INSTALL_PENDING_WAIT_MS) {
                existing.released.await()
                true
            } ?: false
            if (!released) return false
        }
    }

    private suspend fun installWithExternalInstaller(
        plan: InstallerManager.InstallPlan.External,
        targetPackage: String
    ): BatchInstallAttempt {
        val baseline = pm.getPackageInfo(targetPackage)?.let { packageInfo ->
            pm.getVersionCode(packageInfo) to packageInfo.lastUpdateTime
        }
        val requestId = UUID.randomUUID().toString()
        val completion = CompletableDeferred<ActivityResult>()
        val lease = PendingExternalInstallerLease(requestId, completion, plan)
        if (!acquireExternalInstallerLease(lease)) {
            installerManager.cleanup(plan)
            return BatchInstallAttempt(app.getString(R.string.batch_external_installer_pending))
        }

        var timedOut = false
        return try {
            withTimeout(EXTERNAL_INSTALL_ACTIVITY_TIMEOUT_MS) {
                activityRequestChannel.send(
                    BatchActivityRequest(
                        requestId = requestId,
                        intent = plan.intent,
                        completion = completion
                    )
                )
                completion.await()
            }
            if (waitForExternalInstall(targetPackage, baseline)) {
                BatchInstallAttempt()
            } else {
                BatchInstallAttempt(
                    app.getString(
                        R.string.installer_external_finished_no_change,
                        plan.installerLabel
                    )
                )
            }
        } catch (_: TimeoutCancellationException) {
            timedOut = true
            lease.timedOut.set(true)
            completion.invokeOnCompletion {
                releaseExternalInstallerLease(lease)
            }
            scope.launch {
                delay(EXTERNAL_INSTALL_PENDING_GRACE_MS)
                releaseExternalInstallerLease(
                    lease,
                    "External installer response grace period expired"
                )
            }
            BatchInstallAttempt(
                failure = app.getString(
                    R.string.installer_external_timeout,
                    plan.installerLabel
                )
            )
        } finally {
            if (!timedOut) {
                releaseExternalInstallerLease(
                    lease,
                    "External installation stopped"
                )
            }
        }
    }

    private fun releaseExternalInstallerLease(
        lease: PendingExternalInstallerLease,
        cancellationMessage: String? = null
    ): Boolean {
        if (!pendingExternalInstaller.compareAndSet(lease, null)) return false
        try {
            if (cancellationMessage != null && !lease.completion.isCompleted) {
                lease.completion.cancel(CancellationException(cancellationMessage))
            }
            installerManager.cleanup(lease.plan)
        } finally {
            lease.released.complete(Unit)
        }
        return true
    }

    private suspend fun waitForExternalInstall(
        packageName: String,
        baseline: Pair<Long, Long>?
    ): Boolean {
        val deadline = System.currentTimeMillis() + EXTERNAL_INSTALL_VERIFY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val current = pm.getPackageInfo(packageName)
            if (current != null) {
                val changed = baseline == null ||
                    pm.getVersionCode(current) != baseline.first ||
                    current.lastUpdateTime > baseline.second
                if (changed) return true
            }
            delay(EXTERNAL_INSTALL_VERIFY_POLL_MS)
        }
        return false
    }

    private suspend fun waitUntilPackageRemoved(packageName: String): Boolean {
        val deadline = System.currentTimeMillis() + SHIZUKU_UNINSTALL_VERIFY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (pm.getPackageInfo(packageName) == null) return true
            delay(SHIZUKU_UNINSTALL_VERIFY_POLL_MS)
        }
        return pm.getPackageInfo(packageName) == null
    }

    private suspend fun persistInstalledPatchedItem(
        item: BatchPatchItem,
        sourceFile: File,
        targetPackage: String,
        installType: InstallType
    ): File {
        val version = pm.getPackageInfo(sourceFile)?.versionName?.takeIf(String::isNotBlank)
            ?: item.version
            ?: "unknown"
        val selectionPayload = item.selectionPayload
            ?: patchBundleRepository.snapshotSelection(
                item.selection,
                item.options
            )
        val variantIdentity = buildSavedAppVariantIdentity(
            version,
            selectionPayload,
            item.selection
        )
        val replacementTimestamp = System.currentTimeMillis()
        val sourceSavedEntry = item.sourceEntryKey
            ?.let { installedAppRepository.get(it) }
            ?.takeIf { sourceEntry ->
                sourceEntry.installType == InstallType.SAVED &&
                    (
                        isSavedAppEntryForPackage(
                            sourceEntry.currentPackageName,
                            targetPackage
                        ) || sourceEntry.originalPackageName == item.packageName
                    )
            }
        val matchingSavedEntries = (
            findMatchingSavedEntriesForInstalledVariant(
                targetPackage = targetPackage,
                originalPackageName = item.packageName,
                variantIdentity = variantIdentity
            ) + listOfNotNull(sourceSavedEntry)
        ).distinctBy(InstalledApp::currentPackageName)
        val existingTargetEntry = installedAppRepository.get(targetPackage)
        val replacementSortOrder = existingTargetEntry?.sortOrder
            ?: matchingSavedEntries.minByOrNull(InstalledApp::sortOrder)?.sortOrder

        val pendingHistoricalEntry = prepareReplacedInstalledVariant(
            targetPackage = targetPackage,
            originalPackageName = item.packageName,
            newVariantIdentity = variantIdentity
        )

        val installedCopy = fs.getPatchedAppFile(targetPackage, version)
        val copiedInstalledOutput = !sourceFile.absolutePath.equals(
            installedCopy.absolutePath,
            ignoreCase = true
        )
        val installedCopyDirectory = requireNotNull(installedCopy.parentFile)
        val stagingInstalledCopy = installedCopyDirectory.resolve(
            ".${installedCopy.name}.${UUID.randomUUID()}.tmp"
        )
        val backupInstalledCopy = installedCopyDirectory.resolve(
            ".${installedCopy.name}.${UUID.randomUUID()}.bak"
        )
        val previousLastModified = installedCopy.takeIf(File::isFile)?.lastModified()
        var replacementStarted = false
        var keepBackup = false
        try {
            if (copiedInstalledOutput) {
                check(installedCopyDirectory.mkdirs() || installedCopyDirectory.isDirectory) {
                    "Unable to create the installed APK directory"
                }
                sourceFile.copyTo(stagingInstalledCopy, overwrite = true)
                check(
                    stagingInstalledCopy.isFile &&
                        stagingInstalledCopy.length() == sourceFile.length()
                ) {
                    "Failed to verify the installed APK staging copy"
                }
                if (installedCopy.isFile) {
                    installedCopy.copyTo(backupInstalledCopy, overwrite = true)
                    check(
                        backupInstalledCopy.isFile &&
                            backupInstalledCopy.length() == installedCopy.length()
                    ) {
                        "Failed to verify the previous installed APK backup"
                    }
                }
                replacementStarted = true
                stagingInstalledCopy.copyTo(installedCopy, overwrite = true)
                check(installedCopy.isFile && installedCopy.length() == sourceFile.length()) {
                    "Failed to verify the installed APK copy"
                }
            }
            installedCopy.setLastModified(replacementTimestamp)

            val persistReplacement: suspend () -> Unit = {
                installedAppRepository.addOrUpdate(
                    currentPackageName = targetPackage,
                    originalPackageName = item.packageName,
                    version = version,
                    installType = installType,
                    patchSelection = item.selection,
                    selectionPayload = selectionPayload,
                    createdAtOverride = replacementTimestamp,
                    sortOrderOverride = replacementSortOrder
                )
            }
            if (pendingHistoricalEntry != null) {
                pendingHistoricalEntry.commitWith(targetPackage, persistReplacement)
            } else {
                persistReplacement()
            }
        } catch (error: Throwable) {
            if (copiedInstalledOutput && replacementStarted) {
                val restoreError = runCatching {
                    if (backupInstalledCopy.isFile) {
                        backupInstalledCopy.copyTo(installedCopy, overwrite = true)
                        check(
                            installedCopy.isFile &&
                                installedCopy.length() == backupInstalledCopy.length()
                        ) {
                            "Failed to verify the restored installed APK"
                        }
                        previousLastModified?.let(installedCopy::setLastModified)
                    } else {
                        check(installedCopy.delete() || !installedCopy.exists()) {
                            "Failed to remove the uncommitted installed APK"
                        }
                    }
                }.exceptionOrNull()
                if (restoreError != null) {
                    keepBackup = backupInstalledCopy.isFile
                    error.addSuppressed(restoreError)
                    Log.e(TAG, "Failed to restore the previous installed APK", restoreError)
                }
            }
            pendingHistoricalEntry?.discard()
            throw error
        } finally {
            stagingInstalledCopy.delete()
            if (!keepBackup) backupInstalledCopy.delete()
        }
        matchingSavedEntries.forEach { savedEntry ->
            val migrationSucceeded = runPostCommitStep(
                "Failed to migrate automatic patch target from ${savedEntry.currentPackageName} to $targetPackage"
            ) {
                installedAppRepository.migrateAutoPatchTarget(
                    savedEntry.currentPackageName,
                    targetPackage
                )
            }
            if (
                migrationSucceeded &&
                savedEntry.currentPackageName != targetPackage
            ) {
                runPostCommitStep(
                    "Failed to clean up installed saved entry ${savedEntry.currentPackageName}"
                ) {
                    installedAppRepository.delete(savedEntry)
                    fs.getPatchedAppFile(
                        savedEntry.currentPackageName,
                        savedEntry.version
                    ).takeIf { savedFile ->
                        savedFile.exists() &&
                            !savedFile.absolutePath.equals(
                                installedCopy.absolutePath,
                                ignoreCase = true
                            )
                    }?.let { savedFile ->
                        check(savedFile.delete() || !savedFile.exists()) {
                            "The replaced saved APK could not be removed"
                        }
                    }
                }
            }
        }

        if (!sourceFile.absolutePath.equals(installedCopy.absolutePath, ignoreCase = true)) {
            runPostCommitStep("Failed to remove the temporary installed batch APK") {
                check(sourceFile.delete() || !sourceFile.exists()) {
                    "The temporary installed batch APK could not be removed"
                }
            }
        }
        runPostCommitStep("Failed to prune retained original APKs") {
            installedAppRepository.pruneRetainedOriginals()
        }
        return installedCopy
    }

    private suspend fun prepareReplacedInstalledVariant(
        targetPackage: String,
        originalPackageName: String,
        newVariantIdentity: String
    ): PendingHistoricalSavedEntry? {
        val savedEntriesForPackage = installedAppRepository
            .getByInstallType(InstallType.SAVED)
            .filter { savedEntry ->
                isSavedAppEntryForPackage(savedEntry.currentPackageName, targetPackage) ||
                    savedEntry.originalPackageName == originalPackageName ||
                    savedEntry.originalPackageName == targetPackage
            }
        val savedEntryIdentities = mutableMapOf<String, String>()
        savedEntriesForPackage.forEach { savedEntry ->
            savedEntryIdentities[savedEntry.currentPackageName] = savedVariantIdentity(savedEntry)
        }

        val existingTargetEntry = installedAppRepository.get(targetPackage) ?: return null
        val existingInstalledEntry = existingTargetEntry.takeIf {
            it.installType != InstallType.SAVED
        }
        val existingInstalledIdentity = existingInstalledEntry?.let {
            savedVariantIdentity(it)
        }
        if (
            existingInstalledEntry != null &&
            existingInstalledIdentity != null &&
            existingInstalledIdentity != newVariantIdentity &&
            existingInstalledIdentity !in savedEntryIdentities.values
        ) {
            return installedAppRepository.prepareHistoricalSavedEntry(
                sourceApp = existingInstalledEntry,
                targetPackageName = buildSavedAppEntryKey(
                    targetPackage,
                    existingInstalledIdentity
                )
            )
        }

        val existingSavedEntryAtBaseKey = existingTargetEntry.takeIf {
            it.installType == InstallType.SAVED
        }
        val existingSavedEntryIdentity = existingSavedEntryAtBaseKey?.let {
            savedVariantIdentity(it)
        }
        return if (
            existingSavedEntryAtBaseKey != null &&
            existingSavedEntryIdentity != null &&
            existingSavedEntryIdentity != newVariantIdentity &&
            existingSavedEntryIdentity !in savedEntryIdentities
                .filterKeys { it != existingSavedEntryAtBaseKey.currentPackageName }
                .values
        ) {
            installedAppRepository.prepareHistoricalSavedEntry(
                sourceApp = existingSavedEntryAtBaseKey,
                targetPackageName = buildSavedAppEntryKey(
                    targetPackage,
                    existingSavedEntryIdentity
                )
            )
        } else {
            null
        }
    }

    private suspend fun savedVariantIdentity(savedApp: InstalledApp): String =
        buildSavedAppVariantIdentity(
            appVersion = savedApp.version,
            selectionPayload = savedApp.selectionPayload,
            patchSelection = installedAppRepository.getAppliedPatches(
                savedApp.currentPackageName
            )
        )

    private suspend fun findMatchingSavedEntriesForInstalledVariant(
        targetPackage: String,
        originalPackageName: String,
        variantIdentity: String
    ): List<InstalledApp> = installedAppRepository
        .getByInstallType(InstallType.SAVED)
        .filter { savedEntry ->
            val belongsToInstalledApp =
                isSavedAppEntryForPackage(savedEntry.currentPackageName, targetPackage) ||
                    savedEntry.originalPackageName == originalPackageName ||
                    savedEntry.originalPackageName == targetPackage
            belongsToInstalledApp && savedVariantIdentity(savedEntry) == variantIdentity
        }

    private suspend fun runPostCommitStep(
        description: String,
        block: suspend () -> Unit
    ): Boolean = withContext(NonCancellable) {
        try {
            block()
            true
        } catch (error: Exception) {
            Log.w(TAG, description, error)
            false
        }
    }

    suspend fun cancel() {
        try {
            mutableState.update { current ->
                current?.copy(phase = BatchPhase.CANCELLING)
            }
            val workerId = activeWorkerId
            if (workerId != null) {
                workerRepository.cancelUniqueWork(
                    PatcherWorker.UNIQUE_WORK_NAME,
                    expectedId = workerId
                )
            }
            job?.cancelAndJoin()
            installJob?.cancelAndJoin()
            if (workerId != null) {
                PatcherWorker.clearNotification(app)
            }
            if (activeWorkerId == workerId) activeWorkerId = null

            val cancelled = mutableState.value?.copy(
                phase = BatchPhase.FINISHED,
                activeIndex = null,
                detail = null,
                items = mutableState.value?.items.orEmpty().map { item ->
                    if (
                        item.state == BatchItemState.RUNNING ||
                        item.state == BatchItemState.READY
                    ) {
                        item.copy(state = BatchItemState.CANCELLED, installing = false)
                    } else {
                        item.copy(installing = false)
                    }
                }
            )
            if (cancelled != null) finish(cancelled)
        } finally {
            releaseExecution()
        }
    }

    fun clear() {
        if (
            job?.isActive == true ||
            installJob?.isActive == true ||
            mutableState.value?.items?.any { it.saving } == true
        ) return
        discardUnsavedPatchedFiles()
        mutableState.value = null
        liveScheduledExecution = false
        releaseExecution()
    }

    suspend fun finishPreflight(): BatchRunState? {
        val snapshot = mutableState.value ?: return null
        if (snapshot.phase == BatchPhase.FINISHED) return snapshot
        if (snapshot.phase != BatchPhase.PREFLIGHT) return null

        val finished = snapshot.copy(
            phase = BatchPhase.FINISHED,
            activeIndex = null,
            detail = null
        )
        finish(finished)
        return finished
    }

    @Synchronized
    fun restoreLastResult(
        packageNames: List<String>,
        scheduled: Boolean = false,
        requestId: String? = null
    ): Boolean {
        val serialized = when (batchResultStore(scheduled)) {
            BatchResultStore.USER -> prefs.lastBatchPatchResult.getBlocking()
            BatchResultStore.AUTOMATIC -> prefs.lastAutoPatchResult.getBlocking()
        }
        val snapshot = runCatching {
            json.decodeFromString<BatchResultSnapshot>(serialized)
        }.getOrNull() ?: return false
        if (snapshot.scheduled != scheduled) return false
        if (!matchesBatchResultSession(snapshot.requestId, requestId)) return false
        val requestedPackages = packageNames.toSet()
        if (
            requestedPackages.isNotEmpty() &&
            snapshot.items.mapTo(mutableSetOf()) { it.packageName } != requestedPackages
        ) return false

        liveScheduledExecution = false
        mutableState.value = BatchRunState(
            items = snapshot.items.map { item ->
                val restoredState = runCatching { BatchItemState.valueOf(item.state) }
                    .getOrDefault(BatchItemState.FAILED)
                val installOutcome = item.installOutcome?.let { outcome ->
                    runCatching { BatchInstallOutcome.valueOf(outcome) }.getOrNull()
                }
                val patchedFile = item.patchedFilePath?.let(::File)
                val restoredSelection = restoreBatchSelection(item.selectionPayload)
                val patchedFileMissing = restoredState == BatchItemState.SUCCEEDED &&
                    installOutcome != BatchInstallOutcome.INSTALLED &&
                    patchedFile?.isFile != true
                val metadataMissing = restoredState == BatchItemState.SUCCEEDED &&
                    installOutcome != BatchInstallOutcome.INSTALLED &&
                    item.selectionPayload == null
                BatchPatchItem(
                    packageName = item.packageName,
                    appName = item.appName,
                    version = item.version,
                    versionCode = item.versionCode,
                    input = null,
                    selection = restoredSelection,
                    options = emptyMap(),
                    selectionPayload = item.selectionPayload,
                    bundles = item.bundles.ifEmpty {
                        restoreBatchBundleRefs(item.selectionPayload)
                    },
                    state = if (patchedFileMissing || metadataMissing) {
                        BatchItemState.FAILED
                    } else {
                        restoredState
                    },
                    patcherEngine = item.patcherEngine,
                    patcherSessionInfo = item.patcherSessionInfo,
                    message = if (patchedFileMissing || metadataMissing) {
                        app.getString(R.string.batch_patch_result_unavailable)
                    } else {
                        item.message
                    },
                    patchedFile = patchedFile?.takeIf(File::isFile),
                    installOutcome = installOutcome,
                    installMessage = item.installMessage,
                    installedPackageName = item.installedPackageName,
                    savedForLater = item.savedForLater,
                    profileInstallerToken = item.profileInstallerToken,
                    useMount = item.useMount,
                    logLines = item.logLines.takeLast(MAX_LOG_LINES)
                )
            },
            phase = BatchPhase.FINISHED,
            policy = runCatching { BatchInstallPolicy.valueOf(snapshot.policy) }
                .getOrDefault(BatchInstallPolicy.SAVE_ONLY),
            scheduled = snapshot.scheduled,
            requestId = snapshot.requestId,
            progress = 1f,
            restored = true
        )
        return true
    }

    fun showUnavailableResult(packageNames: List<String>, message: String) {
        liveScheduledExecution = false
        mutableState.value = BatchRunState(
            items = packageNames.distinct().map { packageName ->
                BatchPatchItem(
                    packageName = packageName,
                    appName = packageName,
                    version = null,
                    versionCode = null,
                    input = null,
                    selection = emptyMap(),
                    options = emptyMap(),
                    bundles = emptyList(),
                    state = BatchItemState.FAILED,
                    message = message
                )
            },
            phase = BatchPhase.FINISHED,
            policy = BatchInstallPolicy.SAVE_ONLY,
            restored = true
        )
    }

    private suspend fun finish(state: BatchRunState) {
        persistResult(state)
        mutableState.value = state
        if (!shouldRetainBatchExecutionAfterFinish(
                scheduled = state.scheduled,
                liveScheduledExecution = liveScheduledExecution
            )
        ) {
            releaseExecution()
        }
    }

    private suspend fun persistResult(state: BatchRunState) {
        val perItemLogBudget = MAX_RESULT_LOG_CHARACTERS /
            state.items.size.coerceAtLeast(1)
        var snapshot = BatchResultSnapshot(
            completedAt = System.currentTimeMillis(),
            policy = state.policy.name,
            scheduled = state.scheduled,
            items = state.items.map { item ->
                BatchResultItemSnapshot(
                    packageName = item.packageName,
                    appName = item.appName,
                    version = item.version,
                    versionCode = item.versionCode,
                    selectionPayload = item.selectionPayload
                        ?: item.selection.takeIf { it.isNotEmpty() }?.let {
                            runCatching {
                                patchBundleRepository.snapshotSelection(
                                    item.selection,
                                    item.options
                                )
                            }.getOrNull()
                        },
                    bundles = item.bundles.map { bundle ->
                        bundle.copy(patchNames = emptySet())
                    },
                    state = item.state.name,
                    message = item.message?.take(MAX_RESULT_MESSAGE_LENGTH),
                    patchedFilePath = item.patchedFile?.absolutePath,
                    installOutcome = item.installOutcome?.name,
                    installMessage = item.installMessage?.take(MAX_RESULT_MESSAGE_LENGTH),
                    installedPackageName = item.installedPackageName,
                    savedForLater = item.savedForLater,
                    profileInstallerToken = item.profileInstallerToken,
                    useMount = item.useMount,
                    patcherEngine = item.patcherEngine,
                    patcherSessionInfo = item.patcherSessionInfo,
                    logLines = takeLastWithinCharacterBudget(
                        item.logLines.takeLast(MAX_LOG_LINES),
                        perItemLogBudget
                    )
                )
            },
            requestId = state.requestId
        )
        var serialized = try {
            json.encodeToString(snapshot)
        } catch (error: Exception) {
            Log.e(TAG, "Failed to serialize the batch patch result", error)
            return
        }
        if (serialized.toByteArray(Charsets.UTF_8).size > MAX_RESULT_SERIALIZED_BYTES) {
            snapshot = snapshot.copy(
                items = snapshot.items.map { item -> item.copy(logLines = emptyList()) }
            )
            serialized = try {
                json.encodeToString(snapshot)
            } catch (error: Exception) {
                Log.e(TAG, "Failed to serialize the trimmed batch patch result", error)
                return
            }
        }
        if (serialized.toByteArray(Charsets.UTF_8).size > MAX_RESULT_SERIALIZED_BYTES) {
            Log.w(
                TAG,
                "Batch patch result is too large to persist safely (${serialized.length} characters)"
            )
            return
        }
        try {
            when (batchResultStore(state.scheduled)) {
                BatchResultStore.USER -> prefs.lastBatchPatchResult.update(serialized)
                BatchResultStore.AUTOMATIC -> prefs.lastAutoPatchResult.update(serialized)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to persist the batch patch result", error)
            return
        }
        prunePersistedBatchOutputs()
    }

    private suspend fun prunePersistedBatchOutputs() {
        try {
            fs.pruneBatchPatchOutputFiles(
                retainedBatchOutputPaths(
                    json,
                    listOf(
                        prefs.lastBatchPatchResult.get(),
                        prefs.lastAutoPatchResult.get()
                    )
                )
            )
        } catch (error: Exception) {
            Log.w(TAG, "Failed to prune stale batch patch outputs", error)
        }
    }

    private fun updateItem(index: Int, transform: (BatchPatchItem) -> BatchPatchItem) {
        mutableState.update { state ->
            state?.copy(items = state.items.mapIndexed { itemIndex, item ->
                if (itemIndex == index) transform(item) else item
            })
        }
    }

    private fun updateItemByPackage(
        packageName: String,
        transform: (BatchPatchItem) -> BatchPatchItem
    ) {
        mutableState.update { state ->
            state?.copy(items = state.items.map { item ->
                if (item.packageName == packageName) transform(item) else item
            })
        }
    }

    suspend fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        try {
            val phase = mutableState.value?.phase
            val shouldCancel =
                job?.isActive == true ||
                    installJob?.isActive == true ||
                    phase == BatchPhase.PLANNING ||
                    phase == BatchPhase.PREFLIGHT ||
                    phase == BatchPhase.RUNNING ||
                    phase == BatchPhase.INSTALLING ||
                    phase == BatchPhase.CANCELLING
            if (shouldCancel) {
                cancel()
                discardUnsavedPatchedFiles()
            } else {
                discardUnsavedPatchedFiles()
                releaseExecution()
            }
        } finally {
            pendingExternalInstaller.get()?.let { lease ->
                releaseExternalInstallerLease(
                    lease,
                    "Batch patch coordinator shut down"
                )
            }
            activityRequestChannel.close()
            rootDowngradeRequestChannel.close()
            fallbackInstallRequestChannel.close()
            scope.cancel()
        }
    }

    private companion object {
        const val TAG = "BatchPatchCoordinator"
        const val MAX_PROGRESS_EVENTS = 2_000
        const val MAX_MEMORY_SAMPLES = 2_000
        const val MAX_LOG_LINES = 2_000
        const val MAX_RESULT_MESSAGE_LENGTH = 2_000
        const val MAX_RESULT_LOG_CHARACTERS = 250_000
        const val MAX_RESULT_SERIALIZED_BYTES = 512_000
        const val INTERACTIVE_ACTIVITY_TIMEOUT_MS = 5L * 60L * 1_000L
        const val EXTERNAL_INSTALL_ACTIVITY_TIMEOUT_MS = 5L * 60L * 1_000L
        const val EXTERNAL_INSTALL_PENDING_GRACE_MS = 60_000L
        const val EXTERNAL_INSTALL_PENDING_WAIT_MS =
            EXTERNAL_INSTALL_PENDING_GRACE_MS + 5_000L
        const val EXTERNAL_INSTALL_VERIFY_TIMEOUT_MS = 15_000L
        const val EXTERNAL_INSTALL_VERIFY_POLL_MS = 250L
        const val SHIZUKU_UNINSTALL_VERIFY_TIMEOUT_MS = 15_000L
        const val SHIZUKU_UNINSTALL_VERIFY_POLL_MS = 250L
    }
}

internal fun selectBatchSavedEntryKey(
    packageName: String,
    variantIdentity: String,
    overwriteDisabled: Boolean,
    matchingEntryKey: String?,
    uniqueSuffix: String
): String {
    val baseKey = buildSavedAppEntryKey(packageName, variantIdentity)
    return when {
        overwriteDisabled -> "${baseKey}__${uniqueSuffix}"
        matchingEntryKey != null -> matchingEntryKey
        else -> baseKey
    }
}
