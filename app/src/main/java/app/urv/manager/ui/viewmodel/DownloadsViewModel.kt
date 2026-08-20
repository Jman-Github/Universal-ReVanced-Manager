package app.urv.manager.ui.viewmodel

import android.content.Context
import android.content.pm.PackageInfo
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.urv.manager.data.room.apps.downloaded.DownloadedApp
import app.urv.manager.domain.installer.InstallCancelledException
import app.urv.manager.domain.installer.InstallResult
import app.urv.manager.domain.installer.InstallerManager
import app.urv.manager.domain.installer.RootInstaller
import app.urv.manager.domain.installer.SessionDeadException
import app.urv.manager.domain.installer.SessionInstaller
import app.urv.manager.domain.installer.root.RootMountOperation
import app.urv.manager.domain.installer.root.RootMountRequest
import app.urv.manager.domain.installer.root.RootMountTransactionCoordinator
import app.urv.manager.domain.installer.root.requireSuccess
import app.urv.manager.domain.installer.ShizukuInstaller
import app.urv.manager.domain.manager.KeystoreManager
import app.urv.manager.domain.manager.PreferencesManager
import app.urv.manager.domain.repository.DownloadedAppRepository
import app.urv.manager.domain.storage.CacheCleanupGuard
import app.urv.manager.patcher.runtime.MemoryLimitConfig
import app.urv.manager.patcher.split.SplitArchiveDisplayResolver
import app.urv.manager.patcher.split.SplitApkPreparer
import app.urv.manager.patcher.split.SplitMergeProcessRuntime
import app.urv.manager.domain.repository.DownloaderPluginRepository
import app.urv.manager.network.downloader.ApkDownloadHelperContract
import app.urv.manager.network.downloader.DownloaderPluginSourceState
import app.urv.manager.util.PM
import app.urv.manager.util.simpleMessage
import app.urv.manager.util.mutableStateSetOf

import app.urv.manager.util.toast
import app.universal.revanced.manager.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import ru.solrudev.ackpine.session.Session
import ru.solrudev.ackpine.uninstaller.UninstallFailure
import java.nio.file.Files
import java.nio.file.Path

class DownloadsViewModel(
    private val downloadedAppRepository: DownloadedAppRepository,
    private val downloaderPluginRepository: DownloaderPluginRepository,
    private val installerManager: InstallerManager,
    private val rootInstaller: RootInstaller,
    private val rootMountCoordinator: RootMountTransactionCoordinator,
    private val shizukuInstaller: ShizukuInstaller,
    private val keystoreManager: KeystoreManager,
    private val prefs: PreferencesManager,
    private val sessionInstaller: SessionInstaller,
    val pm: PM
) : ViewModel() {
    sealed interface RemoteSourceBusyState {
        data object Importing : RemoteSourceBusyState
        data class Updating(val id: String) : RemoteSourceBusyState
        data class InstallingHelper(val id: String) : RemoteSourceBusyState
        data class UninstallingHelper(val packageName: String) : RemoteSourceBusyState
    }

    val downloaderPluginStates = downloaderPluginRepository.pluginStates
    val downloaderPluginSourceStates = downloaderPluginRepository.sourceStates
    val apkDownloadHelpers = downloaderPluginRepository.apkDownloadHelpers
    val trustedApkDownloadHelpers = downloaderPluginRepository.trustedApkDownloadHelpers
    val downloadedApps = downloadedAppRepository.getAll().map { downloadedApps ->
        downloadedApps.sortedWith(
            compareBy<DownloadedApp> {
                it.packageName
            }.thenBy { it.version }
        )
    }
    val appSelection = mutableStateSetOf<DownloadedApp>()
    private val downloadedAppDisplayInfo = object :
        LruCache<DownloadedAppDisplayKey, DownloadedAppDisplayInfo>(
            MAX_DISPLAY_INFO_CACHE_SIZE_KIB
        ) {
        override fun sizeOf(
            key: DownloadedAppDisplayKey,
            value: DownloadedAppDisplayInfo
        ): Int {
            val iconSizeKib = (value.icon as? BitmapDrawable)
                ?.bitmap
                ?.allocationByteCount
                ?.div(BYTES_PER_KIB)
                ?: 0
            return iconSizeKib.coerceAtLeast(DISPLAY_INFO_METADATA_COST_KIB)
        }
    }

    var isRefreshingPlugins by mutableStateOf(false)
        private set
    var remoteSourceBusyState by mutableStateOf<RemoteSourceBusyState?>(null)
        private set
    var installingApp by mutableStateOf<DownloadedApp?>(null)
        private set
    private val appContext = pm.application
    private val splitMergeRuntime = SplitMergeProcessRuntime(appContext)
    private val installWorkspaceRoot =
        appContext.cacheDir.resolve("download-install").apply { mkdirs() }
    private val displayWorkspaceRoot =
        appContext.cacheDir.resolve("downloaded-app-display").apply { mkdirs() }
    private val installProgressFlow = MutableStateFlow<DownloadInstallProgress?>(null)
    val installProgress = installProgressFlow.asStateFlow()
    private var activeInstallWorkspace: File? = null
    private var installJob: Job? = null
    private var installCancellationRequested = false

    fun displayInfoFor(downloadedApp: DownloadedApp): DownloadedAppDisplayInfo? =
        downloadedAppDisplayInfo.get(displayKey(downloadedApp))

    suspend fun loadDisplayInfo(downloadedApp: DownloadedApp): DownloadedAppDisplayInfo {
        val key = displayKey(downloadedApp)
        downloadedAppDisplayInfo.get(key)?.let { return it }

        val resolved = resolveDisplayInfo(downloadedApp)
        return downloadedAppDisplayInfo.get(key) ?: resolved.also {
            downloadedAppDisplayInfo.put(key, it)
        }
    }

    private suspend fun resolveDisplayInfo(
        downloadedApp: DownloadedApp
    ): DownloadedAppDisplayInfo = withContext(Dispatchers.IO) {
        val source = runCatching {
            downloadedAppRepository.getApkFileForApp(downloadedApp)
        }.getOrNull()

        if (source != null && source.exists()) {
            try {
                if (SplitApkPreparer.isSplitArchive(source)) {
                    SplitArchiveDisplayResolver.resolve(
                        source = source,
                        workspace = displayWorkspaceRoot,
                        app = appContext,
                        pm = pm
                    )?.let { resolved ->
                        return@withContext DownloadedAppDisplayInfo(
                            packageInfo = resolved.packageInfo,
                            label = resolved.label,
                            icon = resolved.icon
                        )
                    }
                } else {
                    pm.getPackageInfo(source)?.let { packageInfo ->
                        val label = pm.getArchiveLabel(source, packageInfo)
                            ?: runCatching { with(pm) { packageInfo.label() } }.getOrNull()
                        return@withContext DownloadedAppDisplayInfo(
                            packageInfo = packageInfo,
                            label = label,
                            icon = null
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to load display info for ${source.absolutePath}", error)
            }
        }

        val installedInfo = pm.getPackageInfo(downloadedApp.packageName)
        DownloadedAppDisplayInfo(
            packageInfo = installedInfo,
            label = installedInfo?.let { runCatching { with(pm) { it.label() } }.getOrNull() },
            icon = null
        )
    }

    private fun displayKey(downloadedApp: DownloadedApp) = DownloadedAppDisplayKey(
        packageName = downloadedApp.packageName,
        version = downloadedApp.version,
        directory = downloadedApp.directory.path
    )

    fun toggleApp(downloadedApp: DownloadedApp) {
        if (appSelection.contains(downloadedApp))
            appSelection.remove(downloadedApp)
        else
            appSelection.add(downloadedApp)
    }

    data class DownloadedAppDisplayInfo(
        val packageInfo: PackageInfo?,
        val label: String?,
        val icon: Drawable?
    )

    private data class DownloadedAppDisplayKey(
        val packageName: String,
        val version: String,
        val directory: String
    )

    fun deleteApps() {
        viewModelScope.launch(NonCancellable) {
            downloadedAppRepository.delete(appSelection)

            withContext(Dispatchers.Main) {
                appSelection.clear()
            }
        }
    }

    fun deleteApp(downloadedApp: DownloadedApp) {
        viewModelScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                downloadedAppRepository.delete(listOf(downloadedApp))
            }
            appSelection.remove(downloadedApp)
        }
    }

    fun installApp(
        downloadedApp: DownloadedApp,
        installerToken: InstallerManager.Token? = null
    ) {
        if (installJob?.isActive == true || installingApp != null) return
        installingApp = downloadedApp
        installCancellationRequested = false
        installJob = viewModelScope.launch {
            val ownerJob = coroutineContext[Job]
            val cacheUseToken = CacheCleanupGuard.begin()
        var installWorkspace: File? = null

        try {
            val source = withContext(Dispatchers.IO) {
                downloadedAppRepository.getApkFileForApp(downloadedApp)
            }
            val isSplitArchive = withContext(Dispatchers.IO) {
                SplitApkPreparer.isSplitArchive(source)
            }
            installProgressFlow.value = DownloadInstallProgress(
                status = appContext.getString(
                    if (isSplitArchive) {
                        R.string.downloaded_app_install_merging
                    } else {
                        R.string.downloaded_app_install_installing
                    }
                ),
                showMergeLog = isSplitArchive
            )
            val apk = if (isSplitArchive) {
                installWorkspace = installWorkspaceRoot
                    .resolve("run-${System.currentTimeMillis()}")
                    .apply { mkdirs() }
                activeInstallWorkspace = installWorkspace

                val unsignedApk = splitMergeRuntime.execute(
                    inputFile = source,
                    workspace = installWorkspace,
                    stripNativeLibs = prefs.stripUnusedNativeLibs.getBlocking(),
                    skipUnneededSplits = prefs.skipUnneededSplitApks.getBlocking(),
                    memoryLimitMb = MemoryLimitConfig.resolveMemoryLimitMb(
                        appContext,
                        prefs.processMemoryLimit.get()
                    ),
                    onProgress = ::updateInstallProgress,
                    onSubSteps = {},
                    onLog = ::appendMergeLog
                )
                updateInstallProgress(
                    appContext.getString(R.string.downloaded_app_install_signing)
                )
                val signedApk = installWorkspace.resolve("merged-for-install.apk")
                withContext(Dispatchers.IO) {
                    keystoreManager.sign(unsignedApk, signedApk)
                    unsignedApk.delete()
                }
                signedApk
            } else {
                source
            }

            val packageInfo = withContext(Dispatchers.IO) {
                pm.getPackageInfo(apk)
                    ?: error(appContext.getString(R.string.failed_to_load_apk))
            }
            val packageName = packageInfo.packageName
            val sourceLabel = with(pm) { packageInfo.label() }
            updateInstallProgress(
                appContext.getString(R.string.downloaded_app_install_installing)
            )
            val plan = withContext(Dispatchers.IO) {
                installerToken?.let { token ->
                    installerManager.resolvePlanForToken(
                        token = token,
                        target = InstallerManager.InstallTarget.SAVED_APP,
                        sourceFile = apk,
                        expectedPackage = packageName,
                        sourceLabel = sourceLabel
                    ) ?: error(appContext.getString(R.string.installer_status_not_supported))
                } ?: installerManager.resolvePlan(
                    target = InstallerManager.InstallTarget.SAVED_APP,
                    sourceFile = apk,
                    expectedPackage = packageName,
                    sourceLabel = sourceLabel
                )
            }

            when (plan) {
                is InstallerManager.InstallPlan.Internal -> installInternally(apk)
                is InstallerManager.InstallPlan.Mount -> installWithRootMount(
                    apk = apk,
                    packageInfo = packageInfo,
                    label = sourceLabel
                )
                is InstallerManager.InstallPlan.Shizuku -> {
                    shizukuInstaller.install(
                        apk,
                        packageName,
                        plan.installerPackageNameOverride
                    )
                    showInstallSuccess()
                }
                is InstallerManager.InstallPlan.External -> launchExternalInstaller(plan)
            }
        } catch (error: CancellationException) {
            Log.i(TAG, "Downloaded app install cancelled")
            throw error
        } catch (error: Exception) {
            if (installCancellationRequested) {
                Log.i(TAG, "Downloaded app install cancelled", error)
            } else {
                Log.e(TAG, "Failed to install downloaded app", error)
                appContext.toast(
                    appContext.getString(
                        R.string.install_app_fail,
                        error.simpleMessage().orEmpty()
                    )
                )
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                installWorkspace?.deleteRecursively()
                runCatching { cacheUseToken.close() }
            }
            if (activeInstallWorkspace == installWorkspace) {
                activeInstallWorkspace = null
            }
            installProgressFlow.value = null
            installingApp = null
            installCancellationRequested = false
            if (installJob === ownerJob) installJob = null
        }
        }
    }

    fun cancelInstall() {
        val job = installJob?.takeIf(Job::isActive) ?: return
        installCancellationRequested = true
        installProgressFlow.value = null
        installingApp = null
        job.cancel(CancellationException("Downloaded app install cancelled"))
    }

    private fun updateInstallProgress(message: String) {
        installProgressFlow.update { current ->
            current?.copy(status = message) ?: current
        }
    }

    private fun appendMergeLog(message: String) {
        val lines = message.lineSequence()
            .map(String::trimEnd)
            .filter(String::isNotBlank)
            .toList()
        if (lines.isEmpty()) return
        installProgressFlow.update { current ->
            current?.copy(
                logLines = (current.logLines + lines).takeLast(MAX_INSTALL_LOG_LINES)
            ) ?: current
        }
    }

    private suspend fun installInternally(apk: File) {
        if (!pm.requestInstallPackagesPermission()) {
            appContext.toast(
                appContext.getString(R.string.downloaded_app_install_permission_required)
            )
            return
        }

        val packageInfo = withContext(Dispatchers.IO) {
            pm.getPackageInfo(apk)
                ?: throw IOException(appContext.getString(R.string.installer_hint_generic))
        }
        val packageName = packageInfo.packageName
        val sourceLabel = with(pm) { packageInfo.label() }
        val result = try {
            sessionInstaller.install(apk, packageName)
        } catch (_: InstallCancelledException) {
            return
        } catch (error: SessionDeadException) {
            Log.w(TAG, "PackageInstaller session died; using intent fallback", error)
            launchExternalInstaller(
                installerManager.createSystemFallbackPlan(
                    target = InstallerManager.InstallTarget.SAVED_APP,
                    sourceFile = apk,
                    expectedPackage = packageName,
                    sourceLabel = sourceLabel
                )
            )
            return
        }

        when (result) {
            InstallResult.Success -> showInstallSuccess()
            is InstallResult.Conflict -> appContext.toast(
                appContext.getString(
                    R.string.install_app_fail,
                    result.message.orEmpty()
                )
            )
            is InstallResult.Failure -> appContext.toast(
                appContext.getString(
                    R.string.install_app_fail,
                    installerManager.formatFailureHint(result.status, result.message)
                        ?: result.message.orEmpty()
                )
            )
        }
    }

    private suspend fun installWithRootMount(
        apk: File,
        packageInfo: PackageInfo,
        label: String
    ) {
        val installed = pm.getPackageInfo(packageInfo.packageName)
            ?: error(appContext.getString(R.string.root_mount_requires_installed_stock))
        val installedVersionCode = pm.getVersionCode(installed)
        check(
            installed.versionName == packageInfo.versionName &&
                installedVersionCode == pm.getVersionCode(packageInfo)
        ) {
            appContext.getString(R.string.root_mount_download_version_mismatch)
        }
        val alreadyMounted = rootInstaller.isAppMounted(packageInfo.packageName)
        val stockApks = if (alreadyMounted) {
            emptyList()
        } else {
            val stockPath = installed.applicationInfo?.sourceDir
                ?: error(appContext.getString(R.string.install_app_fail_missing_stock))
            val stockApk = File(stockPath)
            check(stockApk.isFile) {
                appContext.getString(R.string.install_app_fail_missing_stock)
            }
            listOf(stockApk)
        }
        rootMountCoordinator.execute(
            RootMountRequest(
                packageName = packageInfo.packageName,
                userId = android.os.Process.myUid() / 100_000,
                operation = RootMountOperation.SWITCH_PATCHED_BUILD,
                patchedApk = apk,
                stockApks = stockApks,
                expectedVersionName = packageInfo.versionName,
                expectedVersionCode = pm.getVersionCode(packageInfo),
                expectedStockVersionCode = installedVersionCode,
                label = label
            )
        ).requireSuccess()
        showInstallSuccess()
    }

    private fun showInstallSuccess() {
        appContext.toast(appContext.getString(R.string.downloaded_app_install_success))
    }

    private suspend fun launchExternalInstaller(
        plan: InstallerManager.InstallPlan.External,
        successMessage: String = appContext.getString(R.string.downloaded_app_install_success)
    ) {
        val baseline = pm.getPackageInfo(plan.expectedPackage)?.let { packageInfo ->
            ExternalInstallBaseline(
                versionCode = pm.getVersionCode(packageInfo),
                lastUpdateTime = packageInfo.lastUpdateTime
            )
        }

        try {
            ContextCompat.startActivity(appContext, plan.intent, null)
            installProgressFlow.value = null
            appContext.toast(
                appContext.getString(R.string.installer_external_launched, plan.installerLabel)
            )

            val installed = waitForExternalInstall(plan.expectedPackage, baseline)
            if (installed) {
                appContext.toast(successMessage)
            } else {
                appContext.toast(
                    appContext.getString(
                        R.string.installer_external_finished_no_change,
                        plan.installerLabel
                    )
                )
            }
        } finally {
            cleanupExternalInstall(plan)
        }
    }

    private suspend fun waitForExternalInstall(
        packageName: String,
        baseline: ExternalInstallBaseline?
    ): Boolean = withTimeoutOrNull(EXTERNAL_INSTALL_TIMEOUT_MS) {
        var installChanged = false
        while (!installChanged) {
            delay(EXTERNAL_INSTALL_POLL_INTERVAL_MS)
            val current = pm.getPackageInfo(packageName)
            installChanged = current != null && (
                baseline == null ||
                    pm.getVersionCode(current) != baseline.versionCode ||
                    current.lastUpdateTime != baseline.lastUpdateTime
                )
        }
        true
    } ?: false

    private fun cleanupExternalInstall(plan: InstallerManager.InstallPlan.External) {
        installerManager.cleanup(plan)
    }

    fun refreshPlugins() = viewModelScope.launch {
        reloadPlugins()
    }

    fun refreshInstalledHelperState() = viewModelScope.launch {
        if (remoteSourceBusyState != null) return@launch
        downloaderPluginRepository.reload()
    }

    fun acknowledgeNewPlugins() = viewModelScope.launch {
        downloaderPluginRepository.acknowledgeAllNewPlugins()
    }

    fun importPluginSource(url: String) = viewModelScope.launch {
        remoteSourceBusyState = RemoteSourceBusyState.Importing
        runCatching {
            downloaderPluginRepository.importSourcesFromUrl(url)
        }.onFailure {
            appContext.toast(
                appContext.getString(
                    R.string.downloader_replace_fail,
                    it.simpleMessage().orEmpty()
                )
            )
        }.also {
            remoteSourceBusyState = null
        }
    }

    fun updatePluginSource(id: String) = viewModelScope.launch {
        remoteSourceBusyState = RemoteSourceBusyState.Updating(id)
        runCatching {
            downloaderPluginRepository.updateSource(id)
        }.onFailure {
            appContext.toast(
                appContext.getString(
                    R.string.downloader_update_failed,
                    it.simpleMessage().orEmpty()
                )
            )
        }.also {
            remoteSourceBusyState = null
        }
    }

    fun installHelperSource(id: String) {
        if (remoteSourceBusyState != null || installJob?.isActive == true) return
        val source = downloaderPluginRepository.sourceStates.value[id] ?: return
        val helperState = source.state as? DownloaderPluginSourceState.State.HelperApp ?: return

        viewModelScope.launch {
            remoteSourceBusyState = RemoteSourceBusyState.InstallingHelper(id)
            try {
                val apk = downloaderPluginRepository.getManagedSourceApk(id)
                    ?: error(appContext.getString(R.string.downloader_source_state_missing))
                val packageInfo = withContext(Dispatchers.IO) {
                    pm.getPackageInfo(apk)
                        ?: error(appContext.getString(R.string.failed_to_load_apk))
                }
                check(packageInfo.packageName == helperState.packageName) {
                    "Helper source package changed from ${helperState.packageName} to ${packageInfo.packageName}"
                }
                check(ApkDownloadHelperContract.isHelperArchive(apk, packageInfo)) {
                    "Downloaded APK no longer implements the APK download helper contract"
                }
                val sourceLabel = pm.getArchiveLabel(apk, packageInfo) ?: source.name
                val plan = withContext(Dispatchers.IO) {
                    installerManager.resolvePlan(
                        target = InstallerManager.InstallTarget.DOWNLOADER_HELPER,
                        sourceFile = apk,
                        expectedPackage = packageInfo.packageName,
                        sourceLabel = sourceLabel,
                        allowMount = false
                    )
                }
                val successMessage = appContext.getString(R.string.downloader_helper_source_install_success)
                when (plan) {
                    is InstallerManager.InstallPlan.Internal -> installHelperInternally(
                        apk = apk,
                        packageName = packageInfo.packageName,
                        sourceLabel = sourceLabel,
                        successMessage = successMessage
                    )
                    is InstallerManager.InstallPlan.Shizuku -> {
                        shizukuInstaller.install(
                            apk,
                            packageInfo.packageName,
                            plan.installerPackageNameOverride
                        )
                        appContext.toast(successMessage)
                    }
                    is InstallerManager.InstallPlan.External -> {
                        launchExternalInstaller(plan, successMessage)
                    }
                    is InstallerManager.InstallPlan.Mount -> error(
                        "Root mount is not supported for APK download helper apps"
                    )
                }
                downloaderPluginRepository.reload()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Failed to install APK download helper source", error)
                appContext.toast(
                    appContext.getString(
                        R.string.install_app_fail,
                        error.simpleMessage().orEmpty()
                    )
                )
            } finally {
                if (remoteSourceBusyState == RemoteSourceBusyState.InstallingHelper(id)) {
                    remoteSourceBusyState = null
                }
            }
        }
    }

    private suspend fun installHelperInternally(
        apk: File,
        packageName: String,
        sourceLabel: String,
        successMessage: String
    ) {
        if (!pm.requestInstallPackagesPermission()) {
            appContext.toast(
                appContext.getString(R.string.downloaded_app_install_permission_required)
            )
            return
        }

        val result = try {
            sessionInstaller.install(apk, packageName)
        } catch (_: InstallCancelledException) {
            return
        } catch (error: SessionDeadException) {
            Log.w(TAG, "PackageInstaller session died while installing helper; using intent fallback", error)
            launchExternalInstaller(
                installerManager.createSystemFallbackPlan(
                    target = InstallerManager.InstallTarget.DOWNLOADER_HELPER,
                    sourceFile = apk,
                    expectedPackage = packageName,
                    sourceLabel = sourceLabel
                ),
                successMessage
            )
            return
        }

        when (result) {
            InstallResult.Success -> appContext.toast(successMessage)
            is InstallResult.Conflict -> error(
                result.message ?: appContext.getString(R.string.installer_hint_conflict_generic)
            )
            is InstallResult.Failure -> error(
                installerManager.formatFailureHint(result.status, result.message)
                    ?: result.message
                    ?: appContext.getString(R.string.installer_hint_generic)
            )
        }
    }

    fun removePluginSource(id: String) = viewModelScope.launch {
        downloaderPluginRepository.removeSource(id)
    }

    fun trustPluginSource(id: String) = viewModelScope.launch {
        runCatching {
            downloaderPluginRepository.trustSource(id)
        }.onFailure {
            appContext.toast(
                appContext.getString(
                    R.string.downloader_replace_fail,
                    it.simpleMessage().orEmpty()
                )
            )
        }
    }

    fun revokePluginSourceTrust(id: String) = viewModelScope.launch {
        downloaderPluginRepository.revokeTrustForSource(id)
    }

    fun trustApkDownloadHelper(
        packageName: String,
        expectedSignatureHex: String
    ) = viewModelScope.launch {
        runCatching {
            downloaderPluginRepository.trustApkDownloadHelper(packageName, expectedSignatureHex)
        }.onFailure { error ->
            appContext.toast(
                appContext.getString(
                    R.string.downloader_replace_fail,
                    error.simpleMessage().orEmpty()
                )
            )
        }
    }

    fun revokeApkDownloadHelperTrust(packageName: String) = viewModelScope.launch {
        downloaderPluginRepository.revokeApkDownloadHelperTrust(packageName)
    }

    fun uninstallApkDownloadHelper(packageName: String) {
        if (remoteSourceBusyState != null) return
        viewModelScope.launch {
            remoteSourceBusyState = RemoteSourceBusyState.UninstallingHelper(packageName)
            try {
                val sourceIds = downloaderPluginRepository.sourceStates.value.mapNotNullTo(mutableSetOf()) { (id, source) ->
                    when (val state = source.state) {
                        is DownloaderPluginSourceState.State.HelperApp ->
                            id.takeIf { state.packageName == packageName }
                        is DownloaderPluginSourceState.State.Untrusted ->
                            id.takeIf { state.helperApp && state.packageName == packageName }
                        else -> null
                    }
                }
                val result = withContext(Dispatchers.IO) {
                    pm.uninstallPackage(packageName)
                }
                when (result) {
                    Session.State.Succeeded -> {
                        downloaderPluginRepository.forgetApkDownloadHelper(packageName)
                        if (sourceIds.isNotEmpty()) {
                            downloaderPluginRepository.removeSources(sourceIds)
                        } else {
                            downloaderPluginRepository.reload()
                        }
                        appContext.toast(
                            appContext.getString(
                                R.string.downloader_helper_uninstall_success,
                                packageName
                            )
                        )
                    }
                    is Session.State.Failed<UninstallFailure> -> {
                        if (result.failure is UninstallFailure.Aborted) return@launch
                        appContext.toast(
                            result.failure.message ?: appContext.getString(
                                R.string.downloader_helper_uninstall_failed,
                                packageName
                            )
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Failed to uninstall APK download helper", error)
                appContext.toast(
                    appContext.getString(
                        R.string.downloader_helper_uninstall_failed,
                        packageName
                    )
                )
            } finally {
                if (remoteSourceBusyState == RemoteSourceBusyState.UninstallingHelper(packageName)) {
                    remoteSourceBusyState = null
                }
            }
        }
    }

    fun setPluginSourceAutoUpdate(id: String, enabled: Boolean) = viewModelScope.launch {
        downloaderPluginRepository.setSourceAutoUpdate(id, enabled)
    }

    fun setPluginSourceLatest(id: String, enabled: Boolean) = viewModelScope.launch {
        downloaderPluginRepository.setSourceLatest(id, enabled)
    }

    fun setPluginSourcePrerelease(id: String, enabled: Boolean) = viewModelScope.launch {
        downloaderPluginRepository.setSourcePrerelease(id, enabled)
    }

    fun trustPlugin(packageName: String) = viewModelScope.launch {
        downloaderPluginRepository.trustPackage(packageName)
    }

    fun revokePluginTrust(packageName: String) = viewModelScope.launch {
        downloaderPluginRepository.revokeTrustForPackage(packageName)
    }

    fun uninstallPlugin(packageName: String) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            pm.uninstallPackage(packageName)
        }
        when (result) {
            Session.State.Succeeded -> {
                downloaderPluginRepository.removePlugin(packageName)
                reloadPlugins()
                appContext.toast(
                    appContext.getString(
                        R.string.downloader_plugin_uninstall_success,
                        packageName
                    )
                )
            }

            is Session.State.Failed<UninstallFailure> -> {
                if (result.failure is UninstallFailure.Aborted) return@launch
                val message = result.failure.message
                appContext.toast(
                    message ?: appContext.getString(
                        R.string.downloader_plugin_uninstall_failed,
                        packageName
                    )
                )
            }
        }
    }

    fun exportApps(
        context: Context,
        uri: Uri,
        apps: Collection<DownloadedApp>,
        asArchive: Boolean
    ) =
        viewModelScope.launch {
            val selection = apps.toList()
            if (selection.isEmpty()) return@launch

            val resolver = context.contentResolver

            runCatching {
                withContext(Dispatchers.IO) {
                    resolver.openOutputStream(uri)?.use { output ->
                        if (asArchive) {
                            ZipOutputStream(output).use { zipStream ->
                                selection.forEach { app ->
                                    val apkFile = downloadedAppRepository.getPreparedApkFile(app)
                                    val baseName =
                                        "${app.packageName}_${app.version}".replace('/', '_')
                                    val entry = ZipEntry("$baseName.apk")
                                    zipStream.putNextEntry(entry)
                                    apkFile.inputStream().use { it.copyTo(zipStream) }
                                    zipStream.closeEntry()
                                }
                            }
                        } else {
                            val app = selection.first()
                            val apkFile =
                                downloadedAppRepository.getPreparedApkFile(app)
                            apkFile.inputStream().use { input -> input.copyTo(output) }
                        }
                    } ?: error("Could not open output stream for export")
                }
            }.onSuccess {
                context.toast(
                    context.getString(
                        R.string.downloaded_apps_export_success,
                        selection.size
                    )
                )
            }.onFailure {
                Log.e(TAG, "Failed to export downloaded apps", it)
                context.toast(context.getString(R.string.downloaded_apps_export_failed))
            }
        }

    fun exportAppsToPath(
        context: Context,
        target: Path,
        apps: Collection<DownloadedApp>,
        asArchive: Boolean,
        onResult: (Boolean) -> Unit = {}
    ) = viewModelScope.launch {
        val selection = apps.toList()
        if (selection.isEmpty()) {
            onResult(false)
            return@launch
        }

        val success = runCatching {
            withContext(Dispatchers.IO) {
                target.parent?.let { Files.createDirectories(it) }
                Files.newOutputStream(target).use { output ->
                    if (asArchive) {
                        ZipOutputStream(output).use { zipStream ->
                            selection.forEach { app ->
                                val apkFile = downloadedAppRepository.getPreparedApkFile(app)
                                val baseName =
                                    "${app.packageName}_${app.version}".replace('/', '_')
                                val entry = ZipEntry("$baseName.apk")
                                zipStream.putNextEntry(entry)
                                apkFile.inputStream().use { it.copyTo(zipStream) }
                                zipStream.closeEntry()
                            }
                        }
                    } else {
                        val app = selection.first()
                        val apkFile =
                            downloadedAppRepository.getPreparedApkFile(app)
                        apkFile.inputStream().use { input -> input.copyTo(output) }
                    }
                }
            }
        }.isSuccess

        if (success) {
            context.toast(
                context.getString(
                    R.string.downloaded_apps_export_success,
                    selection.size
                )
            )
        } else {
            context.toast(context.getString(R.string.downloaded_apps_export_failed))
        }
        onResult(success)
    }

    companion object {
        private val TAG = DownloadsViewModel::class.java.simpleName ?: "DownloadsViewModel"
        private const val BYTES_PER_KIB = 1024
        private const val DISPLAY_INFO_METADATA_COST_KIB = 64
        private const val MAX_DISPLAY_INFO_CACHE_SIZE_KIB = 16 * 1024
        private const val EXTERNAL_INSTALL_TIMEOUT_MS = 120_000L
        private const val EXTERNAL_INSTALL_POLL_INTERVAL_MS = 1_000L
        private const val MAX_INSTALL_LOG_LINES = 800
    }

    private suspend fun reloadPlugins() {
        isRefreshingPlugins = true
        try {
            downloaderPluginRepository.reload()
            downloaderPluginRepository.updateCheck()
        } finally {
            isRefreshingPlugins = false
        }
    }

    override fun onCleared() {
        installJob?.cancel()
        super.onCleared()
    }
}

private data class ExternalInstallBaseline(
    val versionCode: Long,
    val lastUpdateTime: Long
)

data class DownloadInstallProgress(
    val status: String,
    val logLines: List<String> = emptyList(),
    val showMergeLog: Boolean = false
)
