package app.revanced.manager.domain.manager

import android.content.Context
import app.revanced.manager.domain.manager.base.BasePreferencesManager
import app.revanced.manager.domain.manager.base.EditorContext
import app.revanced.manager.ui.theme.Theme
import app.revanced.manager.util.isDebuggable
import kotlinx.serialization.Serializable

class PreferencesManager(
    context: Context
) : BasePreferencesManager(context, "settings") {
    val dynamicColor = booleanPreference("dynamic_color", true)
    val theme = enumPreference("theme", Theme.SYSTEM)

    val api = stringPreference("api_url", "https://api.revanced.app")

    val useProcessRuntime = booleanPreference("use_process_runtime", false)
    val patcherProcessMemoryLimit = intPreference("process_runtime_memory_limit", 700)

    val allowMeteredUpdates = booleanPreference("allow_metered_updates", false)

    val keystoreAlias = stringPreference("keystore_alias", KeystoreManager.DEFAULT)
    val keystorePass = stringPreference("keystore_pass", KeystoreManager.DEFAULT)

    val firstLaunch = booleanPreference("first_launch", true)
    val managerAutoUpdates = booleanPreference("manager_auto_updates", false)
    val showManagerUpdateDialogOnLaunch = booleanPreference("show_manager_update_dialog_on_launch", true)
    val useManagerPrereleases = booleanPreference("manager_prereleases", false)
    val usePatchesPrereleases = booleanPreference("patches_prereleases", false)

    val disablePatchVersionCompatCheck = booleanPreference("disable_patch_version_compatibility_check", false)
    val disableSelectionWarning = booleanPreference("disable_selection_warning", false)
    val disableUniversalPatchCheck = booleanPreference("disable_patch_universal_check", false)
    val suggestedVersionSafeguard = booleanPreference("suggested_version_safeguard", true)

    val acknowledgedDownloaderPlugins = stringSetPreference("acknowledged_downloader_plugins", emptySet())

    @Serializable
    data class SettingsSnapshot(
        val dynamicColor: Boolean? = null,
        val theme: Theme? = null,
        val api: String? = null,
        val useProcessRuntime: Boolean? = null,
        val patcherProcessMemoryLimit: Int? = null,
        val allowMeteredUpdates: Boolean? = null,
        val keystoreAlias: String? = null,
        val keystorePass: String? = null,
        val firstLaunch: Boolean? = null,
        val managerAutoUpdates: Boolean? = null,
        val showManagerUpdateDialogOnLaunch: Boolean? = null,
        val useManagerPrereleases: Boolean? = null,
        val disablePatchVersionCompatCheck: Boolean? = null,
        val disableSelectionWarning: Boolean? = null,
        val disableUniversalPatchCheck: Boolean? = null,
        val suggestedVersionSafeguard: Boolean? = null,
        val acknowledgedDownloaderPlugins: Set<String>? = null
    )

    suspend fun exportSettings() = SettingsSnapshot(
        dynamicColor = dynamicColor.get(),
        theme = theme.get(),
        api = api.get(),
        useProcessRuntime = useProcessRuntime.get(),
        patcherProcessMemoryLimit = patcherProcessMemoryLimit.get(),
        allowMeteredUpdates = allowMeteredUpdates.get(),
        keystoreAlias = keystoreAlias.get(),
        keystorePass = keystorePass.get(),
        firstLaunch = firstLaunch.get(),
        managerAutoUpdates = managerAutoUpdates.get(),
        showManagerUpdateDialogOnLaunch = showManagerUpdateDialogOnLaunch.get(),
        useManagerPrereleases = useManagerPrereleases.get(),
        disablePatchVersionCompatCheck = disablePatchVersionCompatCheck.get(),
        disableSelectionWarning = disableSelectionWarning.get(),
        disableUniversalPatchCheck = disableUniversalPatchCheck.get(),
        suggestedVersionSafeguard = suggestedVersionSafeguard.get(),
        acknowledgedDownloaderPlugins = acknowledgedDownloaderPlugins.get()
    )

    suspend fun importSettings(snapshot: SettingsSnapshot) = edit {
        snapshot.dynamicColor?.let { dynamicColor.value = it }
        snapshot.theme?.let { theme.value = it }
        snapshot.api?.let { api.value = it }
        snapshot.useProcessRuntime?.let { useProcessRuntime.value = it }
        snapshot.patcherProcessMemoryLimit?.let { patcherProcessMemoryLimit.value = it }
        snapshot.allowMeteredUpdates?.let { allowMeteredUpdates.value = it }
        snapshot.keystoreAlias?.let { keystoreAlias.value = it }
        snapshot.keystorePass?.let { keystorePass.value = it }
        snapshot.firstLaunch?.let { firstLaunch.value = it }
        snapshot.managerAutoUpdates?.let { managerAutoUpdates.value = it }
        snapshot.showManagerUpdateDialogOnLaunch?.let {
            showManagerUpdateDialogOnLaunch.value = it
        }
        snapshot.useManagerPrereleases?.let { useManagerPrereleases.value = it }
        snapshot.disablePatchVersionCompatCheck?.let { disablePatchVersionCompatCheck.value = it }
        snapshot.disableSelectionWarning?.let { disableSelectionWarning.value = it }
        snapshot.disableUniversalPatchCheck?.let { disableUniversalPatchCheck.value = it }
        snapshot.suggestedVersionSafeguard?.let { suggestedVersionSafeguard.value = it }
        snapshot.acknowledgedDownloaderPlugins?.let { acknowledgedDownloaderPlugins.value = it }
    }
}
