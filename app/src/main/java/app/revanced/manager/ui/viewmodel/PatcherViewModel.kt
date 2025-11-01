package app.revanced.manager.ui.viewmodel

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.ParcelUuid
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.autoSaver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.SavedStateHandleSaveableApi
import androidx.lifecycle.viewmodel.compose.saveable
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.universal.revanced.manager.R
import app.revanced.manager.data.platform.Filesystem
import app.revanced.manager.data.room.apps.installed.InstallType
import app.revanced.manager.data.room.apps.installed.InstalledApp
import app.revanced.manager.domain.installer.InstallerManager
import app.revanced.manager.domain.installer.RootInstaller
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.domain.repository.InstalledAppRepository
import app.revanced.manager.domain.worker.WorkerRepository
import app.revanced.manager.patcher.logger.LogLevel
import app.revanced.manager.patcher.logger.Logger
import app.revanced.manager.patcher.runtime.ProcessRuntime
import app.revanced.manager.patcher.worker.PatcherWorker
import app.revanced.manager.plugin.downloader.PluginHostApi
import app.revanced.manager.plugin.downloader.UserInteractionException
import app.revanced.manager.service.InstallService
import app.revanced.manager.service.UninstallService
import app.revanced.manager.ui.model.InstallerModel
import app.revanced.manager.ui.model.ProgressKey
import app.revanced.manager.ui.model.SelectedApp
import app.revanced.manager.ui.model.State
import app.revanced.manager.ui.model.Step
import app.revanced.manager.ui.model.StepCategory
import app.revanced.manager.ui.model.StepProgressProvider
import app.revanced.manager.ui.model.navigation.Patcher
import app.revanced.manager.util.PM
import app.revanced.manager.util.saveableVar
import app.revanced.manager.util.saver.snapshotStateListSaver
import app.revanced.manager.util.simpleMessage
import app.revanced.manager.util.tag
import app.revanced.manager.util.toast
import app.revanced.manager.util.uiSafe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.time.withTimeout
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import java.util.UUID

@OptIn(SavedStateHandleSaveableApi::class, PluginHostApi::class)
class PatcherViewModel(
    private val input: Patcher.ViewModelParams
) : ViewModel(), KoinComponent, StepProgressProvider, InstallerModel {
    private val app: Application by inject()
    private val fs: Filesystem by inject()
    private val pm: PM by inject()
    private val workerRepository: WorkerRepository by inject()
    private val installedAppRepository: InstalledAppRepository by inject()
    private val rootInstaller: RootInstaller by inject()
    private val installerManager: InstallerManager by inject()
    private val prefs: PreferencesManager by inject()
    private val savedStateHandle: SavedStateHandle = get()

    private var pendingExternalInstall: InstallerManager.InstallPlan.External? = null
    private var externalInstallTimeoutJob: Job? = null


    private var installedApp: InstalledApp? = null
    private val selectedApp = input.selectedApp
    val packageName = selectedApp.packageName
    val version = selectedApp.version

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
    private var savedPatchedApp by savedStateHandle.saveableVar { false }
    val hasSavedPatchedApp get() = savedPatchedApp

    private var currentActivityRequest: Pair<CompletableDeferred<Boolean>, String>? by mutableStateOf(
        null
    )
    val activityPromptDialog by derivedStateOf { currentActivityRequest?.second }

    private var launchedActivity: CompletableDeferred<ActivityResult>? = null
    private val launchActivityChannel = Channel<Intent>()
    val launchActivityFlow = launchActivityChannel.receiveAsFlow()

    private val tempDir = savedStateHandle.saveable(key = "tempDir") {
        fs.uiTempDir.resolve("installer").also {
            it.deleteRecursively()
            it.mkdirs()
        }
    }

    private var inputFile: File? by savedStateHandle.saveableVar()
    private val outputFile = tempDir.resolve("output.apk")

    private val logs by savedStateHandle.saveable<MutableList<Pair<LogLevel, String>>> { mutableListOf() }
    private val logger = object : Logger() {
        override fun log(level: LogLevel, message: String) {
            level.androidLog(message)
            if (level == LogLevel.TRACE) return

            viewModelScope.launch {
                logs.add(level to message)
            }
        }
    }

    private val patchCount = input.selectedPatches.values.sumOf { it.size }
    private var completedPatchCount by savedStateHandle.saveable {
        // SavedStateHandle.saveable only supports the boxed version.
        @Suppress("AutoboxingStateCreation") mutableStateOf(
            0
        )
    }
    val patchesProgress get() = completedPatchCount to patchCount
    override var downloadProgress by savedStateHandle.saveable(
        key = "downloadProgress",
        stateSaver = autoSaver()
    ) {
        mutableStateOf<Pair<Long, Long?>?>(null)
    }
        private set
    data class MemoryAdjustmentDialogState(
        val previousLimit: Int,
        val newLimit: Int,
        val adjusted: Boolean
    )

    var memoryAdjustmentDialog by mutableStateOf<MemoryAdjustmentDialogState?>(null)
        private set
    val steps by savedStateHandle.saveable(saver = snapshotStateListSaver()) {
        generateSteps(
            app,
            input.selectedApp
        ).toMutableStateList()
    }
    private var currentStepIndex = 0

    val progress by derivedStateOf {
        val current = steps.count {
            it.state == State.COMPLETED && it.category != StepCategory.PATCHING
        } + completedPatchCount

        val total = steps.size - 1 + patchCount

        current.toFloat() / total.toFloat()
    }

    private val workManager = WorkManager.getInstance(app)
    private val _patcherSucceeded = MediatorLiveData<Boolean?>()
    val patcherSucceeded: LiveData<Boolean?> get() = _patcherSucceeded
    private var currentWorkSource: LiveData<WorkInfo?>? = null
    private val handledFailureIds = mutableSetOf<UUID>()
    private var forceKeepLocalInput = false

    private var patcherWorkerId: ParcelUuid by savedStateHandle.saveableVar {
        ParcelUuid(launchWorker())
    }

    init {
        observeWorker(patcherWorkerId.uuid)
    }

    private suspend fun persistPatchedApp(
        currentPackageName: String?,
        installType: InstallType
    ): Boolean = withContext(Dispatchers.IO) {
        val installedPackageInfo = currentPackageName?.let(pm::getPackageInfo)
        val patchedPackageInfo = pm.getPackageInfo(outputFile)
        val packageInfo = installedPackageInfo ?: patchedPackageInfo
        if (packageInfo == null) {
            Log.e(TAG, "Failed to resolve package info for patched APK")
            return@withContext false
        }

        val finalPackageName = packageInfo.packageName
        val finalVersion = packageInfo.versionName?.takeUnless { it.isBlank() } ?: version ?: "unspecified"

        if (installType == InstallType.SAVED) {
            try {
                val destination = fs.getPatchedAppFile(finalPackageName, finalVersion)
                outputFile.copyTo(destination, overwrite = true)
            } catch (error: IOException) {
                Log.e(TAG, "Failed to copy patched APK for later", error)
                return@withContext false
            }
        } else {
            val savedCopy = fs.getPatchedAppFile(finalPackageName, finalVersion)
            if (savedCopy.exists() && !savedCopy.delete()) {
                Log.w(TAG, "Failed to delete saved patched APK copy for $finalPackageName")
            }
        }

        installedAppRepository.addOrUpdate(
            finalPackageName,
            packageName,
            finalVersion,
            installType,
            input.selectedPatches
        )

        savedPatchedApp = installType == InstallType.SAVED
        true
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
            val success = persistPatchedApp(null, InstallType.SAVED)
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

    private val installerBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REPLACED -> {
                    val pkg = intent.data?.schemeSpecificPart ?: return
                    handleExternalInstallSuccess(pkg)
                }

                InstallService.APP_INSTALL_ACTION -> {
                    val pmStatus = intent.getIntExtra(
                        InstallService.EXTRA_INSTALL_STATUS,
                        PackageInstaller.STATUS_FAILURE
                    )

                    intent.getStringExtra(UninstallService.EXTRA_UNINSTALL_STATUS_MESSAGE)
                        ?.let(logger::trace)

                    if (pmStatus == PackageInstaller.STATUS_SUCCESS) {
                        app.toast(app.getString(R.string.install_app_success))
                        installedPackageName =
                            intent.getStringExtra(InstallService.EXTRA_PACKAGE_NAME)
                        viewModelScope.launch {
                            val persisted = persistPatchedApp(installedPackageName, InstallType.DEFAULT)
                            if (!persisted) {
                                Log.w(TAG, "Failed to persist installed patched app metadata")
                            }
                        }
                    } else packageInstallerStatus = pmStatus

                    isInstalling = false
                }

                UninstallService.APP_UNINSTALL_ACTION -> {
                    val pmStatus = intent.getIntExtra(
                        UninstallService.EXTRA_UNINSTALL_STATUS,
                        PackageInstaller.STATUS_FAILURE
                    )

                    intent.getStringExtra(UninstallService.EXTRA_UNINSTALL_STATUS_MESSAGE)
                        ?.let(logger::trace)

                    if (pmStatus != PackageInstaller.STATUS_SUCCESS)
                        packageInstallerStatus = pmStatus
                }
            }
        }
    }

    init {
        // TODO: detect system-initiated process death during the patching process.
        ContextCompat.registerReceiver(
            app,
            installerBroadcastReceiver,
            IntentFilter().apply {
                addAction(InstallService.APP_INSTALL_ACTION)
                addAction(UninstallService.APP_UNINSTALL_ACTION)
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        viewModelScope.launch {
            installedApp = installedAppRepository.get(packageName)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        super.onCleared()
        app.unregisterReceiver(installerBroadcastReceiver)
        workManager.cancelWorkById(patcherWorkerId.uuid)
        pendingExternalInstall?.let(installerManager::cleanup)
        pendingExternalInstall = null
        externalInstallTimeoutJob?.cancel()
        externalInstallTimeoutJob = null

        if (input.selectedApp is SelectedApp.Installed && installedApp?.installType == InstallType.MOUNT) {
            GlobalScope.launch(Dispatchers.Main) {
                uiSafe(app, R.string.failed_to_mount, "Failed to mount") {
                    withTimeout(Duration.ofMinutes(1L)) {
                        rootInstaller.mount(packageName)
                    }
                }
            }
        }

        if (input.selectedApp is SelectedApp.Local && input.selectedApp.temporary) {
            inputFile?.takeIf { it.exists() }?.delete()
            inputFile = null
        }
    }

    fun onBack() {
        // tempDir cannot be deleted inside onCleared because it gets called on system-initiated process death.
        tempDir.deleteRecursively()
    }

    fun isDeviceRooted() = rootInstaller.isDeviceRooted()

    fun rejectInteraction() {
        currentActivityRequest?.first?.complete(false)
    }

    fun allowInteraction() {
        currentActivityRequest?.first?.complete(true)
    }

    fun handleActivityResult(result: ActivityResult) {
        launchedActivity?.complete(result)
    }

    fun export(uri: Uri?) = viewModelScope.launch {
        uri?.let { targetUri ->
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

            val wasAlreadySaved = hasSavedPatchedApp
            val saved = persistPatchedApp(null, InstallType.SAVED)
            if (!saved) {
                app.toast(app.getString(R.string.patched_app_save_failed_toast))
            } else if (!wasAlreadySaved) {
                app.toast(app.getString(R.string.patched_app_saved_toast))
            }

            app.toast(app.getString(R.string.save_apk_success))
        }
    }

    fun exportLogs(context: Context) {
        val sendIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(
                Intent.EXTRA_TEXT,
                logs.asSequence().map { (level, msg) -> "[${level.name}]: $msg" }.joinToString("\n")
            )
            type = "text/plain"
        }

        val shareIntent = Intent.createChooser(sendIntent, null)
        context.startActivity(shareIntent)
    }

    fun open() = installedPackageName?.let(pm::launch)

    private suspend fun performInstall(installType: InstallType) {
        var pmInstallStarted = false
        try {
            isInstalling = true

            val currentPackageInfo = pm.getPackageInfo(outputFile)
                ?: throw Exception("Failed to load application info")

            // If the app is currently installed
            val existingPackageInfo = pm.getPackageInfo(currentPackageInfo.packageName)
            if (existingPackageInfo != null) {
                // Check if the app version is less than the installed version
                if (pm.getVersionCode(currentPackageInfo) < pm.getVersionCode(existingPackageInfo)) {
                    // Exit if the selected app version is less than the installed version
                    packageInstallerStatus = PackageInstaller.STATUS_FAILURE_CONFLICT
                    return
                }
            }

            when (installType) {
                InstallType.DEFAULT, InstallType.SAVED -> {
                    // Check if the app is mounted as root
                    // If it is, unmount it first, silently
                    if (rootInstaller.hasRootAccess() && rootInstaller.isAppMounted(packageName)) {
                        rootInstaller.unmount(packageName)
                    }

                    // Install regularly
                    pm.installApp(listOf(outputFile))
                    pmInstallStarted = true
                }

                InstallType.MOUNT -> {
                    try {
                        val packageInfo = pm.getPackageInfo(outputFile)
                            ?: throw Exception("Failed to load application info")
                        val label = with(pm) {
                            packageInfo.label()
                        }

                        // Check for base APK, first check if the app is already installed
                        if (existingPackageInfo == null) {
                            // If the app is not installed, check if the output file is a base apk
                            if (currentPackageInfo.splitNames.isNotEmpty()) {
                                // Exit if there is no base APK package
                                packageInstallerStatus = PackageInstaller.STATUS_FAILURE_INVALID
                                return
                            }
                        }

                        val inputVersion = input.selectedApp.version
                            ?: inputFile?.let(pm::getPackageInfo)?.versionName
                            ?: throw Exception("Failed to determine input APK version")

                        // Install as root
                        rootInstaller.install(
                            outputFile,
                            inputFile,
                            packageName,
                            inputVersion,
                            label
                        )

                        if (!persistPatchedApp(packageInfo.packageName, InstallType.MOUNT)) {
                            Log.w(TAG, "Failed to persist mounted patched app metadata")
                        }

                        rootInstaller.mount(packageName)

                        installedPackageName = packageName

                        app.toast(app.getString(R.string.install_app_success))
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to install as root", e)
                        app.toast(app.getString(R.string.install_app_fail, e.simpleMessage()))
                        try {
                            rootInstaller.uninstall(packageName)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to install", e)
            app.toast(app.getString(R.string.install_app_fail, e.simpleMessage()))
        } finally {
            if (!pmInstallStarted) isInstalling = false
        }
    }

    private suspend fun executeInstallPlan(plan: InstallerManager.InstallPlan) {
        when (plan) {
            is InstallerManager.InstallPlan.Internal -> {
                pendingExternalInstall?.let(installerManager::cleanup)
                pendingExternalInstall = null
                externalInstallTimeoutJob?.cancel()
        externalInstallTimeoutJob = null
                externalInstallTimeoutJob = null
                performInstall(installTypeFor(plan.target))
            }

            is InstallerManager.InstallPlan.Root -> {
                pendingExternalInstall?.let(installerManager::cleanup)
                pendingExternalInstall = null
                externalInstallTimeoutJob?.cancel()
        externalInstallTimeoutJob = null
                externalInstallTimeoutJob = null
                performInstall(InstallType.MOUNT)
            }

            is InstallerManager.InstallPlan.External -> launchExternalInstaller(plan)
        }
    }

    private fun installTypeFor(target: InstallerManager.InstallTarget): InstallType = when (target) {
        InstallerManager.InstallTarget.PATCHER -> InstallType.DEFAULT
        InstallerManager.InstallTarget.SAVED_APP -> InstallType.DEFAULT
        InstallerManager.InstallTarget.MANAGER_UPDATE -> InstallType.DEFAULT
    }

    private fun launchExternalInstaller(plan: InstallerManager.InstallPlan.External) {
        pendingExternalInstall?.let { installerManager.cleanup(it) }
        externalInstallTimeoutJob?.cancel()
        externalInstallTimeoutJob = null

        pendingExternalInstall = plan
        isInstalling = true

        try {
            ContextCompat.startActivity(app, plan.intent, null)
            app.toast(app.getString(R.string.installer_external_launched, plan.installerLabel))
        } catch (error: ActivityNotFoundException) {
            installerManager.cleanup(plan)
            pendingExternalInstall = null
            isInstalling = false
            externalInstallTimeoutJob = null
            app.toast(app.getString(R.string.install_app_fail, error.simpleMessage()))
            return
        }

        externalInstallTimeoutJob = viewModelScope.launch {
            delay(EXTERNAL_INSTALL_TIMEOUT_MS)
            if (pendingExternalInstall == plan) {
                installerManager.cleanup(plan)
                pendingExternalInstall = null
                isInstalling = false
                externalInstallTimeoutJob = null
                app.toast(app.getString(R.string.installer_external_timeout, plan.installerLabel))
            }
        }
    }

    private fun handleExternalInstallSuccess(packageName: String) {
        val plan = pendingExternalInstall ?: return
        if (plan.expectedPackage != packageName) return

        pendingExternalInstall = null
        externalInstallTimeoutJob?.cancel()
        externalInstallTimeoutJob = null
        installerManager.cleanup(plan)
        isInstalling = false

        when (plan.target) {
            InstallerManager.InstallTarget.PATCHER -> {
                installedPackageName = packageName
                viewModelScope.launch {
                    val persisted = persistPatchedApp(packageName, InstallType.DEFAULT)
                    if (!persisted) {
                        Log.w(TAG, "Failed to persist installed patched app metadata (external installer)")
                    }
                }
                app.toast(app.getString(R.string.installer_external_success, plan.installerLabel))
            }

            InstallerManager.InstallTarget.SAVED_APP,
            InstallerManager.InstallTarget.MANAGER_UPDATE -> {
                app.toast(app.getString(R.string.installer_external_success, plan.installerLabel))
            }
        }
    }

    override fun install() {
        if (isInstalling) return
        viewModelScope.launch {
            val plan = installerManager.resolvePlan(
                InstallerManager.InstallTarget.PATCHER,
                outputFile,
                packageName,
                null
            )
            executeInstallPlan(plan)
        }
    }

    override fun reinstall() {
        if (isInstalling) return
        viewModelScope.launch {
            val plan = installerManager.resolvePlan(
                InstallerManager.InstallTarget.PATCHER,
                outputFile,
                packageName,
                null
            )
            when (plan) {
                is InstallerManager.InstallPlan.Internal -> {
                    pendingExternalInstall?.let(installerManager::cleanup)
                    pendingExternalInstall = null
                    externalInstallTimeoutJob?.cancel()
                    externalInstallTimeoutJob = null
                    try {
                        val pkg = pm.getPackageInfo(outputFile)?.packageName
                            ?: throw Exception("Failed to load application info")
                        pm.uninstallPackage(pkg)
                        performInstall(InstallType.DEFAULT)
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to reinstall", e)
                        app.toast(app.getString(R.string.reinstall_app_fail, e.simpleMessage()))
                    }
                }
                is InstallerManager.InstallPlan.Root -> {
                    pendingExternalInstall?.let(installerManager::cleanup)
                    pendingExternalInstall = null
                    externalInstallTimeoutJob?.cancel()
                    externalInstallTimeoutJob = null
                    performInstall(InstallType.MOUNT)
                }
                is InstallerManager.InstallPlan.External -> launchExternalInstaller(plan)
            }
        }
    }

    fun dismissPackageInstallerDialog() {
        packageInstallerStatus = null
    }

    private fun launchWorker(): UUID =
        workerRepository.launchExpedited<PatcherWorker, PatcherWorker.Args>(
            "patching",
            buildWorkerArgs()
        )

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

        return PatcherWorker.Args(
            selectedForRun,
            outputFile.path,
            input.selectedPatches,
            input.options,
            logger,
            onDownloadProgress = {
                withContext(Dispatchers.Main) {
                    downloadProgress = it
                }
            },
            onPatchCompleted = {
                withContext(Dispatchers.Main) { completedPatchCount += 1 }
            },
            setInputFile = { file ->
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

                withContext(Dispatchers.Main) { inputFile = storedFile }
            },
            handleStartActivityRequest = { plugin, intent ->
                withContext(Dispatchers.Main) {
                    if (currentActivityRequest != null) throw Exception("Another request is already pending.")
                    try {
                        val accepted = with(CompletableDeferred<Boolean>()) {
                            currentActivityRequest = this to plugin.name
                            await()
                        }
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
                        currentActivityRequest = null
                    }
                }
            },
            onProgress = { name, state, message ->
                viewModelScope.launch {
                    steps[currentStepIndex] = steps[currentStepIndex].run {
                        copy(
                            name = name ?: this.name,
                            state = state ?: this.state,
                            message = message ?: this.message
                        )
                    }

                    if (state == State.COMPLETED && currentStepIndex != steps.lastIndex) {
                        currentStepIndex++
                        steps[currentStepIndex] =
                            steps[currentStepIndex].copy(state = State.RUNNING)
                    }
                }
            }
        )
    }

    private fun observeWorker(id: UUID) {
        val source = workManager.getWorkInfoByIdLiveData(id)
        currentWorkSource?.let { _patcherSucceeded.removeSource(it) }
        currentWorkSource = source
        _patcherSucceeded.addSource(source) { workInfo ->
            when (workInfo?.state) {
                WorkInfo.State.SUCCEEDED -> {
                    forceKeepLocalInput = false
                    if (input.selectedApp is SelectedApp.Local && input.selectedApp.temporary) {
                        inputFile?.takeIf { it.exists() }?.delete()
                        inputFile = null
                    }
                    _patcherSucceeded.value = true
                }

                WorkInfo.State.FAILED -> {
                    handleWorkerFailure(workInfo)
                    _patcherSucceeded.value = false
                }

                WorkInfo.State.RUNNING,
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.BLOCKED -> _patcherSucceeded.value = null
                else -> _patcherSucceeded.value = null
            }
        }
    }

    private fun handleWorkerFailure(workInfo: WorkInfo) {
        if (!handledFailureIds.add(workInfo.id)) return
        val exitCode = workInfo.outputData.getInt(PatcherWorker.PROCESS_EXIT_CODE_KEY, Int.MIN_VALUE)
        if (exitCode == ProcessRuntime.OOM_EXIT_CODE) {
            viewModelScope.launch {
                if (!prefs.useProcessRuntime.get()) return@launch
                forceKeepLocalInput = true
                val previousFromWorker = workInfo.outputData.getInt(
                    PatcherWorker.PROCESS_PREVIOUS_LIMIT_KEY,
                    -1
                )
                val previousLimit = if (previousFromWorker > 0) previousFromWorker else prefs.patcherProcessMemoryLimit.get()
                val newLimit = (previousLimit - MEMORY_ADJUSTMENT_MB).coerceAtLeast(MIN_MEMORY_LIMIT_MB)
                val adjusted = newLimit < previousLimit
                if (adjusted) {
                    prefs.patcherProcessMemoryLimit.update(newLimit)
                }
                memoryAdjustmentDialog = MemoryAdjustmentDialogState(
                    previousLimit = previousLimit,
                    newLimit = if (adjusted) newLimit else previousLimit,
                    adjusted = adjusted
                )
            }
        }
    }

    fun dismissMemoryAdjustmentDialog() {
        memoryAdjustmentDialog = null
    }

    fun retryAfterMemoryAdjustment() {
        viewModelScope.launch {
            memoryAdjustmentDialog = null
            handledFailureIds.clear()
            resetStateForRetry()
            workManager.cancelWorkById(patcherWorkerId.uuid)
            val newId = launchWorker()
            patcherWorkerId = ParcelUuid(newId)
            observeWorker(newId)
        }
    }

    private fun resetStateForRetry() {
        completedPatchCount = 0
        downloadProgress = null
        val newSteps = generateSteps(app, input.selectedApp).toMutableStateList()
        steps.clear()
        steps.addAll(newSteps)
        currentStepIndex = newSteps.indexOfFirst { it.state == State.RUNNING }.takeIf { it >= 0 } ?: 0
        _patcherSucceeded.value = null
    }

    private companion object {
        const val TAG = "ReVanced Patcher"
        private const val EXTERNAL_INSTALL_TIMEOUT_MS = 60_000L
        private const val MEMORY_ADJUSTMENT_MB = 200
        private const val MIN_MEMORY_LIMIT_MB = 200

        fun LogLevel.androidLog(msg: String) = when (this) {
            LogLevel.TRACE -> Log.v(TAG, msg)
            LogLevel.INFO -> Log.i(TAG, msg)
            LogLevel.WARN -> Log.w(TAG, msg)
            LogLevel.ERROR -> Log.e(TAG, msg)
        }

        fun generateSteps(context: Context, selectedApp: SelectedApp): List<Step> {
            val needsDownload =
                selectedApp is SelectedApp.Download || selectedApp is SelectedApp.Search

            return listOfNotNull(
                Step(
                    context.getString(R.string.download_apk),
                    StepCategory.PREPARING,
                    state = State.RUNNING,
                    progressKey = ProgressKey.DOWNLOAD,
                ).takeIf { needsDownload },
                Step(
                    context.getString(R.string.patcher_step_load_patches),
                    StepCategory.PREPARING,
                    state = if (needsDownload) State.WAITING else State.RUNNING,
                ),
                Step(
                    context.getString(R.string.patcher_step_unpack),
                    StepCategory.PREPARING
                ),

                Step(
                    context.getString(R.string.execute_patches),
                    StepCategory.PATCHING
                ),

                Step(context.getString(R.string.patcher_step_write_patched), StepCategory.SAVING),
                Step(context.getString(R.string.patcher_step_sign_apk), StepCategory.SAVING)
            )
        }
    }
}
