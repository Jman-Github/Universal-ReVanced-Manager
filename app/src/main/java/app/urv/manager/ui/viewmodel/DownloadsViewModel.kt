package app.urv.manager.ui.viewmodel

import android.content.Context
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.urv.manager.data.room.apps.downloaded.DownloadedApp
import app.urv.manager.domain.installer.InstallerManager
import app.urv.manager.domain.installer.RootInstaller
import app.urv.manager.domain.installer.ShizukuInstaller
import app.urv.manager.domain.manager.KeystoreManager
import app.urv.manager.domain.manager.PreferencesManager
import app.urv.manager.domain.repository.DownloadedAppRepository
import app.urv.manager.domain.storage.CacheCleanupGuard
import app.urv.manager.patcher.split.SplitApkPreparer
import app.urv.manager.patcher.split.SplitMergeProcessRuntime
import app.urv.manager.domain.repository.DownloaderPluginRepository
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import ru.solrudev.ackpine.installer.InstallFailure
import ru.solrudev.ackpine.installer.PackageInstaller
import ru.solrudev.ackpine.installer.createSession
import ru.solrudev.ackpine.session.Session
import ru.solrudev.ackpine.session.await
import ru.solrudev.ackpine.session.parameters.Confirmation
import ru.solrudev.ackpine.uninstaller.UninstallFailure
import java.nio.file.Files
import java.nio.file.Path

class DownloadsViewModel(
    private val downloadedAppRepository: DownloadedAppRepository,
    private val downloaderPluginRepository: DownloaderPluginRepository,
    private val installerManager: InstallerManager,
    private val rootInstaller: RootInstaller,
    private val shizukuInstaller: ShizukuInstaller,
    private val keystoreManager: KeystoreManager,
    private val prefs: PreferencesManager,
    private val ackpineInstaller: PackageInstaller,
    val pm: PM
) : ViewModel() {
    sealed interface RemoteSourceBusyState {
        data object Importing : RemoteSourceBusyState
        data class Updating(val id: String) : RemoteSourceBusyState
    }

    val downloaderPluginStates = downloaderPluginRepository.pluginStates
    val downloaderPluginSourceStates = downloaderPluginRepository.sourceStates
    val downloadedApps = downloadedAppRepository.getAll().map { downloadedApps ->
        downloadedApps.sortedWith(
            compareBy<DownloadedApp> {
                it.packageName
            }.thenBy { it.version }
        )
    }
    val appSelection = mutableStateSetOf<DownloadedApp>()

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
    private val installProgressFlow = MutableStateFlow<DownloadInstallProgress?>(null)
    val installProgress = installProgressFlow.asStateFlow()
    private var activeInstallWorkspace: File? = null
    private var installJob: Job? = null
    private var installCancellationRequested = false

    fun toggleApp(downloadedApp: DownloadedApp) {
        if (appSelection.contains(downloadedApp))
            appSelection.remove(downloadedApp)
        else
            appSelection.add(downloadedApp)
    }

    fun deleteApps() {
        viewModelScope.launch(NonCancellable) {
            downloadedAppRepository.delete(appSelection)

            withContext(Dispatchers.Main) {
                appSelection.clear()
            }
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

        val result = withContext(Dispatchers.IO) {
            ackpineInstaller.createSession(Uri.fromFile(apk)) {
                confirmation = Confirmation.IMMEDIATE
            }.await()
        }
        when (result) {
            Session.State.Succeeded -> showInstallSuccess()
            is Session.State.Failed<InstallFailure> -> {
                if (result.failure is InstallFailure.Aborted) return
                appContext.toast(
                    appContext.getString(
                        R.string.install_app_fail,
                        result.failure.message.orEmpty()
                    )
                )
            }
        }
    }

    private suspend fun installWithRootMount(
        apk: File,
        packageInfo: PackageInfo,
        label: String
    ) {
        rootInstaller.install(
            patchedAPK = apk,
            stockAPK = apk,
            packageName = packageInfo.packageName,
            version = packageInfo.versionName.orEmpty(),
            label = label
        )
        rootInstaller.mount(packageInfo.packageName)
        showInstallSuccess()
    }

    private fun showInstallSuccess() {
        appContext.toast(appContext.getString(R.string.downloaded_app_install_success))
    }

    private suspend fun launchExternalInstaller(plan: InstallerManager.InstallPlan.External) {
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
                showInstallSuccess()
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
        runCatching { plan.sharedFile.delete() }
    }

    fun refreshPlugins() = viewModelScope.launch {
        reloadPlugins()
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

    fun exportSelectedApps(context: Context, uri: Uri, asArchive: Boolean) =
        viewModelScope.launch {
            val selection = appSelection.toList()
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

    fun exportSelectedAppsToPath(
        context: Context,
        target: Path,
        asArchive: Boolean,
        onResult: (Boolean) -> Unit = {}
    ) = viewModelScope.launch {
        val selection = appSelection.toList()
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
