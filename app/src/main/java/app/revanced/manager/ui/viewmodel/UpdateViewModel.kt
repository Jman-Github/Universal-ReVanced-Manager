package app.revanced.manager.ui.viewmodel

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import androidx.annotation.StringRes
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.revanced.manager.R
import app.revanced.manager.data.platform.Filesystem
import app.revanced.manager.data.platform.NetworkInfo
import app.revanced.manager.network.api.ReVancedAPI
import app.revanced.manager.network.dto.ReVancedAsset
import app.revanced.manager.network.service.HttpService
import app.revanced.manager.domain.installer.InstallerManager
import app.revanced.manager.domain.installer.ShizukuInstaller
import app.revanced.manager.service.InstallService
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.util.PM
import app.revanced.manager.util.toast
import app.revanced.manager.util.uiSafe
import app.revanced.manager.util.simpleMessage
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class UpdateViewModel(
    private val downloadOnScreenEntry: Boolean
) : ViewModel(), KoinComponent {
    private val app: Application by inject()
    private val reVancedAPI: ReVancedAPI by inject()
    private val http: HttpService by inject()
    private val pm: PM by inject()
    private val shizukuInstaller: ShizukuInstaller by inject()
    private val networkInfo: NetworkInfo by inject()
    private val fs: Filesystem by inject()
    private val prefs: PreferencesManager by inject()
    private val installerManager: InstallerManager by inject()

    private var pendingExternalInstall: InstallerManager.InstallPlan.External? = null
    private var externalInstallTimeoutJob: Job? = null
    private var currentDownloadVersion: String? = null
    private var installSessionId: Int? = null
    private var installSessionCallback: PackageInstaller.SessionCallback? = null
    private var deferInstallState = false
    private var expectedInternalPackage: String? = null
    private var expectedInternalVersionCode: Long? = null
    private var internalInstallMonitorJob: Job? = null
    private var internalInstallStartTimeMs: Long? = null

    var downloadedSize by mutableLongStateOf(0L)
        private set
    var totalSize by mutableLongStateOf(0L)
        private set
    val downloadProgress by derivedStateOf {
        if (downloadedSize == 0L || totalSize == 0L) return@derivedStateOf 0f

        downloadedSize.toFloat() / totalSize.toFloat()
    }
    var showInternetCheckDialog by mutableStateOf(false)
    var state by mutableStateOf(State.CAN_DOWNLOAD)
        private set

    var installError by mutableStateOf("")

    var releaseInfo: ReVancedAsset? by mutableStateOf(null)
        private set

    var canResumeDownload by mutableStateOf(false)
        private set

    private val location = fs.tempDir.resolve("updater.apk")
    private val job = viewModelScope.launch {
        uiSafe(app, R.string.download_manager_failed, "Failed to download Universal ReVanced Manager") {
            reconcilePendingInternalUpdate()
            releaseInfo = reVancedAPI.getAppUpdate() ?: throw Exception("No update available")

            if (downloadOnScreenEntry) {
                downloadUpdate()
            } else {
                state = State.CAN_DOWNLOAD
            }
        }
    }

    fun downloadUpdate(ignoreInternetCheck: Boolean = false) = viewModelScope.launch {
        uiSafe(app, R.string.failed_to_download_update, "Failed to download update") {
            val release = releaseInfo ?: return@uiSafe
            val allowMeteredUpdates = prefs.allowMeteredUpdates.get()
            withContext(Dispatchers.IO) {
                if (!allowMeteredUpdates && !networkInfo.isSafe() && !ignoreInternetCheck) {
                    showInternetCheckDialog = true
                } else {
                    if (currentDownloadVersion != release.version) {
                        currentDownloadVersion = release.version
                        if (location.exists()) {
                            location.delete()
                        }
                        downloadedSize = 0L
                        totalSize = 0L
                        canResumeDownload = false
                    }

                    val resumeOffset = if (location.exists()) location.length() else 0L
                    downloadedSize = resumeOffset
                    totalSize = resumeOffset
                    canResumeDownload = resumeOffset > 0L

                    state = State.DOWNLOADING

                    try {
                        if (resumeOffset == 0L) {
                            http.downloadToFile(
                                saveLocation = location,
                                builder = { url(release.downloadUrl) },
                                onProgress = { bytesRead, contentLength ->
                                    downloadedSize = bytesRead
                                    totalSize = contentLength ?: totalSize
                                }
                            )
                        } else {
                            http.download(location, resumeOffset) {
                                url(release.downloadUrl)
                                onDownload { bytesSentTotal, contentLength ->
                                    downloadedSize = resumeOffset + bytesSentTotal
                                    totalSize = resumeOffset + contentLength
                                }
                            }
                        }
                        canResumeDownload = false
                        installUpdate()
                    } catch (error: Exception) {
                        downloadedSize = location.takeIf { it.exists() }?.length() ?: 0L
                        if (totalSize < downloadedSize) {
                            totalSize = downloadedSize
                        }
                        canResumeDownload = downloadedSize > 0L
                        state = State.CAN_DOWNLOAD
                        throw error
                    }
                }
            }
        }
    }

    fun installUpdate() = viewModelScope.launch {
        if (state == State.INSTALLING || deferInstallState) return@launch
        pendingExternalInstall?.let(installerManager::cleanup)
        pendingExternalInstall = null
        externalInstallTimeoutJob?.cancel()
        externalInstallTimeoutJob = null
        installError = ""
        clearInstallSessionCallback()
        deferInstallState = false
        expectedInternalPackage = null
        expectedInternalVersionCode = null
        internalInstallStartTimeMs = null
        stopInternalInstallMonitor()
        prefs.pendingManagerUpdateVersionCode.update(-1)

        val plan = installerManager.resolvePlan(
            InstallerManager.InstallTarget.MANAGER_UPDATE,
            location,
            app.packageName,
            app.getString(R.string.app_name)
        )

        when (plan) {
            is InstallerManager.InstallPlan.Internal -> {
                if (!pm.requestInstallPackagesPermission()) {
                    state = State.CAN_INSTALL
                    return@launch
                }
                expectedInternalPackage = app.packageName
                expectedInternalVersionCode = pm.getPackageInfo(location)?.let(pm::getVersionCode)
                internalInstallStartTimeMs = System.currentTimeMillis()
                deferInstallState = true
                val sessionId = pm.installApp(listOf(location))
                registerInstallSessionCallback(sessionId)
                startInternalInstallMonitor()
                expectedInternalVersionCode?.let { prefs.pendingManagerUpdateVersionCode.update(it.toInt()) }
            }

            is InstallerManager.InstallPlan.Mount -> {
                val hint = app.getString(R.string.installer_status_not_supported)
                app.toast(app.getString(R.string.install_app_fail, hint))
                installError = hint
                state = State.FAILED
            }

            is InstallerManager.InstallPlan.Shizuku -> {
                state = State.INSTALLING
                try {
                    shizukuInstaller.install(location, app.packageName)
                    installError = ""
                    state = State.SUCCESS
                    app.toast(app.getString(R.string.update_completed))
                } catch (error: ShizukuInstaller.InstallerOperationException) {
                    val message = error.message ?: app.getString(R.string.installer_hint_generic)
                    installError = message
                    app.toast(app.getString(R.string.install_app_fail, message))
                    state = State.FAILED
                } catch (error: Exception) {
                    val message = error.simpleMessage().orEmpty()
                    installError = message
                    app.toast(app.getString(R.string.install_app_fail, message))
                    state = State.FAILED
                }
            }

            is InstallerManager.InstallPlan.External -> launchExternalInstaller(plan)
        }
    }

    private fun launchExternalInstaller(plan: InstallerManager.InstallPlan.External) {
        pendingExternalInstall?.let(installerManager::cleanup)
        externalInstallTimeoutJob?.cancel()

        pendingExternalInstall = plan
        installError = ""
        try {
            ContextCompat.startActivity(app, plan.intent, null)
            app.toast(app.getString(R.string.installer_external_launched, plan.installerLabel))
        } catch (error: ActivityNotFoundException) {
            installerManager.cleanup(plan)
            pendingExternalInstall = null
            installError = error.simpleMessage().orEmpty()
            app.toast(app.getString(R.string.install_app_fail, error.simpleMessage()))
            state = State.FAILED
            return
        }

        state = State.INSTALLING

        externalInstallTimeoutJob = viewModelScope.launch {
            delay(EXTERNAL_INSTALL_TIMEOUT_MS)
            if (pendingExternalInstall == plan) {
                installerManager.cleanup(plan)
                pendingExternalInstall = null
                installError = app.getString(R.string.installer_external_timeout, plan.installerLabel)
                app.toast(installError)
                state = State.FAILED
                externalInstallTimeoutJob = null
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

        installError = ""
        app.toast(app.getString(R.string.installer_external_success, plan.installerLabel))
        state = State.SUCCESS
    }

    private fun handleInternalInstallSuccess(packageName: String) {
        if (expectedInternalPackage != packageName) return
        if (state == State.SUCCESS) return
        expectedInternalPackage = null
        expectedInternalVersionCode = null
        internalInstallStartTimeMs = null
        stopInternalInstallMonitor()
        installError = ""
        app.toast(app.getString(R.string.update_completed))
        state = State.SUCCESS
        deferInstallState = false
        clearInstallSessionCallback()
        viewModelScope.launch {
            prefs.pendingManagerUpdateVersionCode.update(-1)
        }
    }

    private val installBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REPLACED -> {
                    val pkg = intent.data?.schemeSpecificPart ?: return
                    if (pendingExternalInstall != null) {
                        handleExternalInstallSuccess(pkg)
                    } else {
                        handleInternalInstallSuccess(pkg)
                    }
                }

                InstallService.APP_INSTALL_ACTION -> {
                    val reportedPackage = intent.getStringExtra(InstallService.EXTRA_PACKAGE_NAME)
                    val expectedPackage = pendingExternalInstall?.expectedPackage ?: expectedInternalPackage
                    if (reportedPackage != null && expectedPackage != null && reportedPackage != expectedPackage) {
                        return
                    }
                    val pmStatus = intent.getIntExtra(InstallService.EXTRA_INSTALL_STATUS, -999)
                    val extra =
                        intent.getStringExtra(InstallService.EXTRA_INSTALL_STATUS_MESSAGE) ?: ""

                    when (pmStatus) {
                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            if (deferInstallState) {
                                enableInstallState()
                            }
                        }

                        PackageInstaller.STATUS_SUCCESS -> {
                            pendingExternalInstall?.let(installerManager::cleanup)
                            pendingExternalInstall = null
                            externalInstallTimeoutJob?.cancel()
                            externalInstallTimeoutJob = null
                            installError = ""
                            app.toast(app.getString(R.string.install_app_success))
                            state = State.SUCCESS
                            expectedInternalPackage = null
                            expectedInternalVersionCode = null
                            internalInstallStartTimeMs = null
                            stopInternalInstallMonitor()
                            clearInstallSessionCallback()
                            deferInstallState = false
                            viewModelScope.launch {
                                prefs.pendingManagerUpdateVersionCode.update(-1)
                            }
                        }
                        PackageInstaller.STATUS_FAILURE_ABORTED -> {
                            pendingExternalInstall?.let(installerManager::cleanup)
                            pendingExternalInstall = null
                            externalInstallTimeoutJob?.cancel()
                            externalInstallTimeoutJob = null
                            state = State.CAN_INSTALL
                            expectedInternalPackage = null
                            expectedInternalVersionCode = null
                            internalInstallStartTimeMs = null
                            stopInternalInstallMonitor()
                            clearInstallSessionCallback()
                            deferInstallState = false
                            viewModelScope.launch {
                                prefs.pendingManagerUpdateVersionCode.update(-1)
                            }
                        }
                        else -> {
                            val hint = installerManager.formatFailureHint(pmStatus, extra)
                            val message = app.getString(
                                R.string.install_app_fail,
                                hint ?: extra.ifBlank { pmStatus.toString() }
                            )
                            pendingExternalInstall?.let(installerManager::cleanup)
                            pendingExternalInstall = null
                            externalInstallTimeoutJob?.cancel()
                            externalInstallTimeoutJob = null
                            app.toast(message)
                            installError = hint ?: extra
                            state = State.FAILED
                            expectedInternalPackage = null
                            expectedInternalVersionCode = null
                            internalInstallStartTimeMs = null
                            stopInternalInstallMonitor()
                            clearInstallSessionCallback()
                            deferInstallState = false
                            viewModelScope.launch {
                                prefs.pendingManagerUpdateVersionCode.update(-1)
                            }
                        }
                    }
                }
            }
        }
    }

    init {
        ContextCompat.registerReceiver(app, installBroadcastReceiver, IntentFilter().apply {
            addAction(InstallService.APP_INSTALL_ACTION)
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onCleared() {
        super.onCleared()
        app.unregisterReceiver(installBroadcastReceiver)
        clearInstallSessionCallback()

        pendingExternalInstall?.let(installerManager::cleanup)
        pendingExternalInstall = null
        externalInstallTimeoutJob?.cancel()
        externalInstallTimeoutJob = null
        expectedInternalPackage = null
        expectedInternalVersionCode = null
        internalInstallStartTimeMs = null
        stopInternalInstallMonitor()

        job.cancel()
        location.delete()
    }

    private fun enableInstallState() {
        if (!deferInstallState) return
        deferInstallState = false
        state = State.INSTALLING
    }

    private suspend fun reconcilePendingInternalUpdate() {
        val pendingVersion = prefs.pendingManagerUpdateVersionCode.get()
        if (pendingVersion <= 0) return
        val currentVersion = pm.getPackageInfo(app.packageName)?.let(pm::getVersionCode) ?: 0L
        if (currentVersion >= pendingVersion) {
            installError = ""
            state = State.SUCCESS
            app.toast(app.getString(R.string.update_completed))
        } else {
            val message = app.getString(R.string.install_timeout_message)
            installError = message
            state = State.FAILED
        }
        prefs.pendingManagerUpdateVersionCode.update(-1)
    }

    private fun startInternalInstallMonitor() {
        if (internalInstallMonitorJob?.isActive == true) return
        val expectedPackage = expectedInternalPackage ?: return
        val expectedVersionCode = expectedInternalVersionCode
        val startTime = internalInstallStartTimeMs ?: System.currentTimeMillis()
        internalInstallMonitorJob = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + INTERNAL_INSTALL_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val info = pm.getPackageInfo(expectedPackage)
                if (info != null) {
                    val updatedSinceStart = info.lastUpdateTime >= startTime && startTime > 0L
                    val versionOk = expectedVersionCode?.let { pm.getVersionCode(info) >= it } ?: false
                    if (versionOk || updatedSinceStart) {
                        handleInternalInstallSuccess(expectedPackage)
                        return@launch
                    }
                }
                delay(INTERNAL_INSTALL_POLL_MS)
            }
            if (state == State.INSTALLING || deferInstallState) {
                val message = app.getString(R.string.install_timeout_message)
                app.toast(app.getString(R.string.install_app_fail, message))
                installError = message
                state = State.FAILED
                expectedInternalPackage = null
                expectedInternalVersionCode = null
                internalInstallStartTimeMs = null
                deferInstallState = false
                clearInstallSessionCallback()
                prefs.pendingManagerUpdateVersionCode.update(-1)
            }
        }
    }

    private fun stopInternalInstallMonitor() {
        internalInstallMonitorJob?.cancel()
        internalInstallMonitorJob = null
    }

    private fun registerInstallSessionCallback(sessionId: Int) {
        clearInstallSessionCallback()
        installSessionId = sessionId
        val installer = app.packageManager.packageInstaller
        val callback = object : PackageInstaller.SessionCallback() {
            override fun onActiveChanged(id: Int, active: Boolean) {
                if (id != sessionId || !active) return
                viewModelScope.launch { enableInstallState() }
            }

            override fun onFinished(id: Int, success: Boolean) {
                if (id != sessionId) return
                if (success) {
                    expectedInternalPackage?.let(::handleInternalInstallSuccess)
                } else if (state != State.SUCCESS) {
                    val message = app.getString(
                        R.string.install_app_fail,
                        app.getString(R.string.installer_hint_generic)
                    )
                    app.toast(message)
                    installError = app.getString(R.string.installer_hint_generic)
                    state = State.FAILED
                    expectedInternalPackage = null
                    expectedInternalVersionCode = null
                    internalInstallStartTimeMs = null
                    stopInternalInstallMonitor()
                    deferInstallState = false
                    viewModelScope.launch {
                        prefs.pendingManagerUpdateVersionCode.update(-1)
                    }
                }
                clearInstallSessionCallback()
            }

            override fun onCreated(id: Int) {}
            override fun onBadgingChanged(id: Int) {}
            override fun onProgressChanged(id: Int, progress: Float) {}
        }
        installSessionCallback = callback
        installer.registerSessionCallback(callback)
        installer.getSessionInfo(sessionId)?.let { info ->
            if (info.isActive) {
                enableInstallState()
            }
        }
    }

    private fun clearInstallSessionCallback() {
        val callback = installSessionCallback ?: return
        val installer = app.packageManager.packageInstaller
        runCatching { installer.unregisterSessionCallback(callback) }
        installSessionCallback = null
        installSessionId = null
    }

    companion object {
        private const val EXTERNAL_INSTALL_TIMEOUT_MS = 60_000L
        private const val INTERNAL_INSTALL_TIMEOUT_MS = 60_000L
        private const val INTERNAL_INSTALL_POLL_MS = 1_000L
    }

    enum class State(@StringRes val title: Int) {
        CAN_DOWNLOAD(R.string.update_available),
        DOWNLOADING(R.string.downloading_manager_update),
        CAN_INSTALL(R.string.ready_to_install_update),
        INSTALLING(R.string.installing_manager_update),
        FAILED(R.string.install_update_manager_failed),
        SUCCESS(R.string.update_completed)
    }
}
