package app.urv.manager.ui.viewmodel

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.revanced.manager.BuildConfig
import app.universal.revanced.manager.R
import app.urv.manager.data.platform.Filesystem
import app.urv.manager.data.platform.NetworkInfo
import app.urv.manager.network.api.ReVancedAPI
import app.urv.manager.network.dto.ReVancedAsset
import app.urv.manager.network.service.HttpService
import app.urv.manager.domain.installer.InstallCancelledException
import app.urv.manager.domain.installer.InstallResult
import app.urv.manager.domain.installer.InstallerManager
import app.urv.manager.domain.installer.SessionDeadException
import app.urv.manager.domain.installer.SessionInstaller
import app.urv.manager.domain.installer.ShizukuInstaller
import app.urv.manager.domain.manager.PreferencesManager
import app.urv.manager.util.DownloadProgressNotifier
import app.urv.manager.util.PM
import app.urv.manager.util.MANAGER_DATABASE_VERSION_METADATA
import app.urv.manager.util.releaseDatabaseVersion
import app.urv.manager.util.isCompatibleManagerUpdate
import app.urv.manager.util.UPM_PACKAGE_NAME
import app.urv.manager.util.ManagerRebrandConfirmation
import app.urv.manager.util.isSupportedManagerUpdatePackage
import app.urv.manager.util.managerReleaseRequiresMigration
import app.urv.manager.util.managerUpdateDownloadIdentity
import app.urv.manager.util.toast
import app.urv.manager.util.uiSafe
import app.urv.manager.util.simpleMessage
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.url
import kotlinx.coroutines.CancellationException
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
    private val sessionInstaller: SessionInstaller by inject()
    private val downloadProgressNotifier: DownloadProgressNotifier by inject()

    private var pendingExternalInstall: InstallerManager.InstallPlan.External? = null
    private var externalInstallTimeoutJob: Job? = null
    private var currentDownloadVersion: String? = null
    private val rebrandConfirmation = ManagerRebrandConfirmation(SystemClock::elapsedRealtime)
    private var rebrandCountdownJob: Job? = null
    private var pendingRebrandAction: RebrandAction? = null

    var showRebrandNotice by mutableStateOf(false)
        private set
    var rebrandNoticeSeconds by mutableIntStateOf(10)
        private set

    private enum class RebrandAction { DOWNLOAD, INSTALL }

    private fun requireRebrandNotice(action: RebrandAction, packageChanged: Boolean = false): Boolean {
        val release = releaseInfo ?: return false
        if (!packageChanged && !managerReleaseRequiresMigration(app.packageName, release.downloadUrl)) {
            return false
        }
        val identity = managerUpdateDownloadIdentity(release.version, release.downloadUrl)
        if (!rebrandConfirmation.request(identity)) return false
        pendingRebrandAction = action
        showRebrandNotice = true
        rebrandNoticeSeconds = rebrandConfirmation.secondsRemaining
        if (rebrandCountdownJob?.isActive != true) {
            rebrandCountdownJob = viewModelScope.launch {
                while (showRebrandNotice) {
                    rebrandNoticeSeconds = rebrandConfirmation.secondsRemaining
                    if (rebrandNoticeSeconds == 0) break
                    delay(100L)
                }
            }
        }
        return true
    }

    fun confirmRebrandNotice() {
        if (!showRebrandNotice || !rebrandConfirmation.acknowledge()) return
        val action = pendingRebrandAction
        pendingRebrandAction = null
        showRebrandNotice = false
        rebrandCountdownJob?.cancel()
        when (action) {
            RebrandAction.DOWNLOAD -> downloadUpdate()
            RebrandAction.INSTALL -> installUpdate()
            null -> Unit
        }
    }

    fun dismissRebrandNotice() {
        if (!showRebrandNotice || !rebrandConfirmation.cancel()) return
        pendingRebrandAction = null
        showRebrandNotice = false
        rebrandCountdownJob?.cancel()
    }

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
    private val versionMarkerLocation = fs.tempDir.resolve("updater.version")
    private val job = viewModelScope.launch {
        uiSafe(app, R.string.download_manager_failed, "Failed to download Universal ReVanced Manager") {
            val update = reVancedAPI.getAppUpdate() ?: throw Exception("No update available")
            releaseInfo = update
            prefs.viewedManagerUpdateVersion.update(update.version)

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
            if (state == State.DOWNLOADING || state == State.INSTALLING) return@uiSafe
            if (requireRebrandNotice(RebrandAction.DOWNLOAD)) return@uiSafe
            val downloadIdentity = managerUpdateDownloadIdentity(release.version, release.downloadUrl)
            val allowMeteredUpdates = prefs.allowMeteredUpdates.get()
            withContext(Dispatchers.IO) {
                if (!allowMeteredUpdates && !networkInfo.isSafe() && !ignoreInternetCheck) {
                    showInternetCheckDialog = true
                } else {
                    if (currentDownloadVersion == null) {
                        currentDownloadVersion = readPersistedDownloadVersion()
                    }
                    if (currentDownloadVersion != downloadIdentity) {
                        currentDownloadVersion = downloadIdentity
                        persistDownloadVersion(downloadIdentity)
                        if (location.exists()) {
                            location.delete()
                        }
                        downloadedSize = 0L
                        totalSize = 0L
                        canResumeDownload = false
                    } else {
                        persistDownloadVersion(downloadIdentity)
                    }

                    val resumeOffset = if (location.exists()) location.length() else 0L
                    downloadedSize = resumeOffset
                    totalSize = resumeOffset
                    canResumeDownload = resumeOffset > 0L

                    state = State.DOWNLOADING
                    val progressNotification =
                        downloadProgressNotifier.begin(app.getString(R.string.app_name))

                    try {
                        http.download(location, resumeOffset) {
                            url(release.downloadUrl)
                            onDownload { bytesSentTotal, contentLength ->
                                downloadedSize = resumeOffset + bytesSentTotal
                                totalSize = when {
                                    contentLength != null && contentLength > 0L -> resumeOffset + contentLength
                                    totalSize > 0L -> totalSize
                                    else -> downloadedSize
                                }
                                progressNotification.update(downloadedSize, totalSize)
                            }
                        }
                        progressNotification.complete()
                        canResumeDownload = false
                        installUpdate()
                    } catch (error: CancellationException) {
                        progressNotification.cancel()
                        throw error
                    } catch (error: Exception) {
                        progressNotification.fail()
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
        if (state == State.INSTALLING) return@launch
        // Claim the install before archive parsing suspends, so repeated taps cannot
        // create multiple installer sessions while the new package ID is validated.
        state = State.INSTALLING
        pendingExternalInstall?.let(installerManager::cleanup)
        pendingExternalInstall = null
        externalInstallTimeoutJob?.cancel()
        externalInstallTimeoutJob = null
        installError = ""
        prefs.pendingManagerUpdateVersionCode.update(-1)

        val candidate = withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                app.packageManager.getPackageArchiveInfo(location.absolutePath, PackageManager.GET_META_DATA)
            }.getOrNull()
        }
        if (candidate == null || !isSupportedManagerUpdatePackage(app.packageName, candidate.packageName) ||
            (releaseInfo?.let { managerReleaseRequiresMigration(app.packageName, it.downloadUrl) } == true &&
                candidate.packageName != UPM_PACKAGE_NAME)
        ) {
            installError = app.getString(R.string.manager_update_unexpected_package)
            state = State.FAILED
            app.toast(installError)
            return@launch
        }
        val expectedPackage = candidate.packageName
        if (expectedPackage != app.packageName && requireRebrandNotice(RebrandAction.INSTALL, packageChanged = true)) {
            state = State.CAN_INSTALL
            return@launch
        }
        val sourceLabel = if (expectedPackage == UPM_PACKAGE_NAME && expectedPackage != app.packageName) {
            app.getString(R.string.manager_rebrand_app_name)
        } else app.getString(R.string.app_name)

        // PR database/version guards apply to an in-place update. A UPM migration
        // installs a separate app and never opens the running URV profile's database.
        if (BuildConfig.IS_PR_TEST_BUILD && expectedPackage == app.packageName) {
            val compatible = withContext(Dispatchers.IO) {
                runCatching {
                    val metadata = candidate.applicationInfo?.metaData
                    isCompatibleManagerUpdate(
                        currentPackage = app.packageName,
                        currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                        currentDatabaseVersion = releaseDatabaseVersion(app),
                        candidatePackage = candidate.packageName,
                        candidateVersionCode = PackageInfoCompat.getLongVersionCode(candidate),
                        candidateDatabaseVersion = metadata
                            ?.takeIf { it.containsKey(MANAGER_DATABASE_VERSION_METADATA) }
                            ?.getInt(MANAGER_DATABASE_VERSION_METADATA)
                    )
                }.getOrDefault(false)
            }
            if (!compatible) {
                installError = app.getString(R.string.manager_update_incompatible_pr)
                state = State.FAILED
                app.toast(installError)
                return@launch
            }
        }

        val plan = installerManager.resolvePlan(
            InstallerManager.InstallTarget.MANAGER_UPDATE,
            location,
            expectedPackage,
            sourceLabel
        )

        when (plan) {
            is InstallerManager.InstallPlan.Internal -> {
                if (!pm.requestInstallPackagesPermission()) {
                    state = State.CAN_INSTALL
                    return@launch
                }
                state = State.INSTALLING
                val result = try {
                    sessionInstaller.install(location, expectedPackage)
                } catch (_: InstallCancelledException) {
                    state = State.CAN_INSTALL
                    return@launch
                } catch (error: SessionDeadException) {
                    android.util.Log.w(
                        "UpdateViewModel",
                        "PackageInstaller session died; using intent fallback",
                        error
                    )
                    launchExternalInstaller(
                        installerManager.createSystemFallbackPlan(
                            target = InstallerManager.InstallTarget.MANAGER_UPDATE,
                            sourceFile = location,
                            expectedPackage = expectedPackage,
                            sourceLabel = sourceLabel
                        )
                    )
                    return@launch
                }
                when (result) {
                    InstallResult.Success -> {
                        installError = ""
                        state = State.SUCCESS
                        app.toast(app.getString(R.string.update_completed))
                    }

                    is InstallResult.Conflict -> {
                        val message = result.message.orEmpty()
                        app.toast(app.getString(R.string.install_app_fail, message))
                        installError = message
                        state = State.FAILED
                    }

                    is InstallResult.Failure -> {
                        val message = installerManager.formatFailureHint(
                            result.status,
                            result.message
                        ) ?: result.message.orEmpty()
                        app.toast(app.getString(R.string.install_app_fail, message))
                        installError = message
                        state = State.FAILED
                    }
                }
            }

            is InstallerManager.InstallPlan.Mount -> {
                val hint = app.getString(R.string.installer_status_not_supported)
                app.toast(app.getString(R.string.install_app_fail, hint))
                installError = hint
                state = State.FAILED
            }

            is InstallerManager.InstallPlan.RootPlayStore -> {
                val hint = app.getString(R.string.installer_status_not_supported)
                app.toast(app.getString(R.string.install_app_fail, hint))
                installError = hint
                state = State.FAILED
            }

            is InstallerManager.InstallPlan.Shizuku -> {
                state = State.INSTALLING
                try {
                    shizukuInstaller.install(location, expectedPackage, plan.installerPackageNameOverride)
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

        viewModelScope.launch {
            val attributionError = installerManager.tryFinalizePlayStoreAttribution(plan)
            installError = ""
            app.toast(app.getString(R.string.installer_external_success, plan.installerLabel))
            state = State.SUCCESS
            attributionError?.let { error ->
                app.toast(
                    app.getString(
                        R.string.installer_play_store_attribution_failed,
                        error.simpleMessage() ?: error.javaClass.simpleName.orEmpty()
                    )
                )
            }
        }
    }

    private fun readPersistedDownloadVersion(): String? =
        runCatching {
            versionMarkerLocation
                .takeIf { it.exists() }
                ?.readText()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()

    private fun persistDownloadVersion(version: String?) {
        runCatching {
            if (version.isNullOrBlank()) {
                if (versionMarkerLocation.exists()) {
                    versionMarkerLocation.delete()
                }
            } else {
                versionMarkerLocation.parentFile?.mkdirs()
                versionMarkerLocation.writeText(version)
            }
        }
    }

    private val externalInstallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val plan = pendingExternalInstall ?: return
            val pkg = intent?.data?.schemeSpecificPart ?: return
            if (intent.action == Intent.ACTION_PACKAGE_ADDED || intent.action == Intent.ACTION_PACKAGE_REPLACED) {
                if (pkg == plan.expectedPackage) {
                    handleExternalInstallSuccess(pkg)
                }
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            app,
            externalInstallReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onCleared() {
        super.onCleared()
        app.unregisterReceiver(externalInstallReceiver)
        pendingExternalInstall?.let(installerManager::cleanup)
        pendingExternalInstall = null
        externalInstallTimeoutJob?.cancel()
        externalInstallTimeoutJob = null

        job.cancel()
        rebrandCountdownJob?.cancel()
        location.delete()
        persistDownloadVersion(null)
    }

    companion object {
        private const val EXTERNAL_INSTALL_TIMEOUT_MS = 60_000L
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
