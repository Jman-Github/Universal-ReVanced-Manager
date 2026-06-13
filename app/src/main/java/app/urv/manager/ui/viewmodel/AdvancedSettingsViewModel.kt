package app.urv.manager.ui.viewmodel

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.revanced.manager.R
import app.urv.manager.domain.bundles.PatchBundleSource.Extensions.isDefault
import app.urv.manager.domain.installer.InstallerManager
import app.urv.manager.domain.installer.RootInstaller
import app.urv.manager.domain.manager.PreferencesManager
import app.urv.manager.domain.manager.hideInstallerComponent
import app.urv.manager.domain.manager.showInstallerComponent
import app.urv.manager.domain.repository.InstalledAppRepository
import app.urv.manager.domain.repository.PatchBundleRepository
import app.urv.manager.domain.worker.WorkerRepository
import app.urv.manager.data.platform.Filesystem
import app.urv.manager.data.room.apps.installed.InstallType
import app.urv.manager.domain.manager.SearchForUpdatesBackgroundInterval
import app.urv.manager.patcher.logger.PatcherLogMode
import app.urv.manager.patcher.runtime.morphe.MorpheBytecodeMode
import app.urv.manager.patcher.worker.AnnouncementNotificationWorker
import app.urv.manager.util.FilenameUtils
import app.urv.manager.util.tag
import app.urv.manager.util.toast
import app.urv.manager.util.simpleMessage
import com.github.pgreze.process.Redirect
import com.github.pgreze.process.process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.urv.manager.ui.model.PatchSelectionActionKey
import app.urv.manager.ui.model.PatchBundleActionKey
import app.urv.manager.ui.model.SavedAppActionKey
import app.urv.manager.ui.model.PatchProfileActionKey

class AdvancedSettingsViewModel(
    val prefs: PreferencesManager,
    private val app: Application,
    private val patchBundleRepository: PatchBundleRepository,
    private val installedAppRepository: InstalledAppRepository,
    private val filesystem: Filesystem,
    private val workerRepository: WorkerRepository,
    private val installerManager: InstallerManager,
    private val rootInstaller: RootInstaller
) : ViewModel() {
    val hasOfficialBundle = patchBundleRepository.sources
        .map { sources -> sources.any { it.isDefault } }

    val debugLogFileName: String
        get() = FilenameUtils.timestampedLogFileName("debug")

    fun setApiUrl(value: String) = viewModelScope.launch(Dispatchers.Default) {
        if (value == prefs.api.get()) return@launch

        prefs.api.update(value)
        patchBundleRepository.reloadApiBundles()
        patchBundleRepository.updateCheck()
    }

    // PR #35: https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/35
    fun setGitHubPat(value: String) = viewModelScope.launch(Dispatchers.Default) {
        prefs.gitHubPat.update(value.trim())
    }

    fun setIncludeGitHubPatInExports(enabled: Boolean) = viewModelScope.launch(Dispatchers.Default) {
        prefs.includeGitHubPatInExports.update(enabled)
    }

    fun setSearchEngineHost(value: String) = viewModelScope.launch(Dispatchers.Default) {
        prefs.searchEngineHost.update(normalizeSearchEngineHost(value, prefs.searchEngineHost.default))
    }

    fun updateAnnouncementSystemEnabled(enabled: Boolean) = viewModelScope.launch(Dispatchers.Default) {
        prefs.announcementSystemEnabled.update(enabled)
        if (!enabled) {
            prefs.announcementPushNotificationInterval.update(SearchForUpdatesBackgroundInterval.NEVER)
        }
        workerRepository.scheduleAnnouncementNotificationWork(
            if (enabled) {
                prefs.announcementPushNotificationInterval.get()
            } else {
                SearchForUpdatesBackgroundInterval.NEVER
            }
        )
        if (!enabled) {
            app.getSystemService(NotificationManager::class.java)
                ?.cancel(AnnouncementNotificationWorker.ANNOUNCEMENT_NOTIFICATION_ID)
        }
    }

    fun exportDebugLogs(target: Uri) = viewModelScope.launch {
        val exitCode = try {
            withContext(Dispatchers.IO) {
                app.contentResolver.openOutputStream(target)!!.bufferedWriter().use { writer ->
                    val consumer = Redirect.Consume { flow ->
                        flow.onEach {
                            writer.write("${it}\n")
                        }.flowOn(Dispatchers.IO).collect()
                    }

                    process("logcat", "-d", stdout = consumer).resultCode
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Got exception while exporting logs", e)
            app.toast(app.getString(R.string.debug_logs_export_failed))
            return@launch
        }

        if (exitCode == 0)
            app.toast(app.getString(R.string.debug_logs_export_success))
        else
            app.toast(app.getString(R.string.debug_logs_export_read_failed, exitCode))
    }

    fun setPrimaryInstaller(token: InstallerManager.Token) = viewModelScope.launch(Dispatchers.Default) {
        if (token == InstallerManager.Token.AutoSaved) {
            // Request/verify root in background when user explicitly selects the rooted mount installer.
            runCatching { withContext(Dispatchers.IO) { rootInstaller.hasRootAccess() } }
        }
        installerManager.updatePrimaryToken(token)
        val fallback = installerManager.getFallbackToken()
        if (fallback != InstallerManager.Token.None && tokensEqual(fallback, token)) {
            installerManager.updateFallbackToken(InstallerManager.Token.None)
        }
    }

    fun setFallbackInstaller(token: InstallerManager.Token) = viewModelScope.launch(Dispatchers.Default) {
        if (token == InstallerManager.Token.AutoSaved) {
            runCatching { withContext(Dispatchers.IO) { rootInstaller.hasRootAccess() } }
        }
        val primary = installerManager.getPrimaryToken()
        val target = if (token != InstallerManager.Token.None && tokensEqual(primary, token)) {
            InstallerManager.Token.None
        } else token
        installerManager.updateFallbackToken(target)
    }

    fun setChooseInstallerPerInstall(enabled: Boolean) = viewModelScope.launch(Dispatchers.Default) {
        prefs.chooseInstallerPerInstall.update(enabled)
    }

    fun setPatchedAppExportFormat(value: String) = viewModelScope.launch(Dispatchers.Default) {
        prefs.patchedAppExportFormat.update(value)
    }

    fun setMergedApkExportFormat(value: String) = viewModelScope.launch(Dispatchers.Default) {
        prefs.mergedApkExportFormat.update(value)
    }

    fun setMorpheBytecodeMode(mode: MorpheBytecodeMode) = viewModelScope.launch(Dispatchers.Default) {
        prefs.morpheBytecodeMode.update(mode)
    }

    fun setPatcherLogMode(mode: PatcherLogMode) = viewModelScope.launch(Dispatchers.Default) {
        prefs.patcherLogMode.update(mode)
    }

    fun resetPatchedAppExportFormat() = viewModelScope.launch(Dispatchers.Default) {
        prefs.patchedAppExportFormat.update(prefs.patchedAppExportFormat.default)
    }

    fun resetMergedApkExportFormat() = viewModelScope.launch(Dispatchers.Default) {
        prefs.mergedApkExportFormat.update(prefs.mergedApkExportFormat.default)
    }

    fun setSavedAppsEnabled(enabled: Boolean) = viewModelScope.launch(Dispatchers.Default) {
        prefs.enableSavedApps.update(enabled)
        if (enabled) return@launch

        withContext(Dispatchers.IO) {
            val savedApps = installedAppRepository.getByInstallType(InstallType.SAVED)
            savedApps.forEach { app ->
                installedAppRepository.delete(app)
                filesystem.deletePatchedAppFiles(app.currentPackageName)
                if (app.originalPackageName != app.currentPackageName) {
                    filesystem.deletePatchedAppFiles(app.originalPackageName)
                }
            }
        }
    }

    fun setPatchSelectionActionOrder(order: List<PatchSelectionActionKey>) =
        viewModelScope.launch(Dispatchers.Default) {
            val serialized = order.joinToString(",") { it.storageId }
            prefs.patchSelectionActionOrder.update(serialized)
        }

    fun setPatchBundleActionOrder(order: List<PatchBundleActionKey>) =
        viewModelScope.launch(Dispatchers.Default) {
            val serialized = order.joinToString(",") { it.storageId }
            prefs.patchBundleActionOrder.update(serialized)
        }

    fun setPatchBundleHiddenActions(hidden: Set<String>) =
        viewModelScope.launch(Dispatchers.Default) {
            prefs.patchBundleHiddenActions.update(hidden)
        }

    fun setSavedAppActionOrder(order: List<SavedAppActionKey>) =
        viewModelScope.launch(Dispatchers.Default) {
            val serialized = order.joinToString(",") { it.storageId }
            prefs.savedAppActionOrder.update(serialized)
        }

    fun setSavedAppHiddenActions(hidden: Set<String>) =
        viewModelScope.launch(Dispatchers.Default) {
            prefs.savedAppHiddenActions.update(hidden)
        }

    fun setPatchProfileActionOrder(order: List<PatchProfileActionKey>) =
        viewModelScope.launch(Dispatchers.Default) {
            val serialized = order.joinToString(",") { it.storageId }
            prefs.patchProfileActionOrder.update(serialized)
        }

    fun setPatchProfileHiddenActions(hidden: Set<String>) =
        viewModelScope.launch(Dispatchers.Default) {
            prefs.patchProfileHiddenActions.update(hidden)
        }

    fun setPatchSelectionHiddenActions(hidden: Set<String>) =
        viewModelScope.launch(Dispatchers.Default) {
            prefs.patchSelectionHiddenActions.update(hidden)
        }

    fun restoreOfficialBundle() = viewModelScope.launch(Dispatchers.Default) {
        val hasBundle = patchBundleRepository.sources.first().any { it.isDefault }
        if (hasBundle) {
            withContext(Dispatchers.Main) {
                app.toast(app.getString(R.string.restore_official_bundle_already))
            }
            return@launch
        }

        runCatching {
            patchBundleRepository.restoreDefaultBundle()
            patchBundleRepository.refreshDefaultBundle()
        }.onSuccess {
            withContext(Dispatchers.Main) {
                app.toast(app.getString(R.string.restore_official_bundle_success))
            }
        }.onFailure { error ->
            Log.e(tag, "Failed to restore official bundle", error)
            val message = error.simpleMessage() ?: error.javaClass.simpleName.orEmpty()
            withContext(Dispatchers.Main) {
                app.toast(app.getString(R.string.restore_official_bundle_failed, message))
            }
        }
    }

    fun addCustomInstaller(component: ComponentName, onResult: (Boolean) -> Unit = {}) =
        viewModelScope.launch(Dispatchers.Default) {
            val added = installerManager.addCustomInstaller(component)
            if (added) {
                prefs.showInstallerComponent(component)
            }
            withContext(Dispatchers.Main) {
                onResult(added)
            }
        }

    fun removeCustomInstaller(component: ComponentName, onResult: (Boolean) -> Unit = {}) =
        viewModelScope.launch(Dispatchers.Default) {
        val removed = installerManager.removeCustomInstaller(component)
        if (removed) {
            prefs.hideInstallerComponent(component)
            val removedPackage = component.packageName
            val currentPrimary = installerManager.getPrimaryToken()
            val currentFallback = installerManager.getFallbackToken()
            val primaryMatchesRemoved =
                currentPrimary is InstallerManager.Token.Component &&
                    currentPrimary.componentName.packageName == removedPackage
            val fallbackMatchesRemoved =
                currentFallback is InstallerManager.Token.Component &&
                    currentFallback.componentName.packageName == removedPackage

            if (primaryMatchesRemoved) {
                installerManager.updatePrimaryToken(InstallerManager.Token.Internal)
            }
            if (fallbackMatchesRemoved) {
                installerManager.updateFallbackToken(InstallerManager.Token.None)
            }

            val componentAvailable = installerManager.isComponentAvailable(component)
            if (!componentAvailable) {
                if (currentPrimary is InstallerManager.Token.Component &&
                    currentPrimary.componentName == component
                ) {
                    installerManager.updatePrimaryToken(InstallerManager.Token.Internal)
                }
                if (currentFallback is InstallerManager.Token.Component &&
                    currentFallback.componentName == component
                ) {
                    installerManager.updateFallbackToken(InstallerManager.Token.None)
                }
            }
        }
            withContext(Dispatchers.Main) {
                onResult(removed)
            }
        }

    fun searchInstallerEntries(
        query: String,
        target: InstallerManager.InstallTarget
    ): List<InstallerManager.Entry> = installerManager.searchInstallerEntries(query, target)
}

private fun normalizeSearchEngineHost(value: String, fallback: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return fallback
    val noScheme = trimmed.removePrefix("https://").removePrefix("http://")
    val noPath = noScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    return noPath.trim().trimEnd('/').ifBlank { fallback }
}

private fun tokensEqual(a: InstallerManager.Token, b: InstallerManager.Token): Boolean = when {
    a === b -> true
    a is InstallerManager.Token.Component && b is InstallerManager.Token.Component ->
        a.componentName == b.componentName
    else -> false
}
