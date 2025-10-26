package app.revanced.manager.ui.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.revanced.manager.R
import app.revanced.manager.data.platform.Filesystem
import app.revanced.manager.data.room.apps.installed.InstallType
import app.revanced.manager.data.room.apps.installed.InstalledApp
import app.revanced.manager.domain.installer.RootInstaller
import app.revanced.manager.domain.repository.InstalledAppRepository
import app.revanced.manager.service.InstallService
import app.revanced.manager.service.UninstallService
import app.revanced.manager.util.PM
import app.revanced.manager.util.PatchSelection
import app.revanced.manager.util.simpleMessage
import app.revanced.manager.util.tag
import app.revanced.manager.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class InstalledAppInfoViewModel(
    packageName: String
) : ViewModel(), KoinComponent {
    private val context: Application by inject()
    private val pm: PM by inject()
    private val installedAppRepository: InstalledAppRepository by inject()
    val rootInstaller: RootInstaller by inject()
    private val filesystem: Filesystem by inject()

    lateinit var onBackClick: () -> Unit

    var installedApp: InstalledApp? by mutableStateOf(null)
        private set
    var appInfo: PackageInfo? by mutableStateOf(null)
        private set
    var appliedPatches: PatchSelection? by mutableStateOf(null)
    var isMounted by mutableStateOf(false)
        private set
    var isInstalledOnDevice by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            val app = installedAppRepository.get(packageName)
            installedApp = app
            if (app != null) {
                isMounted = rootInstaller.isAppMounted(app.currentPackageName)
                refreshAppState(app)
                appliedPatches = withContext(Dispatchers.IO) {
                    installedAppRepository.getAppliedPatches(app.currentPackageName)
                }
            }
        }
    }

    fun launch() {
        val app = installedApp ?: return
        if (app.installType == InstallType.SAVED) {
            context.toast(context.getString(R.string.saved_app_launch_unavailable))
        } else {
            pm.launch(app.currentPackageName)
        }
    }

    fun installSavedApp() = viewModelScope.launch {
        val app = installedApp ?: return@launch
        if (app.installType != InstallType.SAVED) return@launch

        val apk = savedApkFile(app)
        if (apk == null) {
            context.toast(context.getString(R.string.saved_app_install_missing))
            return@launch
        }

        context.toast(context.getString(R.string.installing_saved_app))
        val success = runCatching {
            pm.installApp(listOf(apk))
        }.onFailure {
            Log.e(tag, "Failed to install saved app", it)
        }.isSuccess

        if (!success) {
            context.toast(context.getString(R.string.saved_app_install_failed))
        }
    }

    fun uninstallSavedInstallation() = viewModelScope.launch {
        val app = installedApp ?: return@launch
        if (app.installType != InstallType.SAVED || !isInstalledOnDevice) return@launch
        pm.uninstallPackage(app.currentPackageName)
    }

    fun mountOrUnmount() = viewModelScope.launch {
        val pkgName = installedApp?.currentPackageName ?: return@launch
        try {
            if (isMounted)
                rootInstaller.unmount(pkgName)
            else
                rootInstaller.mount(pkgName)
        } catch (e: Exception) {
            if (isMounted) {
                context.toast(context.getString(R.string.failed_to_unmount, e.simpleMessage()))
                Log.e(tag, "Failed to unmount", e)
            } else {
                context.toast(context.getString(R.string.failed_to_mount, e.simpleMessage()))
                Log.e(tag, "Failed to mount", e)
            }
        } finally {
            isMounted = rootInstaller.isAppMounted(pkgName)
        }
    }

    fun uninstall() {
        val app = installedApp ?: return
        when (app.installType) {
            InstallType.DEFAULT -> pm.uninstallPackage(app.currentPackageName)

            InstallType.MOUNT -> viewModelScope.launch {
                rootInstaller.uninstall(app.currentPackageName)
                installedAppRepository.delete(app)
                onBackClick()
            }

            InstallType.SAVED -> removeSavedApp()
        }
    }

    fun exportSavedApp(uri: Uri?) = viewModelScope.launch {
        if (uri == null) return@launch
        val file = savedApkFile()
        if (file == null) {
            context.toast(context.getString(R.string.saved_app_export_failed))
            return@launch
        }

        val success = runCatching {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)
                    ?.use { output ->
                        file.inputStream().use { input -> input.copyTo(output) }
                    } ?: throw IOException("Could not open output stream for saved app export")
            }
        }.isSuccess

        context.toast(
            context.getString(
                if (success) R.string.saved_app_export_success else R.string.saved_app_export_failed
            )
        )
    }

    fun removeSavedApp() = viewModelScope.launch {
        val app = installedApp ?: return@launch
        if (app.installType != InstallType.SAVED) return@launch

        installedAppRepository.delete(app)
        withContext(Dispatchers.IO) {
            savedApkFile(app)?.delete()
        }
        installedApp = null
        appInfo = null
        appliedPatches = null
        isInstalledOnDevice = false
        context.toast(context.getString(R.string.saved_app_removed_toast))
        onBackClick()
    }

    private fun savedApkFile(app: InstalledApp? = this.installedApp): File? {
        val target = app ?: return null
        val file = filesystem.getPatchedAppFile(target.currentPackageName, target.version)
        return if (file.exists()) file else null
    }

    private suspend fun refreshAppState(app: InstalledApp) {
        val installedInfo = withContext(Dispatchers.IO) {
            pm.getPackageInfo(app.currentPackageName)
        }
        if (installedInfo != null) {
            isInstalledOnDevice = true
            appInfo = installedInfo
        } else {
            isInstalledOnDevice = false
            appInfo = withContext(Dispatchers.IO) {
                savedApkFile(app)?.let(pm::getPackageInfo)
            }
        }
    }

    private val installBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != InstallService.APP_INSTALL_ACTION) return
            val pkg = intent.getStringExtra(InstallService.EXTRA_PACKAGE_NAME) ?: return
            val status = intent.getIntExtra(
                InstallService.EXTRA_INSTALL_STATUS,
                PackageInstaller.STATUS_FAILURE
            )
            val statusMessage = intent.getStringExtra(InstallService.EXTRA_INSTALL_STATUS_MESSAGE)
            val currentApp = installedApp ?: return
            if (pkg != currentApp.currentPackageName) return

            when (status) {
                PackageInstaller.STATUS_SUCCESS -> {
                    viewModelScope.launch { refreshAppState(currentApp) }
                    this@InstalledAppInfoViewModel.context.toast(
                        this@InstalledAppInfoViewModel.context.getString(
                            R.string.saved_app_install_success
                        )
                    )
                }

                PackageInstaller.STATUS_FAILURE_ABORTED -> Unit

                else -> this@InstalledAppInfoViewModel.context.toast(
                    this@InstalledAppInfoViewModel.context.getString(
                        R.string.install_app_fail,
                        statusMessage ?: status.toString()
                    )
                )
            }
        }
    }.also {
        ContextCompat.registerReceiver(
            context,
            it,
            IntentFilter(InstallService.APP_INSTALL_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private val uninstallBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UninstallService.APP_UNINSTALL_ACTION -> {
                    val targetPackage =
                        intent.getStringExtra(UninstallService.EXTRA_UNINSTALL_PACKAGE_NAME)
                            ?: return
                    val extraStatus =
                        intent.getIntExtra(UninstallService.EXTRA_UNINSTALL_STATUS, -999)
                    val extraStatusMessage =
                        intent.getStringExtra(UninstallService.EXTRA_UNINSTALL_STATUS_MESSAGE)
                    val currentApp = installedApp ?: return
                    if (targetPackage != currentApp.currentPackageName) return

                    if (extraStatus == PackageInstaller.STATUS_SUCCESS) {
                        viewModelScope.launch {
                            if (currentApp.installType == InstallType.SAVED) {
                                refreshAppState(currentApp)
                            } else {
                                installedAppRepository.delete(currentApp)
                                onBackClick()
                            }
                        }
                    } else if (extraStatus != PackageInstaller.STATUS_FAILURE_ABORTED) {
                        this@InstalledAppInfoViewModel.context.toast(
                            this@InstalledAppInfoViewModel.context.getString(
                                R.string.uninstall_app_fail,
                                extraStatusMessage
                            )
                        )
                    }
                }
            }
        }
    }.also {
        ContextCompat.registerReceiver(
            context,
            it,
            IntentFilter(UninstallService.APP_UNINSTALL_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onCleared() {
        super.onCleared()
        context.unregisterReceiver(installBroadcastReceiver)
        context.unregisterReceiver(uninstallBroadcastReceiver)
    }
}
