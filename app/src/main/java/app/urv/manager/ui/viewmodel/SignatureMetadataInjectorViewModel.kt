package app.urv.manager.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInstaller as AndroidPackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.revanced.manager.R
import app.urv.manager.domain.installer.InstallCancelledException
import app.urv.manager.domain.installer.InstallResult
import app.urv.manager.domain.installer.InstallerManager
import app.urv.manager.domain.installer.RootInstaller
import app.urv.manager.domain.installer.SessionDeadException
import app.urv.manager.domain.installer.SessionInstaller
import app.urv.manager.domain.installer.root.RootMountOperation
import app.urv.manager.domain.installer.root.RootMountRequest
import app.urv.manager.domain.installer.root.RootMountResult
import app.urv.manager.domain.installer.root.RootMountSuspension
import app.urv.manager.domain.installer.root.RootMountTransactionCoordinator
import app.urv.manager.domain.installer.root.installAsPlayStoreWithMountRollback
import app.urv.manager.domain.installer.root.launchExternalInstallerWithMountFinalization
import app.urv.manager.domain.installer.root.reinstallMountedStockAsPlayStore
import app.urv.manager.domain.installer.root.restore
import app.urv.manager.domain.installer.root.retire
import app.urv.manager.domain.installer.root.requireSuccess
import app.urv.manager.domain.installer.root.suspendRootMountForPackageInstall
import app.urv.manager.domain.installer.ShizukuInstaller
import app.urv.manager.domain.manager.SignatureMetadataApkInfo
import app.urv.manager.domain.manager.SignatureMetadataInjectorProgress
import app.urv.manager.domain.manager.SignatureMetadataInjectorResult
import app.urv.manager.domain.manager.SignatureMetadataInjectorStage
import app.urv.manager.domain.manager.SignatureMetadataSourceInfo
import app.urv.manager.domain.manager.SignatureMetadataSourceType
import app.urv.manager.domain.manager.SignatureMetadataTargetInfo
import app.urv.manager.domain.manager.SignatureMetadataTargetType
import app.urv.manager.domain.manager.SignatureMetadataInjectionMode
import app.urv.manager.domain.manager.SignatureMetadataInjectorManager
import app.urv.manager.domain.manager.SignatureMetadataSigningMode
import app.urv.manager.domain.manager.SignatureMetadataOutputType
import app.urv.manager.domain.manager.SignatureMetadataSplitOutputMode
import app.urv.manager.domain.storage.CacheCleanupGuard
import app.urv.manager.patcher.split.SplitApkPreparer
import app.urv.manager.util.APK_MIMETYPE
import app.urv.manager.util.APK_SIGNATURE_METADATA_INJECTOR_CACHE_DIR
import app.urv.manager.util.InstalledPackageSnapshot
import app.urv.manager.util.PM
import app.urv.manager.util.installedPackageSnapshot
import app.urv.manager.util.simpleMessage
import app.urv.manager.util.toast
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

enum class SignatureMetadataInputRole {
    SIGNATURE_SOURCE,
    TARGET_APK
}

data class SignatureMetadataSelectionState(
    val displayName: String? = null,
    val stagedFile: File? = null,
    val sourceInfo: SignatureMetadataSourceInfo? = null,
    val targetInfo: SignatureMetadataTargetInfo? = null,
    val analyzing: Boolean = false,
    val error: String? = null
) {
    val apkInfo: SignatureMetadataApkInfo?
        get() = targetInfo?.apkInfo
}

data class SignatureMetadataInjectorUiState(
    val signatureSource: SignatureMetadataSelectionState = SignatureMetadataSelectionState(),
    val targetApk: SignatureMetadataSelectionState = SignatureMetadataSelectionState(),
    val injectionMode: SignatureMetadataInjectionMode? = null,
    val signingModes: Map<SignatureMetadataInjectionMode, SignatureMetadataSigningMode> =
        emptyMap(),
    val splitOutputMode: SignatureMetadataSplitOutputMode =
        SignatureMetadataSplitOutputMode.MERGED_APK,
    val includedSplitModules: Set<String>? = null,
    val splitSelection: SplitApkPreparer.SplitArchiveInspection? = null,
    val preparingSplitSelection: Boolean = false,
    val splitSelectionError: String? = null,
    val injecting: Boolean = false,
    val installing: Boolean = false,
    val progress: SignatureMetadataInjectorProgress? = null,
    val result: SignatureMetadataInjectorResult? = null,
    val installStatus: String? = null,
    val rootDowngradeConfirmationPending: Boolean = false,
    val logEntries: List<String> = emptyList(),
    val logRevision: Long = 0L,
    val logSessionId: Long = 0L,
    val error: String? = null
) {
    val working: Boolean
        get() = injecting || installing || preparingSplitSelection

    val signingSelectionEnabled: Boolean
        get() = signatureSource.sourceInfo?.sourceType ==
            SignatureMetadataSourceType.METADATA_ZIP

    val hasSplitTarget: Boolean
        get() = targetApk.targetInfo?.let { target ->
            target.targetType == SignatureMetadataTargetType.SPLIT_APK_CONTAINER &&
                target.apkEntryCount > 1
        } == true

    val mergeSplitTarget: Boolean
        get() = splitOutputMode == SignatureMetadataSplitOutputMode.MERGED_APK

    val selectedSigningMode: SignatureMetadataSigningMode?
        get() = if (
            signatureSource.sourceInfo?.sourceType?.usesAutomaticSignatureCloning == true
        ) {
            SignatureMetadataSigningMode.APPLY_SUPPLIED_SIGNATURE
        } else {
            injectionMode?.let(signingModes::get)
        }

    val canInject: Boolean
        get() = !working &&
            injectionMode != null &&
            signatureSource.sourceInfo != null &&
            targetApk.apkInfo != null &&
            (!signingSelectionEnabled || selectedSigningMode != null)
}

class SignatureMetadataInjectorViewModel(
    private val app: Application,
    private val manager: SignatureMetadataInjectorManager,
    private val installerManager: InstallerManager,
    private val rootInstaller: RootInstaller,
    private val rootMountCoordinator: RootMountTransactionCoordinator,
    private val shizukuInstaller: ShizukuInstaller,
    private val sessionInstaller: SessionInstaller,
    private val pm: PM
) : ViewModel() {
    private val stateFlow = MutableStateFlow(SignatureMetadataInjectorUiState())
    val state = stateFlow.asStateFlow()

    private val workspace = app.cacheDir
        .resolve(APK_SIGNATURE_METADATA_INJECTOR_CACHE_DIR)
        .resolve(UUID.randomUUID().toString())
    private val fullLogFile = workspace.resolve(FULL_LOG_FILE_NAME)
    private val fullLogLock = Any()
    private var fullLogWriter: BufferedWriter? = null
    private var fullLogWriteDisabled = false
    private var fullLogStoreClosed = false
    private var metadataAnalysisJob: Job? = null
    private var apkAnalysisJob: Job? = null
    private var injectionJob: Job? = null
    private var splitSelectionJob: Job? = null
    private var installJob: Job? = null
    private var pendingExternalInstall: InstallerManager.InstallPlan.External? = null
    private var pendingExternalMountSuspension: RootMountSuspension? = null
    private var pendingRootMountInstallerToken: InstallerManager.Token? = null
    private var workspaceCacheGuard: AutoCloseable? = null
    private var metadataGeneration = 0L
    private var apkGeneration = 0L

    fun select(role: SignatureMetadataInputRole, uri: Uri, displayName: String) {
        if (stateFlow.value.working) return
        acquireWorkspaceCacheGuard()
        val generation = when (role) {
            SignatureMetadataInputRole.SIGNATURE_SOURCE -> {
                metadataAnalysisJob?.cancel()
                ++metadataGeneration
            }
            SignatureMetadataInputRole.TARGET_APK -> {
                apkAnalysisJob?.cancel()
                splitSelectionJob?.cancel()
                stateFlow.update {
                    it.copy(
                        splitOutputMode = SignatureMetadataSplitOutputMode.MERGED_APK,
                        includedSplitModules = null,
                        splitSelection = null,
                        preparingSplitSelection = false,
                        splitSelectionError = null
                    )
                }
                ++apkGeneration
            }
        }
        clearOutput()
        currentSelection(role).stagedFile?.delete()
        updateSelection(
            role,
            SignatureMetadataSelectionState(
                displayName = displayName,
                analyzing = true
            )
        )

        val job = viewModelScope.launch {
            var stagedFile: File? = null
            try {
                CacheCleanupGuard.withCacheInUse {
                    val staged = withContext(Dispatchers.IO) {
                        stageInput(role, uri, displayName)
                    }
                    stagedFile = staged
                    val selection = when (role) {
                        SignatureMetadataInputRole.SIGNATURE_SOURCE ->
                            SignatureMetadataSelectionState(
                                displayName = displayName,
                                stagedFile = staged,
                                sourceInfo = manager.analyzeSignatureSource(staged)
                            )
                        SignatureMetadataInputRole.TARGET_APK ->
                            SignatureMetadataSelectionState(
                                displayName = displayName,
                                stagedFile = staged,
                                targetInfo = manager.analyzeTarget(staged)
                            )
                    }
                    if (generation != generationFor(role)) {
                        staged.delete()
                        return@withCacheInUse
                    }
                    updateSelection(role, selection)
                }
            } catch (_: CancellationException) {
                stagedFile?.delete()
            } catch (error: Throwable) {
                stagedFile?.delete()
                if (generation == generationFor(role)) {
                    updateSelection(
                        role,
                        SignatureMetadataSelectionState(
                            displayName = displayName,
                            error = error.message ?: "Failed to analyze selected input."
                        )
                    )
                    releaseWorkspaceCacheGuardIfUnused()
                }
            }
        }
        when (role) {
            SignatureMetadataInputRole.SIGNATURE_SOURCE -> metadataAnalysisJob = job
            SignatureMetadataInputRole.TARGET_APK -> apkAnalysisJob = job
        }
    }

    fun selectInjectionMode(mode: SignatureMetadataInjectionMode) {
        if (stateFlow.value.working || stateFlow.value.injectionMode == mode) return
        clearOutput()
        stateFlow.update {
            it.copy(injectionMode = mode, installStatus = null, error = null)
        }
    }

    fun selectSigningMode(
        injectionMode: SignatureMetadataInjectionMode,
        signingMode: SignatureMetadataSigningMode
    ) {
        val current = stateFlow.value
        if (
            current.working ||
            current.injectionMode != injectionMode ||
            !current.signingSelectionEnabled
        ) return
        clearOutput()
        stateFlow.update {
            it.copy(
                signingModes = it.signingModes + (injectionMode to signingMode),
                installStatus = null,
                error = null
            )
        }
    }

    fun selectSplitOutputMode(mode: SignatureMetadataSplitOutputMode) {
        val current = stateFlow.value
        if (current.working || !current.hasSplitTarget || current.splitOutputMode == mode) return
        clearOutput()
        stateFlow.update {
            it.copy(
                splitOutputMode = mode,
                splitSelection = null,
                splitSelectionError = null
            )
        }
    }

    fun prepareSplitSelection() {
        val current = stateFlow.value
        val target = current.targetApk.stagedFile ?: return
        if (current.working || !current.hasSplitTarget || !current.mergeSplitTarget) return
        val generation = apkGeneration
        splitSelectionJob?.cancel()
        acquireWorkspaceCacheGuard()
        stateFlow.update {
            it.copy(
                preparingSplitSelection = true,
                splitSelection = null,
                splitSelectionError = null
            )
        }
        splitSelectionJob = viewModelScope.launch {
            try {
                val inspection = CacheCleanupGuard.withCacheInUse {
                    SplitApkPreparer.inspect(target)
                }
                if (
                    generation != apkGeneration ||
                    stateFlow.value.targetApk.stagedFile != target
                ) {
                    return@launch
                }
                stateFlow.update {
                    it.copy(
                        preparingSplitSelection = false,
                        splitSelection = inspection,
                        splitSelectionError = null
                    )
                }
            } catch (_: CancellationException) {
                if (generation == apkGeneration) {
                    stateFlow.update {
                        it.copy(
                            preparingSplitSelection = false,
                            splitSelection = null
                        )
                    }
                }
            } catch (error: Throwable) {
                if (generation == apkGeneration) {
                    stateFlow.update {
                        it.copy(
                            preparingSplitSelection = false,
                            splitSelection = null,
                            splitSelectionError = error.message
                                ?: app.getString(
                                    R.string.tools_signature_metadata_injector_split_selection_failed
                                )
                        )
                    }
                }
            } finally {
                releaseWorkspaceCacheGuardIfUnused()
            }
        }
    }

    fun cancelSplitSelectionPreparation() {
        splitSelectionJob?.cancel(
            CancellationException("User cancelled split selection preparation")
        )
    }

    fun dismissSplitSelection() {
        if (stateFlow.value.working) return
        stateFlow.update { it.copy(splitSelection = null) }
    }

    fun dismissSplitSelectionError() {
        stateFlow.update { it.copy(splitSelectionError = null) }
    }

    fun confirmSplitSelection(includedModules: Set<String>) {
        val current = stateFlow.value
        val inspection = current.splitSelection ?: return
        if (current.working || !current.hasSplitTarget || !current.mergeSplitTarget) return
        val allModules = inspection.modules.mapTo(linkedSetOf()) { it.name }
        clearOutput()
        stateFlow.update {
            it.copy(
                includedSplitModules = includedModules.takeUnless { modules ->
                    modules == allModules
                },
                splitSelection = null,
                splitSelectionError = null
            )
        }
    }

    fun inject(outputFileName: String) {
        val current = stateFlow.value
        if (!current.canInject) return
        val signatureSource = current.signatureSource.stagedFile ?: return
        val targetApk = current.targetApk.stagedFile ?: return
        val mode = current.injectionMode ?: return
        val signingMode = current.selectedSigningMode ?: return
        injectionJob?.cancel()
        clearOutput()
        clearLogs()
        val preserveSplitContainer = current.hasSplitTarget && !current.mergeSplitTarget
        val output = workspace.resolve(
            normalizeOutputName(
                value = outputFileName,
                splitContainer = preserveSplitContainer
            )
        )
        appendLog("Started signature metadata injection or cloning")
        appendLog("Signature source: ${current.signatureSource.displayName}")
        appendLog("Target APK or split container: ${current.targetApk.displayName}")
        appendLog("Metadata mode: ${mode.name.lowercase().replace('_', ' ')}")
        if (current.hasSplitTarget) {
            appendLog(
                if (preserveSplitContainer) {
                    "Split output: preserve split APK container"
                } else {
                    val selected = current.includedSplitModules
                    if (selected == null) {
                        "Split output: merge all splits into one APK"
                    } else {
                        "Split output: merge ${selected.size} selected splits into one APK"
                    }
                }
            )
        }
        appendLog(
            if (
                current.signatureSource.sourceInfo
                    ?.sourceType
                    ?.usesAutomaticSignatureCloning == true
            ) {
                "Signing behavior: clone source signature automatically"
            } else {
                "Signing mode: ${signingMode.name.lowercase().replace('_', ' ')}"
            }
        )
        stateFlow.update {
            it.copy(
                injecting = true,
                progress = SignatureMetadataInjectorProgress(
                    SignatureMetadataInjectorStage.ANALYZING
                ),
                result = null,
                installStatus = null,
                error = null
            )
        }

        injectionJob = viewModelScope.launch {
            try {
                val result = CacheCleanupGuard.withCacheInUse {
                    manager.inject(
                        signatureSource = signatureSource,
                        targetApk = targetApk,
                        outputApk = output,
                        mode = mode,
                        signingMode = signingMode,
                        splitOutputMode = if (preserveSplitContainer) {
                            SignatureMetadataSplitOutputMode.SPLIT_APK_CONTAINER
                        } else {
                            SignatureMetadataSplitOutputMode.MERGED_APK
                        },
                        includedSplitModules = current.includedSplitModules
                            .takeIf { current.hasSplitTarget && current.mergeSplitTarget },
                        onProgress = { progress ->
                            stateFlow.update { state -> state.copy(progress = progress) }
                        },
                        onLog = ::appendLog
                    )
                }
                stateFlow.update {
                    it.copy(
                        injecting = false,
                        progress = SignatureMetadataInjectorProgress(
                            SignatureMetadataInjectorStage.COMPLETE
                        ),
                        result = result,
                        error = null
                    )
                }
            } catch (_: CancellationException) {
                output.delete()
                appendLog("Injection cancelled")
                stateFlow.update {
                    it.copy(
                        injecting = false,
                        progress = null,
                        result = null,
                        error = app.getString(
                            R.string.tools_signature_metadata_injector_cancelled
                        )
                    )
                }
            } catch (error: Throwable) {
                output.delete()
                appendLog("Error: ${error.message ?: error::class.java.simpleName}")
                appendLog(error.stackTraceToString())
                stateFlow.update {
                    it.copy(
                        injecting = false,
                        progress = null,
                        result = null,
                        error = error.message
                            ?: app.getString(
                                R.string.tools_signature_metadata_injector_failed
                            )
                    )
                }
            }
        }
    }

    fun cancelInjection() {
        injectionJob?.cancel(CancellationException("User cancelled metadata injection"))
        manager.cancelActiveExecution()
    }

    suspend fun getLogContent(): String = withContext(Dispatchers.IO) {
        readFullLogContent()
    }

    fun exportLogsToPath(
        target: Path,
        onResult: (Boolean) -> Unit = {}
    ) = viewModelScope.launch {
        val exportSucceeded = runCatching {
            withContext(Dispatchers.IO) {
                val content = readFullLogContent()
                target.parent?.let { Files.createDirectories(it) }
                Files.newBufferedWriter(
                    target,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                ).use { writer -> writer.write(content) }
            }
        }.isSuccess

        app.toast(
            app.getString(
                if (exportSucceeded) {
                    R.string.tools_signature_metadata_injector_log_export_success
                } else {
                    R.string.tools_signature_metadata_injector_log_export_failed
                }
            )
        )
        onResult(exportSucceeded)
    }

    fun exportLogsToUri(
        target: Uri?,
        onResult: (Boolean) -> Unit = {}
    ) = viewModelScope.launch {
        if (target == null) {
            onResult(false)
            return@launch
        }
        val exportSucceeded = runCatching {
            withContext(Dispatchers.IO) {
                val content = readFullLogContent()
                app.contentResolver.openOutputStream(target, "wt")
                    ?.bufferedWriter(StandardCharsets.UTF_8)
                    ?.use { writer -> writer.write(content) }
                    ?: throw IOException("Could not open injector log export destination.")
            }
        }.isSuccess

        app.toast(
            app.getString(
                if (exportSucceeded) {
                    R.string.tools_signature_metadata_injector_log_export_success
                } else {
                    R.string.tools_signature_metadata_injector_log_export_failed
                }
            )
        )
        onResult(exportSucceeded)
    }

    fun installerOptions(): List<InstallerManager.Entry> {
        val current = stateFlow.value
        val targetIsSplit = current.targetApk.targetInfo?.targetType ==
            SignatureMetadataTargetType.SPLIT_APK_CONTAINER
        val outputFile = current.result?.outputFile
        val entries = if (outputFile != null) {
            installerManager.listEntriesForFile(
                target = InstallerManager.InstallTarget.PATCHER,
                includeNone = false,
                sourceFile = outputFile
            )
        } else {
            installerManager.listEntries(
                InstallerManager.InstallTarget.PATCHER,
                includeNone = false
            )
        }
        return entries.filterNot { entry ->
            targetIsSplit && entry.token == InstallerManager.Token.AutoSaved
        }
    }

    fun openShizuku(): Boolean = installerManager.openShizukuApp()

    fun install(installerToken: InstallerManager.Token? = null) {
        startInstall(installerToken, downgradeFallbackConfirmed = false)
    }

    private fun startInstall(
        installerToken: InstallerManager.Token?,
        downgradeFallbackConfirmed: Boolean
    ) {
        val currentState = stateFlow.value
        val result = currentState.result ?: return
        val stockApk = currentState.targetApk.stagedFile ?: return
        if (currentState.working) return
        val targetIsSplit = currentState.targetApk.targetInfo?.targetType ==
            SignatureMetadataTargetType.SPLIT_APK_CONTAINER
        installJob?.cancel()
        pendingExternalInstall?.let(installerManager::cleanup)
        pendingExternalInstall = null
        pendingRootMountInstallerToken = null
        stateFlow.update {
            it.copy(
                installing = true,
                installStatus = null,
                rootDowngradeConfirmationPending = false,
                error = null
            )
        }
        appendLog("Resolving configured installer")
        val splitOutput = result.outputType ==
            SignatureMetadataOutputType.SPLIT_APK_CONTAINER
        val splitInstallWorkspace = if (splitOutput) {
            workspace.resolve("split-install-${UUID.randomUUID()}")
        } else {
            null
        }

        installJob = viewModelScope.launch {
            try {
                restorePendingExternalMount()
                CacheCleanupGuard.withCacheInUse {
                    val installFiles = if (splitOutput) {
                        appendLog("Extracting split APKs for installation")
                        SplitApkPreparer.extractForInstall(
                            source = result.outputFile,
                            targetDir = checkNotNull(splitInstallWorkspace)
                        )
                    } else {
                        listOf(result.outputFile)
                    }
                    require(installFiles.isNotEmpty()) {
                        app.getString(R.string.split_installer_no_apk_entries)
                    }
                    val packageInfo = withContext(Dispatchers.IO) {
                        installFiles.asSequence()
                            .mapNotNull { file -> pm.getPackageInfo(file) }
                            .firstOrNull { info ->
                                info.packageName == result.packageName &&
                                    info.splitNames.isNullOrEmpty()
                            }
                            ?: error(app.getString(R.string.failed_to_load_apk))
                    }
                    val packageName = packageInfo.packageName
                    val label = with(pm) { packageInfo.label() }
                    val requestedPlan = withContext(Dispatchers.IO) {
                        installerToken?.let { token ->
                            installerManager.resolvePlanForToken(
                                token = token,
                                target = InstallerManager.InstallTarget.PATCHER,
                                sourceFile = result.outputFile,
                                expectedPackage = packageName,
                                sourceLabel = label
                            ) ?: error(
                                app.getString(R.string.installer_status_not_supported)
                            )
                        } ?: resolveConfiguredInstallerPlan(
                            result = result,
                            packageName = packageName,
                            label = label,
                            targetIsSplit = targetIsSplit
                        )
                    }
                    val plan = if (
                        targetIsSplit &&
                        requestedPlan is InstallerManager.InstallPlan.Mount
                    ) {
                        appendLog(
                            "Root mount is unavailable for split targets; " +
                                "using the internal package installer"
                        )
                        InstallerManager.InstallPlan.Internal(
                            InstallerManager.InstallTarget.PATCHER
                        )
                    } else {
                        requestedPlan
                    }
                    appendLog("Installer plan: ${plan::class.java.simpleName}")
                    when (plan) {
                        is InstallerManager.InstallPlan.Internal -> {
                            if (splitOutput) {
                                installSplitInternally(installFiles, packageName)
                            } else {
                                installInternally(result.outputFile, packageName, label)
                            }
                        }
                        is InstallerManager.InstallPlan.Mount -> {
                            val stockInfo = pm.getPackageInfo(stockApk)
                                ?: error(app.getString(R.string.install_app_fail_missing_stock))
                            val mountResult = rootMountCoordinator.execute(
                                RootMountRequest(
                                    packageName = packageName,
                                    userId = android.os.Process.myUid() / 100_000,
                                    operation = RootMountOperation.REPLACE_STOCK_AND_MOUNT,
                                    patchedApk = result.outputFile,
                                    stockApks = listOf(stockApk),
                                    expectedVersionName = packageInfo.versionName,
                                    expectedVersionCode = pm.getVersionCode(packageInfo),
                                    expectedStockVersionCode = pm.getVersionCode(stockInfo),
                                    label = label,
                                    downgradeFallbackConfirmed = downgradeFallbackConfirmed
                                )
                            )
                            when (mountResult) {
                                is RootMountResult.Success -> {
                                    val sourceAttributionError = if (plan.installAsPlayStore) {
                                        reinstallMountedStockAsPlayStore(
                                            context = app,
                                            rootInstaller = rootInstaller,
                                            rootMountCoordinator = rootMountCoordinator,
                                            packageName = packageName,
                                            userId = android.os.Process.myUid() / 100_000
                                        )
                                    } else null
                                    installationSucceeded()
                                    sourceAttributionError?.let { error ->
                                        val warning = app.getString(
                                            R.string.installer_play_store_attribution_failed,
                                            error.simpleMessage()
                                                ?: error.javaClass.simpleName.orEmpty()
                                        )
                                        appendLog(warning)
                                        app.toast(warning)
                                    }
                                }
                                is RootMountResult.RequiresDowngradeConfirmation -> {
                                    pendingRootMountInstallerToken = installerToken
                                    stateFlow.update {
                                        it.copy(rootDowngradeConfirmationPending = true)
                                    }
                                }
                                else -> mountResult.requireSuccess()
                            }
                        }
                        // Code adapted from Morphe, see third-party/NOTICE for more information
                        // https://github.com/MorpheApp/morphe-manager/commit/7e24461c1454b712da4df21440db6f417c94ce58
                        is InstallerManager.InstallPlan.RootPlayStore -> {
                            check(!splitOutput) {
                                app.getString(R.string.installer_status_not_supported)
                            }
                            installAsPlayStoreWithMountRollback(
                                rootInstaller = rootInstaller,
                                rootMountCoordinator = rootMountCoordinator,
                                apkFile = result.outputFile,
                                packageName = packageName,
                                userId = android.os.Process.myUid() / 100_000
                            )
                            installationSucceeded()
                        }
                        is InstallerManager.InstallPlan.Shizuku -> {
                            if (splitOutput) {
                                val installResult = shizukuInstaller.installMultiple(
                                    installFiles,
                                    packageName,
                                    plan.installerPackageNameOverride
                                )
                                if (installResult.status != AndroidPackageInstaller.STATUS_SUCCESS) {
                                    throw IOException(
                                        installResult.message
                                            ?: app.getString(R.string.split_installer_failed)
                                    )
                                }
                            } else {
                                shizukuInstaller.install(
                                    result.outputFile,
                                    packageName,
                                    plan.installerPackageNameOverride
                                )
                            }
                            installationSucceeded()
                        }
                        is InstallerManager.InstallPlan.External -> {
                            launchExternalInstaller(plan)
                        }
                    }
                }
            } catch (_: CancellationException) {
                appendLog("Installation cancelled")
            } catch (error: Throwable) {
                val message = app.getString(
                    R.string.install_app_fail,
                    error.simpleMessage().orEmpty()
                )
                appendLog("Install error: $message")
                appendLog(error.stackTraceToString())
                app.toast(message)
                stateFlow.update { it.copy(error = message) }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    splitInstallWorkspace?.deleteRecursively()
                }
                stateFlow.update { it.copy(installing = false) }
            }
        }
    }

    fun confirmRootDowngrade() {
        if (!stateFlow.value.rootDowngradeConfirmationPending) return
        val installerToken = pendingRootMountInstallerToken
        pendingRootMountInstallerToken = null
        startInstall(installerToken, downgradeFallbackConfirmed = true)
    }

    fun dismissRootDowngradeConfirmation() {
        pendingRootMountInstallerToken = null
        stateFlow.update { it.copy(rootDowngradeConfirmationPending = false) }
    }

    fun cancelInstall() {
        if (
            pendingExternalInstall?.token == InstallerManager.Token.PlayStore &&
            pendingExternalMountSuspension != null
        ) {
            return
        }
        installJob?.cancel(CancellationException("User cancelled installation"))
    }

    private suspend fun installInternally(
        apk: File,
        packageName: String,
        label: String?
    ) {
        if (!pm.requestInstallPackagesPermission()) {
            throw IOException(
                app.getString(R.string.downloaded_app_install_permission_required)
            )
        }
        appendLog("Launching internal Package Installer")
        val installResult = try {
            sessionInstaller.install(apk, packageName)
        } catch (_: InstallCancelledException) {
            appendLog("Package Installer was cancelled")
            return
        } catch (_: SessionDeadException) {
            appendLog("Package Installer session ended early; launching system fallback")
            val fallbackPlan = installerManager.createSystemFallbackPlan(
                target = InstallerManager.InstallTarget.PATCHER,
                sourceFile = apk,
                expectedPackage = packageName,
                sourceLabel = label
            )
            launchExternalInstaller(fallbackPlan)
            return
        }
        when (installResult) {
            InstallResult.Success -> installationSucceeded()
            is InstallResult.Conflict -> throw IOException(
                installerManager.formatFailureHint(
                    AndroidPackageInstaller.STATUS_FAILURE_CONFLICT,
                    installResult.message
                ) ?: installResult.message ?: app.getString(R.string.installer_hint_generic)
            )
            is InstallResult.Failure -> throw IOException(
                installerManager.formatFailureHint(installResult.status, installResult.message)
                    ?: installResult.message
                    ?: app.getString(R.string.installer_hint_generic)
            )
        }
    }

    private suspend fun installSplitInternally(
        apkFiles: List<File>,
        expectedPackage: String
    ) {
        if (!pm.requestInstallPackagesPermission()) {
            throw IOException(
                app.getString(R.string.downloaded_app_install_permission_required)
            )
        }
        appendLog("Launching internal Package Installer for ${apkFiles.size} split APKs")
        val beforeInstall = withContext(Dispatchers.IO) {
            pm.installedPackageSnapshot(expectedPackage, includeHashes = false)
        }
        val outcome = withContext(Dispatchers.IO) {
            val packageInstaller = app.packageManager.packageInstaller
            val params = AndroidPackageInstaller.SessionParams(
                AndroidPackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setInstallReason(PackageManager.INSTALL_REASON_USER)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    setRequestUpdateOwnership(true)
                }
            }
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)
            var completed = false
            try {
                apkFiles.forEachIndexed { index, file ->
                    currentCoroutineContext().ensureActive()
                    file.inputStream().use { input ->
                        session.openWrite("$index.apk", 0, file.length()).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                            session.fsync(output)
                        }
                    }
                }

                val result = CompletableDeferred<InstallOutcome>()
                val intentSender = IntentSenderCompat.create { resultIntent ->
                    val status = resultIntent.getIntExtra(
                        AndroidPackageInstaller.EXTRA_STATUS,
                        AndroidPackageInstaller.STATUS_FAILURE
                    )
                    if (status == AndroidPackageInstaller.STATUS_PENDING_USER_ACTION) {
                        val confirmationIntent = resultIntent.readConfirmationIntent()
                        if (confirmationIntent == null) {
                            result.complete(
                                InstallOutcome(
                                    AndroidPackageInstaller.STATUS_FAILURE,
                                    app.getString(R.string.split_installer_failed)
                                )
                            )
                        } else {
                            appendLog("Waiting for Package Installer confirmation")
                            confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            viewModelScope.launch(Dispatchers.Main.immediate) {
                                runCatching {
                                    ContextCompat.startActivity(
                                        app,
                                        confirmationIntent,
                                        null
                                    )
                                }.onFailure { error ->
                                    if (!result.isCompleted) {
                                        result.complete(
                                            InstallOutcome(
                                                AndroidPackageInstaller.STATUS_FAILURE,
                                                error.message
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    } else if (!result.isCompleted) {
                        result.complete(
                            InstallOutcome(
                                status,
                                resultIntent.getStringExtra(
                                    AndroidPackageInstaller.EXTRA_STATUS_MESSAGE
                                )
                            )
                        )
                    }
                }
                session.commit(intentSender)
                val installOutcome = withTimeout(SPLIT_INSTALL_TIMEOUT_MS) {
                    result.await()
                }
                completed = installOutcome.status ==
                    AndroidPackageInstaller.STATUS_SUCCESS
                installOutcome
            } finally {
                runCatching { session.close() }
                if (!completed) {
                    runCatching { packageInstaller.abandonSession(sessionId) }
                }
            }
        }
        val installedDespiteFailure =
            outcome.status != AndroidPackageInstaller.STATUS_SUCCESS &&
                confirmSplitInstallCompleted(apkFiles, expectedPackage, beforeInstall)
        if (outcome.status != AndroidPackageInstaller.STATUS_SUCCESS && !installedDespiteFailure) {
            throw IOException(
                outcome.message ?: app.getString(R.string.split_installer_failed)
            )
        }
        if (installedDespiteFailure) {
            // Code adapted from Morphe, see third-party/NOTICE for more information
            // https://github.com/MorpheApp/morphe-manager/pull/598
            appendLog(
                "Package Installer reported failure but APK verification succeeded for " +
                    expectedPackage
            )
        }
        installationSucceeded()
    }

    private suspend fun confirmSplitInstallCompleted(
        apkFiles: List<File>,
        expectedPackage: String,
        beforeInstall: InstalledPackageSnapshot?
    ): Boolean = withContext(Dispatchers.IO) {
        val afterInstall = pm.installedPackageSnapshot(expectedPackage)
            ?: return@withContext false
        afterInstall.changedSince(beforeInstall) && afterInstall.matches(apkFiles)
    }

    private fun resolveConfiguredInstallerPlan(
        result: SignatureMetadataInjectorResult,
        packageName: String,
        label: String?,
        targetIsSplit: Boolean
    ): InstallerManager.InstallPlan {
        val target = InstallerManager.InstallTarget.PATCHER

        fun resolve(token: InstallerManager.Token): InstallerManager.InstallPlan? {
            if (token == InstallerManager.Token.None) return null
            if (targetIsSplit && token == InstallerManager.Token.AutoSaved) return null
            return installerManager.resolvePlanForToken(
                token = token,
                target = target,
                sourceFile = result.outputFile,
                expectedPackage = packageName,
                sourceLabel = label
            )
        }

        val primaryToken = installerManager.getPrimaryToken()
        resolve(primaryToken)?.let { return it }

        val fallbackToken = installerManager.getFallbackToken()
        if (fallbackToken != primaryToken) {
            resolve(fallbackToken)?.let { fallbackPlan ->
                appendLog(
                    "Primary installer is unavailable for this output; " +
                        "using the configured fallback installer"
                )
                return fallbackPlan
            }
        }

        appendLog(
            "Primary and fallback installers are unavailable for this output; " +
                "using the internal package installer"
        )
        return InstallerManager.InstallPlan.Internal(target)
    }

    private fun installationSucceeded() {
        val message = app.getString(R.string.install_app_success)
        appendLog(message)
        app.toast(message)
        stateFlow.update { it.copy(installStatus = message) }
    }

    private suspend fun launchExternalInstaller(plan: InstallerManager.InstallPlan.External) {
        val baseline = withContext(Dispatchers.IO) {
            pm.installedPackageSnapshot(plan.expectedPackage, includeHashes = false)
        }
        val suspendedMount = if (plan.token == InstallerManager.Token.PlayStore) {
            suspendRootMountForPackageInstall(
                rootInstaller = rootInstaller,
                rootMountCoordinator = rootMountCoordinator,
                packageName = plan.expectedPackage,
                userId = android.os.Process.myUid() / 100_000,
                recoveryContext = app
            )
        } else {
            null
        }

        pendingExternalInstall = plan
        pendingExternalMountSuspension = suspendedMount
        var recoveryOwnsCleanup = false
        fun showLaunched() {
            val message = app.getString(
                R.string.installer_external_launched,
                plan.installerLabel
            )
            appendLog(message)
            app.toast(message)
            stateFlow.update { it.copy(installStatus = message) }
        }

        val installed = try {
            if (suspendedMount != null) {
                launchExternalInstallerWithMountFinalization(
                    context = app,
                    targetIntent = plan.intent,
                    suspendedMount = suspendedMount,
                    installChanged = {
                        val current = pm.installedPackageSnapshot(
                            plan.expectedPackage,
                            includeHashes = false
                        )
                        current != null && current.changedSince(baseline)
                    },
                    activityTimeoutMs = EXTERNAL_INSTALL_TIMEOUT_MS,
                    onLaunched = ::showLaunched,
                    onRecoveryOwnershipTransferred = {
                        recoveryOwnsCleanup = true
                        if (pendingExternalInstall === plan) {
                            pendingExternalInstall = null
                        }
                    },
                    cleanupFile = plan.sharedFile,
                    cleanup = { installerManager.cleanup(plan) }
                )
            } else {
                ContextCompat.startActivity(app, plan.intent, null)
                showLaunched()
                withTimeoutOrNull(EXTERNAL_INSTALL_TIMEOUT_MS) {
                    while (true) {
                        delay(EXTERNAL_INSTALL_POLL_INTERVAL_MS)
                        val current = withContext(Dispatchers.IO) {
                            pm.installedPackageSnapshot(
                                plan.expectedPackage,
                                includeHashes = false
                            )
                        }
                        if (current != null && current.changedSince(baseline)) break
                    }
                    true
                } ?: false
            }
        } finally {
            pendingExternalInstall = null
            pendingExternalMountSuspension = null
            if (!recoveryOwnsCleanup) installerManager.cleanup(plan)
        }

        if (installed) {
            val attributionError = installerManager.tryFinalizePlayStoreAttribution(plan)
            installationSucceeded()
            attributionError?.let { error ->
                val warning = app.getString(
                    R.string.installer_play_store_attribution_failed,
                    error.simpleMessage() ?: error.javaClass.simpleName.orEmpty()
                )
                appendLog(warning)
                app.toast(warning)
            }
        } else {
            val timeoutMessage = app.getString(
                R.string.installer_external_timeout,
                plan.installerLabel
            )
            appendLog(timeoutMessage)
            stateFlow.update { it.copy(installStatus = null, error = timeoutMessage) }
        }
    }

    private suspend fun restorePendingExternalMount() {
        val suspension = pendingExternalMountSuspension ?: return
        withContext(NonCancellable) { suspension.restore() }
        if (pendingExternalMountSuspension === suspension) {
            pendingExternalMountSuspension = null
        }
    }

    override fun onCleared() {
        val jobsToAwait = listOfNotNull(
            metadataAnalysisJob,
            apkAnalysisJob,
            injectionJob,
            splitSelectionJob,
            installJob
        )
        metadataAnalysisJob?.cancel()
        apkAnalysisJob?.cancel()
        injectionJob?.cancel()
        splitSelectionJob?.cancel()
        manager.cancelActiveExecution()
        installJob?.cancel()
        val rootInstallerStillOpen =
            pendingExternalInstall?.token == InstallerManager.Token.PlayStore &&
                pendingExternalMountSuspension != null
        if (!rootInstallerStillOpen) {
            pendingExternalInstall?.let(installerManager::cleanup)
            pendingExternalInstall = null
        }
        val suspendedMount = pendingExternalMountSuspension.takeUnless {
            rootInstallerStillOpen
        }
        if (!rootInstallerStillOpen) pendingExternalMountSuspension = null
        synchronized(fullLogLock) {
            fullLogStoreClosed = true
            closeFullLogWriterLocked()
        }
        val cacheGuard = workspaceCacheGuard
        workspaceCacheGuard = null
        CoroutineScope(Dispatchers.IO).launch {
            jobsToAwait.forEach { job ->
                runCatching { job.join() }
            }
            runCatching { workspace.deleteRecursively() }
            runCatching { cacheGuard?.close() }
            runCatching { suspendedMount?.restore() }
        }
        super.onCleared()
    }

    private fun updateSelection(
        role: SignatureMetadataInputRole,
        selection: SignatureMetadataSelectionState
    ) {
        stateFlow.update { current ->
            when (role) {
                SignatureMetadataInputRole.SIGNATURE_SOURCE ->
                    current.copy(signatureSource = selection, error = null)
                SignatureMetadataInputRole.TARGET_APK ->
                    current.copy(targetApk = selection, error = null)
            }
        }
    }

    private fun currentSelection(
        role: SignatureMetadataInputRole
    ): SignatureMetadataSelectionState = when (role) {
        SignatureMetadataInputRole.SIGNATURE_SOURCE ->
            stateFlow.value.signatureSource
        SignatureMetadataInputRole.TARGET_APK ->
            stateFlow.value.targetApk
    }

    private fun generationFor(role: SignatureMetadataInputRole): Long = when (role) {
        SignatureMetadataInputRole.SIGNATURE_SOURCE -> metadataGeneration
        SignatureMetadataInputRole.TARGET_APK -> apkGeneration
    }

    private fun clearOutput() {
        stateFlow.value.result?.outputFile?.delete()
        stateFlow.update {
            it.copy(
                result = null,
                progress = null,
                installStatus = null,
                error = null
            )
        }
    }

    private fun appendLog(message: String) {
        val lines = message.lineSequence()
            .map(String::trimEnd)
            .filter(String::isNotBlank)
            .toList()
        if (lines.isEmpty()) return
        val timestamp = "%1\$tH:%1\$tM:%1\$tS".format(Date())
        val formattedLines = lines.map { line -> "[$timestamp] $line" }
        synchronized(fullLogLock) {
            appendFullLogLocked(formattedLines)
            stateFlow.update { current ->
                current.copy(
                    logEntries = (current.logEntries + formattedLines)
                        .takeLast(MAX_VISIBLE_LOG_LINES),
                    logRevision = current.logRevision + 1
                )
            }
        }
    }

    private fun clearLogs() {
        synchronized(fullLogLock) {
            resetFullLogLocked()
        }
        stateFlow.update { current ->
            current.copy(
                logEntries = emptyList(),
                logRevision = current.logRevision + 1,
                logSessionId = current.logSessionId + 1
            )
        }
    }

    private fun appendFullLogLocked(lines: List<String>) {
        if (fullLogStoreClosed || fullLogWriteDisabled) return
        runCatching {
            val writer = fullLogWriter ?: Files.newBufferedWriter(
                fullLogFile.toPath(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            ).also { fullLogWriter = it }
            lines.forEach { line ->
                writer.write(line)
                writer.newLine()
            }
        }.onFailure {
            closeFullLogWriterLocked()
            fullLogWriteDisabled = true
        }
    }

    private fun readFullLogContent(): String = synchronized(fullLogLock) {
        val visibleLog = stateFlow.value.logEntries.joinToString("\n")
        if (fullLogWriteDisabled || !fullLogFile.isFile) return@synchronized visibleLog
        runCatching {
            fullLogWriter?.flush()
            fullLogFile.readText(StandardCharsets.UTF_8).trimEnd('\r', '\n')
        }.getOrElse {
            closeFullLogWriterLocked()
            fullLogWriteDisabled = true
            visibleLog
        }
    }

    private fun resetFullLogLocked() {
        closeFullLogWriterLocked()
        fullLogWriteDisabled = false
        if (fullLogStoreClosed) {
            fullLogWriteDisabled = true
            return
        }
        runCatching {
            Files.createDirectories(workspace.toPath())
            Files.newBufferedWriter(
                fullLogFile.toPath(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            ).use { }
        }.onFailure {
            fullLogWriteDisabled = true
        }
    }

    private fun closeFullLogWriterLocked() {
        runCatching { fullLogWriter?.close() }
        fullLogWriter = null
    }

    private fun acquireWorkspaceCacheGuard() {
        if (workspaceCacheGuard == null) {
            workspaceCacheGuard = CacheCleanupGuard.begin()
        }
    }

    private fun releaseWorkspaceCacheGuardIfUnused() {
        val current = stateFlow.value
        val hasActiveWorkspaceFiles = current.signatureSource.stagedFile != null ||
            current.targetApk.stagedFile != null ||
            current.result != null
        val analysisInProgress =
            current.signatureSource.analyzing || current.targetApk.analyzing
        if (!current.working && !analysisInProgress && !hasActiveWorkspaceFiles) {
            workspaceCacheGuard?.close()
            workspaceCacheGuard = null
        }
    }

    private suspend fun stageInput(
        role: SignatureMetadataInputRole,
        uri: Uri,
        displayName: String
    ): File {
        workspace.mkdirs()
        val displayNameExtension = displayName
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .takeIf(SUPPORTED_SIGNATURE_METADATA_INPUT_EXTENSIONS::contains)
        val extension = displayNameExtension ?: runCatching {
            app.contentResolver.getType(uri)
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
        }.getOrNull()
            ?.takeIf { it == APK_MIMETYPE }
            ?.let { "apk" }
            ?: "zip"
        val output = workspace.resolve(
            "${role.name.lowercase()}-${UUID.randomUUID()}.$extension"
        )
        val sizeLimit = MAX_INPUT_APK_SIZE
        try {
            val input = if (uri.scheme.equals("file", ignoreCase = true)) {
                val path = uri.path ?: throw IOException("Invalid input path.")
                File(path).inputStream()
            } else {
                app.contentResolver.openInputStream(uri)
                    ?: throw IOException("Unable to open selected input.")
            }
            input.use { source ->
                output.outputStream().use { destination ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > sizeLimit) {
                            throw IOException(
                                "Selected input exceeds the supported size limit."
                            )
                        }
                        destination.write(buffer, 0, count)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            return output
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    private fun normalizeOutputName(
        value: String,
        splitContainer: Boolean
    ): String {
        val sanitized = value.trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "metadata-injected" }
        val requestedExtension = sanitized
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
        val extension = if (splitContainer) {
            requestedExtension
                .takeIf(SPLIT_CONTAINER_EXTENSIONS::contains)
                ?.let { ".$it" }
                ?: ".apks"
        } else {
            ".apk"
        }
        val withoutKnownExtension = sanitized.removeKnownApkContainerExtension()
        return "$withoutKnownExtension$extension"
    }

    private fun String.removeKnownApkContainerExtension(): String {
        val extension = substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
        return if (extension in SUPPORTED_SIGNATURE_METADATA_INPUT_EXTENSIONS) {
            substringBeforeLast('.')
        } else {
            this
        }
    }

    private companion object {
        const val MAX_INPUT_APK_SIZE = 4_294_967_295L
        val SUPPORTED_SIGNATURE_METADATA_INPUT_EXTENSIONS =
            setOf("apk", "apks", "xapk", "apkm", "zip")
        val SPLIT_CONTAINER_EXTENSIONS = setOf("apks", "xapk", "apkm", "zip")
        const val MAX_VISIBLE_LOG_LINES = 2_000
        const val FULL_LOG_FILE_NAME = "injector.log"
        const val SPLIT_INSTALL_TIMEOUT_MS = 5L * 60L * 1000L
        const val EXTERNAL_INSTALL_TIMEOUT_MS = 5L * 60L * 1000L
        const val EXTERNAL_INSTALL_POLL_INTERVAL_MS = 500L
    }
}
