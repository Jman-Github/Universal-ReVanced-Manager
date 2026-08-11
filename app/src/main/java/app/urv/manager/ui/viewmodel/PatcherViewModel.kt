package app.urv.manager.ui.viewmodel

import android.app.Activity

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.os.ParcelUuid
import android.os.Parcelable
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.autoSaver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.SavedStateHandleSaveableApi
import androidx.lifecycle.viewmodel.compose.saveable
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.universal.revanced.manager.R
import app.urv.manager.data.platform.Filesystem
import app.urv.manager.data.room.apps.installed.InstallType
import app.urv.manager.data.room.apps.installed.InstalledApp
import app.urv.manager.domain.installer.InstallCancelledException
import app.urv.manager.domain.installer.InstallResult
import app.urv.manager.domain.installer.InstallerManager
import app.urv.manager.domain.installer.installerTokenMatchesPatchMode
import app.urv.manager.domain.installer.RootInstaller
import app.urv.manager.domain.installer.RootServiceException
import app.urv.manager.domain.installer.SessionDeadException
import app.urv.manager.domain.installer.SessionInstaller
import app.urv.manager.domain.installer.ShizukuInstaller
import app.urv.manager.domain.installer.root.RootMountOperation
import app.urv.manager.domain.installer.root.RootMountPhase
import app.urv.manager.domain.installer.root.RootMountRequest
import app.urv.manager.domain.installer.root.RootMountResult
import app.urv.manager.domain.installer.root.RootMountTransactionCoordinator
import app.urv.manager.domain.installer.root.describeRecovery
import app.urv.manager.domain.installer.root.requireSuccess
import app.urv.manager.domain.manager.PreferencesManager
import app.urv.manager.domain.repository.DownloadResult
import app.urv.manager.domain.repository.DownloadedAppRepository
import app.urv.manager.domain.repository.DownloaderPluginRepository
import app.urv.manager.domain.repository.PatchBundleRepository
import app.urv.manager.domain.repository.PatchOptionsRepository
import app.urv.manager.domain.repository.PatchSelectionRepository
import app.urv.manager.domain.repository.InstalledAppRepository
import app.urv.manager.domain.repository.PendingHistoricalSavedEntry
import app.urv.manager.domain.worker.UniqueWorkAlreadyRunningException
import app.urv.manager.domain.worker.WorkerRepository
import app.urv.manager.patcher.ProgressEvent
import app.urv.manager.patcher.RemoteError
import app.urv.manager.patcher.StepId
import app.urv.manager.patcher.logger.LogLevel
import app.urv.manager.patcher.logger.Logger
import app.urv.manager.patcher.logger.isVerbosePatcherExportLog
import app.urv.manager.patcher.runtime.MemoryLimitConfig
import app.urv.manager.patcher.runtime.Revanced22ProcessRuntime
import app.urv.manager.patcher.runCancellableBlockingIo
import app.urv.manager.patcher.split.SplitApkPreparer
import app.urv.manager.patcher.worker.PatcherWorker
import app.urv.manager.patcher.worker.PatcherMemoryUsage
import app.urv.manager.patcher.worker.PatcherWorkerProgressState
import app.urv.manager.patcher.worker.PatcherWorkerProgressUpdate
import app.urv.manager.network.downloader.LoadedDownloaderPlugin
import app.urv.manager.plugin.downloader.GetScope
import app.urv.manager.plugin.downloader.PluginHostApi
import app.urv.manager.plugin.downloader.UserInteractionException
import app.urv.manager.ui.model.InstallerModel
import app.urv.manager.patcher.PatcherSessionInfo
import app.urv.manager.ui.model.SelectedApp
import app.urv.manager.ui.model.State
import app.urv.manager.ui.model.Step
import app.urv.manager.ui.model.StepCategory
import app.urv.manager.ui.model.StepDetail
import app.urv.manager.ui.model.withState
import app.urv.manager.patcher.parsePatcherSessionInfo
import app.urv.manager.patcher.updatedFromLog
import app.urv.manager.ui.model.navigation.Patcher
import app.urv.manager.service.PatchingTaskMonitorService
import app.universal.revanced.manager.BuildConfig
import app.urv.manager.util.PM
import app.urv.manager.util.PatchBundleExportData
import app.urv.manager.util.PatchedAppExportData
import app.urv.manager.util.Options
import app.urv.manager.util.PatchSelection
import app.urv.manager.patcher.patch.PatchBundleInfo
import app.urv.manager.patcher.patch.applyAvailability
import app.urv.manager.patcher.patch.installerTypeFor
import app.urv.manager.patcher.patch.removeGmsCoreSupport
import app.urv.manager.patcher.patch.PatchBundleType
import app.urv.manager.patcher.patch.patcherEngineDisplayName
import app.urv.manager.util.AppForeground
import app.urv.manager.util.buildSavedAppEntryKey
import app.urv.manager.util.buildSavedAppVariantIdentity
import app.urv.manager.util.isSavedAppEntryForPackage
import app.urv.manager.util.saveableVar
import app.urv.manager.util.saver.snapshotStateListSaver
import app.urv.manager.util.simpleMessage
import app.urv.manager.util.tag
import app.urv.manager.util.toast
import app.urv.manager.util.awaitUserConfirmation
import app.urv.manager.util.toastHandle
import app.urv.manager.util.uiSafe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.time.withTimeout
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.coroutines.resume
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import ru.solrudev.ackpine.session.Session
import ru.solrudev.ackpine.session.await
import ru.solrudev.ackpine.session.parameters.Confirmation
import ru.solrudev.ackpine.uninstaller.PackageUninstaller
import ru.solrudev.ackpine.uninstaller.UninstallFailure
import ru.solrudev.ackpine.uninstaller.createSession
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private class PendingPatchedAppReplacement private constructor(
    private val target: File,
    private val staging: File,
    private val backup: File,
    private val targetExisted: Boolean,
    private val previousLastModified: Long?
) {
    private var finished = false

    fun commit() {
        if (finished) return
        finished = true
        staging.delete()
        backup.delete()
    }

    fun rollback(cause: Throwable) {
        if (finished) return
        val restoreError = runCatching {
            if (targetExisted) {
                check(backup.isFile) { "The previous saved APK backup is unavailable" }
                backup.copyTo(target, overwrite = true)
                check(target.isFile && target.length() == backup.length()) {
                    "Failed to verify the restored saved APK"
                }
                previousLastModified?.let(target::setLastModified)
            } else {
                check(target.delete() || !target.exists()) {
                    "Failed to remove the uncommitted saved APK"
                }
            }
        }.exceptionOrNull()
        staging.delete()
        if (restoreError == null) {
            backup.delete()
        } else {
            cause.addSuppressed(restoreError)
        }
        finished = true
    }

    companion object {
        fun prepare(source: File, target: File): PendingPatchedAppReplacement {
            check(source.isFile) { "The patched APK is unavailable" }
            val directory = requireNotNull(target.parentFile)
            check(directory.mkdirs() || directory.isDirectory) {
                "Unable to create the saved APK directory"
            }
            val staging = directory.resolve(".${target.name}.${UUID.randomUUID()}.tmp")
            val backup = directory.resolve(".${target.name}.${UUID.randomUUID()}.bak")
            val targetExisted = target.isFile
            val previousLastModified = target.takeIf(File::isFile)?.lastModified()
            val replacement = PendingPatchedAppReplacement(
                target = target,
                staging = staging,
                backup = backup,
                targetExisted = targetExisted,
                previousLastModified = previousLastModified
            )
            var replacementStarted = false
            try {
                source.copyTo(staging, overwrite = true)
                check(staging.isFile && staging.length() == source.length()) {
                    "Failed to verify the saved APK staging copy"
                }
                if (targetExisted) {
                    target.copyTo(backup, overwrite = true)
                    check(backup.isFile && backup.length() == target.length()) {
                        "Failed to verify the previous saved APK backup"
                    }
                }
                replacementStarted = true
                staging.copyTo(target, overwrite = true)
                check(target.isFile && target.length() == source.length()) {
                    "Failed to verify the saved patched APK"
                }
                return replacement
            } catch (error: Throwable) {
                if (replacementStarted) {
                    replacement.rollback(error)
                } else {
                    staging.delete()
                    backup.delete()
                }
                throw error
            }
        }
    }
}

@OptIn(SavedStateHandleSaveableApi::class, PluginHostApi::class)
class PatcherViewModel(
    private val input: Patcher.ViewModelParams
) : ViewModel(), KoinComponent, InstallerModel {
    private val app: Application by inject()
    private val fs: Filesystem by inject()
    private val pm: PM by inject()
    private val workerRepository: WorkerRepository by inject()
    private val patchBundleRepository: PatchBundleRepository by inject()
    private val patchSelectionRepository: PatchSelectionRepository by inject()
    private val patchOptionsRepository: PatchOptionsRepository by inject()
    private val installedAppRepository: InstalledAppRepository by inject()
    private val downloaderPluginRepository: DownloaderPluginRepository by inject()
    private val downloadedAppRepository: DownloadedAppRepository by inject()
    private val rootInstaller: RootInstaller by inject()
    private val rootMountCoordinator: RootMountTransactionCoordinator by inject()
    private val shizukuInstaller: ShizukuInstaller by inject()
    private val installerManager: InstallerManager by inject()
    private val sessionInstaller: SessionInstaller by inject()
    private val prefs: PreferencesManager by inject()
    private val skipApkSigning = prefs.skipApkSigning.getBlocking()
    private val savedStateHandle: SavedStateHandle = get()
    private val ackpineUninstaller: PackageUninstaller = get()
    private val selectionBundleType by lazy(LazyThreadSafetyMode.NONE) {
        runBlocking { patchBundleRepository.selectionBundleType(input.selectedPatches) }
    }
    private val selectionMorpheBytecodeMode by lazy(LazyThreadSafetyMode.NONE) {
        if (selectionBundleType == PatchBundleType.MORPHE) {
            runBlocking { prefs.morpheBytecodeMode.get().runtimeValue }
        } else {
            null
        }
    }

    private var pendingExternalInstall: InstallerManager.InstallPlan.External? = null
    private var externalInstallBaseline: Pair<Long?, Long?>? = null
    private var externalInstallStartTime: Long? = null
    private var externalPackageWasPresentAtStart: Boolean = false
    private var externalInstallTimeoutJob: Job? = null
    private var externalInstallPresenceJob: Job? = null
    private var expectedInstallSignature: ByteArray? = null
    private var baselineInstallSignature: ByteArray? = null
    private var internalInstallBaseline: Pair<Long?, Long?>? = null
    private var postTimeoutGraceJob: Job? = null
    private var activeInstallJob: Job? = null
    private var installProgressToastJob: Job? = null
    private var installProgressToast: Toast? = null
    private var deferInstallProgressToasts = false
    private var uninstallProgressToastJob: Job? = null
    private var uninstallProgressToast: Toast? = null
    private var deferUninstallProgressToasts = false
    private var pendingSignatureMismatchPlan: InstallerManager.InstallPlan? = null
    private var pendingSignatureMismatchPackage: String? = null
    private var lastInstallToken: InstallerManager.Token? = null
    private var lastInstallTarget: InstallerManager.InstallTarget? = null
    private var lastInstallExpectedPackage: String? = null
    private var lastInstallSourceLabel: String? = null
    private var pendingInstallFailureMessage: String? = null
    var keystoreMissingDialog by mutableStateOf(false)
        private set
    var rootDowngradeConfirmationPending by mutableStateOf(false)
        private set
    var rootMountPhase by mutableStateOf<RootMountPhase?>(null)
        private set
    var supportsRootMount by mutableStateOf(true)
        private set
    private var installedApp: InstalledApp? = null
    private suspend fun sourceInstalledApp(): InstalledApp? =
        input.sourceEntryKey
            ?.let { installedAppRepository.get(it) }
            ?: installedAppRepository.get(packageName)

    private val selectedApp = input.selectedApp
    val packageName = selectedApp.packageName
    val version = selectedApp.version
    val versionCode = selectedApp.versionCode
    val usingMountInstall = input.useMount
    val hasProfileInstallerPreference = input.profileInstallerToken
        ?.let(installerManager::parseToken)
        ?.let { installerTokenMatchesPatchMode(it, usingMountInstall) } == true

    fun isInstallerTokenAllowed(token: InstallerManager.Token): Boolean =
        installerTokenMatchesPatchMode(token, usingMountInstall)

    var basePackageInstalled by mutableStateOf(pm.getPackageInfo(packageName) != null)
        private set

    var installedPackageName by savedStateHandle.saveable(
        key = "installedPackageName",
        // Force Kotlin to select the correct overload.
        stateSaver = autoSaver()
    ) {
        mutableStateOf<String?>(null)
    }
        private set
    private var ongoingPmSession: Boolean by savedStateHandle.saveableVar { false }
    var packageInstallerStatus: Int? by savedStateHandle.saveable(
        key = "packageInstallerStatus",
        stateSaver = autoSaver()
    ) {
        mutableStateOf(null)
    }
        private set

    var isInstalling by mutableStateOf(ongoingPmSession)
        private set
    private var autoInstallTriggered: Boolean by savedStateHandle.saveableVar { false }
    var installStatus by mutableStateOf<InstallCompletionStatus?>(null)
        private set
    var signatureMismatchPackage by mutableStateOf<String?>(null)
        private set
    var activeInstallType by mutableStateOf<InstallType?>(null)
        private set
    var lastInstallType by mutableStateOf<InstallType?>(null)
        private set

    private fun updateInstallingState(value: Boolean) {
        ongoingPmSession = value
        isInstalling = value
        if (!value) {
            externalInstallTimeoutJob?.cancel()
            externalInstallTimeoutJob = null
            externalInstallPresenceJob?.cancel()
            externalInstallPresenceJob = null
            externalInstallBaseline = null
            internalInstallBaseline = null
            stopInstallProgressToasts()
            activeInstallType = null
            suppressFailureAfterSuccess = false
            packageInstallerStatus = null
            expectedInstallSignature = null
            baselineInstallSignature = null
            pendingSignatureMismatchPlan = null
            pendingSignatureMismatchPackage = null
            signatureMismatchPackage = null
            stopUninstallProgressToasts()
            deferInstallProgressToasts = false
        } else {
            postTimeoutGraceJob?.cancel()
            postTimeoutGraceJob = null
            if (!deferInstallProgressToasts) {
                startInstallProgressToasts()
            }
            suppressFailureAfterSuccess = false
        }
    }

    private fun markInstallSuccess(packageName: String?) {
        if (installStatus is InstallCompletionStatus.Success) return
        installStatus = InstallCompletionStatus.Success(packageName)
        app.toast(app.getString(R.string.install_app_success))
    }

    private fun handleUninstallFailure(message: String) {
        pendingSignatureMismatchPlan = null
        pendingSignatureMismatchPackage = null
        signatureMismatchPackage = null
        stopUninstallProgressToasts()
        showInstallFailure(message)
    }

    private var savedPatchedApp by savedStateHandle.saveableVar { false }
    val hasSavedPatchedApp get() = savedPatchedApp

    var exportMetadata by mutableStateOf<PatchedAppExportData?>(null)
        private set
    private var appliedSelection: PatchSelection = input.selectedPatches.mapValues { it.value.toSet() }
    private var appliedOptions: Options = input.options
    val currentSelectedApp: SelectedApp
        get() = when (val current = selectedApp) {
            is SelectedApp.Local -> inputFile?.let { current.copy(file = it) } ?: current
            else -> current
        }

    fun currentSelectionSnapshot(): PatchSelection =
        appliedSelection.mapValues { (_, patches) -> patches.toSet() }

    fun currentOptionsSnapshot(): Options =
        appliedOptions.mapValues { (_, bundleOptions) ->
            bundleOptions.mapValues { (_, patchOptions) -> patchOptions.toMap() }.toMap()
        }.toMap()

    val selectedPatchCount: Int
        get() = appliedSelection.values.sumOf { patches -> patches.size }

    val fallbackInputSizeBytes: Long?
        get() = (inputFile ?: (selectedApp as? SelectedApp.Local)?.file)
            ?.takeIf(File::isFile)
            ?.length()

    val fallbackInputIsSplitApk: Boolean?
        get() = when {
            requiresSplitPreparation -> true
            selectedApp is SelectedApp.Local -> false
            inputFile != null -> false
            else -> null
        }

fun dismissMissingPatchWarning() {
    missingPatchWarning = null
}

fun proceedAfterMissingPatchWarning() {
    if (missingPatchWarning == null) return
    viewModelScope.launch {
        missingPatchWarning = null
        beginPrePatchFlow()
    }
}

    fun removeMissingPatchesAndStart() {
        val warning = missingPatchWarning ?: return
        viewModelScope.launch {
            val scopedBundles = gatherScopedBundles()
            val sanitizedSelection = applyCurrentPatchRules(
                sanitizeSelection(appliedSelection, scopedBundles),
                scopedBundles
            )
            val sanitizedOptions = sanitizeOptions(appliedOptions, scopedBundles)
            appliedSelection = sanitizedSelection
            appliedOptions = sanitizedOptions
            refreshPatcherInformationMetadata(scopedBundles)
            missingPatchWarning = null
            beginPrePatchFlow()
        }
    }

    data class SplitSelectionDialogState(
        val inspection: SplitApkPreparer.SplitArchiveInspection,
        val initialModules: Set<String>,
        val initialStripNativeLibs: Boolean
    )

    data class PrePatchDownloadProgress(
        val downloadedBytes: Long,
        val totalBytes: Long?
    ) {
        val fraction: Float?
            get() = totalBytes
                ?.takeIf { it > 0L }
                ?.let { total ->
                    (downloadedBytes.toDouble() / total.toDouble())
                        .coerceIn(0.0, 1.0)
                        .toFloat()
                }
    }

    private var pendingSplitSelectionDialog: SplitSelectionDialogState? by mutableStateOf(null)
    val splitSelectionDialog by derivedStateOf { pendingSplitSelectionDialog }

    var isPreparingSplitSelection by mutableStateOf(false)
        private set
    var prePatchDownloadProgress by mutableStateOf<PrePatchDownloadProgress?>(null)
        private set
    var splitSelectionPreparationError by mutableStateOf<String?>(null)
        private set

    private var prePatchPreparationJob: Job? = null
    private var workerLaunchJob: Job? = null
    private var preparedInput: DownloadResult? = null
    private var preparedInputIncludesDownload = false
    private var selectedSplitConfiguration: PatcherWorker.SplitSelection? = null

    fun confirmSplitSelection(includedModules: Set<String>, stripNativeLibs: Boolean) {
        if (pendingSplitSelectionDialog == null) return
        selectedSplitConfiguration = PatcherWorker.SplitSelection(
            includedModules = includedModules,
            stripNativeLibs = stripNativeLibs
        )
        pendingSplitSelectionDialog = null
        startWorker()
    }

    fun cancelSplitSelectionPreparation() {
        prePatchPreparationJob?.cancel()
        prePatchPreparationJob = null
        isPreparingSplitSelection = false
        prePatchDownloadProgress = null
        pendingSplitSelectionDialog = null
        cleanupPreparedInput()
    }

    fun dismissSplitSelectionPreparationError() {
        splitSelectionPreparationError = null
    }

    private fun beginPrePatchFlow() {
        if (!prefs.chooseSplitApksBeforePatching.getBlocking()) {
            startWorker()
            return
        }
        prePatchPreparationJob?.cancel()
        prePatchPreparationJob = viewModelScope.launch {
            isPreparingSplitSelection = true
            prePatchDownloadProgress = when (input.selectedApp) {
                is SelectedApp.Download,
                is SelectedApp.Search -> PrePatchDownloadProgress(0L, null)
                else -> null
            }
            splitSelectionPreparationError = null
            try {
                val localInput = input.selectedApp as? SelectedApp.Local
                val localSplitEntryNames = localInput?.let { selected ->
                    withContext(Dispatchers.IO) {
                        SplitApkPreparer.splitApkEntryNames(selected.file)
                    }
                }
                if (localSplitEntryNames != null && localSplitEntryNames.size <= 1) {
                    isPreparingSplitSelection = false
                    startWorker()
                    return@launch
                }

                val resolvedInput = localInput?.let { selected ->
                    DownloadResult(selected.file, needsSplit = true)
                } ?: resolveInputBeforePatching()
                prePatchDownloadProgress = null
                preparedInput = resolvedInput
                preparedInputIncludesDownload =
                    input.selectedApp is SelectedApp.Download || input.selectedApp is SelectedApp.Search
                inputFile = resolvedInput.file
                updateSplitStepRequirement(
                    file = resolvedInput.file,
                    needsSplitOverride = resolvedInput.needsSplit,
                    merged = resolvedInput.merged
                )

                val resolvedSplitEntryNames = if (
                    localInput != null &&
                    resolvedInput.file.absoluteFile == localInput.file.absoluteFile
                ) {
                    localSplitEntryNames.orEmpty()
                } else if (resolvedInput.needsSplit) {
                    withContext(Dispatchers.IO) {
                        SplitApkPreparer.splitApkEntryNames(resolvedInput.file)
                    }
                } else {
                    emptySet()
                }
                val hasSelectableSplits =
                    resolvedInput.needsSplit && resolvedSplitEntryNames.size > 1
                if (!hasSelectableSplits) {
                    isPreparingSplitSelection = false
                    startWorker()
                    return@launch
                }

                val inspection = withContext(Dispatchers.IO) {
                    SplitApkPreparer.inspect(resolvedInput.file)
                }
                val initialStripNativeLibs = prefs.stripUnusedNativeLibs.get()
                val skipUnneededSplitApks = prefs.skipUnneededSplitApks.get()
                val allModules = inspection.modules.mapTo(linkedSetOf()) { it.name }
                var initialModules: Set<String> = allModules
                if (skipUnneededSplitApks) {
                    initialModules = initialModules intersect inspection.languageTrimmedModules
                    initialModules = initialModules intersect inspection.densityTrimmedModules
                }
                if (initialStripNativeLibs) {
                    initialModules = initialModules intersect inspection.abiTrimmedModules
                }

                pendingSplitSelectionDialog = SplitSelectionDialogState(
                    inspection = inspection,
                    initialModules = initialModules,
                    initialStripNativeLibs = initialStripNativeLibs
                )
            } catch (error: CancellationException) {
                cleanupPreparedInput()
                throw error
            } catch (error: Throwable) {
                cleanupPreparedInput()
                splitSelectionPreparationError =
                    error.simpleMessage() ?: error.javaClass.simpleName
            } finally {
                isPreparingSplitSelection = false
                prePatchDownloadProgress = null
                prePatchPreparationJob = null
            }
        }
    }

    private suspend fun resolveInputBeforePatching(): DownloadResult {
        suspend fun download(plugin: LoadedDownloaderPlugin, data: Parcelable): DownloadResult =
            downloadedAppRepository.download(
                plugin = plugin,
                data = data,
                expectedPackageName = packageName,
                expectedVersion = input.selectedApp.version,
                appCompatibilityCheck = prefs.suggestedVersionSafeguard.get(),
                patchesCompatibilityCheck = !prefs.disablePatchVersionCompatCheck.get(),
                onDownload = { (downloadedBytes, totalBytes) ->
                    prePatchDownloadProgress = PrePatchDownloadProgress(
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes
                    )
                },
                persistDownload = prefs.autoSaveDownloaderApks.get()
            )

        return when (val selected = input.selectedApp) {
            is SelectedApp.Download -> {
                val (plugin, data) = downloaderPluginRepository.unwrapParceledData(selected.data)
                download(plugin, data)
            }

            is SelectedApp.Search -> {
                var lastInteractionFailure: UserInteractionException? = null
                for (plugin in downloaderPluginRepository.loadedPluginsFlow.first()) {
                    val interactionFailure = AtomicReference<UserInteractionException?>(null)
                    try {
                        val scope = object : GetScope {
                            override val pluginPackageName = plugin.packageName
                            override val hostPackageName = app.packageName

                            override suspend fun requestStartActivity(intent: Intent): Intent? {
                                interactionFailure.get()?.let { throw it }
                                val result = try {
                                    handleDownloaderActivityRequest(plugin, intent)
                                } catch (error: UserInteractionException) {
                                    interactionFailure.compareAndSet(null, error)
                                    throw error
                                }
                                interactionFailure.get()?.let { throw it }
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
                            plugin.get(scope, selected.packageName, selected.version)
                        }?.takeIf { (_, version) ->
                            selected.version == null || version == null || version == selected.version
                        }
                        if (result != null) {
                            return download(plugin, result.first)
                        }
                    } catch (error: UserInteractionException.Activity.NotCompleted) {
                        throw error
                    } catch (error: UserInteractionException) {
                        lastInteractionFailure = error
                    }
                }
                throw (lastInteractionFailure ?: IllegalStateException("App is not available."))
            }

            is SelectedApp.Local -> {
                val needsSplit = SplitApkPreparer.isSplitArchive(selected.file)
                DownloadResult(selected.file, needsSplit = needsSplit)
            }

            is SelectedApp.Installed -> prepareInstalledInputBeforePatching(selected.packageName)
        }
    }

    private suspend fun prepareInstalledInputBeforePatching(
        packageName: String
    ): DownloadResult = withContext(Dispatchers.IO) {
        val packageInfo = pm.getPackageInfo(packageName)
            ?: throw IllegalStateException("Installed package not found: $packageName")
        val appInfo = packageInfo.applicationInfo
            ?: throw IllegalStateException("ApplicationInfo missing for package: $packageName")
        val baseApk = File(
            appInfo.sourceDir
                ?: throw IllegalStateException("sourceDir missing for package: $packageName")
        )
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

        val archiveDir = fs.tempDir
            .resolve("prepatch-installed-splits-${System.currentTimeMillis()}")
            .apply { mkdirs() }
        val archiveFile = archiveDir.resolve("${packageName.replace('.', '_')}.apks")
        try {
            buildInstalledSplitArchive(listOf(baseApk) + splitApks, archiveFile)
            DownloadResult(
                file = archiveFile,
                needsSplit = true,
                cleanup = { archiveDir.deleteRecursively() }
            )
        } catch (error: Throwable) {
            archiveDir.deleteRecursively()
            throw error
        }
    }

    private fun buildInstalledSplitArchive(apkFiles: List<File>, output: File) {
        output.parentFile?.mkdirs()
        val usedNames = LinkedHashSet<String>()
        var writtenEntries = 0
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            apkFiles.forEachIndexed { index, apk ->
                if (!apk.exists()) return@forEachIndexed
                val normalized = apk.name.takeIf { it.endsWith(".apk", ignoreCase = true) }
                    ?: "${apk.name}.apk"
                var entryName = normalized
                var counter = 1
                while (!usedNames.add(entryName)) {
                    entryName = "${normalized.removeSuffix(".apk")}_${index}_${counter++}.apk"
                }
                zip.putNextEntry(ZipEntry(entryName).apply { time = apk.lastModified() })
                apk.inputStream().buffered().use { source -> source.copyTo(zip) }
                zip.closeEntry()
                writtenEntries++
            }
        }
        check(writtenEntries > 0) {
            "Failed to build installed split archive: no APK entries written."
        }
    }

    private fun cleanupPreparedInput() {
        preparedInput?.cleanup?.let { cleanup -> runCatching { cleanup() } }
        preparedInput = null
        preparedInputIncludesDownload = false
        selectedSplitConfiguration = null
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

    data class ActivityPromptDialogState(
        val title: String,
        val requestId: Long
    )

    private data class ActivityPromptRequest(
        val completion: CompletableDeferred<Boolean>,
        val dialogState: ActivityPromptDialogState
    )

    private var nextActivityPromptRequestId = 0L
    private var currentActivityRequest: ActivityPromptRequest? by mutableStateOf(null)
    val activityPromptDialog by derivedStateOf { currentActivityRequest?.dialogState }
    private val activityRequestMutex = Mutex()
    private val progressEventMutex = Mutex()
    private val persistPatchedAppMutex = Mutex()

    private var launchedActivity: CompletableDeferred<ActivityResult>? = null
    private var pendingActivityResumeFallback: Job? = null
    private val launchActivityChannel = Channel<Intent>()
    val launchActivityFlow = launchActivityChannel.receiveAsFlow()

    var installFailureMessage by mutableStateOf<String?>(null)
        private set
    var rootMountRecoveryMessage by mutableStateOf<String?>(null)
        private set
    var fallbackInstallPrompt by mutableStateOf<FallbackInstallPrompt?>(null)
        private set
    private var suppressFailureAfterSuccess = false
    private var lastSuccessInstallType: InstallType? = null
    private var lastSuccessAtMs: Long = 0L

    private fun tokensEqual(a: InstallerManager.Token, b: InstallerManager.Token): Boolean = when {
        a === b -> true
        a is InstallerManager.Token.Component && b is InstallerManager.Token.Component ->
            a.componentName == b.componentName
        else -> false
    }

    private fun recordInstallPlan(
        token: InstallerManager.Token,
        target: InstallerManager.InstallTarget,
        expectedPackage: String?,
        sourceLabel: String?
    ) {
        lastInstallToken = token
        lastInstallTarget = target
        lastInstallExpectedPackage = expectedPackage
        lastInstallSourceLabel = sourceLabel
    }

    private fun recordInstallPlan(
        plan: InstallerManager.InstallPlan,
        expectedPackage: String?,
        sourceLabel: String?
    ) {
        val token = when (plan) {
            is InstallerManager.InstallPlan.Internal -> InstallerManager.Token.Internal
            is InstallerManager.InstallPlan.Mount -> InstallerManager.Token.AutoSaved
            is InstallerManager.InstallPlan.Shizuku -> plan.token
            is InstallerManager.InstallPlan.External -> plan.token
        }
        val target = when (plan) {
            is InstallerManager.InstallPlan.Internal -> plan.target
            is InstallerManager.InstallPlan.Mount -> plan.target
            is InstallerManager.InstallPlan.Shizuku -> plan.target
            is InstallerManager.InstallPlan.External -> plan.target
        }
        val resolvedPackage = expectedPackage
            ?: (plan as? InstallerManager.InstallPlan.External)?.expectedPackage
            ?: lastInstallExpectedPackage
            ?: packageName
        recordInstallPlan(token, target, resolvedPackage, sourceLabel)
    }

    private fun buildFallbackPrompt(message: String): FallbackInstallPrompt? {
        if (prefs.chooseInstallerPerInstall.getBlocking()) return null
        val target = lastInstallTarget ?: return null
        val lastToken = lastInstallToken ?: return null
        val primaryToken = installerManager.getPrimaryToken()
        if (!tokensEqual(primaryToken, lastToken)) return null
        val fallbackToken = installerManager.getFallbackToken()
        if (fallbackToken == InstallerManager.Token.None) return null
        if (!isInstallerTokenAllowed(fallbackToken)) return null
        if (tokensEqual(primaryToken, fallbackToken)) return null
        val fallbackEntry = installerManager.describeEntry(fallbackToken, target) ?: return null
        if (!fallbackEntry.availability.available) return null
        val expectedPackage = lastInstallExpectedPackage ?: packageName
        if (fallbackToken == InstallerManager.Token.AutoSaved && expectedPackage != packageName) {
            return null
        }
        val plan = installerManager.resolvePlanForToken(
            token = fallbackToken,
            target = target,
            sourceFile = outputFile,
            expectedPackage = expectedPackage,
            sourceLabel = lastInstallSourceLabel,
            allowMount = usingMountInstall && expectedPackage == packageName
        ) ?: return null
        if (plan is InstallerManager.InstallPlan.Internal && fallbackToken is InstallerManager.Token.Component) {
            return null
        }
        return FallbackInstallPrompt(
            failureMessage = message,
            fallbackLabel = fallbackEntry.label,
            fallbackToken = fallbackToken,
            target = target
        )
    }

    private fun cleanupFailedInstall() {
        updateInstallingState(false)
        stopInstallProgressToasts()
        pendingExternalInstall?.let(installerManager::cleanup)
        pendingExternalInstall = null
        externalInstallBaseline = null
        externalInstallStartTime = null
        externalPackageWasPresentAtStart = false
        expectedInstallSignature = null
        baselineInstallSignature = null
        packageInstallerStatus = null
    }

    private fun applyInstallFailure(message: String) {
        rootMountRecoveryMessage = null
        installFailureMessage = message
        installStatus = InstallCompletionStatus.Failure(message)
        cleanupFailedInstall()
    }

    private fun showRootMountRecovery(message: String) {
        fallbackInstallPrompt = null
        pendingInstallFailureMessage = null
        installFailureMessage = null
        installStatus = null
        rootMountRecoveryMessage = message
        cleanupFailedInstall()
    }

    private fun showInstallFailure(message: String, allowFallback: Boolean = true) {
        val now = System.currentTimeMillis()
        if (activeInstallType == InstallType.SHIZUKU && suppressFailureAfterSuccess) return
        if (lastSuccessInstallType == InstallType.SHIZUKU && now - lastSuccessAtMs < SUPPRESS_FAILURE_AFTER_SUCCESS_MS) return
        if (lastSuccessInstallType == InstallType.SHIZUKU) return
        if (installStatus is InstallCompletionStatus.Success || suppressFailureAfterSuccess) return
        val adjusted = if (activeInstallType == InstallType.MOUNT) {
            message
                .replace("Failed to install app:", "Failed to mount app:", ignoreCase = true)
                .replace("for install", "for mount", ignoreCase = true)
        } else message
        if (activeInstallType != null) {
            lastInstallType = activeInstallType
        }
        val fallbackPrompt = if (allowFallback) buildFallbackPrompt(adjusted) else null
        if (fallbackPrompt != null) {
            pendingInstallFailureMessage = adjusted
            installFailureMessage = null
            installStatus = null
            fallbackInstallPrompt = fallbackPrompt
            cleanupFailedInstall()
            return
        }
        applyInstallFailure(adjusted)
    }

    private fun showSignatureMismatchPrompt(
        packageName: String,
        plan: InstallerManager.InstallPlan
    ) {
        stopInstallProgressToasts()
        if (isInstalling || installStatus != null) {
            updateInstallingState(false)
        } else {
            installStatus = null
            packageInstallerStatus = null
            installFailureMessage = null
        }
        pendingSignatureMismatchPlan = plan
        pendingSignatureMismatchPackage = packageName
        signatureMismatchPackage = packageName
    }

    private fun scheduleInstallTimeout(
        packageName: String,
        durationMs: Long = SYSTEM_INSTALL_TIMEOUT_MS,
        timeoutMessage: (() -> String)? = null
    ) {
        externalInstallTimeoutJob?.cancel()
        externalInstallTimeoutJob = viewModelScope.launch {
            delay(durationMs)
            if (installStatus is InstallCompletionStatus.InProgress) {
                logger.trace("install timeout for $packageName")
                val baselineSnapshot = internalInstallBaseline ?: externalInstallBaseline
                val startTimeSnapshot = externalInstallStartTime
                val expectedSignatureSnapshot = expectedInstallSignature
                val baselineSignatureSnapshot = baselineInstallSignature
                val packageWasPresentAtStartSnapshot = externalPackageWasPresentAtStart
                val installTypeSnapshot = pendingExternalInstall
                    ?.takeIf { it.expectedPackage == packageName }
                    ?.let { plan ->
                        if (plan.token is InstallerManager.Token.Component) InstallType.CUSTOM else InstallType.DEFAULT
                    }
                    ?: activeInstallType
                    ?: InstallType.DEFAULT
                val customInstallerPackageNameSnapshot = pendingExternalInstall
                    ?.takeIf { it.expectedPackage == packageName }
                    ?.token
                    ?.let { it as? InstallerManager.Token.Component }
                    ?.componentName
                    ?.packageName

                packageInstallerStatus = null
                if (!tryMarkInstallIfPresent(packageName)) {
                    val message = timeoutMessage?.invoke() ?: app.getString(R.string.install_timeout_message)
                    showInstallFailure(message)
                    startPostTimeoutGraceWatch(
                        packageName = packageName,
                        installType = installTypeSnapshot,
                        baseline = baselineSnapshot,
                        startTimeMs = startTimeSnapshot,
                        expectedSignature = expectedSignatureSnapshot,
                        baselineSignature = baselineSignatureSnapshot,
                        packageWasPresentAtStart = packageWasPresentAtStartSnapshot,
                        customInstallerPackageName = customInstallerPackageNameSnapshot
                    )
                }
            }
        }
    }

    private fun startPostTimeoutGraceWatch(
        packageName: String,
        installType: InstallType,
        baseline: Pair<Long?, Long?>?,
        startTimeMs: Long?,
        expectedSignature: ByteArray?,
        baselineSignature: ByteArray?,
        packageWasPresentAtStart: Boolean,
        customInstallerPackageName: String?
    ) {
        postTimeoutGraceJob?.cancel()
        postTimeoutGraceJob = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + POST_TIMEOUT_GRACE_MS
            while (isActive && System.currentTimeMillis() < deadline) {
                val info = pm.getPackageInfo(packageName)
                if (info != null) {
                    val updated = isUpdatedSinceBaseline(info, baseline, startTimeMs)
                    val signatureChangedToExpected = if (expectedSignature != null) {
                        val current = readInstalledSignatureBytes(packageName)
                        current != null &&
                            current.contentEquals(expectedSignature) &&
                            (!packageWasPresentAtStart || baselineSignature != null) &&
                            (baselineSignature == null || !baselineSignature.contentEquals(current))
                    } else {
                        false
                    }

                    if (updated || signatureChangedToExpected) {
                        forceMarkInstallSuccess(
                            packageName,
                            installType,
                            customInstallerPackageName
                        )
                        return@launch
                    }
                }
                delay(INSTALL_MONITOR_POLL_MS)
            }
        }
    }

    private fun monitorExternalInstall(plan: InstallerManager.InstallPlan.External) {
        externalInstallTimeoutJob?.cancel()
        externalInstallTimeoutJob = viewModelScope.launch {
            val timeoutAt = System.currentTimeMillis() + EXTERNAL_INSTALL_TIMEOUT_MS
            while (isActive) {
                if (pendingExternalInstall != plan) return@launch

                val currentInfo = pm.getPackageInfo(plan.expectedPackage)
                if (currentInfo != null) {
                    if (tryHandleExternalInstallSuccess(plan, currentInfo)) {
                        return@launch
                    }
                }

                val remaining = timeoutAt - System.currentTimeMillis()
                if (remaining <= 0L) break
                delay(INSTALL_MONITOR_POLL_MS)
            }

            if (pendingExternalInstall == plan && installStatus is InstallCompletionStatus.InProgress) {
                val info = pm.getPackageInfo(plan.expectedPackage)
                if (info != null && tryHandleExternalInstallSuccess(plan, info)) return@launch
                showInstallFailure(app.getString(R.string.installer_external_timeout, plan.installerLabel))
            }
        }
        startExternalPresenceWatch(plan.expectedPackage)
    }

    private fun isUpdatedSinceBaseline(
        info: PackageInfo,
        baseline: Pair<Long?, Long?>?,
        startTime: Long?
    ): Boolean {
        val vc = pm.getVersionCode(info)
        val updated = info.lastUpdateTime
        val baseVc = baseline?.first
        val baseUpdated = baseline?.second
        val versionChanged = baseVc != null && vc != baseVc
        val timestampChanged = baseUpdated != null && updated > baseUpdated
        val started = startTime ?: 0L
        val updatedSinceStart = updated >= started && started > 0L
        return baseline == null || versionChanged || timestampChanged || updatedSinceStart
    }

    private fun forceMarkInstallSuccess(
        packageName: String,
        installType: InstallType = InstallType.DEFAULT,
        customInstallerPackageName: String? = pendingExternalInstall
            ?.takeIf { it.expectedPackage == packageName }
            ?.token
            ?.let { it as? InstallerManager.Token.Component }
            ?.componentName
            ?.packageName
    ) {
        if (installStatus is InstallCompletionStatus.Success) return
        suppressFailureAfterSuccess = true
        postTimeoutGraceJob?.cancel()
        postTimeoutGraceJob = null
        pendingExternalInstall?.let(installerManager::cleanup)
        pendingExternalInstall = null
        externalInstallTimeoutJob?.cancel()
        externalInstallTimeoutJob = null
        externalInstallBaseline = null
        externalInstallStartTime = null
        externalPackageWasPresentAtStart = false
        expectedInstallSignature = null
        baselineInstallSignature = null
        internalInstallBaseline = null
        installedPackageName = packageName
        installFailureMessage = null
        packageInstallerStatus = null
        markInstallSuccess(packageName)
        updateInstallingState(false)
        stopInstallProgressToasts()
        lastSuccessInstallType = installType
        lastSuccessAtMs = System.currentTimeMillis()
        viewModelScope.launch {
            val persisted = persistPatchedApp(
                packageName,
                installType,
                customInstallerPackageName = customInstallerPackageName
            )
            if (!persisted) {
                Log.w(TAG, "Failed to persist installed patched app metadata (detected)")
            }
        }
    }

    private fun handleDetectedInstall(packageName: String): Boolean {
        val info = pm.getPackageInfo(packageName) ?: return false
        val externalPlan = pendingExternalInstall?.takeIf { it.expectedPackage == packageName }
        val updated =
            if (externalPlan != null) {
                isUpdatedSinceExternalBaseline(info, externalInstallBaseline, externalInstallStartTime)
            } else {
                val baseline = internalInstallBaseline ?: externalInstallBaseline
                isUpdatedSinceBaseline(info, baseline, externalInstallStartTime)
            }
        val signatureChangedToExpected =
            if (externalPlan != null) {
                shouldTreatAsInstalledBySignature(packageName, externalPackageWasPresentAtStart)
            } else {
                false
            }
        if (!updated && !signatureChangedToExpected) return false

        val installType = pendingExternalInstall
            ?.takeIf { it.expectedPackage == packageName }
            ?.let { plan ->
                if (plan.token is InstallerManager.Token.Component) InstallType.CUSTOM else InstallType.DEFAULT
            }
            ?: activeInstallType
            ?: InstallType.DEFAULT

        forceMarkInstallSuccess(packageName, installType)
        return true
    }

    private fun startExternalPresenceWatch(packageName: String) {
        externalInstallPresenceJob?.cancel()
        externalInstallPresenceJob = viewModelScope.launch {
            while (isActive) {
                val plan = pendingExternalInstall ?: return@launch
                if (plan.expectedPackage != packageName) return@launch

                val info = pm.getPackageInfo(packageName)
                if (info != null) {
                    if (tryHandleExternalInstallSuccess(plan, info)) {
                        return@launch
                    }
                }
                delay(INSTALL_MONITOR_POLL_MS)
            }
        }
    }

    private fun shouldTreatAsInstalledBySignature(packageName: String, packageWasPresentAtStart: Boolean): Boolean {
        val expected = expectedInstallSignature ?: return false
        val current = readInstalledSignatureBytes(packageName) ?: return false
        if (!current.contentEquals(expected)) return false
        val baseline = baselineInstallSignature
        if (packageWasPresentAtStart && baseline == null) return false
        return baseline == null || !baseline.contentEquals(current)
    }

    private fun readInstalledSignatureBytes(packageName: String): ByteArray? = runCatching {
        pm.getSignature(packageName).toByteArray()
    }.getOrNull()

    private fun readArchiveSignatureBytes(file: File): ByteArray? = runCatching {
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        val pkgInfo = app.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: return null

        val signature: Signature? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.signingInfo?.apkContentsSigners?.firstOrNull()
                    ?: pkgInfo.signatures?.firstOrNull()
            } else {
                pkgInfo.signatures?.firstOrNull()
            }

        signature?.toByteArray()
    }.getOrNull()

    private fun hasSignatureMismatch(packageName: String, file: File): Boolean {
        val installed = readInstalledSignatureBytes(packageName) ?: return false
        val expected = readArchiveSignatureBytes(file) ?: return false
        return !installed.contentEquals(expected)
    }
    private fun tryMarkInstallIfPresent(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val externalPlan = pendingExternalInstall?.takeIf { it.expectedPackage == packageName }
        val info = if (externalPlan != null) pm.getPackageInfo(packageName) else null
        if (externalPlan != null && info != null) {
            return tryHandleExternalInstallSuccess(externalPlan, info)
        }
        return handleDetectedInstall(packageName)
    }

    private fun isUpdatedSinceExternalBaseline(
        info: PackageInfo,
        baseline: Pair<Long?, Long?>?,
        startTime: Long?
    ): Boolean {
        val vc = pm.getVersionCode(info)
        val updated = info.lastUpdateTime
        val baseVc = baseline?.first
        val baseUpdated = baseline?.second
        val versionChanged = baseVc != null && vc != baseVc
        val timestampChanged = baseUpdated != null && updated > baseUpdated
        val started = startTime ?: 0L
        val updatedSinceStart = updated >= started && started > 0L
        return versionChanged || timestampChanged || updatedSinceStart
    }

    private fun tryHandleExternalInstallSuccess(
        plan: InstallerManager.InstallPlan.External,
        info: PackageInfo
    ): Boolean {
        if (pendingExternalInstall != plan) return false
        val updatedSinceStart = isUpdatedSinceExternalBaseline(info, externalInstallBaseline, externalInstallStartTime)
        val signatureChangedToExpected =
            shouldTreatAsInstalledBySignature(plan.expectedPackage, externalPackageWasPresentAtStart)
        if (updatedSinceStart || signatureChangedToExpected) {
            handleExternalInstallSuccess(plan.expectedPackage)
            return true
        }
        return false
    }

    private fun startInstallProgressToasts() {
        if (installProgressToastJob?.isActive == true) return
        installProgressToastJob = viewModelScope.launch {
            while (isActive) {
                val messageRes =
                    if (activeInstallType == InstallType.MOUNT) R.string.mounting_ellipsis
                    else R.string.installing_ellipsis
                installProgressToast?.cancel()
                installProgressToast = app.toastHandle(app.getString(messageRes))
                delay(INSTALL_PROGRESS_TOAST_INTERVAL_MS)
            }
        }
    }

    private fun enableInstallProgressToasts() {
        if (!deferInstallProgressToasts) return
        deferInstallProgressToasts = false
        if (isInstalling) {
            startInstallProgressToasts()
        }
    }

    private fun stopInstallProgressToasts() {
        installProgressToastJob?.cancel()
        installProgressToastJob = null
        installProgressToast?.cancel()
        installProgressToast = null
    }

    private fun startUninstallProgressToasts() {
        if (deferUninstallProgressToasts) return
        if (uninstallProgressToastJob?.isActive == true) return
        uninstallProgressToastJob = viewModelScope.launch {
            while (isActive) {
                uninstallProgressToast?.cancel()
                uninstallProgressToast = app.toastHandle(app.getString(R.string.uninstalling_ellipsis))
                delay(INSTALL_PROGRESS_TOAST_INTERVAL_MS)
            }
        }
    }

    private fun stopUninstallProgressToasts() {
        uninstallProgressToastJob?.cancel()
        uninstallProgressToastJob = null
        uninstallProgressToast?.cancel()
        uninstallProgressToast = null
        deferUninstallProgressToasts = false
    }

    private fun enableUninstallProgressToasts() {
        if (!deferUninstallProgressToasts) return
        deferUninstallProgressToasts = false
        startUninstallProgressToasts()
    }

    private fun launchUninstallConfirmationToast(session: Session<*>): Job =
        viewModelScope.launch {
            if (session.awaitUserConfirmation()) {
                enableUninstallProgressToasts()
            }
        }

    fun suppressInstallProgressToasts() = stopInstallProgressToasts()

    private val tempDir = savedStateHandle.saveable(key = "tempDir") {
        fs.uiTempDir.resolve("installer").also {
            it.deleteRecursively()
            it.mkdirs()
        }
    }

    private var inputFile: File? by savedStateHandle.saveableVar()
    private var requiresSplitPreparation by savedStateHandle.saveableVar {
        initialSplitRequirement(input.selectedApp)
    }
    private val outputFile = tempDir.resolve("output.apk")

    private val logs by savedStateHandle.saveable<MutableList<Pair<LogLevel, String>>> { mutableListOf() }
    var patcherSessionInfo by mutableStateOf(
        parsePatcherSessionInfo(logs.map { (_, message) -> message })
    )
        private set
    var selectedPatchBundleLabels by mutableStateOf<List<String>>(emptyList())
        private set
    var fallbackPatcherEngine by mutableStateOf<String?>(null)
        private set
    private var droppedLogLineCount by savedStateHandle.saveableVar { 0 }
    private var runtimeReportedMemoryLimitMb: Int? by savedStateHandle.saveableVar()
    private var lastPatchFailure: RemoteError? by savedStateHandle.saveableVar()
    private var lastPatchFailureStep: String? by savedStateHandle.saveableVar()
    private val dexCompilePattern =
        Regex("(Compiling|Compiled)\\s+(classes\\d*\\.dex)", RegexOption.IGNORE_CASE)
    private val dexWritePattern =
        Regex("Write\\s+\\[[^\\]]+\\]\\s+(classes\\d*\\.dex)", RegexOption.IGNORE_CASE)
    private val morpheProcessingClassesPattern =
        Regex("Processing\\s+(\\d+)\\s+classes\\s+in\\s+parallel", RegexOption.IGNORE_CASE)
    private val morpheWroteDexFilesPattern =
        Regex("Wrote\\s+(\\d+)\\s+dex\\s+files\\b", RegexOption.IGNORE_CASE)
    private val morpheStrippedDexPattern =
        Regex(
            "Stripped\\s+\\d+\\s+class_def\\s+entries\\s+from\\s+(classes\\d*\\.dex)",
            RegexOption.IGNORE_CASE
        )
    private fun parseMemoryLimitMb(raw: String?): Int? {
        val value = raw?.trim() ?: return null
        val match = Regex("""(\d+)\s*(?:m|mb|mib)?""", RegexOption.IGNORE_CASE)
            .find(value)
            ?: return null

        return match.groupValues.getOrNull(1)?.toIntOrNull()
    }

    private fun appendBoundedLog(level: LogLevel, message: String) {
        val boundedMessage = if (message.length > PATCHER_LOG_MESSAGE_CHAR_LIMIT) {
            buildString(PATCHER_LOG_MESSAGE_CHAR_LIMIT + 96) {
                append(message.take(PATCHER_LOG_MESSAGE_CHAR_LIMIT))
                append("\n[log message truncated to ")
                append(PATCHER_LOG_MESSAGE_CHAR_LIMIT)
                append(" characters]")
            }
        } else {
            message
        }

        if (logs.size >= PATCHER_LOG_ENTRY_HARD_LIMIT) {
            val trimCount = (logs.size - PATCHER_LOG_ENTRY_SOFT_LIMIT + 1).coerceAtLeast(1)
            val safeTrimCount = trimCount.coerceAtMost(logs.size)
            logs.subList(0, safeTrimCount).clear()
            droppedLogLineCount += safeTrimCount
        }

        logs.add(level to boundedMessage)
    }

    private val logger = object : Logger() {
        override fun log(level: LogLevel, message: String) {
            level.androidLog(message)
            if (level == LogLevel.TRACE) return
            if (message.startsWith("Memory limit:")) {
                parseMemoryLimitMb(
                    message.removePrefix("Memory limit:").trim()
                )?.let { runtimeReportedMemoryLimitMb = it }
            }

            viewModelScope.launch {
                patcherSessionInfo = patcherSessionInfo.updatedFromLog(message)
                if (!isVerbosePatcherExportLog(level, message)) {
                    appendBoundedLog(level, message)
                }
                if (_isPatchingActive.value != true) {
                    handleDexCompileLine(message)
                }
            }
        }
    }

    data class MissingPatchWarningState(
        val patchNames: List<String>
    )
var missingPatchWarning by mutableStateOf<MissingPatchWarningState?>(null)
    private set

    private suspend fun gatherScopedBundles(): Map<Int, PatchBundleInfo.Scoped> =
        patchBundleRepository.scopedBundleInfoFlow(
            packageName,
            input.selectedApp.version,
            input.selectedApp.versionCode
        ).first().associateBy { it.uid }

    private suspend fun refreshPatcherInformationMetadata(
        scopedBundles: Map<Int, PatchBundleInfo.Scoped>
    ) {
        val activeSelection = appliedSelection.filterValues { patches -> patches.isNotEmpty() }
        selectedPatchBundleLabels = activeSelection.keys.mapNotNull { uid ->
            scopedBundles[uid]?.let { bundle ->
                bundle.version
                    ?.takeIf(String::isNotBlank)
                    ?.let { version -> "${bundle.name} $version" }
                    ?: bundle.name.takeIf(String::isNotBlank)
            }
        }
        val bundleType = patchBundleRepository.selectionBundleType(activeSelection)
        if (
            bundleType == PatchBundleType.REVANCED &&
            patchBundleRepository.selectionHasMixedRevancedPatcherVersions(activeSelection)
        ) {
            fallbackPatcherEngine = null
            return
        }
        val usesRevancedPatcher22 = bundleType == PatchBundleType.REVANCED &&
            patchBundleRepository.selectionUsesRevancedPatcher22(activeSelection)
        fallbackPatcherEngine = patcherEngineDisplayName(
            bundleType,
            usesRevancedPatcher22
        )
    }

    private suspend fun collectSelectedBundleMetadata(): List<PatchBundleExportData> {
        val globalBundles = patchBundleRepository.bundleInfoFlow.first()
        val scopedBundles = gatherScopedBundles()
        val sanitizedSelection = sanitizeSelection(appliedSelection, scopedBundles)
        val displayNames = patchBundleRepository.sources.first().associate { it.uid to it.displayTitle }
        return sanitizedSelection.keys.mapNotNull { uid ->
            val scoped = scopedBundles[uid]
            val global = globalBundles[uid]
            val displayName = displayNames[uid]?.takeIf { it.isNotBlank() }
                ?: scoped?.name?.takeIf { it.isNotBlank() }
                ?: global?.name?.takeIf { it.isNotBlank() }
            val version = global?.version?.takeIf { it.isNotBlank() }
                ?: scoped?.version?.takeIf { it.isNotBlank() }
            if (displayName == null && version == null) {
                null
            } else {
                PatchBundleExportData(name = displayName, version = version)
            }
        }
    }

    private suspend fun collectSelectedPatchDescriptions(): List<String> {
        val globalBundles = patchBundleRepository.bundleInfoFlow.first()
        val scopedBundles = gatherScopedBundles()
        val sanitizedSelection = sanitizeSelection(appliedSelection, scopedBundles)
        val displayNames = patchBundleRepository.sources.first().associate { it.uid to it.displayTitle }
        return sanitizedSelection.entries.flatMap { (uid, patchNames) ->
            val bundleName = displayNames[uid]
                ?: scopedBundles[uid]?.name
                ?: globalBundles[uid]?.name
                ?: "Unknown bundle"
            patchNames.sorted().map { patchName -> "$patchName - $bundleName" }
        }
    }

    private fun resolveDeviceName(): String {
        val marketName = sequenceOf(
            "ro.product.marketname",
            "ro.product.odm.marketname",
            "ro.product.vendor.marketname",
            "ro.config.marketing_name",
            "ro.vendor.product.display"
        ).mapNotNull(::readSystemProperty)
            .firstOrNull()
        return marketName ?: formatDeviceName(Build.MANUFACTURER, Build.MODEL)
    }

    private fun readSystemProperty(key: String): String? = runCatching {
        val systemPropertiesClass = Class.forName("android.os.SystemProperties")
        val getMethod = systemPropertiesClass.getMethod("get", String::class.java)
        (getMethod.invoke(null, key) as? String)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun formatDeviceName(manufacturer: String?, model: String?): String {
        val manufacturerValue = manufacturer?.trim().orEmpty()
        val modelValue = model?.trim().orEmpty()
        if (manufacturerValue.isEmpty() && modelValue.isEmpty()) return "unknown"
        if (manufacturerValue.isEmpty()) return modelValue
        if (modelValue.isEmpty()) return manufacturerValue
        return if (modelValue.startsWith(manufacturerValue, ignoreCase = true)) {
            modelValue
        } else {
            "${manufacturerValue.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }} $modelValue"
        }
    }

    private suspend fun buildExportMetadata(packageInfo: PackageInfo?): PatchedAppExportData? {
        val info = packageInfo ?: pm.getPackageInfo(outputFile) ?: return null
        val patchBundles = collectSelectedBundleMetadata()
        val label = runCatching { with(pm) { info.label() } }.getOrNull()
        val versionName = info.versionName?.takeUnless { it.isBlank() } ?: version ?: "unspecified"
        return PatchedAppExportData(
            appName = label,
            packageName = info.packageName,
            appVersion = versionName,
            patchBundles = patchBundles
        )
    }

    private fun refreshExportMetadata() {
        viewModelScope.launch(Dispatchers.IO) {
            val metadata = buildExportMetadata(null)
            withContext(Dispatchers.Main) {
                exportMetadata = metadata
            }
        }
    }

    private suspend fun ensureExportMetadata() {
        if (exportMetadata != null) return
        val metadata = buildExportMetadata(null) ?: return
        withContext(Dispatchers.Main) {
            exportMetadata = metadata
        }
    }
        val steps by savedStateHandle.saveable(saver = snapshotStateListSaver()) {
            generateSteps(
                app,
                input.selectedApp,
                input.selectedPatches,
                requiresSplitPreparation,
                skipApkSigning
            ).toMutableStateList()
        }
    val stepSubSteps = mutableStateMapOf<StepId, SnapshotStateList<StepDetail>>()
    private var dexSubStepsReady = false
    private val pendingDexCompileLines = mutableListOf<String>()
    private var writeApkStepStarted = false
    private var deferLoadPatchesUntilSplitComplete = false
    private val deferredLoadPatchesEvents = mutableListOf<ProgressEvent>()
    private var deferredLoadPatchesStepSnapshot: Step? = null

    var progress by mutableFloatStateOf(0f)
        private set
    val patcherMemoryUsageSamples = mutableStateListOf<PatcherMemoryUsage>()

    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/blob/a2c3d31bd7ab42e6bc4b9dd528ed856fc72fb948/app/src/main/java/app/morphe/manager/ui/viewmodel/PatcherViewModel.kt
    data class MemoryAdjustmentDialogState(
        val previousLimit: Int,
        val newLimit: Int,
        val adjusted: Boolean
    )

    var memoryAdjustmentDialog by mutableStateOf<MemoryAdjustmentDialogState?>(null)
        private set

    fun dismissMemoryAdjustmentDialog() {
        memoryAdjustmentDialog = null
    }

    private val workManager = WorkManager.getInstance(app)
    private val _patcherSucceeded = MediatorLiveData<Boolean?>()
    val patcherSucceeded: LiveData<Boolean?> get() = _patcherSucceeded
    private val _isPatchingActive = MediatorLiveData<Boolean>().apply { value = patcherWorkerId?.uuid != null }
    val isPatchingActive: LiveData<Boolean> get() = _isPatchingActive
    private var currentWorkSource: LiveData<WorkInfo?>? = null
    private val handledFailureIds = mutableSetOf<UUID>()
    private var replayWorkerProgressSnapshots = false
    private var lastAppliedWorkerProgressGeneration = Long.MIN_VALUE
    private var lastAppliedWorkerProgressSequence = Long.MIN_VALUE
    private var patcherMemoryUsageGeneration = -1L
    private var patcherMemoryUsageSequence = Long.MIN_VALUE
    private var patcherMemoryUsageSampleTimeMs = Long.MIN_VALUE
    private var forceKeepLocalInput = false
    private var lastLoggedErrorSignature: String? = null

    private var patcherWorkerId: ParcelUuid?
        get() = savedStateHandle.get("patcher_worker_id")
        set(value) {
            if (value == null) {
                savedStateHandle.remove<ParcelUuid>("patcher_worker_id")
            } else {
                savedStateHandle["patcher_worker_id"] = value
            }
        }

    init {
        viewModelScope.launch {
            var observedResumeGeneration = AppForeground.resumeGeneration
            while (true) {
                observedResumeGeneration = AppForeground.awaitNextResume(observedResumeGeneration)
                syncWorkerProgressFromCurrentSnapshot()
            }
        }
        val existingId = patcherWorkerId?.uuid
        if (existingId != null) {
            viewModelScope.launch {
                refreshPatcherInformationMetadata(gatherScopedBundles())
            }
            replayWorkerProgressSnapshots = true
            startPatchingTaskMonitor()
            observeWorker(existingId)
        } else {
            viewModelScope.launch {
                runPreflightCheck()
            }
        }
    }

    private suspend fun runPreflightCheck() {
        val scopedBundles = gatherScopedBundles()
        val sanitizedSelection = sanitizeSelection(appliedSelection, scopedBundles)
        val missing = mutableListOf<String>()
        appliedSelection.forEach { (uid, patches) ->
            val kept = sanitizedSelection[uid] ?: emptySet()
            patches.filterNot { it in kept }.forEach { missing += it }
        }
        appliedSelection = applyCurrentPatchRules(sanitizedSelection, scopedBundles)
        refreshPatcherInformationMetadata(scopedBundles)
        if (missing.isNotEmpty()) {
            missingPatchWarning = MissingPatchWarningState(
                patchNames = missing.distinct().sorted()
            )
        } else {
            beginPrePatchFlow()
        }
    }

    private fun logBatteryOptimizationStatus() {
        val isIgnoring = app.getSystemService<PowerManager>()
            ?.isIgnoringBatteryOptimizations(app.packageName) == true
        val state = if (isIgnoring) "disabled" else "enabled"
        logger.info("Battery optimization: $state")
    }

    private fun startWorker() {
        if (workerLaunchJob?.isActive == true || _isPatchingActive.value == true) return
        workerLaunchJob = viewModelScope.launch {
            try {
                startWorkerNow()
            } finally {
                workerLaunchJob = null
            }
        }
    }

    private suspend fun startWorkerNow() {
        resetDexCompileState()
        resetFailureLogState()
        patcherMemoryUsageGeneration = -1L
        patcherMemoryUsageSequence = Long.MIN_VALUE
        patcherMemoryUsageSampleTimeMs = Long.MIN_VALUE
        patcherMemoryUsageSamples.clear()
        val configuredProcessMemoryLimit = MemoryLimitConfig.resolveMemoryLimitMb(
            app,
            prefs.processMemoryLimit.get()
        )
        patcherSessionInfo = PatcherSessionInfo(
            runtimeProcess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
            memoryLimitMb = configuredProcessMemoryLimit,
            nativeLibsStripped = selectedSplitConfiguration?.stripNativeLibs
                ?: prefs.stripUnusedNativeLibs.get()
        )
        runtimeReportedMemoryLimitMb = null
        replayWorkerProgressSnapshots = false
        lastAppliedWorkerProgressGeneration = Long.MIN_VALUE
        lastAppliedWorkerProgressSequence = Long.MIN_VALUE
        resetVisualProgress()
        if (preparedInputIncludesDownload) {
            val downloadIndex = steps.indexOfFirst { it.id == StepId.DownloadAPK }
            if (downloadIndex >= 0) {
                steps[downloadIndex] = steps[downloadIndex].withState(
                    state = State.COMPLETED,
                    progress = null
                )
            }
        }
        markInitialStepRunning()
        _isPatchingActive.value = true
        startPatchingTaskMonitor()
        logBatteryOptimizationStatus()
        val workId = try {
            launchWorker()
        } catch (_: UniqueWorkAlreadyRunningException) {
            stopPatchingTaskMonitor()
            _isPatchingActive.value = false
            reconcileFailureState(app.getString(R.string.patcher_already_running))
            _patcherSucceeded.value = false
            return
        } catch (error: Exception) {
            stopPatchingTaskMonitor()
            _isPatchingActive.value = false
            reconcileFailureState(
                error.simpleMessage() ?: app.getString(R.string.patcher_launch_failed)
            )
            _patcherSucceeded.value = false
            return
        }
        patcherWorkerId = ParcelUuid(workId)
        PatcherWorker.showInitialNotification(app)
        observeWorker(workId)
    }

    private fun clearPatchingNotification() {
        PatcherWorker.clearNotification(app)
    }

    private fun startPatchingTaskMonitor() {
        runCatching {
            PatchingTaskMonitorService.start(app)
        }.onFailure { error ->
            Log.d(TAG, "Failed to start patching task monitor", error)
        }
    }

    private fun stopPatchingTaskMonitor() {
        runCatching {
            PatchingTaskMonitorService.stop(app)
        }.onFailure { error ->
            Log.d(TAG, "Failed to stop patching task monitor", error)
        }
    }

    private fun hasTemporaryLocalInput() =
        input.selectedApp is SelectedApp.Local && input.selectedApp.temporary

    private fun originalTemporaryLocalInputFile(): File? =
        (input.selectedApp as? SelectedApp.Local)?.takeIf { it.temporary }?.file

    private fun redundantTemporaryLocalInputSourceFile(): File? {
        val original = originalTemporaryLocalInputFile() ?: return null
        val preserved = inputFile
        return original.takeUnless { preserved?.absolutePath == original.absolutePath }
    }

    private fun clearTemporaryLocalInputState() {
        inputFile = null
    }

    private fun deleteTemporaryLocalInput(file: File?) {
        file?.takeIf { it.exists() }?.delete()
    }

    private fun cleanupTemporaryLocalInput() {
        if (!hasTemporaryLocalInput()) return
        val preservedFileToDelete = inputFile
        val originalFileToDelete = redundantTemporaryLocalInputSourceFile()
        clearTemporaryLocalInputState()
        deleteTemporaryLocalInput(preservedFileToDelete)
        deleteTemporaryLocalInput(originalFileToDelete)
    }

    private suspend fun awaitWorkToFinish(workId: UUID) = suspendCancellableCoroutine<Unit> { continuation ->
        val source = workManager.getWorkInfoByIdLiveData(workId)
        val observer = object : Observer<WorkInfo?> {
            override fun onChanged(workInfo: WorkInfo?) {
                if (workInfo != null && !workInfo.state.isFinished) return
                source.removeObserver(this)
                if (continuation.isActive) {
                    continuation.resume(Unit)
                }
            }
        }
        source.observeForever(observer)
        continuation.invokeOnCancellation { source.removeObserver(observer) }
    }

    private fun cleanupTemporaryLocalInputAfterWorkStops(workId: UUID?) {
        if (!hasTemporaryLocalInput()) return
        val preservedFileToDelete = inputFile
        val originalFileToDelete = redundantTemporaryLocalInputSourceFile()
        if (preservedFileToDelete == null && originalFileToDelete == null) return
        clearTemporaryLocalInputState()
        CoroutineScope(Dispatchers.IO).launch {
            workId?.let { activeWorkId ->
                withContext(Dispatchers.Main.immediate) {
                    awaitWorkToFinish(activeWorkId)
                }
            }
            deleteTemporaryLocalInput(preservedFileToDelete)
            deleteTemporaryLocalInput(originalFileToDelete)
        }
    }

    private suspend fun persistPatchedApp(
        currentPackageName: String?,
        installType: InstallType,
        forceSave: Boolean = false,
        customInstallerPackageName: String? = null
    ): Boolean = persistPatchedAppMutex.withLock {
        val savedAppsEnabled = prefs.enableSavedApps.get()
        val disableSavedAppOverwrite =
            savedAppsEnabled && prefs.disableSavedAppOverwrite.get()
        val latestInstalledApp = sourceInstalledApp()
        if (latestInstalledApp != installedApp) {
            installedApp = latestInstalledApp
        }
        val shouldSaveForLater = savedAppsEnabled || forceSave
        withContext(Dispatchers.IO) {
            val installedPackageInfo = currentPackageName?.let(pm::getPackageInfo)
            val patchedPackageInfo = pm.getPackageInfo(outputFile)
            val packageInfo = installedPackageInfo ?: patchedPackageInfo
            if (packageInfo == null) {
                Log.e(TAG, "Failed to resolve package info for patched APK")
                return@withContext false
            }

            val finalPackageName = packageInfo.packageName
            val finalVersion = packageInfo.versionName?.takeUnless { it.isBlank() } ?: version ?: "unspecified"

            val metadata = buildExportMetadata(patchedPackageInfo ?: packageInfo)
            withContext(Dispatchers.Main) {
                exportMetadata = metadata
            }

            val globalBundlesFinal = patchBundleRepository.allBundlesInfoFlow.first()
            val seenPatchesByBundle = globalBundlesFinal.mapValues { (_, bundle) ->
                bundle.patches.map { it.name }.toSet()
            }
            val sanitizedSelectionFinal = sanitizeSelection(appliedSelection, globalBundlesFinal)
            val sanitizedOptionsFinal = sanitizeOptions(appliedOptions, globalBundlesFinal)
            val sanitizedSelectionOriginal = sanitizeSelection(appliedSelection, globalBundlesFinal)
            val sanitizedOptionsOriginal = sanitizeOptions(appliedOptions, globalBundlesFinal)

            val selectionPayload = patchBundleRepository.snapshotSelection(
                sanitizedSelectionFinal,
                sanitizedOptionsFinal
            )

            val newVariantIdentity = buildSavedAppVariantIdentity(
                appVersion = finalVersion,
                selectionPayload = selectionPayload,
                patchSelection = sanitizedSelectionFinal
            )
            val savedEntriesForPackage = installedAppRepository.getByInstallType(InstallType.SAVED)
                .filter { savedApp ->
                    isSavedAppEntryForPackage(savedApp.currentPackageName, finalPackageName)
                }
            val sourceSavedEntry = installedApp?.takeIf { sourceEntry ->
                sourceEntry.installType == InstallType.SAVED &&
                    (
                        isSavedAppEntryForPackage(
                            sourceEntry.currentPackageName,
                            finalPackageName
                        ) ||
                            sourceEntry.originalPackageName == packageName ||
                            sourceEntry.originalPackageName == finalPackageName
                    )
            }
            val replacementSourceSavedEntry = sourceSavedEntry
                ?.takeUnless { disableSavedAppOverwrite }
            val savedEntryIdentities = mutableMapOf<String, String>()
            savedEntriesForPackage.forEach { savedApp ->
                savedEntryIdentities[savedApp.currentPackageName] = savedEntryIdentity(savedApp)
            }
            val matchingSavedEntries = savedEntriesForPackage.filter { savedApp ->
                savedEntryIdentities[savedApp.currentPackageName] == newVariantIdentity
            }
            val matchingSavedEntry = if (disableSavedAppOverwrite) {
                null
            } else {
                replacementSourceSavedEntry ?: matchingSavedEntries.firstOrNull()
            }
            val persistedInstallType = installType
            val shouldArchiveExistingVisibleEntry = persistedInstallType != InstallType.SAVED
            val existingFinalPackageEntry = installedAppRepository.get(finalPackageName)
            val existingInstalledEntry = existingFinalPackageEntry?.takeIf {
                it.installType != InstallType.SAVED
            }
            val existingSavedEntryAtBaseKey = existingFinalPackageEntry?.takeIf {
                it.installType == InstallType.SAVED
            }
            val effectiveShouldSaveForLater = shouldSaveForLater
            val existingInstalledIdentity = existingInstalledEntry?.let { savedEntryIdentity(it) }
            val existingSavedEntryIdentity = existingSavedEntryAtBaseKey?.let { savedEntryIdentity(it) }
            val savedVariantAlreadyInstalled =
                persistedInstallType == InstallType.SAVED &&
                    !disableSavedAppOverwrite &&
                    existingInstalledEntry != null &&
                    existingInstalledIdentity == newVariantIdentity
            val pendingHistoricalEntry: PendingHistoricalSavedEntry? = when {
                disableSavedAppOverwrite &&
                    effectiveShouldSaveForLater &&
                    persistedInstallType != InstallType.SAVED &&
                    shouldArchiveExistingVisibleEntry &&
                    existingInstalledEntry != null &&
                    existingInstalledIdentity != null &&
                    existingInstalledIdentity != newVariantIdentity &&
                    existingInstalledIdentity !in savedEntryIdentities.values ->
                    installedAppRepository.prepareHistoricalSavedEntry(
                        sourceApp = existingInstalledEntry,
                        targetPackageName = buildSavedAppEntryKey(
                            finalPackageName,
                            existingInstalledIdentity
                        )
                    )
                disableSavedAppOverwrite &&
                    effectiveShouldSaveForLater &&
                    persistedInstallType != InstallType.SAVED &&
                    shouldArchiveExistingVisibleEntry &&
                    existingSavedEntryAtBaseKey != null &&
                    existingSavedEntryIdentity != null &&
                    existingSavedEntryIdentity != newVariantIdentity &&
                    existingSavedEntryIdentity !in savedEntryIdentities
                        .filterKeys { it != existingSavedEntryAtBaseKey.currentPackageName }
                        .values ->
                    installedAppRepository.prepareHistoricalSavedEntry(
                        sourceApp = existingSavedEntryAtBaseKey,
                        targetPackageName = buildSavedAppEntryKey(
                            finalPackageName,
                            existingSavedEntryIdentity
                        )
                    )
                else -> null
            }
            val persistedPackageName = if (persistedInstallType == InstallType.SAVED) {
                if (savedVariantAlreadyInstalled) {
                    finalPackageName
                } else if (matchingSavedEntry != null) {
                    matchingSavedEntry.currentPackageName
                } else if (disableSavedAppOverwrite) {
                    buildUniqueSavedAppEntryKey(finalPackageName, newVariantIdentity)
                } else {
                    val canUseBaseKey = savedEntriesForPackage.isEmpty() &&
                        (existingFinalPackageEntry == null || existingFinalPackageEntry.installType == InstallType.SAVED)
                    if (canUseBaseKey) finalPackageName
                    else buildSavedAppEntryKey(finalPackageName, newVariantIdentity)
                }
            } else {
                finalPackageName
            }

            val savedCopyPackageName = if (persistedInstallType == InstallType.SAVED) {
                persistedPackageName
            } else {
                finalPackageName
            }
            val savedCopy = fs.getPatchedAppFile(savedCopyPackageName, finalVersion)
            var savedCopyReplacement: PendingPatchedAppReplacement? = null
            val savedCopyWritten = if (effectiveShouldSaveForLater) {
                try {
                    savedCopyReplacement = PendingPatchedAppReplacement.prepare(
                        source = outputFile,
                        target = savedCopy
                    )
                    true
                } catch (error: Exception) {
                    pendingHistoricalEntry?.discard()
                    Log.e(
                        TAG,
                        "Failed to prepare saved APK copy for $savedCopyPackageName",
                        error
                    )
                    return@withContext false
                }
            } else {
                false
            }

            val persistReplacement: suspend () -> Unit = {
                when {
                    persistedInstallType != InstallType.SAVED ->
                        installedAppRepository.addOrUpdate(
                            persistedPackageName,
                            packageName,
                            finalVersion,
                            persistedInstallType,
                            sanitizedSelectionFinal,
                            selectionPayload,
                            resetCreatedAt = true,
                            customInstallerPackageName = customInstallerPackageName
                        )
                    effectiveShouldSaveForLater &&
                        savedCopyWritten &&
                        !savedVariantAlreadyInstalled ->
                        installedAppRepository.addOrUpdate(
                            persistedPackageName,
                            packageName,
                            finalVersion,
                            InstallType.SAVED,
                            sanitizedSelectionFinal,
                            selectionPayload,
                            resetCreatedAt = true
                        )
                }
            }
            try {
                if (pendingHistoricalEntry != null) {
                    pendingHistoricalEntry.commitWith(
                        persistedPackageName,
                        persistReplacement
                    )
                } else {
                    persistReplacement()
                }
            } catch (error: Throwable) {
                savedCopyReplacement?.rollback(error)
                throw error
            }
            savedCopyReplacement?.commit()

            runPostCommitPersistenceStep(
                "Failed to clean up saved app references after persistence"
            ) {
                if (persistedInstallType != InstallType.SAVED) {
                    sourceSavedEntry?.takeIf {
                        it.currentPackageName != persistedPackageName
                    }?.let { sourceEntry ->
                        installedAppRepository.migrateAutoPatchTarget(
                            sourceEntry.currentPackageName,
                            persistedPackageName
                        )
                    }
                    replacementSourceSavedEntry?.let { sourceEntry ->
                        if (sourceEntry.currentPackageName != persistedPackageName) {
                            installedAppRepository.delete(sourceEntry)
                        }
                        if (
                            sourceEntry.currentPackageName != persistedPackageName ||
                            sourceEntry.version != finalVersion
                        ) {
                            fs.getPatchedAppFile(
                                sourceEntry.currentPackageName,
                                sourceEntry.version
                            ).takeIf { oldFile ->
                                oldFile.exists() &&
                                    !oldFile.absolutePath.equals(
                                        savedCopy.absolutePath,
                                        ignoreCase = true
                                    )
                            }?.delete()
                        }
                    }
                    collapseMatchingSavedEntriesForInstalledVariant(
                        packageName = finalPackageName,
                        installedPackageName = persistedPackageName,
                        variantIdentity = newVariantIdentity
                    )
                }
                if (
                    effectiveShouldSaveForLater &&
                    savedCopyWritten &&
                    persistedInstallType == InstallType.SAVED
                ) {
                    sourceSavedEntry?.takeIf {
                        it.currentPackageName != persistedPackageName
                    }?.let { sourceEntry ->
                        installedAppRepository.migrateAutoPatchTarget(
                            sourceEntry.currentPackageName,
                            persistedPackageName
                        )
                    }
                    replacementSourceSavedEntry?.let { sourceEntry ->
                        if (
                            sourceEntry.currentPackageName == persistedPackageName &&
                            sourceEntry.version != finalVersion
                        ) {
                            fs.getPatchedAppFile(
                                sourceEntry.currentPackageName,
                                sourceEntry.version
                            ).takeIf { oldFile ->
                                oldFile.exists() &&
                                    !oldFile.absolutePath.equals(
                                        savedCopy.absolutePath,
                                        ignoreCase = true
                                    )
                            }?.delete()
                        }
                    }
                    if (!disableSavedAppOverwrite) {
                        collapseMatchingSavedEntriesForInstalledVariant(
                            packageName = finalPackageName,
                            installedPackageName = persistedPackageName,
                            variantIdentity = newVariantIdentity
                        )
                    }
                }
            }

            runPostCommitPersistenceStep(
                "Failed to update patch configuration after persistence"
            ) {
                if (finalPackageName != packageName) {
                    patchSelectionRepository.updateSelectionWithSeenPatches(
                        finalPackageName,
                        sanitizedSelectionFinal,
                        seenPatchesByBundle
                    )
                    patchOptionsRepository.saveOptions(
                        finalPackageName,
                        sanitizedOptionsFinal
                    )
                }
                patchSelectionRepository.updateSelectionWithSeenPatches(
                    packageName,
                    sanitizedSelectionOriginal,
                    seenPatchesByBundle
                )
                patchOptionsRepository.saveOptions(packageName, sanitizedOptionsOriginal)
                appliedSelection = sanitizedSelectionOriginal
                appliedOptions = sanitizedOptionsOriginal
            }

            runPostCommitPersistenceStep(
                "Failed to prune saved APK files after persistence"
            ) {
                pruneUnreferencedPatchedAppFiles()
            }

            savedPatchedApp = savedPatchedApp ||
                (effectiveShouldSaveForLater && (savedCopyWritten || savedCopy.exists()))
            true
        }
    }

    private suspend fun runPostCommitPersistenceStep(
        description: String,
        block: suspend () -> Unit
    ) = withContext(NonCancellable) {
        try {
            block()
        } catch (error: Exception) {
            Log.w(TAG, description, error)
        }
    }

    fun savePatchedAppForLater(
        onResult: (Boolean) -> Unit = {},
        showToast: Boolean = true
    ) {
        if (!outputFile.exists()) {
            app.toast(app.getString(R.string.patched_app_save_failed_toast))
            onResult(false)
            return
        }

        viewModelScope.launch {
            val success = persistPatchedApp(null, InstallType.SAVED, forceSave = true)
            if (success) {
                if (showToast) {
                    app.toast(app.getString(R.string.patched_app_saved_toast))
                }
            } else {
                app.toast(app.getString(R.string.patched_app_save_failed_toast))
            }
            onResult(success)
        }
    }

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val pkg = intent.data?.schemeSpecificPart ?: return
            if (pkg == packageName) {
                basePackageInstalled = action != Intent.ACTION_PACKAGE_REMOVED
            }
            if (action == Intent.ACTION_PACKAGE_ADDED || action == Intent.ACTION_PACKAGE_REPLACED) {
                handleExternalInstallSuccess(pkg)
            }
        }
    }

    init {
        // TODO: detect system-initiated process death during the patching process.
        ContextCompat.registerReceiver(
            app,
            packageChangeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        viewModelScope.launch {
            installedApp = sourceInstalledApp()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        super.onCleared()
        app.unregisterReceiver(packageChangeReceiver)
        pendingExternalInstall?.let(installerManager::cleanup)
        pendingExternalInstall = null
        externalInstallTimeoutJob?.cancel()
        externalInstallTimeoutJob = null
        externalInstallStartTime = null

        if (input.selectedApp is SelectedApp.Installed &&
            installerManager.getPrimaryToken() == InstallerManager.Token.AutoSaved
        ) {
            GlobalScope.launch(Dispatchers.Main) {
                val shouldRemount = withContext(Dispatchers.IO) {
                    installedAppRepository.get(packageName)?.installType == InstallType.MOUNT
                }
                if (!shouldRemount) return@launch
                uiSafe(app, R.string.failed_to_mount, "Failed to mount") {
                    withTimeout(Duration.ofMinutes(1L)) {
                        rootMountCoordinator.execute(
                            RootMountRequest(
                                packageName,
                                userId = android.os.Process.myUid() / 100_000,
                                operation = RootMountOperation.MOUNT_ONLY
                            )
                        ).requireSuccess()
                    }
                }
            }
        }

    }

    fun onBack(cleanupLocalInput: Boolean) {
        cancelSplitSelectionPreparation()
        // tempDir cannot be deleted inside onCleared because it gets called on system-initiated process death.
        if (_isPatchingActive.value == true) {
            val workId = patcherWorkerId?.uuid
            workId?.let(workManager::cancelWorkById)
            clearPatchingNotification()
            stopPatchingTaskMonitor()
            if (cleanupLocalInput) {
                cleanupTemporaryLocalInputAfterWorkStops(workId)
            }
        } else if (cleanupLocalInput) {
            cleanupTemporaryLocalInput()
        }
        tempDir.deleteRecursively()
    }

    fun isDeviceRooted() = rootInstaller.isDeviceRooted()

    private fun completeActivityRequest(requestId: Long, accepted: Boolean) {
        currentActivityRequest
            ?.takeIf { it.dialogState.requestId == requestId }
            ?.let { request ->
                request.completion.complete(accepted)
            }
    }

    fun rejectInteraction(requestId: Long) = completeActivityRequest(requestId, accepted = false)

    fun allowInteraction(requestId: Long) = completeActivityRequest(requestId, accepted = true)

    fun handleActivityResult(result: ActivityResult) {
        pendingActivityResumeFallback?.cancel()
        pendingActivityResumeFallback = null
        launchedActivity?.complete(result)
    }

    fun onHostResumed() {
        val pending = launchedActivity ?: return
        if (currentActivityRequest != null || pending.isCompleted) return

        pendingActivityResumeFallback?.cancel()
        pendingActivityResumeFallback = viewModelScope.launch {
            delay(DOWNLOADER_ACTIVITY_RESULT_GRACE_MS)
            if (launchedActivity === pending && currentActivityRequest == null && !pending.isCompleted) {
                pending.complete(ActivityResult(Activity.RESULT_CANCELED, null))
                launchedActivity = null
            }
        }
    }

    private fun clearPendingActivityInteractions() {
        pendingActivityResumeFallback?.cancel()
        pendingActivityResumeFallback = null
        currentActivityRequest?.let { request ->
            if (currentActivityRequest === request) {
                currentActivityRequest = null
            }
            request.completion.complete(false)
        }
        launchedActivity?.complete(ActivityResult(Activity.RESULT_CANCELED, null))
        launchedActivity = null
    }

    fun export(uri: Uri?) = viewModelScope.launch {
        uri?.let { targetUri ->
            ensureExportMetadata()
            val exportSucceeded = runCatching {
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(targetUri)
                        ?.use { stream -> Files.copy(outputFile.toPath(), stream) }
                        ?: throw IOException("Could not open output stream for export")
                }
            }.isSuccess

            if (!exportSucceeded) {
                app.toast(app.getString(R.string.saved_app_export_failed))
                return@launch
            }

            finalizeExport()
        }
    }

    fun exportToPath(
        target: Path,
        onResult: (Boolean) -> Unit = {}
    ) = viewModelScope.launch {
        ensureExportMetadata()
        val exportSucceeded = runCatching {
            withContext(Dispatchers.IO) {
                target.parent?.let { Files.createDirectories(it) }
                Files.copy(outputFile.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
            }
        }.isSuccess

        if (!exportSucceeded) {
            app.toast(app.getString(R.string.saved_app_export_failed))
            onResult(false)
            return@launch
        }

        finalizeExport()
        onResult(true)
    }

    private suspend fun finalizeExport() {
        if (prefs.enableSavedApps.get()) {
            val wasAlreadySaved = hasSavedPatchedApp
            val saved = persistPatchedApp(null, InstallType.SAVED)
            if (!saved) {
                app.toast(app.getString(R.string.patched_app_save_failed_toast))
            } else if (!wasAlreadySaved) {
                app.toast(app.getString(R.string.patched_app_saved_toast))
            }
        }

        app.toast(app.getString(R.string.save_apk_success))
    }

    private fun buildLogContent(context: Context): String {
        val logSnapshot = logs.toList()
        val logMessages = logSnapshot.map { it.second }
        fun findLogValue(prefix: String): String? =
            logMessages.lastOrNull { it.startsWith(prefix) }
                ?.removePrefix(prefix)
                ?.trim()

        data class LogPrefsSnapshot(
            val bundleType: String,
            val morpheBytecodeMode: String?,
            val revancedPatcherVersion: String?,
            val stripNativeLibs: Boolean,
            val skipUnusedSplits: Boolean,
            val environment: String,
            val selectedPatchLines: List<String>
        )
        val prefsSnapshot = runBlocking {
            val bundleType = patchBundleRepository.selectionBundleType(input.selectedPatches)
            val bundle = bundleType?.name ?: "UNKNOWN"
            val morpheBytecodeMode = if (bundleType == PatchBundleType.MORPHE) {
                prefs.morpheBytecodeMode.get().runtimeValue
            } else {
                null
            }
            val revancedPatcherVersion = when (bundleType) {
                PatchBundleType.REVANCED ->
                    if (patchBundleRepository.selectionUsesRevancedPatcher22(input.selectedPatches)) {
                        "22.0.0"
                    } else {
                        "21.0.0"
                    }
                else -> null
            }
            val stripNative = prefs.stripUnusedNativeLibs.get()
            val skipSplits = prefs.skipUnneededSplitApks.get()
            val environment = withContext(Dispatchers.IO) {
                when (rootInstaller.peekRootAccess()) {
                    true -> "root"
                    false -> "unrooted"
                    null -> if (rootInstaller.isDeviceRooted()) "rooted" else "unrooted"
                }
            }
            val selectedPatchLines = collectSelectedPatchDescriptions()
            LogPrefsSnapshot(
                bundle,
                morpheBytecodeMode,
                revancedPatcherVersion,
                stripNative,
                skipSplits,
                environment,
                selectedPatchLines
            )
        }
        val bundleType = prefsSnapshot.bundleType
        val morpheBytecodeMode = prefsSnapshot.morpheBytecodeMode
        val revancedPatcherVersion = prefsSnapshot.revancedPatcherVersion
        val stripNativeLibs = prefsSnapshot.stripNativeLibs
        val skipUnusedSplits = prefsSnapshot.skipUnusedSplits
        val environment = prefsSnapshot.environment
        val selectedPatchLines = prefsSnapshot.selectedPatchLines

        val runtimeReportedLimit = runtimeReportedMemoryLimitMb ?: parseMemoryLimitMb(
            logMessages.lastOrNull { it.startsWith("Memory limit:") }
                ?.removePrefix("Memory limit:")
                ?.trim()
        )
        val effectiveLimit = runtimeReportedLimit ?: MemoryLimitConfig.resolveMemoryLimitMb(
            context,
            prefs.processMemoryLimit.getBlocking()
        )
        val processRuntimeEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val runtimeMode = findLogValue("Runtime mode:")
            ?: if (processRuntimeEnabled) "process" else "in-process"
        val memoryOverride = findLogValue("Memory override:")
            ?: if (processRuntimeEnabled) "enabled" else "disabled"
        val aapt2 = findLogValue("AAPT2:") ?: when (bundleType) {
            PatchBundleType.REVANCED.name -> "Modern"
            PatchBundleType.MORPHE.name -> "N/A"
            else -> "N/A"
        }
        val aapt2Fallback = findLogValue("AAPT2 fallback:") ?: "false"
        val appVersionCode = findLogValue("App version code:")
            ?: input.selectedApp.versionCode?.toString()
            ?: "unspecified"
        val includedSplits = findLogValue("Included splits:")
        val excludedSplits = findLogValue("Excluded splits:")
        val patchFailure = lastPatchFailure
        val patchFailureStep = lastPatchFailureStep
        val failureSummaryLog = patchFailure?.let { error ->
            "Failure in step=${patchFailureStep ?: "Unknown"}: ${error.message ?: error.type}"
        }
        val hasCombinedPatchFailureLog =
            patchFailureStep == StepId.ExecutePatch::class.java.simpleName &&
                patchFailure?.stackTrace
                    ?.lineSequence()
                    ?.firstOrNull(String::isNotBlank)
                    ?.let { firstStackLine ->
                        logSnapshot.any { (_, msg) ->
                            msg.contains(" failed:\n") && msg.contains(firstStackLine)
                        }
                    } == true

        val isIgnoring = context.getSystemService<PowerManager>()
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
        val batteryOptimization = if (isIgnoring) "disabled" else "enabled"
        val deviceName = resolveDeviceName()

        val sizeBytes = inputFile?.length() ?: 0L
        val sizeMb = if (sizeBytes > 0L) {
            "${(sizeBytes / 1_000_000.0).roundToInt()}MB"
        } else {
            "unknown"
        }
        val splitCount = inputFile
            ?.takeIf { SplitApkPreparer.isSplitArchive(it) }
            ?.let { file -> SplitApkPreparer.splitApkEntryNames(file).size }

        val appVersion = input.selectedApp.version
            ?.takeUnless { it.isBlank() }
            ?: "unspecified"
        val patchCount = selectedPatchLines.size
        val droppedLines = droppedLogLineCount

        val logLines = logSnapshot
            .filterNot { (level, msg) ->
                    msg.startsWith("Battery optimization:") ||
                    msg.startsWith("Patching started at ") ||
                    msg.startsWith("Patcher runtime:") ||
                    msg.startsWith("Memory limit:") ||
                    msg.startsWith("Runtime mode:") ||
                    msg.startsWith("Memory override:") ||
                    msg.startsWith("AAPT2:") ||
                    msg.startsWith("AAPT2 fallback:") ||
                    msg.startsWith("App version code:") ||
                    msg.startsWith("Included splits:") ||
                    msg.startsWith("Excluded splits:") ||
                    failureSummaryLog?.let { matchesBoundedLogMessage(msg, it) } == true ||
                    (hasCombinedPatchFailureLog &&
                        patchFailure?.let { matchesBoundedLogMessage(msg, it.stackTrace) } == true) ||
                    isVerbosePatcherExportLog(level, msg)
            }
            .map { (level, msg) -> "[${level.name}]: $msg" }

        return buildString {
            appendLine("------------")
            appendLine("Information:")
            appendLine("------------")
            appendLine("URV version: ${BuildConfig.VERSION_NAME}")
            appendLine("Device architecture: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("Device name: $deviceName")
            appendLine("Device model: ${Build.MODEL}")
            appendLine("Android version: ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})")
            appendLine("Environment: $environment")
            appendLine("Effective memory limit: ${effectiveLimit}MB")
            appendLine("Bundle type: $bundleType")
            morpheBytecodeMode?.let {
                appendLine("Morphe bytecode mode: $it")
            }
            revancedPatcherVersion?.let {
                appendLine("ReVanced Patcher version: $it")
            }
            appendLine("Runtime mode: $runtimeMode")
            appendLine("Memory override: $memoryOverride")
            appendLine("AAPT2: $aapt2")
            appendLine("AAPT2 fallback: $aapt2Fallback")
            appendLine("Strip native libs: ${if (stripNativeLibs) "on" else "off"}")
            appendLine("Skip unused splits: ${if (skipUnusedSplits) "on" else "off"}")
            appendLine("Battery optimization: $batteryOptimization")
            appendLine("App package: ${input.selectedApp.packageName}")
            appendLine("App version: $appVersion")
            appendLine("App version code: $appVersionCode")
            appendLine("App size: $sizeMb")
            splitCount?.let { appendLine("Split: $it") }
            includedSplits?.let { appendLine("Included splits: $it") }
            excludedSplits?.let { appendLine("Excluded splits: $it") }
            patchFailure?.let { error ->
                appendLine("Patch result: failed")
                appendLine("Failure step: ${patchFailureStep ?: "Unknown"}")
                appendLine("Failure type: ${error.type.substringAfterLast('.')}")
                appendLine("Failure message: ${conciseFailureMessage(error)}")
            }
            appendLine("Patches: $patchCount")
            appendLine("Selected patches:")
            if (selectedPatchLines.isEmpty()) {
                appendLine("None")
            } else {
                selectedPatchLines.forEach { appendLine(it) }
            }
            appendLine()
            appendLine("------------")
            appendLine("Patcher Log:")
            appendLine("------------")
            if (droppedLines > 0) {
                appendLine("[WARN]: Log guard trimmed $droppedLines older line(s) to keep size bounded.")
            }
            if (logLines.isEmpty()) {
                appendLine("No log messages recorded.")
            } else {
                logLines.forEach { appendLine(it) }
            }
        }
    }

    fun getLogContent(context: Context): String = buildLogContent(context)

    fun exportLogsToPath(
        context: Context,
        target: Path,
        onResult: (Boolean) -> Unit = {}
    ) = viewModelScope.launch {
        val exportSucceeded = runCatching {
            withContext(Dispatchers.IO) {
                target.parent?.let { Files.createDirectories(it) }
                Files.newBufferedWriter(
                    target,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                ).use { writer ->
                    writer.write(buildLogContent(context))
                }
            }
        }.isSuccess

        if (!exportSucceeded) {
            app.toast(app.getString(R.string.patcher_log_export_failed))
            onResult(false)
            return@launch
        }

        app.toast(app.getString(R.string.patcher_log_export_success))
        onResult(true)
    }

    fun exportLogsToUri(
        context: Context,
        target: Uri?,
        onResult: (Boolean) -> Unit = {}
    ) = viewModelScope.launch {
        if (target == null) {
            onResult(false)
            return@launch
        }

        val exportSucceeded = runCatching {
            withContext(Dispatchers.IO) {
                app.contentResolver.openOutputStream(target, "wt")
                    ?.bufferedWriter(StandardCharsets.UTF_8)
                    ?.use { writer ->
                        writer.write(buildLogContent(context))
                    }
                    ?: throw IOException("Could not open output stream for log export")
            }
        }.isSuccess

        if (!exportSucceeded) {
            app.toast(app.getString(R.string.patcher_log_export_failed))
            onResult(false)
            return@launch
        }

        app.toast(app.getString(R.string.patcher_log_export_success))
        onResult(true)
    }

    fun exportLogs(context: Context) {
        val content = buildLogContent(context)

        val sendIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, content)
            type = "text/plain"
        }

        val shareIntent = Intent.createChooser(sendIntent, null)
        context.startActivity(shareIntent)
    }

    fun open() = installedPackageName?.let(pm::launch)

    private suspend fun performInstall(
        installType: InstallType,
        downgradeFallbackConfirmed: Boolean = false
    ) {
        val operationJob = currentCoroutineContext()[Job]
        activeInstallJob = operationJob
        try {
            rootMountRecoveryMessage = null
            activeInstallType = installType
            deferInstallProgressToasts = installType != InstallType.MOUNT
            updateInstallingState(true)
            installStatus = InstallCompletionStatus.InProgress

            Log.d(TAG, "performInstall(type=$installType, outputExists=${outputFile.exists()}, output=${outputFile.absolutePath})")
            val currentPackageInfo = pm.getPackageInfo(outputFile)
                ?: throw Exception("Failed to load application info")

            // If the app is currently installed
            val existingPackageInfo = pm.getPackageInfo(currentPackageInfo.packageName)
            if (existingPackageInfo != null && installType != InstallType.MOUNT) {
                // Check if the app version is less than the installed version
                if (pm.getVersionCode(currentPackageInfo) < pm.getVersionCode(existingPackageInfo)) {
                    val hint = app.getString(R.string.installer_hint_downgrade)
                    showInstallFailure(app.getString(R.string.install_app_fail, hint))
                    return
                }
            }

            when (installType) {
                InstallType.DEFAULT, InstallType.CUSTOM, InstallType.SAVED, InstallType.SHIZUKU -> {
                    if (!pm.requestInstallPackagesPermission()) {
                        val hint = installerManager.formatFailureHint(PackageInstaller.STATUS_FAILURE_BLOCKED, null)
                            ?: app.getString(R.string.installer_hint_blocked)
                        showInstallFailure(app.getString(R.string.install_app_fail, hint))
                        return
                    }
                    // Check if the app is mounted as root
                    // If it is, unmount it first, silently
                    if (rootInstaller.hasRootAccess() && rootInstaller.isAppMounted(packageName)) {
                        rootMountCoordinator.execute(
                            RootMountRequest(
                                packageName,
                                userId = android.os.Process.myUid() / 100_000,
                                operation = RootMountOperation.UNMOUNT
                            )
                        ).requireSuccess()
                    }

                    val result = try {
                        sessionInstaller.install(
                            outputFile,
                            currentPackageInfo.packageName,
                            ::enableInstallProgressToasts
                        )
                    } catch (_: InstallCancelledException) {
                        installStatus = null
                        updateInstallingState(false)
                        return
                    } catch (error: SessionDeadException) {
                        Log.w(TAG, "PackageInstaller session died; using intent fallback", error)
                        val fallbackPlan = installerManager.createSystemFallbackPlan(
                            target = InstallerManager.InstallTarget.PATCHER,
                            sourceFile = outputFile,
                            expectedPackage = currentPackageInfo.packageName,
                            sourceLabel = null
                        )
                        launchExternalInstaller(fallbackPlan)
                        return
                    }

                    when (result) {
                        InstallResult.Success -> {
                            val persisted = persistPatchedApp(currentPackageInfo.packageName, installType)
                            if (!persisted) {
                                Log.w(TAG, "Failed to persist installed patched app metadata")
                            }
                            installedPackageName = currentPackageInfo.packageName
                            packageInstallerStatus = null
                            installFailureMessage = null
                            markInstallSuccess(currentPackageInfo.packageName)
                            lastSuccessInstallType = installType
                            lastSuccessAtMs = System.currentTimeMillis()
                            updateInstallingState(false)
                        }

                        is InstallResult.Conflict -> {
                            val backendReason = result.message
                            if (installerManager.isSignatureMismatch(backendReason)) {
                                val plan = installerManager.resolvePlan(
                                    InstallerManager.InstallTarget.PATCHER,
                                    outputFile,
                                    currentPackageInfo.packageName,
                                    null,
                                    allowMount = usingMountInstall &&
                                        currentPackageInfo.packageName == packageName
                                )
                                showSignatureMismatchPrompt(currentPackageInfo.packageName, plan)
                                return
                            }
                            val message = installerManager.formatFailureHint(
                                PackageInstaller.STATUS_FAILURE_CONFLICT,
                                backendReason
                            ) ?: backendReason ?: app.getString(R.string.installer_hint_conflict_generic)
                            showInstallFailure(app.getString(R.string.install_app_fail, message))
                        }

                        is InstallResult.Failure -> {
                            val backendReason = result.message
                            val message = installerManager.formatFailureHint(
                                result.status,
                                backendReason
                            ) ?: backendReason ?: app.getString(R.string.installer_hint_generic)
                            showInstallFailure(app.getString(R.string.install_app_fail, message))
                        }
                    }
                }

                InstallType.MOUNT -> performRootMount(currentPackageInfo, downgradeFallbackConfirmed)
            }
        } catch (cancelled: CancellationException) {
            packageInstallerStatus = null
            installStatus = null
            installFailureMessage = null
            updateInstallingState(false)
            throw cancelled
        } catch (e: Exception) {
            Log.e(tag, "Failed to install", e)
            packageInstallerStatus = null
            showInstallFailure(
                app.getString(
                    R.string.install_app_fail,
                    e.simpleMessage() ?: e.javaClass.simpleName.orEmpty()
                ),
                allowFallback = installType != InstallType.MOUNT
            )
        } finally {
            if (activeInstallJob === operationJob) activeInstallJob = null
        }
    }

    private suspend fun performRootMount(
        packageInfo: PackageInfo,
        downgradeFallbackConfirmed: Boolean
    ) {
        check(packageInfo.packageName == packageName) {
            app.getString(R.string.root_mount_renamed_package_not_supported)
        }
        if (!withContext(Dispatchers.IO) { rootInstaller.hasRootAccess() }) throw RootServiceException()
        val installedBaseInfo = pm.getPackageInfo(packageName)
        basePackageInstalled = installedBaseInfo != null
        val targetVersionCode = pm.getVersionCode(packageInfo)
        val stockNeedsReplacement = installedBaseInfo == null ||
            pm.getVersionCode(installedBaseInfo) != targetVersionCode ||
            installedBaseInfo.versionName != packageInfo.versionName
        val originalInput = inputFile
        val originalInputIsSplit = originalInput?.let(SplitApkPreparer::isSplitArchive) == true
        val stockApks = if (stockNeedsReplacement) {
            val stock = originalInput
                ?.takeUnless { originalInputIsSplit }
                ?: throw IllegalStateException(
                    app.getString(R.string.install_app_fail_missing_stock)
                )
            listOf(stock)
        } else if (rootInstaller.isAppMounted(packageName)) {
            // applicationInfo.sourceDir resolves through the active bind mount. It is the
            // patched payload, not independent proof of the raw stock APK.
            emptyList()
        } else {
            val installedStock = installedBaseInfo?.applicationInfo?.sourceDir
                ?.let(::File)
                ?.takeIf(File::isFile)
            listOfNotNull(
                if (originalInputIsSplit) installedStock else originalInput ?: installedStock
            )
        }
        val label = with(pm) { packageInfo.label() }
        val result = try {
            rootMountCoordinator.execute(
                RootMountRequest(
                    packageName = packageInfo.packageName,
                    userId = android.os.Process.myUid() / 100_000,
                    operation = if (stockNeedsReplacement) {
                        RootMountOperation.REPLACE_STOCK_AND_MOUNT
                    } else {
                        RootMountOperation.SWITCH_PATCHED_BUILD
                    },
                    patchedApk = outputFile,
                    stockApks = stockApks,
                    expectedVersionName = packageInfo.versionName,
                    expectedVersionCode = targetVersionCode,
                    label = label,
                    downgradeFallbackConfirmed = downgradeFallbackConfirmed
                )
            ) { phase -> rootMountPhase = phase }
        } finally {
            rootMountPhase = null
        }
        when (result) {
            is RootMountResult.Success -> {
                rootDowngradeConfirmationPending = false
                if (!persistPatchedApp(packageInfo.packageName, InstallType.MOUNT)) {
                    Log.w(TAG, "Failed to persist mounted patched app metadata")
                }
                installedPackageName = packageInfo.packageName
                markInstallSuccess(packageInfo.packageName)
                updateInstallingState(false)
            }

            is RootMountResult.RequiresDowngradeConfirmation -> {
                rootDowngradeConfirmationPending = true
                installStatus = null
                updateInstallingState(false)
            }

            is RootMountResult.RecoveredToPreviousMount -> {
                showRootMountRecovery(
                    app.getString(R.string.root_mount_recovered_previous_message, result.diagnosticId)
                )
            }
            is RootMountResult.RecoveredToStock -> {
                showRootMountRecovery(
                    app.getString(R.string.root_mount_recovered_stock_message, result.diagnosticId)
                )
            }
            is RootMountResult.RequiresRepatch -> {
                showInstallFailure(result.reason, allowFallback = false)
            }
            is RootMountResult.Busy -> {
                val detail = result.reason ?: "Persisted phase: " +
                    (result.phase?.name?.lowercase()?.replace('_', ' ') ?: "preparing")
                showInstallFailure(
                    app.getString(R.string.root_mount_recovery_in_progress, detail),
                    allowFallback = false
                )
            }
            is RootMountResult.Failure -> throw IllegalStateException(
                "${result.message} ${result.recoveryState.describeRecovery()} " +
                    "Diagnostic ${result.diagnosticId}."
            )
        }
    }

    fun confirmRootDowngrade() {
        rootDowngradeConfirmationPending = false
        viewModelScope.launch { performInstall(InstallType.MOUNT, downgradeFallbackConfirmed = true) }
    }

    fun dismissRootDowngradeConfirmation() {
        rootDowngradeConfirmationPending = false
        installStatus = null
        activeInstallType = null
        updateInstallingState(false)
    }

    private suspend fun performShizukuInstall(
        installerPackageNameOverride: String? = null,
        allowAutoUninstall: Boolean = false
    ) {
        val operationJob = currentCoroutineContext()[Job]
        activeInstallJob = operationJob
        activeInstallType = InstallType.SHIZUKU
        updateInstallingState(true)
        installStatus = InstallCompletionStatus.InProgress
        packageInstallerStatus = null
        try {

            val currentPackageInfo = pm.getPackageInfo(outputFile)
                ?: throw Exception("Failed to load application info")

            val existingPackageInfo = pm.getPackageInfo(currentPackageInfo.packageName)
            if (existingPackageInfo != null) {
                if (pm.getVersionCode(currentPackageInfo) < pm.getVersionCode(existingPackageInfo)) {
                    val hint = app.getString(R.string.installer_hint_downgrade)
                    showInstallFailure(app.getString(R.string.install_app_fail, hint))
                    return
                }
            }

            if (rootInstaller.hasRootAccess() && rootInstaller.isAppMounted(packageName)) {
                rootMountCoordinator.execute(
                    RootMountRequest(
                        packageName,
                        userId = android.os.Process.myUid() / 100_000,
                        operation = RootMountOperation.UNMOUNT
                    )
                ).requireSuccess()
            }

            val result = shizukuInstaller.install(
                outputFile,
                currentPackageInfo.packageName,
                installerPackageNameOverride
            )
            if (result.status != PackageInstaller.STATUS_SUCCESS) {
                throw ShizukuInstaller.InstallerOperationException(result.status, result.message)
            }

            val persisted = persistPatchedApp(currentPackageInfo.packageName, InstallType.SHIZUKU)
            if (!persisted) {
                Log.w(TAG, "Failed to persist installed patched app metadata")
            }

            installedPackageName = currentPackageInfo.packageName
            packageInstallerStatus = null
            installFailureMessage = null
            installStatus = InstallCompletionStatus.Success(currentPackageInfo.packageName)
            updateInstallingState(false)
            suppressFailureAfterSuccess = true
            lastSuccessInstallType = InstallType.SHIZUKU
            lastSuccessAtMs = System.currentTimeMillis()
        } catch (cancelled: CancellationException) {
            packageInstallerStatus = null
            installStatus = null
            installFailureMessage = null
            throw cancelled
        } catch (error: ShizukuInstaller.InstallerOperationException) {
            Log.e(tag, "Failed to install via Shizuku", error)
            val currentPackage = pm.getPackageInfo(outputFile)?.packageName ?: packageName
            if (
                allowAutoUninstall &&
                installerManager.isSignatureMismatch(error.message) &&
                tryAutoUninstallSignatureConflict(currentPackage, automatic = true)
            ) {
                performShizukuInstall(
                    installerPackageNameOverride = installerPackageNameOverride,
                    allowAutoUninstall = false
                )
                return
            }
            val backendReason = error.message ?: error.javaClass.simpleName
            val message = installerManager.formatShizukuFailure(error.status, backendReason)
            packageInstallerStatus = null
            showInstallFailure(message)
        } catch (error: Exception) {
            Log.e(tag, "Failed to install via Shizuku", error)
            if (packageInstallerStatus == null) {
                packageInstallerStatus = PackageInstaller.STATUS_FAILURE
            }
            showInstallFailure(
                app.getString(
                    R.string.install_app_fail,
                    error.simpleMessage() ?: error.javaClass.simpleName.orEmpty()
                )
            )
        } finally {
            if (packageInstallerStatus == PackageInstaller.STATUS_SUCCESS && installStatus !is InstallCompletionStatus.Success) {
                markInstallSuccess(installedPackageName ?: packageName)
            }
            updateInstallingState(false)
            if (activeInstallJob === operationJob) activeInstallJob = null
        }
    }

    private suspend fun executeInstallPlan(
        plan: InstallerManager.InstallPlan,
        automatic: Boolean = false
    ) {
        Log.d(TAG, "executeInstallPlan(plan=${plan::class.java.simpleName})")
        recordInstallPlan(plan, lastInstallExpectedPackage ?: packageName, lastInstallSourceLabel)
        when (plan) {
            is InstallerManager.InstallPlan.Internal -> {
                pendingExternalInstall?.let(installerManager::cleanup)
                pendingExternalInstall = null
                externalInstallTimeoutJob?.cancel()
                externalInstallTimeoutJob = null
                performInstall(installTypeFor(plan.target))
            }

            is InstallerManager.InstallPlan.Mount -> {
                pendingExternalInstall?.let(installerManager::cleanup)
                pendingExternalInstall = null
                externalInstallTimeoutJob?.cancel()
                externalInstallTimeoutJob = null
                performInstall(InstallType.MOUNT)
            }

            is InstallerManager.InstallPlan.Shizuku -> {
                pendingExternalInstall?.let(installerManager::cleanup)
                pendingExternalInstall = null
                externalInstallTimeoutJob?.cancel()
                externalInstallTimeoutJob = null
                performShizukuInstall(
                    installerPackageNameOverride = plan.installerPackageNameOverride,
                    allowAutoUninstall = automatic
                )
            }

            is InstallerManager.InstallPlan.External -> launchExternalInstaller(plan)
        }
    }

    private fun installTypeFor(target: InstallerManager.InstallTarget): InstallType = when (target) {
        InstallerManager.InstallTarget.PATCHER -> InstallType.DEFAULT
        InstallerManager.InstallTarget.SAVED_APP -> InstallType.DEFAULT
        InstallerManager.InstallTarget.MANAGER_UPDATE,
        InstallerManager.InstallTarget.LSPOSED_MODULE,
        InstallerManager.InstallTarget.DOWNLOADER_HELPER -> InstallType.DEFAULT
    }

    private suspend fun launchExternalInstaller(plan: InstallerManager.InstallPlan.External) {
        pendingExternalInstall?.let { installerManager.cleanup(it) }
        externalInstallTimeoutJob?.cancel()
        externalInstallTimeoutJob = null

        pendingExternalInstall = plan
        externalInstallStartTime = System.currentTimeMillis()
        val baselineInfo = pm.getPackageInfo(plan.expectedPackage)
        externalPackageWasPresentAtStart = baselineInfo != null
        externalInstallBaseline = baselineInfo?.let { info ->
            pm.getVersionCode(info) to info.lastUpdateTime
        }
        baselineInstallSignature = readInstalledSignatureBytes(plan.expectedPackage)
        expectedInstallSignature = readArchiveSignatureBytes(plan.sharedFile)
        internalInstallBaseline = null
        activeInstallType = InstallType.DEFAULT
        updateInstallingState(true)
        installStatus = InstallCompletionStatus.InProgress
        scheduleInstallTimeout(
            packageName = plan.expectedPackage,
            durationMs = EXTERNAL_INSTALL_TIMEOUT_MS,
            timeoutMessage = { app.getString(R.string.installer_external_timeout, plan.installerLabel) }
        )

        if (isInstallerX(plan) && launchedActivity == null) {
            val activityDeferred = CompletableDeferred<ActivityResult>()
            launchedActivity = activityDeferred
            val launchIntent = Intent(plan.intent).apply { removeFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            launchActivityChannel.send(launchIntent)
            monitorExternalInstall(plan)
            viewModelScope.launch {
                try {
                    activityDeferred.await()
                    delay(EXTERNAL_INSTALLER_RESULT_GRACE_MS)
                    if (pendingExternalInstall != plan) return@launch
                    val deadline = System.currentTimeMillis() + EXTERNAL_INSTALLER_POST_CLOSE_TIMEOUT_MS
                    while (pendingExternalInstall == plan && System.currentTimeMillis() < deadline) {
                        if (tryMarkInstallIfPresent(plan.expectedPackage)) return@launch
                        delay(INSTALL_MONITOR_POLL_MS)
                    }
                    if (pendingExternalInstall != plan) return@launch
                    showInstallFailure(
                        app.getString(
                            R.string.install_app_fail,
                            app.getString(R.string.installer_external_finished_no_change, plan.installerLabel)
                        )
                    )
                } finally {
                    if (launchedActivity === activityDeferred) launchedActivity = null
                }
            }
            return
        }

        try {
            ContextCompat.startActivity(app, plan.intent, null)
        } catch (error: ActivityNotFoundException) {
            installerManager.cleanup(plan)
            pendingExternalInstall = null
            updateInstallingState(false)
            externalInstallTimeoutJob = null
            showInstallFailure(
                app.getString(
                    R.string.install_app_fail,
                    error.simpleMessage() ?: error.javaClass.simpleName.orEmpty()
                )
            )
            return
        }

        monitorExternalInstall(plan)
    }

    private fun isInstallerX(plan: InstallerManager.InstallPlan.External): Boolean {
        fun normalize(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }
        val label = normalize(plan.installerLabel)
        val tokenPkg = (plan.token as? InstallerManager.Token.Component)?.componentName?.packageName.orEmpty()
        val componentPkg = plan.intent.component?.packageName.orEmpty()
        val pkg = normalize(if (tokenPkg.isNotBlank()) tokenPkg else componentPkg)
        return "installerx" in label || "installerx" in pkg || pkg.startsWith("comrosaninstaller")
    }

    private fun handleExternalInstallSuccess(packageName: String): Boolean {
        val plan = pendingExternalInstall ?: return false
        if (plan.expectedPackage != packageName) return false

        pendingExternalInstall = null
        externalInstallTimeoutJob?.cancel()
        externalInstallTimeoutJob = null
        externalInstallBaseline = null
        externalInstallStartTime = null
        externalPackageWasPresentAtStart = false
        expectedInstallSignature = null
        baselineInstallSignature = null
        installerManager.cleanup(plan)
        updateInstallingState(false)
        stopInstallProgressToasts()
        val installType = if (plan?.token is InstallerManager.Token.Component) InstallType.CUSTOM else InstallType.DEFAULT
        val customInstallerPackageName =
            (plan.token as? InstallerManager.Token.Component)
                ?.componentName
                ?.packageName
        markInstallSuccess(packageName)
        suppressFailureAfterSuccess = true

        when (plan.target) {
            InstallerManager.InstallTarget.PATCHER -> {
                installedPackageName = packageName
                viewModelScope.launch {
                    val persisted = persistPatchedApp(
                        packageName,
                        installType,
                        customInstallerPackageName = customInstallerPackageName
                    )
                    if (!persisted) {
                        Log.w(TAG, "Failed to persist installed patched app metadata (external installer)")
                    }
                }
            }

            InstallerManager.InstallTarget.SAVED_APP,
            InstallerManager.InstallTarget.MANAGER_UPDATE,
            InstallerManager.InstallTarget.LSPOSED_MODULE,
            InstallerManager.InstallTarget.DOWNLOADER_HELPER -> {
            }
        }
        suppressFailureAfterSuccess = true
        lastSuccessInstallType = installType
        lastSuccessAtMs = System.currentTimeMillis()
        return true
    }

    fun cancelInstall() {
        if (!isInstalling) return

        val cancellation = CancellationException("Installation cancelled")
        val hadActiveJob = activeInstallJob != null

        pendingExternalInstall?.let(installerManager::cleanup)
        pendingExternalInstall = null
        externalInstallTimeoutJob?.cancel()
        externalInstallTimeoutJob = null
        externalInstallPresenceJob?.cancel()
        externalInstallPresenceJob = null
        postTimeoutGraceJob?.cancel()
        postTimeoutGraceJob = null
        pendingActivityResumeFallback?.cancel()
        pendingActivityResumeFallback = null
        externalInstallStartTime = null
        externalPackageWasPresentAtStart = false
        launchedActivity?.cancel(cancellation)
        launchedActivity = null

        activeInstallJob?.cancel(cancellation)
        if (!hadActiveJob) {
            installStatus = null
            installFailureMessage = null
            updateInstallingState(false)
        }
    }

    override fun install() {
        if (isInstalling) return
        if (usingMountInstall) {
            installWithToken(InstallerManager.Token.AutoSaved)
            return
        }
        input.profileInstallerToken?.let { storedToken ->
            installWithToken(
                installerManager.withPlayStoreSource(
                    installerManager.parseToken(storedToken),
                    prefs.shizukuInstallAsPlayStore.getBlocking()
                )
            )
            return
        }
        viewModelScope.launch {
            runCatching {
                val expectedPackage = pm.getPackageInfo(outputFile)?.packageName ?: packageName
                Log.d(TAG, "install() requested, expected=$expectedPackage, outputExists=${outputFile.exists()}")
                val plan = installerManager.resolvePlan(
                    InstallerManager.InstallTarget.PATCHER,
                    outputFile,
                    expectedPackage,
                    null,
                    allowMount = usingMountInstall && expectedPackage == packageName
                )
                Log.d(TAG, "install() resolved plan=${plan::class.java.simpleName}")
                if (plan !is InstallerManager.InstallPlan.Mount &&
                    hasSignatureMismatch(expectedPackage, outputFile)
                ) {
                    showSignatureMismatchPrompt(expectedPackage, plan)
                    return@runCatching
                }
                recordInstallPlan(plan, expectedPackage, null)
                executeInstallPlan(plan)
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                Log.e(TAG, "install() failed to start", error)
                showInstallFailure(
                    app.getString(
                        R.string.install_app_fail,
                        error.simpleMessage() ?: error.javaClass.simpleName.orEmpty()
                    ),
                    allowFallback = !usingMountInstall
                )
            }
        }
    }

    fun maybeAutoInstall() {
        if (autoInstallTriggered) return
        val token = when {
            input.autoInstall && input.profileInstallerToken != null ->
                installerManager.withPlayStoreSource(
                    installerManager.parseToken(input.profileInstallerToken),
                    prefs.shizukuInstallAsPlayStore.getBlocking()
                )
            !usingMountInstall && prefs.autoInstallWithShizuku.getBlocking() -> {
                val primary = installerManager.getPrimaryToken()
                if (!installerManager.isShizukuToken(primary)) return
                primary
            }
            else -> return
        }
        autoInstallTriggered = true
        installWithToken(token, automatic = true)
    }

    fun installWithToken(token: InstallerManager.Token, automatic: Boolean = false) {
        if (isInstalling) return
        if (!isInstallerTokenAllowed(token)) {
            showInstallFailure(
                app.getString(R.string.installer_patch_mode_mismatch),
                allowFallback = false
            )
            return
        }
        viewModelScope.launch {
            runCatching {
                val expectedPackage = pm.getPackageInfo(outputFile)?.packageName ?: packageName
                check(!usingMountInstall || expectedPackage == packageName) {
                    app.getString(R.string.root_mount_renamed_package_not_supported)
                }
                Log.d(TAG, "installWithToken() requested, token=$token, expected=$expectedPackage, outputExists=${outputFile.exists()}")
                val plan = installerManager.resolvePlanForToken(
                    token = token,
                    target = InstallerManager.InstallTarget.PATCHER,
                    sourceFile = outputFile,
                    expectedPackage = expectedPackage,
                    sourceLabel = null,
                    allowMount = usingMountInstall && expectedPackage == packageName
                ) ?: throw IllegalStateException("Selected installer is unavailable")
                Log.d(TAG, "installWithToken() resolved plan=${plan::class.java.simpleName}")
                if (plan !is InstallerManager.InstallPlan.Mount &&
                    hasSignatureMismatch(expectedPackage, outputFile)
                ) {
                    if (!tryAutoUninstallSignatureConflict(
                            expectedPackage,
                            plan,
                            automatic
                        )
                    ) {
                        showSignatureMismatchPrompt(expectedPackage, plan)
                        return@runCatching
                    }
                }
                recordInstallPlan(plan, expectedPackage, null)
                executeInstallPlan(plan, automatic)
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                Log.e(TAG, "installWithToken() failed to start", error)
                showInstallFailure(
                    app.getString(
                        R.string.install_app_fail,
                        error.simpleMessage() ?: error.javaClass.simpleName.orEmpty()
                    )
                )
            }
        }
    }

    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/pull/734
    private suspend fun tryAutoUninstallSignatureConflict(
        packageName: String,
        plan: InstallerManager.InstallPlan? = null,
        automatic: Boolean = false
    ): Boolean {
        if (!automatic || !prefs.autoUninstallWithShizuku.get()) return false
        if (plan != null && plan !is InstallerManager.InstallPlan.Shizuku) return false
        if (!installerManager.shizukuStatus(InstallerManager.InstallTarget.PATCHER).availability.available) {
            return false
        }
        val downgradeWouldOccur = withContext(Dispatchers.IO) {
            val installedPackage = pm.getPackageInfo(packageName)
            val patchedPackage = pm.getPackageInfo(outputFile)
            installedPackage != null &&
                patchedPackage != null &&
                pm.getVersionCode(patchedPackage) < pm.getVersionCode(installedPackage)
        }
        if (downgradeWouldOccur) return false

        return runCatching {
            installerManager.uninstallWithShizuku(packageName)
            waitUntilPackageRemoved(packageName)
        }.onFailure {
            Log.w(TAG, "Shizuku auto-uninstall failed for $packageName", it)
        }.getOrDefault(false)
    }

    private suspend fun waitUntilPackageRemoved(packageName: String): Boolean {
        val deadline = System.currentTimeMillis() + SHIZUKU_UNINSTALL_VERIFY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (withContext(Dispatchers.IO) { pm.getPackageInfo(packageName) == null }) {
                return true
            }
            delay(SHIZUKU_UNINSTALL_VERIFY_POLL_MS)
        }
        return withContext(Dispatchers.IO) { pm.getPackageInfo(packageName) == null }
    }

    override fun reinstall() {
        if (isInstalling) return
        viewModelScope.launch {
            val expectedPackage = pm.getPackageInfo(outputFile)?.packageName ?: packageName
            val plan = installerManager.resolvePlan(
                InstallerManager.InstallTarget.PATCHER,
                outputFile,
                expectedPackage,
                null,
                    allowMount = usingMountInstall && expectedPackage == packageName
            )
            recordInstallPlan(plan, expectedPackage, null)
            when (plan) {
                is InstallerManager.InstallPlan.Internal -> {
                    pendingExternalInstall?.let(installerManager::cleanup)
                    pendingExternalInstall = null
                    externalInstallTimeoutJob?.cancel()
                    externalInstallTimeoutJob = null
                    try {
                        val pkg = pm.getPackageInfo(outputFile)?.packageName
                            ?: throw Exception("Failed to load application info")
                        when (val result = pm.uninstallPackage(pkg)) {
                            is Session.State.Failed<UninstallFailure> -> {
                                val message = result.failure.message.orEmpty()
                                handleUninstallFailure(
                                    app.getString(R.string.uninstall_app_fail, message)
                                )
                            }

                            Session.State.Succeeded -> {
                                performInstall(InstallType.DEFAULT)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to reinstall", e)
                        app.toast(app.getString(R.string.reinstall_app_fail, e.simpleMessage()))
                    }
                }
                is InstallerManager.InstallPlan.Mount -> {
                    pendingExternalInstall?.let(installerManager::cleanup)
                    pendingExternalInstall = null
                    externalInstallTimeoutJob?.cancel()
                    externalInstallTimeoutJob = null
                    performInstall(InstallType.MOUNT)
                }
                is InstallerManager.InstallPlan.Shizuku -> {
                    pendingExternalInstall?.let(installerManager::cleanup)
                    pendingExternalInstall = null
                    externalInstallTimeoutJob?.cancel()
                    externalInstallTimeoutJob = null
                    performShizukuInstall(plan.installerPackageNameOverride)
                }
                is InstallerManager.InstallPlan.External -> launchExternalInstaller(plan)
            }
        }
    }

    fun dismissPackageInstallerDialog() {
        packageInstallerStatus = null
    }

    fun dismissSignatureMismatchPrompt() {
        signatureMismatchPackage = null
        pendingSignatureMismatchPlan = null
        pendingSignatureMismatchPackage = null
    }

    fun confirmSignatureMismatchInstall() {
        val targetPackage = pendingSignatureMismatchPackage ?: return
        val plan = pendingSignatureMismatchPlan ?: return
        signatureMismatchPackage = null
        pendingSignatureMismatchPackage = null
        pendingSignatureMismatchPlan = null
        stopInstallProgressToasts()
        deferUninstallProgressToasts = true
        startUninstallProgressToasts()
        viewModelScope.launch {
            val session = ackpineUninstaller.createSession(targetPackage) {
                confirmation = Confirmation.IMMEDIATE
            }
            val toastJob = launchUninstallConfirmationToast(session)
            val result = try {
                withContext(Dispatchers.IO) {
                    session.await()
                }
            } finally {
                toastJob.cancel()
            }
            when (result) {
                is Session.State.Failed<UninstallFailure> -> {
                    stopUninstallProgressToasts()
                    if (result.failure is UninstallFailure.Aborted) {
                        updateInstallingState(false)
                        return@launch
                    }
                    val message = result.failure.message.orEmpty()
                    handleUninstallFailure(app.getString(R.string.uninstall_app_fail, message))
                }

                Session.State.Succeeded -> {
                    stopUninstallProgressToasts()
                    recordInstallPlan(plan, targetPackage, null)
                    executeInstallPlan(plan)
                }
            }
        }
    }

    fun shouldSuppressPackageInstallerDialog(): Boolean {
        if (activeInstallType == InstallType.SHIZUKU) return true
        val lastType = lastSuccessInstallType
        if (lastType != InstallType.SHIZUKU) return false
        val now = System.currentTimeMillis()
        return now - lastSuccessAtMs < SUPPRESS_FAILURE_AFTER_SUCCESS_MS
    }

    fun dismissInstallFailureMessage() {
        installFailureMessage = null
        packageInstallerStatus = null
        installStatus = null
        pendingInstallFailureMessage = null
    }

    fun shouldSuppressInstallFailureDialog(): Boolean {
        if (activeInstallType == InstallType.SHIZUKU) return true
        val lastType = lastSuccessInstallType
        if (lastType != InstallType.SHIZUKU) return false
        val now = System.currentTimeMillis()
        return now - lastSuccessAtMs < SUPPRESS_FAILURE_AFTER_SUCCESS_MS
    }

    fun clearInstallStatus() {
        installStatus = null
    }

    fun clearRootMountRecoveryMessage() {
        rootMountRecoveryMessage = null
    }

    fun confirmFallbackInstallPrompt() {
        val prompt = fallbackInstallPrompt ?: return
        val expectedPackage = lastInstallExpectedPackage ?: packageName
        val plan = installerManager.resolvePlanForToken(
            token = prompt.fallbackToken,
            target = prompt.target,
            sourceFile = outputFile,
            expectedPackage = expectedPackage,
            sourceLabel = lastInstallSourceLabel,
            allowMount = usingMountInstall && expectedPackage == packageName
        )
        fallbackInstallPrompt = null
        pendingInstallFailureMessage = null
        installFailureMessage = null
        installStatus = null
        if (plan == null) {
            val message = app.getString(R.string.installer_hint_generic)
            applyInstallFailure(message)
            return
        }
        recordInstallPlan(plan, expectedPackage, lastInstallSourceLabel)
        viewModelScope.launch {
            executeInstallPlan(plan)
        }
    }

    fun dismissFallbackInstallPrompt() {
        val message = pendingInstallFailureMessage
        fallbackInstallPrompt = null
        pendingInstallFailureMessage = null
        installFailureMessage = null
        installStatus = null
        if (message != null) {
            applyInstallFailure(message)
        }
    }

    data class FallbackInstallPrompt(
        val failureMessage: String,
        val fallbackLabel: String,
        val fallbackToken: InstallerManager.Token,
        val target: InstallerManager.InstallTarget
    )

    sealed class InstallCompletionStatus {
        data object InProgress : InstallCompletionStatus()
        data class Success(val packageName: String?) : InstallCompletionStatus()
        data class Failure(val message: String) : InstallCompletionStatus()
    }

    private suspend fun launchWorker(): UUID =
        workerRepository.launchExpedited<PatcherWorker, PatcherWorker.Args>(
            PatcherWorker.UNIQUE_WORK_NAME,
            buildWorkerArgs()
        )

    private suspend fun handleDownloaderActivityRequest(
        plugin: LoadedDownloaderPlugin,
        intent: Intent
    ): ActivityResult = withContext(Dispatchers.Main) {
        activityRequestMutex.withLock {
            val request = ActivityPromptRequest(
                completion = CompletableDeferred(),
                dialogState = ActivityPromptDialogState(
                    title = plugin.shortDisplayName,
                    requestId = nextActivityPromptRequestId++
                )
            )
            try {
                currentActivityRequest = request
                val accepted = try {
                    request.completion.await()
                } finally {
                    if (currentActivityRequest === request) {
                        currentActivityRequest = null
                    }
                }
                delay(DOWNLOADER_DIALOG_SETTLE_MS)
                if (!accepted) throw UserInteractionException.RequestDenied()

                try {
                    with(CompletableDeferred<ActivityResult>()) {
                        launchedActivity = this
                        launchActivityChannel.send(intent)
                        await()
                    }
                } finally {
                    launchedActivity = null
                }
            } finally {
                if (currentActivityRequest === request) {
                    currentActivityRequest = null
                }
            }
        }
    }

    private fun buildWorkerArgs(): PatcherWorker.Args {
        val selectedForRun = when (val selected = input.selectedApp) {
            is SelectedApp.Local -> {
                val reuseFile = inputFile ?: selected.file
                val temporary = if (forceKeepLocalInput) false else selected.temporary
                selected.copy(file = reuseFile, temporary = temporary)
            }

            else -> selected
        }

        val shouldPreserveInput =
            selectedForRun is SelectedApp.Local && (selectedForRun.temporary || forceKeepLocalInput)
        val resolvedPreparedInput = preparedInput
        val resolvedSplitSelection = selectedSplitConfiguration
        preparedInput = null
        preparedInputIncludesDownload = false
        selectedSplitConfiguration = null

        return PatcherWorker.Args(
            selectedForRun,
            outputFile.path,
            input.selectedPatches,
            input.options,
            skipApkSigning,
            logger,
            preparedInput = resolvedPreparedInput,
            splitSelection = resolvedSplitSelection,
            setInputFile = { file, needsSplit, merged ->
                val storedFile = if (shouldPreserveInput) {
                    val existing = inputFile
                    if (existing?.exists() == true) {
                        existing
                    } else withContext(Dispatchers.IO) {
                        val destination = File(fs.tempDir, "input-${System.currentTimeMillis()}.apk")
                        file.copyTo(destination, overwrite = true)
                        destination
                    }
                } else file

                withContext(Dispatchers.Main) {
                    inputFile = storedFile
                    updateSplitStepRequirement(storedFile, needsSplit, merged)
                }
            },
            handleStartActivityRequest = ::handleDownloaderActivityRequest,
            onEvent = ::handleProgressEvent
        )
    }

    private fun handleProgressEvent(update: PatcherWorkerProgressUpdate) {
        if (update.isMemorySample) {
            viewModelScope.launch {
                recordPatcherMemoryUsage(
                    generation = update.generation,
                    sequence = update.sequence,
                    memoryUsage = update.memoryUsage
                )
            }
        }
        update.event?.let { event ->
            enqueueWorkerProgressEvent(
                generation = update.generation,
                sequence = update.sequence,
                event = event,
                notificationProgressCurrent = update.notificationProgressCurrent,
                notificationProgressMax = update.notificationProgressMax
            )
        }
    }

    private fun recordPatcherMemoryUsage(
        generation: Long,
        sequence: Long,
        memoryUsage: PatcherMemoryUsage?
    ) {
        memoryUsage ?: return
        if (patcherMemoryUsageGeneration > generation) return
        if (
            patcherMemoryUsageGeneration == generation &&
            sequence <= patcherMemoryUsageSequence
        ) {
            return
        }
        val normalized = memoryUsage.copy(
            usedMb = memoryUsage.usedMb.coerceAtLeast(0L),
            maxMb = memoryUsage.maxMb.coerceAtLeast(1L),
            requestedMaxMb = memoryUsage.requestedMaxMb.coerceAtLeast(1L)
        )
        if (patcherMemoryUsageGeneration != generation) {
            patcherMemoryUsageGeneration = generation
            patcherMemoryUsageSequence = Long.MIN_VALUE
            patcherMemoryUsageSampleTimeMs = Long.MIN_VALUE
            patcherMemoryUsageSamples.clear()
        }
        if (normalized.sampledAtElapsedRealtimeMs <= patcherMemoryUsageSampleTimeMs) return
        patcherMemoryUsageSequence = sequence
        patcherMemoryUsageSampleTimeMs = normalized.sampledAtElapsedRealtimeMs
        patcherMemoryUsageSamples.add(normalized)
    }

    private fun enqueueWorkerProgressEvent(
        generation: Long,
        sequence: Long,
        event: ProgressEvent,
        notificationProgressCurrent: Int?,
        notificationProgressMax: Int?,
        failedPatchIndexes: Set<Int> = emptySet(),
        seedFromWorkerSnapshot: Boolean = false
    ) = viewModelScope.launch {
        progressEventMutex.withLock {
            if (!shouldApplyWorkerProgress(generation, sequence)) return@withLock
            recordAppliedWorkerProgress(generation, sequence)
            if (seedFromWorkerSnapshot) {
                seedVisualProgressFromWorkerProgress(
                    current = notificationProgressCurrent,
                    max = notificationProgressMax
                )
                seedProgressStateFromWorkerSnapshot(event, failedPatchIndexes)
            }
            processProgressEventLocked(event)
        }
    }

    private fun enqueueProgressEvent(
        event: ProgressEvent,
        seedFromWorkerSnapshot: Boolean = false
    ) = viewModelScope.launch {
        progressEventMutex.withLock {
            if (seedFromWorkerSnapshot) {
                seedProgressStateFromWorkerSnapshot(event)
            }
            processProgressEventLocked(event)
        }
    }

    private fun processProgressEventLocked(event: ProgressEvent) {
        prepareSplitStepForIncomingEvent(event)
        if (bufferLoadPatchesEventDuringSplitPreparation(event)) return
        applyProgressEvent(event)

        if (event.stepId == StepId.PrepareSplitApk) {
            when (event) {
                is ProgressEvent.Completed -> replayDeferredLoadPatchesEvents()
                is ProgressEvent.Failed -> clearDeferredLoadPatchesEvents()
                else -> Unit
            }
        }
        enforceSplitPreparationVisualPriority()
        refreshVisualProgress()
    }

    private fun applyProgressEvent(event: ProgressEvent) {
        val eventStepId = event.stepId
        if (shouldResetProgressStateForAutomaticRetry(event)) {
            resetProgressStateForAutomaticRetry()
        }
        val isDuplicateFailureWrapper = event is ProgressEvent.Failed &&
            lastPatchFailure?.matchesUnderlyingFailure(event.error) == true &&
            (
                eventStepId == null ||
                    (
                        eventStepId == StepId.ExecutePatches &&
                            lastPatchFailureStep == StepId.ExecutePatch::class.java.simpleName
                    )
            )
        val stepIndex = steps.indexOfFirst { step ->
            eventStepId?.let { id -> id == step.id }
                ?: (step.state == State.RUNNING || step.state == State.WAITING)
        }

        if (eventStepId != null && isExpandableStep(eventStepId)) {
            when (event) {
                is ProgressEvent.Started -> {
                    if (eventStepId == StepId.WriteAPK) {
                        resetDexCompileState()
                        writeApkStepStarted = true
                        if (!event.subSteps.isNullOrEmpty()) {
                            prepareSubSteps(eventStepId, event.subSteps)
                        } else {
                            stepSubSteps.remove(eventStepId)
                        }
                    } else {
                        if (!event.subSteps.isNullOrEmpty()) {
                            prepareSubSteps(eventStepId, event.subSteps)
                        } else {
                            stepSubSteps.remove(eventStepId)
                        }
                    }
                }
                is ProgressEvent.Progress -> {
                    val progress = event.current?.let { current -> current to event.total }
                    event.subSteps?.let { prepareSubSteps(eventStepId, it) }
                    if (!event.message.isNullOrBlank() || progress != null) {
                        updateSubStep(eventStepId, event.message, progress)
                    }
                }
                is ProgressEvent.Completed -> {
                    if (eventStepId == StepId.WriteAPK) {
                        writeApkStepStarted = false
                    }
                    finalizeSubSteps(eventStepId)
                }
                is ProgressEvent.Failed -> {
                    if (eventStepId == StepId.WriteAPK) {
                        writeApkStepStarted = false
                    }
                    finalizeSubSteps(
                        eventStepId,
                        failed = true,
                        errorMessage = event.error.message ?: event.error.type
                    )
                }
            }
        }

        if (stepIndex != -1) {
            val step = steps[stepIndex]
            val updatedStep = when (event) {
                is ProgressEvent.Started -> {
                    if (step.state == State.COMPLETED || step.state == State.FAILED) {
                        null
                    } else {
                        step.withState(State.RUNNING)
                    }
                }

                is ProgressEvent.Progress -> {
                    if (step.state == State.COMPLETED || step.state == State.FAILED) {
                        null
                    } else {
                        val nextState = if (step.state == State.WAITING) State.RUNNING else step.state
                        val nextMessage = if (eventStepId == StepId.LoadPatches) {
                            null
                        } else {
                            event.message ?: step.message
                        }
                        step.withState(
                            state = nextState,
                            message = nextMessage,
                            progress = event.current?.let { event.current to event.total } ?: step.progress
                        )
                    }
                }

                is ProgressEvent.Completed -> {
                    if (step.state == State.FAILED) {
                        null
                    } else {
                        step.withState(State.COMPLETED, progress = null)
                    }
                }

                is ProgressEvent.Failed -> {
                    if (isDuplicateFailureWrapper && steps.any { it.state == State.FAILED }) return
                    step.withState(
                        State.FAILED,
                        message = formatDisplayedFailure(event.error),
                        progress = null
                    )
                }
            }

            if (updatedStep != null) {
                steps[stepIndex] = updatedStep
                if (event is ProgressEvent.Completed && updatedStep.state == State.COMPLETED) {
                    promoteImmediateSignStepIfNeeded(stepIndex)
                    promoteNextSectionStepIfNeeded(stepIndex)
                }
            }
        }

        if (event is ProgressEvent.Failed) {
            val stepName = event.stepId?.let { it::class.java.simpleName } ?: "Unknown"
            val shouldRecordFailure = !isDuplicateFailureWrapper
            if (shouldRecordFailure) {
                lastPatchFailure = event.error
                lastPatchFailureStep = stepName
            }
            val shouldLogStandaloneFailure =
                shouldRecordFailure && event.stepId !is StepId.ExecutePatch
            if (shouldLogStandaloneFailure && shouldLogFailure(event.error)) {
                val message = event.error.message ?: event.error.type
                logger.error("Failure in step=$stepName: $message")
                logger.error(event.error.stackTrace)
            }
            handleKeystoreMissing(event.error)
        }
    }

    private fun bufferLoadPatchesEventDuringSplitPreparation(event: ProgressEvent): Boolean {
        if (event.stepId != StepId.LoadPatches) return false
        if (!shouldDeferLoadPatchesEventUntilSplitComplete()) return false

        deferLoadPatchesUntilSplitComplete = true
        deferredLoadPatchesEvents += event
        pauseLoadPatchesVisualProgress()
        return true
    }

    private fun shouldDeferLoadPatchesEventUntilSplitComplete(): Boolean {
        val splitStep = steps.firstOrNull { it.id == StepId.PrepareSplitApk } ?: return false
        return splitStep.state != State.COMPLETED && splitStep.state != State.FAILED
    }

    private fun prepareSplitStepForIncomingEvent(event: ProgressEvent) {
        if (event.stepId != StepId.PrepareSplitApk) return
        if (steps.none { it.id == StepId.PrepareSplitApk }) {
            requiresSplitPreparation = true
            addSplitStep()
        }
        when (event) {
            is ProgressEvent.Started,
            is ProgressEvent.Progress -> pauseLoadPatchesForSplitPreparation()
            else -> Unit
        }
    }

    private fun pauseLoadPatchesForSplitPreparation() {
        val loadIndex = steps.indexOfFirst { it.id == StepId.LoadPatches }
        if (loadIndex == -1) return

        val loadStep = steps[loadIndex]
        if (loadStep.state == State.WAITING || loadStep.state == State.FAILED) return

        if (deferredLoadPatchesStepSnapshot == null) {
            deferredLoadPatchesStepSnapshot = loadStep
        }
        deferLoadPatchesUntilSplitComplete = true
        pauseLoadPatchesVisualProgress()
    }

    private fun pauseLoadPatchesVisualProgress() {
        val loadIndex = steps.indexOfFirst { it.id == StepId.LoadPatches }
        if (loadIndex != -1) {
            val loadStep = steps[loadIndex]
            if (loadStep.state != State.FAILED) {
                steps[loadIndex] = loadStep.withState(
                    state = State.WAITING,
                    message = null,
                    progress = null
                )
            }
        }

        val splitIndex = steps.indexOfFirst { it.id == StepId.PrepareSplitApk }
        if (splitIndex == -1) return

        val splitStep = steps[splitIndex]
        if (splitStep.state == State.WAITING) {
            steps[splitIndex] = splitStep.withState(
                state = State.RUNNING,
                message = null,
                progress = null
            )
        }
    }

    private fun replayDeferredLoadPatchesEvents() {
        if (!deferLoadPatchesUntilSplitComplete) return
        val pending = deferredLoadPatchesEvents.toList()
        val snapshot = deferredLoadPatchesStepSnapshot
        deferredLoadPatchesEvents.clear()
        deferredLoadPatchesStepSnapshot = null
        deferLoadPatchesUntilSplitComplete = false
        if (pending.isEmpty() && snapshot != null) {
            val loadIndex = steps.indexOfFirst { it.id == StepId.LoadPatches }
            if (loadIndex != -1 && steps[loadIndex].state != State.FAILED) {
                steps[loadIndex] = snapshot
            }
            return
        }
        pending.forEach(::applyProgressEvent)
    }

    private fun clearDeferredLoadPatchesEvents() {
        deferredLoadPatchesEvents.clear()
        deferredLoadPatchesStepSnapshot = null
        deferLoadPatchesUntilSplitComplete = false
    }

    private fun enforceSplitPreparationVisualPriority() {
        if (!shouldDeferLoadPatchesEventUntilSplitComplete()) return
        pauseLoadPatchesVisualProgress()
    }

    private fun resetFailureLogState() {
        lastLoggedErrorSignature = null
        lastPatchFailure = null
        lastPatchFailureStep = null
    }

    private fun RemoteError.matchesUnderlyingFailure(other: RemoteError): Boolean =
        this == other || (
            stackTrace.isNotBlank() &&
                stackTrace == other.stackTrace
        )

    private fun conciseFailureMessage(error: RemoteError): String = (
        error.message
            ?.lineSequence()
            ?.firstOrNull(String::isNotBlank)
            ?: error.stackTrace.lineSequence().firstOrNull(String::isNotBlank)
            ?: error.type
        ).trim().take(FAILURE_LOG_SUMMARY_CHAR_LIMIT)

    private fun matchesBoundedLogMessage(loggedMessage: String, sourceMessage: String): Boolean {
        if (loggedMessage == sourceMessage) return true
        if (sourceMessage.length <= PATCHER_LOG_MESSAGE_CHAR_LIMIT) return false
        return loggedMessage.startsWith(sourceMessage.take(PATCHER_LOG_MESSAGE_CHAR_LIMIT)) &&
            loggedMessage.endsWith(
                "[log message truncated to $PATCHER_LOG_MESSAGE_CHAR_LIMIT characters]"
            )
    }

    private fun shouldLogFailure(error: app.urv.manager.patcher.RemoteError): Boolean {
        val signature = listOf(error.type, error.message, error.stackTrace).joinToString("|")
        if (signature == lastLoggedErrorSignature) return false
        lastLoggedErrorSignature = signature
        return true
    }

    private fun formatDisplayedFailure(error: app.urv.manager.patcher.RemoteError): String {
        if (error.type.contains("UserInteractionException")) {
            return error.message ?: "Downloader search cancelled by user."
        }
        return error.stackTrace
    }

    private fun handleKeystoreMissing(error: app.urv.manager.patcher.RemoteError) {
        if (keystoreMissingDialog) return
        val needle = "Keystore missing"
        val messageMatch = error.message?.contains(needle, ignoreCase = true) == true
        val stackMatch = error.stackTrace.contains(needle, ignoreCase = true)
        if (messageMatch || stackMatch) {
            keystoreMissingDialog = true
        }
    }

    private fun isExpandableStep(stepId: StepId) = when (stepId) {
        StepId.PrepareSplitApk,
        StepId.WriteAPK -> true
        else -> false
    }

    private fun prepareSubSteps(stepId: StepId, titles: List<String>) {
        val normalized = titles.filter { it.isNotBlank() }.map { it.trim() }
        val existing = stepSubSteps[stepId]
        val effectiveTitles = if (stepId == StepId.WriteAPK) {
            mergeWriteApkSubStepTitles(normalized, existing)
        } else {
            normalized
        }
        val list = buildSubStepList(effectiveTitles, existing, stepId)
        if (stepId == StepId.WriteAPK) {
            collapsePreparedWriteApkDexChildren(list)
            reconcilePreparedWriteApkSubSteps(list)
        }
        stepSubSteps[stepId] = list
        if (stepId == StepId.WriteAPK) {
            dexSubStepsReady = list.isNotEmpty()
            flushPendingDexCompileLines(force = true)
        }
    }

    private fun buildSubStepList(
        titles: List<String>,
        existing: List<StepDetail>?,
        stepId: StepId
    ): SnapshotStateList<StepDetail> {
        val list = mutableStateListOf<StepDetail>()
        titles.forEach { rawTitle ->
            val (title, skipped) = parseSubStepTitle(rawTitle)
            val previous = existing?.firstOrNull { it.title.equals(title, ignoreCase = true) }
            val effectiveSkipped = skipped || previous?.skipped == true
            val state = when {
                effectiveSkipped -> if (previous?.state == State.FAILED) State.FAILED else State.COMPLETED
                previous != null -> previous.state
                else -> State.WAITING
            }
            val expandable = when {
                previous != null -> previous.expandable
                stepId == StepId.WriteAPK && isDexCompileGroupTitle(title) -> true
                else -> false
            }
            list.add(
                previous?.copy(
                    title = title,
                    state = state,
                    skipped = effectiveSkipped,
                    expandable = expandable
                ) ?: StepDetail(
                    title = title,
                    state = state,
                    skipped = effectiveSkipped,
                    expandable = expandable
                )
            )
        }
        if (stepId == StepId.PrepareSplitApk && list.isNotEmpty()) {
            val extraction = list.filter {
                it.title.equals("Extracting split APKs", ignoreCase = true)
            }
            val remaining = list.filterNot {
                it.title.equals("Extracting split APKs", ignoreCase = true)
            }
            val ordered = extraction + remaining.filter { it.skipped } + remaining.filter { !it.skipped }
            list.clear()
            list.addAll(ordered)
        }
        return list
    }

    private fun mergeWriteApkSubStepTitles(
        incomingTitles: List<String>,
        existing: List<StepDetail>?
    ): List<String> {
        val incoming = incomingTitles
            .map { normalizeWriteApkTitle(StepId.WriteAPK, it) }
            .filter { it.isNotBlank() }
        val incomingDexTitles = incoming
            .filter(::isWriteApkDexChildTitle)
            .distinctBy { it.lowercase() }

        val existingDexTitles = existing.orEmpty()
            .map { it.title }
            .filter(::isWriteApkDexChildTitle)
            .distinctBy { it.lowercase() }
        val merged = incoming.toMutableList()
        if ((incomingDexTitles.isNotEmpty() || existingDexTitles.isNotEmpty()) &&
            merged.none(::isDexCompileGroupTitle)
        ) {
            merged.add(writeApkDexInsertIndex(merged), currentWriteApkDexGroupTitle())
        }
        return merged.distinctBy { it.lowercase() }
    }

    private fun writeApkDexInsertIndex(titles: List<String>): Int {
        return titles.indexOfFirst(::isResourceCompileTitle).takeIf { it != -1 }
            ?: titles.indexOfFirst { it.equals("Writing output APK", ignoreCase = true) }
                .takeIf { it != -1 }
            ?: titles.indexOfFirst { it.equals("Finalizing output", ignoreCase = true) }
                .takeIf { it != -1 }
            ?: titles.size
    }

    private fun updateSubStep(
        stepId: StepId,
        message: String?,
        progress: Pair<Long, Long?>?
    ) {
        val list = stepSubSteps.getOrPut(stepId) { mutableStateListOf() }
        if (message.isNullOrBlank()) {
            if (progress != null && list.isNotEmpty()) {
                val runningIndex = list.indexOfFirst { it.state == State.RUNNING }
                val targetIndex = if (runningIndex != -1) runningIndex else list.lastIndex
                val target = list[targetIndex]
                list[targetIndex] = target.copy(progress = progress)
            }
            return
        }

        val title = message.trim()
        val splitNormalized = if (stepId == StepId.PrepareSplitApk) {
            normalizeSplitApkTitle(title)
        } else {
            title
        }
        val normalized = normalizeWriteApkTitle(stepId, splitNormalized)
        if (stepId == StepId.WriteAPK) {
            when {
                normalized.equals("Writing patched files...", ignoreCase = true) -> {
                    activateWriteApkFromWritingPatchedFiles(list)
                    return
                }

                isWriteApkDexChildTitle(normalized) -> {
                    updateWriteApkDexChildSubStep(list, normalized)
                    return
                }

                isDexCompilePhaseTitle(normalized) || isDexCompileGroupTitle(normalized) -> {
                    activateWriteApkDexGroup(list)
                    return
                }

                isResourceCompileTitle(normalized) -> {
                    activateResourceCompileStep(list, progress)
                    return
                }

                normalized.equals("Writing output APK", ignoreCase = true) ||
                    normalized.equals("Finalizing output", ignoreCase = true) ||
                    normalized.equals("Stripping native libraries", ignoreCase = true) -> {
                    activateKnownWriteApkSubStep(list, normalized, progress)
                    return
                }
            }
        }
        var existingIndex = list.indexOfFirst { it.title == normalized }
        val runningIndex = list.indexOfFirst { !it.skipped && it.state == State.RUNNING }
        if (stepId == StepId.PrepareSplitApk && list.isNotEmpty()) {
            if (normalized.startsWith("Merging ", ignoreCase = true)) {
                if (existingIndex == -1) {
                    existingIndex = findBestSubStepIndex(list, normalized)
                    if (existingIndex == -1) {
                        return
                    }
                }
                if (runningIndex != -1 && existingIndex < runningIndex) {
                    val stale = list[existingIndex]
                    if (!stale.skipped && stale.state != State.COMPLETED) {
                        list[existingIndex] = stale.copy(state = State.COMPLETED, progress = null)
                    }
                    return
                }
                completePrepareSplitApkPriorSteps(list, existingIndex)
            }
        }
        if (stepId == StepId.PrepareSplitApk &&
            (normalized.equals("Writing merged APK", ignoreCase = true)
                || normalized.equals("Finalizing merged APK", ignoreCase = true)
                || normalized.equals("Stripping native libraries", ignoreCase = true))
        ) {
            val limit = if (existingIndex != -1) existingIndex else list.size
            for (index in 0 until limit) {
                val detail = list[index]
                if (detail.skipped || detail.state == State.COMPLETED) continue
                list[index] = detail.copy(state = State.COMPLETED, progress = null)
            }
        }
        if (existingIndex == -1 && list.isNotEmpty()) {
            existingIndex = findBestSubStepIndex(list, normalized)
        }
        if (existingIndex != -1) {
            if (list[existingIndex].skipped) return
            if (stepId == StepId.WriteAPK && existingIndex > 0 && (runningIndex == -1 || existingIndex >= runningIndex)) {
                completeWriteApkPriorSteps(list, existingIndex)
            }
            if (stepId == StepId.PrepareSplitApk && runningIndex != -1 && existingIndex < runningIndex) {
                val existing = list[existingIndex]
                if (existing.state != State.COMPLETED) {
                    list[existingIndex] = existing.copy(state = State.COMPLETED, progress = null)
                }
                return
            }
            if (runningIndex != -1 && existingIndex < runningIndex) {
                return
            }
            if (runningIndex != -1 && runningIndex != existingIndex) {
                val running = list[runningIndex]
                list[runningIndex] = running.copy(state = State.COMPLETED, progress = null)
            }
            val existing = list[existingIndex]
            list[existingIndex] = existing.copy(
                state = if (existing.state == State.COMPLETED) State.COMPLETED else State.RUNNING,
                progress = progress
            )
            return
        }

        if (list.isNotEmpty()) {
            return
        }

        if (runningIndex != -1) {
            val running = list[runningIndex]
            list[runningIndex] = running.copy(state = State.COMPLETED, progress = null)
        }

        list.add(StepDetail(title = normalized, state = State.RUNNING, progress = progress))
    }

    private fun normalizeWriteApkTitle(stepId: StepId, title: String): String {
        if (stepId != StepId.WriteAPK) return title
        if (isMorpheSelection()) {
            when {
                title.equals("Copying base APK", ignoreCase = true) -> return "Copy base APK"
                title.equals("Compiling patched dex files", ignoreCase = true) ->
                    return currentWriteApkDexGroupTitle()
                dexCompilePattern.containsMatchIn(title) || dexWritePattern.containsMatchIn(title) ->
                    return ""
                morpheProcessingClassesPattern.containsMatchIn(title) ->
                    return "Processing ${morpheProcessingClassesPattern.find(title)?.groupValues?.get(1)} classes"
                morpheWroteDexFilesPattern.containsMatchIn(title) ->
                    return "Wrote ${morpheWroteDexFilesPattern.find(title)?.groupValues?.get(1)} dex files"
                morpheStrippedDexPattern.containsMatchIn(title) -> {
                    val dexName = morpheStrippedDexPattern.find(title)?.groupValues?.get(1) ?: return title
                    return "Modified $dexName"
                }
            }
        }
        if (title.equals("Compiling patched dex files", ignoreCase = true)) {
            return currentWriteApkDexGroupTitle()
        }
        if (title.equals("Compiling patched resources", ignoreCase = true) ||
            title.equals("Compiled patched resources", ignoreCase = true)
        ) {
            return "Compiling modified resources"
        }
        return if (title.startsWith("Compiled ", ignoreCase = true)) {
            "Compiling " + title.removePrefix("Compiled ").trim()
        } else {
            title
        }
    }

    private fun normalizeSplitApkTitle(title: String): String {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return trimmed
        val prefix = when {
            trimmed.startsWith("Merging:", ignoreCase = true) -> "Merging:"
            trimmed.startsWith("Merging ", ignoreCase = true) -> "Merging "
            else -> return trimmed
        }
        val raw = trimmed.substringAfter(prefix).trim()
        if (raw.isEmpty()) return trimmed
        val name = if (raw.endsWith(".apk", ignoreCase = true)) raw else "$raw.apk"
        return "Merging $name"
    }

    private fun isDexCompileTitle(title: String): Boolean {
        if (!title.startsWith("Compiling ", ignoreCase = true)) return false
        val suffix = title.removePrefix("Compiling ").trim()
        return suffix.startsWith("classes") && suffix.endsWith(".dex")
    }

    private fun isMorpheWriteApkDexChildTitle(title: String): Boolean {
        return title.startsWith("Processing ", ignoreCase = true) &&
            title.endsWith(" classes", ignoreCase = true) ||
            title.startsWith("Wrote ", ignoreCase = true) &&
            title.contains(" dex files", ignoreCase = true) ||
            title.startsWith("Modified classes", ignoreCase = true) &&
            title.endsWith(".dex", ignoreCase = true)
    }

    private fun isWriteApkDexChildTitle(title: String): Boolean =
        if (isMorpheSelection()) {
            isMorpheWriteApkDexChildTitle(title)
        } else {
            isDexCompileTitle(title)
        }

    private fun writeApkDexSortKey(name: String): Int {
        val base = name.removeSuffix(".dex")
        if (base.equals("classes", ignoreCase = true)) return 1
        val suffix = base.removePrefix("classes")
        return suffix.toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun isDexCompilePhaseTitle(title: String): Boolean =
        title.equals("Compiling patched dex files", ignoreCase = true) ||
            isDexCompileGroupTitle(title)

    private fun isDexCompileGroupTitle(title: String): Boolean =
        title.equals(WRITE_APK_DEX_GROUP_TITLE, ignoreCase = true) ||
            title.startsWith("$WRITE_APK_DEX_GROUP_TITLE:", ignoreCase = true)

    private fun isResourceCompileTitle(title: String): Boolean =
        title.equals("Compiling modified resources", ignoreCase = true) ||
            title.equals("Compiling patched resources", ignoreCase = true)

    private fun isMorpheSelection(): Boolean = selectionBundleType == PatchBundleType.MORPHE

    private fun currentWriteApkDexGroupTitle(): String {
        if (!isMorpheSelection()) return WRITE_APK_DEX_GROUP_TITLE
        return if (selectionMorpheBytecodeMode.equals("FULL", ignoreCase = true)) {
            "Compiling DEX files: FULL"
        } else {
            "Compiling DEX files: FAST"
        }
    }

    private fun resetDexCompileState() {
        dexSubStepsReady = false
        pendingDexCompileLines.clear()
        writeApkStepStarted = false
    }

    private fun reconcileProgressStateAfterSuccess() {
        resetDexCompileState()
        resetFailureLogState()
        steps.forEachIndexed { index, step ->
            if (step.state == State.FAILED) return@forEachIndexed
            steps[index] = step.withState(
                state = State.COMPLETED,
                message = null,
                progress = null
            )
        }
        stepSubSteps.forEach { (_, list) ->
            list.forEachIndexed { index, detail ->
                if (detail.state == State.FAILED) return@forEachIndexed
                list[index] = detail.withRecursiveState(
                    state = State.COMPLETED,
                    message = null,
                    progress = null
                )
            }
        }
        progress = 1f
    }

    private fun resetVisualProgress() {
        progress = 0f
    }

    private data class VisualProgressUnits(
        val completed: Double,
        val total: Int
    )

    private fun refreshVisualProgress() {
        val totalSteps = steps.size
        if (totalSteps <= 0) {
            progress = 0f
            return
        }

        val completedSteps = steps.sumOf(::calculateProgressFraction)
        val candidate = (completedSteps / totalSteps.toDouble()).toFloat().coerceIn(0f, 1f)
        if (candidate > progress) {
            progress = candidate
        }
    }

    private fun calculateProgressFraction(step: Step): Double {
        if (step.state == State.COMPLETED) return 1.0

        val subSteps = stepSubSteps[step.id].orEmpty()
            .filterNot { it.skipped }
            .map(::calculateProgressUnits)
        val subStepTotal = subSteps.sumOf { it.total }
        if (subStepTotal > 0) {
            return (subSteps.sumOf { it.completed } / subStepTotal.toDouble())
                .coerceIn(0.0, 1.0)
        }

        return step.state.progressFraction(step.progress)
    }

    private fun calculateProgressUnits(detail: StepDetail): VisualProgressUnits {
        val children = detail.children
            .filterNot { it.skipped }
            .map(::calculateProgressUnits)
        val childTotal = children.sumOf { it.total }
        if (childTotal > 0) {
            val completed = if (detail.state == State.COMPLETED) {
                childTotal.toDouble()
            } else {
                children.sumOf { it.completed }
            }
            return VisualProgressUnits(completed = completed, total = childTotal)
        }

        return VisualProgressUnits(
            completed = detail.state.progressFraction(detail.progress),
            total = 1
        )
    }

    private fun State.progressFraction(progress: Pair<Long, Long?>?): Double = when (this) {
        State.COMPLETED -> 1.0
        State.RUNNING -> {
            val current = progress?.first
            val total = progress?.second?.takeIf { it > 0L }
            if (current != null && total != null) {
                (current.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
        }
        else -> 0.0
    }

    private fun flushPendingDexCompileLines(force: Boolean = false) {
        if (pendingDexCompileLines.isEmpty()) return
        val list = stepSubSteps[StepId.WriteAPK] ?: return
        val iterator = pendingDexCompileLines.iterator()
        while (iterator.hasNext()) {
            val title = iterator.next()
            val hasEntry = list.any { it.title.equals(title, ignoreCase = true) }
            if (force || hasEntry) {
                updateSubStep(StepId.WriteAPK, title, null)
                iterator.remove()
            }
        }
    }

    private fun completeWriteApkApplyChanges(list: SnapshotStateList<StepDetail>) {
        val index = list.indexOfFirst {
            it.title.equals("Applying patched changes", ignoreCase = true)
        }
        if (index == -1) return
        val detail = list[index]
        if (detail.state == State.COMPLETED) return
        list[index] = detail.copy(state = State.COMPLETED, progress = null)
    }

    private fun completeResourceCompileIfPending(list: SnapshotStateList<StepDetail>) {
        val index = list.indexOfFirst {
            isResourceCompileTitle(it.title)
        }
        if (index == -1) return
        val detail = list[index]
        if (detail.skipped || detail.state == State.COMPLETED) return
        list[index] = detail.copy(state = State.COMPLETED, progress = null)
    }

    private fun completeWriteApkPriorSteps(
        list: SnapshotStateList<StepDetail>,
        untilExclusive: Int
    ) {
        if (untilExclusive <= 0) return
        val limit = untilExclusive.coerceAtMost(list.size)
        for (index in 0 until limit) {
            val detail = list[index]
            if (detail.skipped || detail.state == State.COMPLETED) continue
            list[index] = detail.copy(state = State.COMPLETED, progress = null)
        }
    }

    private fun reconcilePreparedWriteApkSubSteps(list: SnapshotStateList<StepDetail>) {
        val activeIndex = list.indexOfLast { detail ->
            !detail.skipped && (
                detail.state == State.RUNNING ||
                    detail.state == State.COMPLETED ||
                    detail.children.any { child ->
                        !child.skipped &&
                            (child.state == State.RUNNING || child.state == State.COMPLETED)
                    }
                )
        }
        if (activeIndex == -1) return

        val activeDetail = list[activeIndex]
        if (activeDetail.state == State.WAITING && activeDetail.children.isNotEmpty()) {
            list[activeIndex] = activeDetail.copy(
                state = State.RUNNING,
                progress = null,
                expandable = true
            )
        }
        completeWriteApkPriorSteps(list, activeIndex)
        if (isMorpheSelection()) {
            promoteNextWriteApkSubStep(list, activeIndex)
        }
    }

    private fun collapsePreparedWriteApkDexChildren(list: SnapshotStateList<StepDetail>) {
        val flatDexEntries = list
            .filter(::isPreparedWriteApkDexChild)
        if (flatDexEntries.isEmpty()) return

        val groupIndex = ensureWriteApkDexGroupIndex(list)
        val group = list[groupIndex]
        val mergedChildren = (group.children + flatDexEntries)
            .distinctBy { it.title.lowercase() }
            .let { children ->
                if (isMorpheSelection()) {
                    children
                } else {
                    children.sortedBy { writeApkDexSortKey(it.title.removePrefix("Compiling ").trim()) }
                }
            }

        for (index in list.lastIndex downTo 0) {
            if (index == groupIndex) continue
            if (isPreparedWriteApkDexChild(list[index])) {
                list.removeAt(index)
            }
        }

        val updatedGroupIndex = list.indexOfFirst(::matchesWriteApkDexGroup)
        if (updatedGroupIndex == -1) return
        val updatedGroup = list[updatedGroupIndex]
        list[updatedGroupIndex] = updatedGroup.copy(
            expandable = true,
            children = mergedChildren
        )
    }

    private fun isPreparedWriteApkDexChild(detail: StepDetail): Boolean =
        isWriteApkDexChildTitle(detail.title)

    private fun completePrepareSplitApkPriorSteps(
        list: SnapshotStateList<StepDetail>,
        untilExclusive: Int
    ) {
        if (untilExclusive <= 0) return
        val limit = untilExclusive.coerceAtMost(list.size)
        for (index in 0 until limit) {
            val detail = list[index]
            if (detail.skipped || detail.state == State.COMPLETED) continue
            list[index] = detail.copy(state = State.COMPLETED, progress = null)
        }
    }

    private fun promoteNextWriteApkSubStep(
        list: SnapshotStateList<StepDetail>,
        completedIndex: Int
    ) {
        val runningIndex = list.indexOfFirst { !it.skipped && it.state == State.RUNNING }
        if (runningIndex != -1) return

        val nextIndex = ((completedIndex + 1) until list.size)
            .firstOrNull { index ->
                val detail = list[index]
                !detail.skipped && detail.state == State.WAITING
            }
            ?: return

        val next = list[nextIndex]
        list[nextIndex] = next.copy(state = State.RUNNING, progress = null)
    }

    private fun ensureWriteApkDexGroupIndex(list: SnapshotStateList<StepDetail>): Int {
        val existingIndex = list.indexOfFirst(::matchesWriteApkDexGroup)
        if (existingIndex != -1) return existingIndex

        val insertIndex = writeApkDexInsertIndex(list.map { it.title })
        list.add(
            insertIndex,
            StepDetail(
                title = currentWriteApkDexGroupTitle(),
                state = State.WAITING,
                expandable = true
            )
        )
        return insertIndex
    }

    private fun matchesWriteApkDexGroup(detail: StepDetail): Boolean =
        isDexCompileGroupTitle(detail.title)

    private fun activateWriteApkDexGroup(list: SnapshotStateList<StepDetail>) {
        val groupIndex = ensureWriteApkDexGroupIndex(list)
        completeWriteApkApplyChanges(list)
        completeWriteApkPriorSteps(list, groupIndex)

        val runningIndex = list.indexOfFirst { !it.skipped && it.state == State.RUNNING }
        if (runningIndex != -1 && runningIndex != groupIndex) {
            val running = list[runningIndex]
            list[runningIndex] = running.copy(state = State.COMPLETED, progress = null)
        }

        val group = list[groupIndex]
        if (group.state != State.COMPLETED) {
            list[groupIndex] = group.copy(state = State.RUNNING, progress = null, expandable = true)
        }
    }

    private fun activateWriteApkFromWritingPatchedFiles(list: SnapshotStateList<StepDetail>) {
        val applyIndex = list.indexOfFirst {
            it.title.equals("Applying patched changes", ignoreCase = true)
        }
        if (applyIndex != -1) {
            completeWriteApkPriorSteps(list, applyIndex + 1)
            val apply = list[applyIndex]
            if (apply.state != State.COMPLETED) {
                list[applyIndex] = apply.copy(state = State.COMPLETED, progress = null)
            }
            if (isMorpheSelection()) {
                promoteNextWriteApkSubStep(list, applyIndex)
            }
            return
        }

        val copyIndex = list.indexOfFirst {
            it.title.equals("Copy base APK", ignoreCase = true)
        }
        if (copyIndex != -1) {
            completeWriteApkPriorSteps(list, copyIndex + 1)
            if (isMorpheSelection()) {
                promoteNextWriteApkSubStep(list, copyIndex)
            }
        }
    }

    private fun updateWriteApkDexChildSubStep(
        list: SnapshotStateList<StepDetail>,
        normalizedTitle: String
    ) {
        if (isMorpheSelection() && isMorpheWriteApkDexChildTitle(normalizedTitle)) {
            updateMorpheWriteApkDexChildSubStep(list, normalizedTitle)
            return
        }

        val groupIndex = ensureWriteApkDexGroupIndex(list)
        val group = list[groupIndex]
        val initialChildren = group.children
        val initialRunningChildIndex = initialChildren.indexOfFirst { !it.skipped && it.state == State.RUNNING }
        val initialExistingIndex = initialChildren.indexOfFirst { it.title.equals(normalizedTitle, ignoreCase = true) }

        if (initialExistingIndex != -1 && initialChildren[initialExistingIndex].state == State.COMPLETED) {
            reconcilePreparedWriteApkSubSteps(list)
            return
        }

        if (initialRunningChildIndex != -1 &&
            initialChildren[initialRunningChildIndex].title.equals(normalizedTitle, ignoreCase = true)
        ) {
            reconcilePreparedWriteApkSubSteps(list)
            return
        }

        activateWriteApkDexGroup(list)
        val activatedGroup = list[groupIndex]
        val children = activatedGroup.children.toMutableList()
        val runningChildIndex = children.indexOfFirst { !it.skipped && it.state == State.RUNNING }
        val existingIndex = children.indexOfFirst { it.title.equals(normalizedTitle, ignoreCase = true) }

        if (runningChildIndex != -1) {
            val runningChild = children[runningChildIndex]
            children[runningChildIndex] = runningChild.copy(state = State.COMPLETED, progress = null)
        }

        val targetIndex = if (existingIndex != -1) existingIndex else children.size
        for (index in 0 until targetIndex) {
            val child = children[index]
            if (!child.skipped && child.state != State.COMPLETED) {
                children[index] = child.copy(state = State.COMPLETED, progress = null)
            }
        }

        if (existingIndex != -1) {
            val existingChild = children[existingIndex]
            children[existingIndex] = existingChild.copy(state = State.RUNNING, progress = null)
        } else {
            children.add(StepDetail(title = normalizedTitle, state = State.RUNNING))
        }

        list[groupIndex] = list[groupIndex].copy(
            state = State.RUNNING,
            progress = null,
            expandable = true,
            children = children
        )
    }

    private fun updateMorpheWriteApkDexChildSubStep(
        list: SnapshotStateList<StepDetail>,
        normalizedTitle: String
    ) {
        val groupIndex = ensureWriteApkDexGroupIndex(list)
        val group = list[groupIndex]
        val initialChildren = group.children
        val initialRunningChildIndex = initialChildren.indexOfFirst { !it.skipped && it.state == State.RUNNING }
        val initialExistingIndex = initialChildren.indexOfFirst { it.title.equals(normalizedTitle, ignoreCase = true) }

        if (initialExistingIndex != -1 && initialChildren[initialExistingIndex].state == State.COMPLETED) {
            reconcilePreparedWriteApkSubSteps(list)
            return
        }

        if (initialRunningChildIndex != -1 &&
            initialChildren[initialRunningChildIndex].title.equals(normalizedTitle, ignoreCase = true)
        ) {
            reconcilePreparedWriteApkSubSteps(list)
            return
        }

        activateWriteApkDexGroup(list)
        val activatedGroup = list[groupIndex]
        val children = activatedGroup.children.toMutableList()
        val runningChildIndex = children.indexOfFirst { !it.skipped && it.state == State.RUNNING }
        val existingIndex = children.indexOfFirst { it.title.equals(normalizedTitle, ignoreCase = true) }

        if (runningChildIndex != -1) {
            val runningChild = children[runningChildIndex]
            children[runningChildIndex] = runningChild.copy(state = State.COMPLETED, progress = null)
        }

        if (existingIndex != -1) {
            val existingChild = children[existingIndex]
            children[existingIndex] = existingChild.copy(state = State.RUNNING, progress = null)
        } else {
            children.add(StepDetail(title = normalizedTitle, state = State.RUNNING))
        }

        list[groupIndex] = list[groupIndex].copy(
            state = State.RUNNING,
            progress = null,
            expandable = true,
            children = children
        )
    }

    private fun completeWriteApkDexGroup(list: SnapshotStateList<StepDetail>) {
        val groupIndex = list.indexOfFirst(::matchesWriteApkDexGroup)
        if (groupIndex == -1) return

        val group = list[groupIndex]
        val children = group.children.toMutableList()
        for (index in children.indices) {
            val child = children[index]
            if (!child.skipped && child.state != State.COMPLETED) {
                children[index] = child.copy(state = State.COMPLETED, progress = null)
            }
        }

        val finalState = if (group.state == State.WAITING && children.isEmpty()) State.WAITING else State.COMPLETED
        list[groupIndex] = group.copy(
            state = finalState,
            progress = null,
            expandable = true,
            children = children
        )
        if (isMorpheSelection()) {
            promoteNextWriteApkSubStep(list, groupIndex)
        }
    }

    private fun activateResourceCompileStep(
        list: SnapshotStateList<StepDetail>,
        progress: Pair<Long, Long?>?
    ) {
        val resourceIndex = list.indexOfFirst {
            isResourceCompileTitle(it.title)
        }.takeIf { it != -1 } ?: run {
            val insertIndex = list.indexOfFirst {
                it.title.equals("Writing output APK", ignoreCase = true)
            }.takeIf { it != -1 } ?: list.size
            list.add(insertIndex, StepDetail(title = "Compiling modified resources", state = State.WAITING))
            insertIndex
        }
        completeWriteApkPriorSteps(list, resourceIndex)
        completeWriteApkDexGroup(list)

        val runningIndex = list.indexOfFirst { it.state == State.RUNNING }
        if (runningIndex != -1 && runningIndex != resourceIndex) {
            val running = list[runningIndex]
            list[runningIndex] = running.copy(state = State.COMPLETED, progress = null)
        }

        val resourceStep = list[resourceIndex]
        if (resourceStep.state == State.COMPLETED) return
        list[resourceIndex] = resourceStep.copy(state = State.RUNNING, progress = progress)
    }

    private fun activateKnownWriteApkSubStep(
        list: SnapshotStateList<StepDetail>,
        normalizedTitle: String,
        progress: Pair<Long, Long?>?
    ) {
        val existingIndex = list.indexOfFirst { it.title.equals(normalizedTitle, ignoreCase = true) }
        if (existingIndex == -1) return

        completeWriteApkDexGroup(list)
        completeWriteApkPriorSteps(list, existingIndex)

        val runningIndex = list.indexOfFirst { !it.skipped && it.state == State.RUNNING }
        if (runningIndex != -1 && runningIndex != existingIndex) {
            val running = list[runningIndex]
            list[runningIndex] = running.copy(state = State.COMPLETED, progress = null)
        }

        val existing = list[existingIndex]
        list[existingIndex] = existing.copy(
            state = if (existing.state == State.COMPLETED) State.COMPLETED else State.RUNNING,
            progress = progress
        )
    }

    private fun handleDexCompileLine(rawLine: String) {
        val line = rawLine.trim()
        if (line.isEmpty()) return
        if (line.startsWith("[STDIO]:", ignoreCase = true)) return
        if (!writeApkStepStarted && shouldStartWriteApkFromLog(line)) {
            startWriteApkFromLogFallback()
        }
        if (!writeApkStepStarted) return
        if (line.contains("Writing patched files", ignoreCase = true)) {
            viewModelScope.launch {
                if (shouldBufferWriteApkLogProgress()) {
                    pendingDexCompileLines += "Writing patched files..."
                    return@launch
                }
                updateSubStep(StepId.WriteAPK, "Writing patched files...", null)
            }
            return
        }
        if (line.contains("Compiling modified resources", ignoreCase = true) ||
            line.contains("Compiling patched resources", ignoreCase = true)
        ) {
            viewModelScope.launch {
                if (shouldBufferWriteApkLogProgress()) {
                    pendingDexCompileLines += "Compiling modified resources"
                    return@launch
                }
                if (!hasWriteApkApplyPhaseStarted()) return@launch
                updateSubStep(StepId.WriteAPK, line, null)
            }
            return
        }
        if (isDexCompilePhaseTitle(line)) {
            viewModelScope.launch {
                if (shouldBufferWriteApkLogProgress()) {
                    pendingDexCompileLines += line
                    return@launch
                }
                if (!hasWriteApkApplyPhaseStarted()) return@launch
                updateSubStep(StepId.WriteAPK, line, null)
            }
            return
        }
        morpheProcessingClassesPattern.find(line)?.let { match ->
            val title = "Processing ${match.groupValues[1]} classes"
            viewModelScope.launch {
                if (shouldBufferWriteApkLogProgress()) {
                    pendingDexCompileLines += title
                    return@launch
                }
                if (!hasWriteApkApplyPhaseStarted()) return@launch
                updateSubStep(StepId.WriteAPK, title, null)
            }
            return
        }
        morpheWroteDexFilesPattern.find(line)?.let { match ->
            val title = "Wrote ${match.groupValues[1]} dex files"
            viewModelScope.launch {
                if (shouldBufferWriteApkLogProgress()) {
                    pendingDexCompileLines += title
                    return@launch
                }
                if (!hasWriteApkApplyPhaseStarted()) return@launch
                updateSubStep(StepId.WriteAPK, title, null)
            }
            return
        }
        morpheStrippedDexPattern.find(line)?.let { match ->
            val title = "Modified ${match.groupValues[1]}"
            viewModelScope.launch {
                if (shouldBufferWriteApkLogProgress()) {
                    pendingDexCompileLines += title
                    return@launch
                }
                if (!hasWriteApkApplyPhaseStarted()) return@launch
                updateSubStep(StepId.WriteAPK, title, null)
            }
            return
        }
        if (isMorpheSelection()) return
        val match = dexCompilePattern.find(line) ?: dexWritePattern.find(line) ?: return
        val completionKeyword = match.groupValues.getOrNull(1)
        val dexName = match.groupValues.lastOrNull()?.takeIf { it.endsWith(".dex") } ?: return
        viewModelScope.launch {
            val isCompletion = completionKeyword.equals("Compiled", ignoreCase = true)
            val title = if (isCompletion) "Compiled $dexName" else "Compiling $dexName"
            if (shouldBufferWriteApkLogProgress()) {
                pendingDexCompileLines += title
                return@launch
            }
            if (!hasWriteApkApplyPhaseStarted()) return@launch
            updateSubStep(StepId.WriteAPK, title, null)
        }
    }

    private fun shouldBufferWriteApkLogProgress(): Boolean {
        val list = stepSubSteps[StepId.WriteAPK] ?: return true
        return list.isEmpty()
    }

    private fun hasWriteApkApplyPhaseStarted(): Boolean {
        val list = stepSubSteps[StepId.WriteAPK] ?: return false
        val applyStep = list.firstOrNull {
            it.title.equals("Applying patched changes", ignoreCase = true)
        } ?: return false
        return applyStep.state == State.RUNNING || applyStep.state == State.COMPLETED
    }

    private fun shouldStartWriteApkFromLog(line: String): Boolean {
        if (line.contains("Writing patched files", ignoreCase = true)) return true
        if (line.contains("Compiling patched dex files", ignoreCase = true)) return true
        if (line.contains("Applying patched changes", ignoreCase = true)) return true
        if (line.contains("Compiled modified resources", ignoreCase = true)) return true
        if (line.contains("Compiled patched resources", ignoreCase = true)) return true
        if (line.contains("Compiling modified resources", ignoreCase = true)) return true
        if (line.contains("Writing output APK", ignoreCase = true)) return true
        if (line.contains("Finalizing output", ignoreCase = true)) return true
        if (line.contains("Patched apk saved to", ignoreCase = true)) return true
        if (morpheProcessingClassesPattern.containsMatchIn(line)) return true
        if (morpheWroteDexFilesPattern.containsMatchIn(line)) return true
        if (morpheStrippedDexPattern.containsMatchIn(line)) return true
        if (dexCompilePattern.containsMatchIn(line)) return true
        if (dexWritePattern.containsMatchIn(line)) return true
        return false
    }

    private fun startWriteApkFromLogFallback() {
        writeApkStepStarted = true
        val writeIndex = steps.indexOfFirst { it.id == StepId.WriteAPK }
        if (writeIndex == -1) return

        val runningIndex = steps.indexOfFirst { it.state == State.RUNNING }
        if (runningIndex != -1 && runningIndex != writeIndex && runningIndex < writeIndex) {
            val running = steps[runningIndex]
            steps[runningIndex] = running.withState(State.COMPLETED, progress = null)
        }

        val writeStep = steps[writeIndex]
        if (writeStep.state == State.WAITING) {
            steps[writeIndex] = writeStep.withState(State.RUNNING)
        }
    }

    private fun promoteNextSectionStepIfNeeded(completedIndex: Int) {
        val completedStep = steps.getOrNull(completedIndex) ?: return
        if (completedStep.hide) return
        if (!isLastVisibleStepInSection(completedIndex)) return

        val nextVisibleIndex = ((completedIndex + 1) until steps.size)
            .firstOrNull { !steps[it].hide }
            ?: return
        val nextStep = steps[nextVisibleIndex]
        if (nextStep.category == completedStep.category) return
        if (nextStep.state != State.WAITING) return

        val anotherVisibleRunning = steps.indices.any { index ->
            index != nextVisibleIndex && !steps[index].hide && steps[index].state == State.RUNNING
        }
        if (anotherVisibleRunning) return

        steps[nextVisibleIndex] = nextStep.withState(
            state = State.RUNNING,
            message = null,
            progress = null
        )
    }

    private fun promoteImmediateSignStepIfNeeded(completedIndex: Int) {
        val completedStep = steps.getOrNull(completedIndex) ?: return
        if (completedStep.id != StepId.WriteAPK || completedStep.hide) return

        val signIndex = ((completedIndex + 1) until steps.size)
            .firstOrNull { index ->
                val step = steps[index]
                !step.hide && step.id == StepId.SignAPK
            }
            ?: return

        val signStep = steps[signIndex]
        if (signStep.state != State.WAITING) return

        val anotherVisibleRunning = steps.indices.any { index ->
            index != signIndex && !steps[index].hide && steps[index].state == State.RUNNING
        }
        if (anotherVisibleRunning) return

        steps[signIndex] = signStep.withState(
            state = State.RUNNING,
            message = null,
            progress = null
        )
    }

    private fun isLastVisibleStepInSection(stepIndex: Int): Boolean {
        val step = steps.getOrNull(stepIndex) ?: return false
        return ((stepIndex + 1) until steps.size).none { index ->
            !steps[index].hide && steps[index].category == step.category
        }
    }

    private fun findBestSubStepIndex(
        list: List<StepDetail>,
        title: String
    ): Int {
        val needle = title.lowercase()
        val prefixIndex = list.indexOfFirst { needle.startsWith(it.title.lowercase()) }
        if (prefixIndex != -1) return prefixIndex
        val reversePrefix = list.indexOfFirst { it.title.lowercase().startsWith(needle) }
        if (reversePrefix != -1) return reversePrefix
        val containsIndex = list.indexOfFirst { needle.contains(it.title.lowercase()) }
        return containsIndex
    }

    private fun finalizeSubSteps(
        stepId: StepId,
        failed: Boolean = false,
        errorMessage: String? = null
    ) {
        val list = stepSubSteps[stepId] ?: return
        if (list.isEmpty()) return
        if (!failed) {
            list.forEachIndexed { index, detail ->
                list[index] = detail.withRecursiveState(state = State.COMPLETED, progress = null)
            }
            return
        }

        val runningIndex = list.indexOfFirst { !it.skipped && it.state == State.RUNNING }
        val failedIndex = when {
            runningIndex != -1 -> runningIndex
            else -> list.indexOfFirst { !it.skipped && it.state != State.COMPLETED }.takeIf { it != -1 }
        } ?: list.lastIndex

        list.forEachIndexed { index, detail ->
            if (detail.skipped) {
                list[index] = detail.copy(progress = null)
                return@forEachIndexed
            }
            val updated = when {
                index == failedIndex -> detail.withRecursiveState(
                    state = State.FAILED,
                    message = errorMessage,
                    progress = null
                )
                detail.state == State.RUNNING -> detail.withRecursiveState(state = State.WAITING, progress = null)
                else -> detail.withRecursiveState(progress = null)
            }
            list[index] = updated
        }
    }

    private fun StepDetail.withRecursiveState(
        state: State = this.state,
        message: String? = this.message,
        progress: Pair<Long, Long?>? = this.progress
    ): StepDetail = copy(
        state = state,
        message = message,
        progress = progress,
        children = children.map { child ->
            child.withRecursiveState(
                state = state,
                message = if (state == State.FAILED) message else child.message,
                progress = null
            )
        }
    )

    private fun parseSubStepTitle(rawTitle: String): Pair<String, Boolean> {
        val trimmed = rawTitle.trim()
        return if (trimmed.startsWith(SKIPPED_SUBSTEP_PREFIX)) {
            trimmed.removePrefix(SKIPPED_SUBSTEP_PREFIX).trim() to true
        } else {
            trimmed to false
        }
    }

    private fun observeWorker(id: UUID) {
        val source = workManager.getWorkInfoByIdLiveData(id)
        currentWorkSource?.let {
            _patcherSucceeded.removeSource(it)
            _isPatchingActive.removeSource(it)
        }
        currentWorkSource = source
        _patcherSucceeded.addSource(source) { workInfo ->
            when (workInfo?.state) {
                WorkInfo.State.RUNNING,
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.BLOCKED -> replayWorkerProgressSnapshot(
                    workInfo,
                    enabled = replayWorkerProgressSnapshots
                )
                else -> Unit
            }
            val progressActive =
                workInfo?.progress?.getBoolean(PatcherWorker.PATCHING_ACTIVE_KEY, false) == true
            _isPatchingActive.value = when (workInfo?.state) {
                WorkInfo.State.SUCCEEDED,
                WorkInfo.State.FAILED,
                WorkInfo.State.CANCELLED -> false
                WorkInfo.State.RUNNING,
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.BLOCKED -> true
                else -> progressActive
            }
            when (workInfo?.state) {
                WorkInfo.State.SUCCEEDED -> {
                    replayWorkerProgressSnapshots = false
                    workerRepository.clearActiveProgressSnapshot(id)
                    patcherWorkerId = null
                    stopPatchingTaskMonitor()
                    clearPendingActivityInteractions()
                    clearPatchingNotification()
                    forceKeepLocalInput = false
                    if (requiresSplitPreparation) {
                        updateSplitStepRequirement(
                            file = null,
                            needsSplitOverride = requiresSplitPreparation,
                            merged = true
                        )
                    }
                    applyFailedPatchIndexes(
                        workInfo.outputData.getIntArray(PatcherWorker.FAILED_PATCH_INDEXES_KEY)
                            ?.toSet()
                            .orEmpty()
                    )
                    reconcileProgressStateAfterSuccess()
                    refreshExportMetadata()
                    // Code adapted from Morphe, see third-party/NOTICE for more information
                    // https://github.com/MorpheApp/morphe-manager/pull/779
                    supportsRootMount = pm.getPackageInfo(outputFile)?.packageName == packageName
                    _patcherSucceeded.value = true
                }

                WorkInfo.State.FAILED -> {
                    replayWorkerProgressSnapshots = false
                    workerRepository.clearActiveProgressSnapshot(id)
                    patcherWorkerId = null
                    stopPatchingTaskMonitor()
                    clearPendingActivityInteractions()
                    clearPatchingNotification()
                    handleWorkerFailure(workInfo)
                    _patcherSucceeded.value = false
                }

                WorkInfo.State.RUNNING,
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.BLOCKED -> _patcherSucceeded.value = null
                WorkInfo.State.CANCELLED -> {
                    replayWorkerProgressSnapshots = false
                    workerRepository.clearActiveProgressSnapshot(id)
                    patcherWorkerId = null
                    stopPatchingTaskMonitor()
                    clearPendingActivityInteractions()
                    clearPatchingNotification()
                    reconcileFailureState(
                        failureMessage = workInfo.outputData.getString(PatcherWorker.PROCESS_FAILURE_MESSAGE_KEY)
                            ?: "Patching was cancelled."
                    )
                    _patcherSucceeded.value = null
                }
                else -> _patcherSucceeded.value = null
            }
        }
    }

    private suspend fun syncWorkerProgressFromCurrentSnapshot() {
        val workerId = patcherWorkerId?.uuid ?: return
        val workInfo = withContext(Dispatchers.IO) {
            runCatching { workManager.getWorkInfoById(workerId).get() }.getOrNull()
        } ?: currentWorkSource?.value ?: return

        when (workInfo.state) {
            WorkInfo.State.RUNNING,
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED -> replayWorkerProgressSnapshot(workInfo, enabled = true)
            else -> Unit
        }
    }

    private fun replayWorkerProgressSnapshot(workInfo: WorkInfo, enabled: Boolean) {
        if (!enabled) return

        val workerId = patcherWorkerId?.uuid
        val persistedSnapshot = PatcherWorkerProgressState.fromWorkData(workInfo.progress)
        val snapshot = when {
            workerId == null -> persistedSnapshot
            persistedSnapshot == null -> workerRepository.activeProgressSnapshot(workerId)
            else -> {
                val inMemorySnapshot = workerRepository.activeProgressSnapshot(workerId)
                when {
                    inMemorySnapshot == null -> persistedSnapshot
                    isNewerWorkerSnapshot(inMemorySnapshot, persistedSnapshot) -> inMemorySnapshot
                    else -> persistedSnapshot
                }
            }
        } ?: return
        recordPatcherMemoryUsage(
            generation = snapshot.generation,
            sequence = snapshot.sequence,
            memoryUsage = snapshot.memoryUsage
        )
        enqueueWorkerProgressEvent(
            generation = snapshot.generation,
            sequence = snapshot.sequence,
            event = snapshot.event,
            notificationProgressCurrent = snapshot.notificationProgressCurrent,
            notificationProgressMax = snapshot.notificationProgressMax,
            failedPatchIndexes = snapshot.failedPatchIndexes,
            seedFromWorkerSnapshot = true
        )
    }

    private fun shouldApplyWorkerProgress(generation: Long, sequence: Long): Boolean = when {
        generation > lastAppliedWorkerProgressGeneration -> true
        generation < lastAppliedWorkerProgressGeneration -> false
        else -> sequence > lastAppliedWorkerProgressSequence
    }

    private fun recordAppliedWorkerProgress(generation: Long, sequence: Long) {
        lastAppliedWorkerProgressGeneration = generation
        lastAppliedWorkerProgressSequence = sequence
    }

    private fun isNewerWorkerSnapshot(
        candidate: app.urv.manager.patcher.worker.PatcherWorkerProgressSnapshot,
        existing: app.urv.manager.patcher.worker.PatcherWorkerProgressSnapshot
    ): Boolean = when {
        candidate.generation > existing.generation -> true
        candidate.generation < existing.generation -> false
        else -> candidate.sequence > existing.sequence
    }

    private fun seedVisualProgressFromWorkerProgress(current: Int?, max: Int?) {
        val safeCurrent = current ?: return
        val safeMax = max?.takeIf { it > 0 } ?: return
        val candidate = (safeCurrent.toFloat() / safeMax.toFloat()).coerceIn(0f, 1f)
        if (candidate > progress) {
            progress = candidate
        }
    }

    private fun seedProgressStateFromWorkerSnapshot(
        event: ProgressEvent,
        failedPatchIndexes: Set<Int> = emptySet()
    ) {
        if (event.stepId == StepId.PrepareSplitApk && steps.none { it.id == StepId.PrepareSplitApk }) {
            requiresSplitPreparation = true
            val regeneratedSteps = generateSteps(
                app,
                input.selectedApp,
                input.selectedPatches,
                splitStepActive = true,
                skipApkSigning = skipApkSigning
            ).toMutableStateList()
            steps.clear()
            steps.addAll(regeneratedSteps)
        }

        val stepIndex = event.stepId?.let { stepId ->
            steps.indexOfFirst { it.id == stepId }
        } ?: -1
        if (stepIndex == -1) return

        for (index in 0 until stepIndex) {
            val step = steps[index]
            if (step.state == State.WAITING) {
                steps[index] = step.withState(state = State.COMPLETED, progress = null)
            }
        }
        applyFailedPatchIndexes(failedPatchIndexes)
    }

    private fun applyFailedPatchIndexes(failedPatchIndexes: Set<Int>) {
        failedPatchIndexes.forEach { patchIndex ->
            val stepIndex = steps.indexOfFirst { it.id == StepId.ExecutePatch(patchIndex) }
            if (stepIndex == -1) return@forEach
            val step = steps[stepIndex]
            if (step.state == State.FAILED) return@forEach
            steps[stepIndex] = step.withState(
                state = State.FAILED,
                message = step.message,
                progress = null
            )
        }
    }

    private fun handleWorkerFailure(workInfo: WorkInfo) {
        if (!handledFailureIds.add(workInfo.id)) return
        reconcileFailureState(workInfo.outputData.getString(PatcherWorker.PROCESS_FAILURE_MESSAGE_KEY))
        val exitCode = workInfo.outputData.getInt(PatcherWorker.PROCESS_EXIT_CODE_KEY, Int.MIN_VALUE)
        if (exitCode == Revanced22ProcessRuntime.OOM_EXIT_CODE ||
            exitCode == Revanced22ProcessRuntime.LOW_MEMORY_KILL_EXIT_CODE ||
            exitCode == Revanced22ProcessRuntime.SEGMENTATION_FAULT_EXIT_CODE) {
            forceKeepLocalInput = true
        }

        // Code adapted from Morphe, see third-party/NOTICE for more information
        // https://github.com/MorpheApp/morphe-manager/blob/a2c3d31bd7ab42e6bc4b9dd528ed856fc72fb948/app/src/main/java/app/morphe/manager/ui/viewmodel/PatcherViewModel.kt
        if (exitCode == Revanced22ProcessRuntime.OOM_EXIT_CODE) {
            viewModelScope.launch {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@launch
                val previousFromWorker = workInfo.outputData.getInt(
                    PatcherWorker.PROCESS_PREVIOUS_LIMIT_KEY,
                    -1
                )
                val storedLimit = if (previousFromWorker > 0) {
                    previousFromWorker
                } else {
                    prefs.processMemoryLimit.get()
                }
                val previousLimit =
                    MemoryLimitConfig.clampConfiguredMemoryLimitMb(storedLimit)
                val newLimit = (
                    previousLimit - MemoryLimitConfig.PROCESS_RUNTIME_MEMORY_STEP
                ).coerceAtLeast(MemoryLimitConfig.PROCESS_RUNTIME_MEMORY_MINIMUM)
                val adjusted = newLimit < previousLimit
                if (newLimit != storedLimit) {
                    prefs.processMemoryLimit.update(newLimit)
                }
                memoryAdjustmentDialog = MemoryAdjustmentDialogState(
                    previousLimit = previousLimit,
                    newLimit = if (adjusted) newLimit else previousLimit,
                    adjusted = adjusted
                )
            }
        }

        // Missing patch issues are handled during preflight validation.
    }

    private fun reconcileFailureState(failureMessage: String?) {
        val message = failureMessage?.takeIf { it.isNotBlank() }
        if (steps.any { it.state == State.FAILED }) return

        val failedIndex = steps.indexOfFirst { it.state == State.RUNNING }
            .takeIf { it != -1 }
            ?: steps.indexOfFirst { it.state == State.WAITING }.takeIf { it != -1 }
            ?: return

        steps[failedIndex] = steps[failedIndex].withState(
            state = State.FAILED,
            message = message,
            progress = null
        )
    }

    fun dismissKeystoreMissingDialog() {
        keystoreMissingDialog = false
    }

    private fun resetStateForRetry() {
        val newSteps = generateSteps(
            app,
            input.selectedApp,
            input.selectedPatches,
            requiresSplitPreparation,
            skipApkSigning
        ).toMutableStateList()
        steps.clear()
        resetDexCompileState()
        resetFailureLogState()
        resetVisualProgress()
        steps.addAll(newSteps)
        stepSubSteps.clear()
        _patcherSucceeded.value = null
    }

    private fun shouldResetProgressStateForAutomaticRetry(event: ProgressEvent): Boolean {
        if (event !is ProgressEvent.Started || event.stepId != StepId.LoadPatches) return false

        val loadIndex = steps.indexOfFirst { it.id == StepId.LoadPatches }
        if (loadIndex == -1) return false

        val loadStep = steps[loadIndex]
        if (loadStep.state == State.COMPLETED || loadStep.state == State.FAILED) {
            return true
        }

        val preservedIds = setOf<StepId>(StepId.PrepareSplitApk)
        val resettableIds = steps.drop(loadIndex).map { it.id }.toSet() - preservedIds
        return steps.drop(loadIndex + 1).any { it.id !in preservedIds && it.state != State.WAITING } ||
            stepSubSteps.keys.any { it in resettableIds } ||
            writeApkStepStarted ||
            dexSubStepsReady ||
            pendingDexCompileLines.isNotEmpty()
    }

    private fun resetProgressStateForAutomaticRetry() {
        clearDeferredLoadPatchesEvents()
        val loadIndex = steps.indexOfFirst { it.id == StepId.LoadPatches }
        if (loadIndex == -1) return

        val preservedIds = setOf<StepId>(StepId.PrepareSplitApk)
        val resettableIds = steps.drop(loadIndex).map { it.id }.toSet() - preservedIds
        resetDexCompileState()
        resetFailureLogState()
        runtimeReportedMemoryLimitMb = null
        resetVisualProgress()

        steps.forEachIndexed { index, step ->
            if (index < loadIndex) {
                steps[index] = step.withState(
                    state = if (step.state == State.FAILED) State.COMPLETED else step.state,
                    progress = null
                )
                return@forEachIndexed
            }

            if (step.id in preservedIds) {
                steps[index] = step.withState(
                    state = if (step.state == State.FAILED) State.COMPLETED else step.state,
                    progress = null
                )
                return@forEachIndexed
            }

            steps[index] = step.withState(
                state = State.WAITING,
                message = null,
                progress = null
            )
        }

        stepSubSteps.keys
            .filter { it in resettableIds }
            .toList()
            .forEach(stepSubSteps::remove)
    }

    private fun markInitialStepRunning() {
        val index = steps.indexOfFirst { step ->
            !step.hide && step.state == State.WAITING
        }
        if (index == -1) return
        val step = steps[index]
        steps[index] = step.withState(state = State.RUNNING, message = null, progress = null)
        stepSubSteps.remove(step.id)
    }

    private fun initialSplitRequirement(selectedApp: SelectedApp): Boolean =
        when (selectedApp) {
            is SelectedApp.Local -> SplitApkPreparer.isSplitArchive(selectedApp.file)
            else -> false
        }

    private fun updateSplitStepRequirement(
        file: File?,
        needsSplitOverride: Boolean? = null,
        merged: Boolean = false
    ) {
        val needsSplit = needsSplitOverride
            ?: merged
            || file?.let(SplitApkPreparer::isSplitArchive) == true
        when {
            needsSplit && !requiresSplitPreparation -> {
                requiresSplitPreparation = true
                addSplitStep()
            }

            !needsSplit && requiresSplitPreparation -> {
                requiresSplitPreparation = false
                removeSplitStep()
                return
            }
        }

        if (needsSplit && merged) {
            val index = steps.indexOfFirst { it.id == StepId.PrepareSplitApk }
            if (index >= 0) {
                steps[index] = steps[index].withState(State.COMPLETED)
            }
        }

    }

    private fun addSplitStep() {
        if (steps.any { it.id == StepId.PrepareSplitApk }) return

        val loadIndex = steps.indexOfFirst { it.id == StepId.LoadPatches }
        val insertIndex = when {
            loadIndex >= 0 -> loadIndex
            else -> steps.indexOfFirst { it.id == StepId.ReadAPK }.takeIf { it >= 0 } ?: steps.size
        }
        steps.add(insertIndex, buildSplitStep(app))
        pauseLoadPatchesForSplitPreparation()
    }

    private fun removeSplitStep() {
        clearDeferredLoadPatchesEvents()
        val index = steps.indexOfFirst { it.id == StepId.PrepareSplitApk }
        if (index == -1) return
        steps.removeAt(index)
    }

    private fun sanitizeSelection(
        selection: PatchSelection,
        bundles: Map<Int, PatchBundleInfo>
    ): PatchSelection = buildMap {
        selection.forEach { (uid, patches) ->
            val bundle = bundles[uid]
            if (bundle == null) {
                // Keep unknown bundles so applied patches stay visible even if the source is missing.
                if (patches.isNotEmpty()) put(uid, patches.toSet())
                return@forEach
            }

            val valid = bundle.patches.map { it.name }.toSet()
            val kept = patches.filter { it in valid }.toSet()
            if (kept.isNotEmpty()) {
                put(uid, kept)
            } else if (patches.isNotEmpty()) {
                // If everything was filtered out by compatibility, still keep the original set so
                // the app info screen can show the applied bundle/patch names.
                put(uid, patches.toSet())
            }
        }
    }

    private suspend fun applyCurrentPatchRules(
        selection: PatchSelection,
        bundles: Map<Int, PatchBundleInfo.Scoped>,
    ): PatchSelection {
        val allowIncompatible = prefs.disablePatchVersionCompatCheck.get() ||
            bundles.any { (uid, bundle) ->
                val selected = selection[uid].orEmpty()
                bundle.incompatible.any { it.name in selected }
            }
        val availabilityEnabled = prefs.patchAvailabilityEnabled.get()
        val removeGmsCore = usingMountInstall &&
            installerManager.getPrimaryToken() == InstallerManager.Token.AutoSaved &&
            prefs.removeGmsCoreForPrimaryMount.get()

        return selection.applyAvailability(
            installerTypeFor(usingMountInstall),
            bundles.mapValues { (_, bundle) ->
                bundle.patchSequence(allowIncompatible).associateBy { it.name }
            },
            availabilityEnabled
        ).removeGmsCoreSupport(removeGmsCore)
    }

    private fun sanitizeOptions(
        options: Options,
        bundles: Map<Int, PatchBundleInfo>
    ): Options = buildMap {
        options.forEach { (uid, patchOptions) ->
            val bundle = bundles[uid] ?: return@forEach
            val patches = bundle.patches.associateBy { it.name }
            val filtered = buildMap<String, Map<String, Any?>> {
                patchOptions.forEach { (patchName, values) ->
                    val patch = patches[patchName] ?: return@forEach
                    val validKeys = patch.options?.map { it.key }?.toSet() ?: emptySet()
                    val kept = if (validKeys.isEmpty()) values else values.filterKeys { it in validKeys }
                    if (kept.isNotEmpty()) put(patchName, kept)
                }
            }
            if (filtered.isNotEmpty()) put(uid, filtered)
        }
    }

    private suspend fun savedEntryIdentity(installedApp: InstalledApp): String {
        val patchSelection = installedAppRepository.getAppliedPatches(installedApp.currentPackageName)
        return buildSavedAppVariantIdentity(
            appVersion = installedApp.version,
            selectionPayload = installedApp.selectionPayload,
            patchSelection = patchSelection
        )
    }

    private suspend fun collapseMatchingSavedEntriesForInstalledVariant(
        packageName: String,
        installedPackageName: String,
        variantIdentity: String
    ) {
        installedAppRepository.getByInstallType(InstallType.SAVED)
            .filter { savedEntry ->
                savedEntry.currentPackageName != installedPackageName &&
                    isSavedAppEntryForPackage(savedEntry.currentPackageName, packageName)
            }
            .forEach { savedEntry ->
                if (savedEntryIdentity(savedEntry) != variantIdentity) return@forEach
                installedAppRepository.migrateAutoPatchTarget(
                    savedEntry.currentPackageName,
                    installedPackageName
                )
                installedAppRepository.delete(savedEntry)
                fs.getPatchedAppFile(
                    savedEntry.currentPackageName,
                    savedEntry.version
                ).takeIf { it.exists() }?.delete()
            }
    }

    private suspend fun pruneUnreferencedPatchedAppFiles() {
        val retainedFiles = installedAppRepository.getAll().first().map { installedApp ->
            fs.getPatchedAppFile(installedApp.currentPackageName, installedApp.version)
        }
        val removed = fs.prunePatchedAppFiles(retainedFiles)
        if (removed > 0) {
            Log.d(TAG, "Removed $removed stale saved patched APK file(s)")
        }
        installedAppRepository.pruneRetainedOriginals()
    }

    private fun buildUniqueSavedAppEntryKey(packageName: String, variantIdentity: String): String {
        val keyBase = buildSavedAppEntryKey(packageName, variantIdentity)
        val nonce = UUID.randomUUID().toString().replace("-", "").take(8)
        return "${keyBase}__${nonce}"
    }

    internal companion object {
        const val TAG = "ReVanced Patcher"
        const val SKIPPED_SUBSTEP_PREFIX = "[skipped]"
        private const val WRITE_APK_DEX_GROUP_TITLE = "Compiling DEX files"
        private const val DOWNLOADER_DIALOG_SETTLE_MS = 32L
        private const val DOWNLOADER_ACTIVITY_RESULT_GRACE_MS = 750L
        private const val SYSTEM_INSTALL_TIMEOUT_MS = 60_000L
        private const val EXTERNAL_INSTALL_TIMEOUT_MS = 60_000L
        private const val SHIZUKU_UNINSTALL_VERIFY_TIMEOUT_MS = 10_000L
        private const val SHIZUKU_UNINSTALL_VERIFY_POLL_MS = 250L
        private const val POST_TIMEOUT_GRACE_MS = 5_000L
        private const val EXTERNAL_INSTALLER_RESULT_GRACE_MS = 1500L
        private const val EXTERNAL_INSTALLER_POST_CLOSE_TIMEOUT_MS = 30_000L
        private const val INSTALL_MONITOR_POLL_MS = 500L
        private const val INSTALL_PROGRESS_TOAST_INTERVAL_MS = 2500L
        private const val SUPPRESS_FAILURE_AFTER_SUCCESS_MS = 5000L
        private const val PATCHER_LOG_ENTRY_SOFT_LIMIT = 9_000
        private const val PATCHER_LOG_ENTRY_HARD_LIMIT = 12_000
        private const val PATCHER_LOG_MESSAGE_CHAR_LIMIT = 12_000
        private const val FAILURE_LOG_SUMMARY_CHAR_LIMIT = 1_000
        fun LogLevel.androidLog(msg: String) = when (this) {
            LogLevel.TRACE -> Log.v(TAG, msg)
            LogLevel.INFO -> Log.i(TAG, msg)
            LogLevel.WARN -> Log.w(TAG, msg)
            LogLevel.ERROR -> Log.e(TAG, msg)
        }

        fun generateSteps(
            context: Context,
            selectedApp: SelectedApp,
            selectedPatches: PatchSelection,
            splitStepActive: Boolean,
            skipApkSigning: Boolean
        ): List<Step> = buildList {
            if (selectedApp is SelectedApp.Download || selectedApp is SelectedApp.Search) {
                add(
                    Step(
                        StepId.DownloadAPK,
                        context.getString(R.string.download_apk),
                        StepCategory.PREPARING
                    )
                )
            }

            if (splitStepActive) {
                add(buildSplitStep(context))
            }

            add(
                Step(
                    StepId.LoadPatches,
                    context.getString(R.string.patcher_step_load_patches),
                    StepCategory.PREPARING
                )
            )

            add(
                Step(
                    StepId.ReadAPK,
                    context.getString(R.string.patcher_step_unpack),
                    StepCategory.PREPARING
                )
            )

            add(
                Step(
                    StepId.ExecutePatches,
                    context.getString(R.string.execute_patches),
                    StepCategory.PATCHING,
                    hide = true
                )
            )

            selectedPatches.values.asSequence().flatten().sorted().forEachIndexed { index, name ->
                add(
                    Step(
                        StepId.ExecutePatch(index),
                        name,
                        StepCategory.PATCHING
                    )
                )
            }

            add(
                Step(
                    StepId.WriteAPK,
                    context.getString(R.string.patcher_step_write_patched),
                    StepCategory.SAVING
                )
            )
            if (!skipApkSigning) {
                add(
                    Step(
                        StepId.SignAPK,
                        context.getString(R.string.patcher_step_sign_apk),
                        StepCategory.SAVING
                    )
                )
            }
        }

    }
}

private fun buildSplitStep(
    context: Context,
    message: String? = null
) = Step(
    id = StepId.PrepareSplitApk,
    title = context.getString(R.string.patcher_step_prepare_split_apk),
    category = StepCategory.PREPARING,
    message = message
)
