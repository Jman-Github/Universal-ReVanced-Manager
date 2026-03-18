package app.revanced.manager.ui.viewmodel

import android.content.Context
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.revanced.manager.data.room.apps.downloaded.DownloadedApp
import app.revanced.manager.domain.repository.DownloadedAppRepository
import app.revanced.manager.domain.repository.DownloaderPluginRepository
import app.revanced.manager.util.PM
import app.revanced.manager.util.simpleMessage
import app.revanced.manager.util.mutableStateSetOf

import app.revanced.manager.util.toast
import app.universal.revanced.manager.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import ru.solrudev.ackpine.session.Session
import ru.solrudev.ackpine.uninstaller.UninstallFailure
import java.nio.file.Files
import java.nio.file.Path

class DownloadsViewModel(
    private val downloadedAppRepository: DownloadedAppRepository,
    private val downloaderPluginRepository: DownloaderPluginRepository,
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
    private val appContext = pm.application

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

    fun refreshPlugins() = viewModelScope.launch {
        reloadPlugins()
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
    }

    private suspend fun reloadPlugins() {
        isRefreshingPlugins = true
        try {
            downloaderPluginRepository.ensureDefaultSourcesImported()
            downloaderPluginRepository.reload()
            downloaderPluginRepository.updateCheck()
        } finally {
            isRefreshingPlugins = false
        }
    }

    override fun onCleared() {
        super.onCleared()
    }
}
