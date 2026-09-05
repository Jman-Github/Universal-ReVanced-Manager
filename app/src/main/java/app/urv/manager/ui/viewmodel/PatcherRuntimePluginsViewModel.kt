package app.urv.manager.ui.viewmodel

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.revanced.manager.R
import app.urv.manager.domain.repository.PatcherRuntimePluginRepository
import app.urv.manager.util.PM
import app.urv.manager.util.simpleMessage
import app.urv.manager.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.solrudev.ackpine.session.Session
import ru.solrudev.ackpine.uninstaller.UninstallFailure

class PatcherRuntimePluginsViewModel(
    private val runtimePluginRepository: PatcherRuntimePluginRepository,
    val pm: PM
) : ViewModel() {
    sealed interface RemoteSourceBusyState {
        data object Importing : RemoteSourceBusyState
        data class Updating(val id: String) : RemoteSourceBusyState
    }

    val runtimePluginStates = runtimePluginRepository.pluginStates
    val runtimePluginSourceStates = runtimePluginRepository.sourceStates
    val loadedRuntimes = runtimePluginRepository.loadedRuntimes
    val newPluginPackageNames = runtimePluginRepository.newPluginPackageNames

    var isRefreshingPlugins by mutableStateOf(false)
        private set
    var remoteSourceBusyState by mutableStateOf<RemoteSourceBusyState?>(null)
        private set

    private val appContext = pm.application

    fun refreshPlugins() = viewModelScope.launch {
        reloadPlugins()
    }

    fun acknowledgeNewPlugins() = viewModelScope.launch {
        runtimePluginRepository.acknowledgeAllNewPlugins()
    }

    fun importPluginSource(url: String) = viewModelScope.launch {
        remoteSourceBusyState = RemoteSourceBusyState.Importing
        runCatching {
            runtimePluginRepository.importSourcesFromUrl(url)
        }.onFailure {
            appContext.toast(
                appContext.getString(
                    R.string.patcher_runtime_import_failed,
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
            runtimePluginRepository.updateSource(id)
        }.onFailure {
            appContext.toast(
                appContext.getString(
                    R.string.patcher_runtime_update_failed,
                    it.simpleMessage().orEmpty()
                )
            )
        }.also {
            remoteSourceBusyState = null
        }
    }

    fun removePluginSource(id: String) = viewModelScope.launch {
        runtimePluginRepository.removeSource(id)
    }

    fun trustPluginSource(id: String) = viewModelScope.launch {
        runCatching {
            runtimePluginRepository.trustSource(id)
        }.onFailure {
            appContext.toast(
                appContext.getString(
                    R.string.patcher_runtime_import_failed,
                    it.simpleMessage().orEmpty()
                )
            )
        }
    }

    fun revokePluginSourceTrust(id: String) = viewModelScope.launch {
        runtimePluginRepository.revokeTrustForSource(id)
    }

    fun setPluginSourceAutoUpdate(id: String, enabled: Boolean) = viewModelScope.launch {
        runtimePluginRepository.setSourceAutoUpdate(id, enabled)
    }

    fun setPluginSourceLatest(id: String, enabled: Boolean) = viewModelScope.launch {
        runtimePluginRepository.setSourceLatest(id, enabled)
    }

    fun setPluginSourcePrerelease(id: String, enabled: Boolean) = viewModelScope.launch {
        runtimePluginRepository.setSourcePrerelease(id, enabled)
    }

    fun trustPlugin(packageName: String) = viewModelScope.launch {
        runCatching {
            runtimePluginRepository.trustPackage(packageName)
        }.onFailure {
            appContext.toast(
                appContext.getString(
                    R.string.patcher_runtime_import_failed,
                    it.simpleMessage().orEmpty()
                )
            )
        }
    }

    fun revokePluginTrust(packageName: String) = viewModelScope.launch {
        runtimePluginRepository.revokeTrustForPackage(packageName)
    }

    fun uninstallPlugin(packageName: String) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            pm.uninstallPackage(packageName)
        }
        when (result) {
            Session.State.Succeeded -> {
                runtimePluginRepository.removePlugin(packageName)
                reloadPlugins()
                appContext.toast(
                    appContext.getString(
                        R.string.patcher_runtime_uninstall_success,
                        packageName
                    )
                )
            }

            is Session.State.Failed<UninstallFailure> -> {
                if (result.failure is UninstallFailure.Aborted) return@launch
                val message = result.failure.message
                appContext.toast(
                    message ?: appContext.getString(
                        R.string.patcher_runtime_uninstall_failed,
                        packageName
                    )
                )
            }
        }
    }

    private suspend fun reloadPlugins() {
        isRefreshingPlugins = true
        try {
            runtimePluginRepository.reload()
            runtimePluginRepository.updateCheck()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to refresh patcher runtime plugins", t)
        } finally {
            isRefreshingPlugins = false
        }
    }

    companion object {
        private val TAG = PatcherRuntimePluginsViewModel::class.java.simpleName
            ?: "PatcherRuntimePluginsViewModel"
    }
}
