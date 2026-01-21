package app.revanced.manager.domain.manager

import android.content.ComponentName
import android.content.Context
import app.universal.revanced.manager.R
import app.revanced.manager.domain.manager.base.BasePreferencesManager
import app.revanced.manager.domain.manager.base.EditorContext
import app.revanced.manager.patcher.runtime.MemoryLimitConfig
import app.revanced.manager.ui.theme.Theme
import app.revanced.manager.util.ExportNameFormatter
import app.revanced.manager.util.isDebuggable
import kotlinx.serialization.Serializable
import java.nio.file.Paths
import kotlin.io.path.isDirectory
import kotlin.io.path.isReadable

import app.revanced.manager.ui.model.PatchSelectionActionKey

enum class SearchForUpdatesBackgroundInterval(val displayName: Int, val value: Long) {
    NEVER(R.string.never, 0),
    MIN15(R.string.minutes_15, 15),
    HOUR(R.string.hourly, 60),
    DAY(R.string.daily, 60 * 24)
}

class PreferencesManager(
    context: Context
) : BasePreferencesManager(context, "settings") {
    companion object {
        private val PATCH_ACTION_ORDER_DEFAULT =
            PatchSelectionActionKey.DefaultOrder.joinToString(",") { it.storageId }
    }
    val dynamicColor = booleanPreference("dynamic_color", false)
    val pureBlackTheme = booleanPreference("pure_black_theme", false)
    val pureBlackOnSystemDark = booleanPreference("pure_black_on_system_dark", false)
    val themePresetSelectionEnabled = booleanPreference("theme_preset_selection_enabled", true)
    val themePresetSelectionName = stringPreference("theme_preset_selection_name", "DEFAULT")
    val customAccentColor = stringPreference("custom_accent_color", "")
    val customThemeColor = stringPreference("custom_theme_color", "")
    val theme = enumPreference("theme", Theme.SYSTEM)
    val appLanguage = stringPreference("app_language", "system")

    val api = stringPreference("api_url", "https://api.revanced.app")
    // PR #35: https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/35
    val gitHubPat = stringPreference("github_pat", "")
    val includeGitHubPatInExports = booleanPreference("include_github_pat_in_exports", false)

    val useProcessRuntime = booleanPreference("use_process_runtime", false)
    val stripUnusedNativeLibs = booleanPreference("strip_unused_native_libs", false)
    val skipUnneededSplitApks = booleanPreference("skip_unneeded_split_apks", false)
    val patcherProcessMemoryLimit = intPreference(
        "process_runtime_memory_limit",
        MemoryLimitConfig.recommendedLimitMb(context)
    )
    val patcherProcessMemoryAggressive = booleanPreference(
        "process_runtime_memory_aggressive",
        false
    )
    val patchedAppExportFormat = stringPreference(
        "patched_app_export_format",
        ExportNameFormatter.DEFAULT_TEMPLATE
    )
    val officialBundleRemoved = booleanPreference("official_bundle_removed", false)
    val officialBundleSortOrder = intPreference("official_bundle_sort_order", -1)
    val officialBundleCustomDisplayName = stringPreference("official_bundle_custom_display_name", "")
    val patchBundleCacheVersionCode = intPreference("patch_bundle_cache_version_code", -1)
    val autoCollapsePatcherSteps = booleanPreference("auto_collapse_patcher_steps", false)
    val autoExpandRunningSteps = booleanPreference("auto_expand_running_steps", true)
    val enableSavedApps = booleanPreference("enable_saved_apps", true)

    val allowMeteredUpdates = booleanPreference("allow_metered_updates", true)
    val installerPrimary = stringPreference("installer_primary", InstallerPreferenceTokens.INTERNAL)
    val installerFallback = stringPreference("installer_fallback", InstallerPreferenceTokens.NONE)
    val installerCustomComponents = stringSetPreference("installer_custom_components", emptySet())
    val installerHiddenComponents = stringSetPreference("installer_hidden_components", emptySet())

    val keystoreAlias = stringPreference("keystore_alias", KeystoreManager.DEFAULT)
    val keystorePass = stringPreference("keystore_pass", KeystoreManager.DEFAULT)
    val keystoreKeyPass = stringPreference("keystore_key_pass", KeystoreManager.DEFAULT)

    val firstLaunch = booleanPreference("first_launch", true)
    val managerAutoUpdates = booleanPreference("manager_auto_updates", false)
    val showManagerUpdateDialogOnLaunch = booleanPreference("show_manager_update_dialog_on_launch", true)
    val useManagerPrereleases = booleanPreference("manager_prereleases", false)
    val usePatchesPrereleases = booleanPreference("patches_prereleases", false)
    val showBatteryOptimizationBanner = booleanPreference("show_battery_optimization_banner", true)
    val allowPatchProfileBundleOverride = booleanPreference(
        "allow_patch_profile_bundle_override",
        false
    )
    val searchForUpdatesBackgroundInterval = enumPreference(
        "background_bundle_update_time",
        SearchForUpdatesBackgroundInterval.NEVER
    )
    val pendingManagerUpdateVersionCode = intPreference("pending_manager_update_version_code", -1)

    val disablePatchVersionCompatCheck = booleanPreference("disable_patch_version_compatibility_check", false)
    val disableSelectionWarning = booleanPreference("disable_selection_warning", false)
    val disableUniversalPatchCheck = booleanPreference("disable_patch_universal_check", true)
    val suggestedVersionSafeguard = booleanPreference("suggested_version_safeguard", true)
    val disablePatchSelectionConfirmations = booleanPreference("disable_patch_selection_confirmations", false)
    val showPatchSelectionSummary = booleanPreference("show_patch_selection_summary", true)
    val collapsePatchActionsOnSelection = booleanPreference("collapse_patch_actions_on_selection", true)
    val patchSelectionFilterFlags = intPreference("patch_selection_filter_flags", -1)
    val patchSelectionSortAlphabetical = booleanPreference("patch_selection_sort_alphabetical", false)
    val patchSelectionSortSettingsMode = stringPreference("patch_selection_sort_settings_mode", "None")
    val patchSelectionActionOrder =
        stringPreference("patch_selection_action_order", PATCH_ACTION_ORDER_DEFAULT)
    val patchSelectionHiddenActions =
        stringSetPreference("patch_selection_hidden_actions", emptySet())
    val patchSelectionShowVersionTags = booleanPreference("patch_selection_show_version_tags", true)
    val pathSelectorFavorites = stringSetPreference("path_selector_favorites", emptySet())
    val pathSelectorLastDirectory = stringPreference("path_selector_last_directory", "")
    val patchBundleDiscoveryShowRelease = booleanPreference("patch_bundle_discovery_show_release", true)
    val patchBundleDiscoveryShowPrerelease = booleanPreference("patch_bundle_discovery_show_prerelease", true)

    val acknowledgedDownloaderPlugins = stringSetPreference("acknowledged_downloader_plugins", emptySet())
    val autoSaveDownloaderApks = booleanPreference("auto_save_downloader_apks", true)
    val searchEngineHost = stringPreference("search_engine_host", "google.com")

    @Serializable
    data class SettingsSnapshot(
        val dynamicColor: Boolean? = null,
        val pureBlackTheme: Boolean? = null,
        val pureBlackOnSystemDark: Boolean? = null,
        val customAccentColor: String? = null,
        val customThemeColor: String? = null,
        val themePresetSelectionName: String? = null,
        val themePresetSelectionEnabled: Boolean? = null,
        val stripUnusedNativeLibs: Boolean? = null,
        val skipUnneededSplitApks: Boolean? = null,
        val theme: Theme? = null,
        val appLanguage: String? = null,
        val api: String? = null,
        val gitHubPat: String? = null,
        val includeGitHubPatInExports: Boolean? = null,
        val useProcessRuntime: Boolean? = null,
        val patcherProcessMemoryLimit: Int? = null,
        val patcherProcessMemoryAggressive: Boolean? = null,
        val autoCollapsePatcherSteps: Boolean? = null,
        val autoExpandRunningSteps: Boolean? = null,
        val enableSavedApps: Boolean? = null,
        val patchedAppExportFormat: String? = null,
        val officialBundleRemoved: Boolean? = null,
        val officialBundleCustomDisplayName: String? = null,
        val allowMeteredUpdates: Boolean? = null,
        val installerPrimary: String? = null,
        val installerFallback: String? = null,
        val installerCustomComponents: Set<String>? = null,
        val installerHiddenComponents: Set<String>? = null,
        val keystoreAlias: String? = null,
        val keystorePass: String? = null,
        val keystoreKeyPass: String? = null,
        val firstLaunch: Boolean? = null,
        val managerAutoUpdates: Boolean? = null,
        val showManagerUpdateDialogOnLaunch: Boolean? = null,
        val useManagerPrereleases: Boolean? = null,
        val showBatteryOptimizationBanner: Boolean? = null,
        val allowPatchProfileBundleOverride: Boolean? = null,
        val searchForUpdatesBackgroundInterval: SearchForUpdatesBackgroundInterval? = null,
        val disablePatchVersionCompatCheck: Boolean? = null,
        val disableSelectionWarning: Boolean? = null,
        val disableUniversalPatchCheck: Boolean? = null,
        val suggestedVersionSafeguard: Boolean? = null,
        val disablePatchSelectionConfirmations: Boolean? = null,
        val showPatchSelectionSummary: Boolean? = null,
        val collapsePatchActionsOnSelection: Boolean? = null,
        val patchSelectionFilterFlags: Int? = null,
        val patchSelectionSortAlphabetical: Boolean? = null,
        val patchSelectionSortSettingsMode: String? = null,
        val patchSelectionActionOrder: String? = null,
        val patchSelectionHiddenActions: Set<String>? = null,
        val patchSelectionShowVersionTags: Boolean? = null,
        val acknowledgedDownloaderPlugins: Set<String>? = null,
        val autoSaveDownloaderApks: Boolean? = null,
        val pathSelectorFavorites: Set<String>? = null,
        val pathSelectorLastDirectory: String? = null,
        val patchBundleDiscoveryShowRelease: Boolean? = null,
        val patchBundleDiscoveryShowPrerelease: Boolean? = null,
        val searchEngineHost: String? = null,
    )

    suspend fun exportSettings() = SettingsSnapshot(
        dynamicColor = dynamicColor.get(),
        pureBlackTheme = pureBlackTheme.get(),
        pureBlackOnSystemDark = pureBlackOnSystemDark.get(),
        customAccentColor = customAccentColor.get(),
        customThemeColor = customThemeColor.get(),
        themePresetSelectionName = themePresetSelectionName.get(),
        themePresetSelectionEnabled = themePresetSelectionEnabled.get(),
        stripUnusedNativeLibs = stripUnusedNativeLibs.get(),
        skipUnneededSplitApks = skipUnneededSplitApks.get(),
        theme = theme.get(),
        appLanguage = appLanguage.get(),
        api = api.get(),
        gitHubPat = gitHubPat.get().takeIf { includeGitHubPatInExports.get() },
        includeGitHubPatInExports = includeGitHubPatInExports.get(),
        useProcessRuntime = useProcessRuntime.get(),
        patcherProcessMemoryLimit = patcherProcessMemoryLimit.get(),
        patcherProcessMemoryAggressive = patcherProcessMemoryAggressive.get(),
        autoCollapsePatcherSteps = autoCollapsePatcherSteps.get(),
        autoExpandRunningSteps = autoExpandRunningSteps.get(),
        enableSavedApps = enableSavedApps.get(),
        patchedAppExportFormat = patchedAppExportFormat.get(),
        officialBundleRemoved = officialBundleRemoved.get(),
        officialBundleCustomDisplayName = officialBundleCustomDisplayName.get(),
        allowMeteredUpdates = allowMeteredUpdates.get(),
        installerPrimary = installerPrimary.get(),
        installerFallback = installerFallback.get(),
        installerCustomComponents = installerCustomComponents.get(),
        installerHiddenComponents = installerHiddenComponents.get(),
        keystoreAlias = keystoreAlias.get(),
        keystorePass = keystorePass.get(),
        keystoreKeyPass = keystoreKeyPass.get(),
        firstLaunch = firstLaunch.get(),
        managerAutoUpdates = managerAutoUpdates.get(),
        showManagerUpdateDialogOnLaunch = showManagerUpdateDialogOnLaunch.get(),
        useManagerPrereleases = useManagerPrereleases.get(),
        showBatteryOptimizationBanner = showBatteryOptimizationBanner.get(),
        allowPatchProfileBundleOverride = allowPatchProfileBundleOverride.get(),
        searchForUpdatesBackgroundInterval = searchForUpdatesBackgroundInterval.get(),
        disablePatchVersionCompatCheck = disablePatchVersionCompatCheck.get(),
        disableSelectionWarning = disableSelectionWarning.get(),
        disableUniversalPatchCheck = disableUniversalPatchCheck.get(),
        suggestedVersionSafeguard = suggestedVersionSafeguard.get(),
        disablePatchSelectionConfirmations = disablePatchSelectionConfirmations.get(),
        showPatchSelectionSummary = showPatchSelectionSummary.get(),
        collapsePatchActionsOnSelection = collapsePatchActionsOnSelection.get(),
        patchSelectionFilterFlags = patchSelectionFilterFlags.get(),
        patchSelectionSortAlphabetical = patchSelectionSortAlphabetical.get(),
        patchSelectionSortSettingsMode = patchSelectionSortSettingsMode.get(),
        patchSelectionActionOrder = patchSelectionActionOrder.get(),
        patchSelectionHiddenActions = patchSelectionHiddenActions.get(),
        patchSelectionShowVersionTags = patchSelectionShowVersionTags.get(),
        acknowledgedDownloaderPlugins = acknowledgedDownloaderPlugins.get(),
        autoSaveDownloaderApks = autoSaveDownloaderApks.get(),
        pathSelectorFavorites = pathSelectorFavorites.get(),
        pathSelectorLastDirectory = pathSelectorLastDirectory.get().takeIf { it.isNotBlank() },
        patchBundleDiscoveryShowRelease = patchBundleDiscoveryShowRelease.get(),
        patchBundleDiscoveryShowPrerelease = patchBundleDiscoveryShowPrerelease.get(),
        searchEngineHost = searchEngineHost.get(),
    )

    suspend fun importSettings(snapshot: SettingsSnapshot) = edit {
        snapshot.dynamicColor?.let { dynamicColor.value = it }
        snapshot.pureBlackTheme?.let { pureBlackTheme.value = it }
        snapshot.pureBlackOnSystemDark?.let { pureBlackOnSystemDark.value = it }
        snapshot.customAccentColor?.let { customAccentColor.value = it }
        snapshot.customThemeColor?.let { customThemeColor.value = it }
        snapshot.themePresetSelectionName?.let { themePresetSelectionName.value = it }
        snapshot.themePresetSelectionEnabled?.let { themePresetSelectionEnabled.value = it }
        snapshot.stripUnusedNativeLibs?.let { stripUnusedNativeLibs.value = it }
        snapshot.skipUnneededSplitApks?.let { skipUnneededSplitApks.value = it }
        snapshot.theme?.let { theme.value = it }
        snapshot.appLanguage?.let { appLanguage.value = it }
        snapshot.api?.let { api.value = it }
        snapshot.gitHubPat?.let { gitHubPat.value = it }
        snapshot.includeGitHubPatInExports?.let { includeGitHubPatInExports.value = it }
        snapshot.useProcessRuntime?.let { useProcessRuntime.value = it }
        snapshot.patcherProcessMemoryLimit?.let { patcherProcessMemoryLimit.value = it }
        snapshot.patcherProcessMemoryAggressive?.let { patcherProcessMemoryAggressive.value = it }
        snapshot.autoCollapsePatcherSteps?.let { autoCollapsePatcherSteps.value = it }
        snapshot.autoExpandRunningSteps?.let { autoExpandRunningSteps.value = it }
        snapshot.enableSavedApps?.let { enableSavedApps.value = it }
        snapshot.patchedAppExportFormat?.let { patchedAppExportFormat.value = it }
        snapshot.officialBundleRemoved?.let { officialBundleRemoved.value = it }
        snapshot.officialBundleCustomDisplayName?.let { officialBundleCustomDisplayName.value = it }
        snapshot.allowMeteredUpdates?.let { allowMeteredUpdates.value = it }
        snapshot.installerPrimary?.let { installerPrimary.value = it }
        snapshot.installerFallback?.let { installerFallback.value = it }
        snapshot.installerCustomComponents?.let { installerCustomComponents.value = it }
        snapshot.installerHiddenComponents?.let { installerHiddenComponents.value = it }
        snapshot.keystoreAlias?.let { keystoreAlias.value = it }
        snapshot.keystorePass?.let { keystorePass.value = it }
        snapshot.keystoreKeyPass?.let { keystoreKeyPass.value = it }
        snapshot.firstLaunch?.let { firstLaunch.value = it }
        snapshot.managerAutoUpdates?.let { managerAutoUpdates.value = it }
        snapshot.showManagerUpdateDialogOnLaunch?.let {
            showManagerUpdateDialogOnLaunch.value = it
        }
        snapshot.useManagerPrereleases?.let { useManagerPrereleases.value = it }
        snapshot.showBatteryOptimizationBanner?.let { showBatteryOptimizationBanner.value = it }
        snapshot.allowPatchProfileBundleOverride?.let { allowPatchProfileBundleOverride.value = it }
        snapshot.searchForUpdatesBackgroundInterval?.let {
            searchForUpdatesBackgroundInterval.value = it
        }
        snapshot.disablePatchVersionCompatCheck?.let { disablePatchVersionCompatCheck.value = it }
        snapshot.disableSelectionWarning?.let { disableSelectionWarning.value = it }
        snapshot.disableUniversalPatchCheck?.let { disableUniversalPatchCheck.value = it }
        snapshot.suggestedVersionSafeguard?.let { suggestedVersionSafeguard.value = it }
        snapshot.disablePatchSelectionConfirmations?.let { disablePatchSelectionConfirmations.value = it }
        snapshot.showPatchSelectionSummary?.let { showPatchSelectionSummary.value = it }
        snapshot.collapsePatchActionsOnSelection?.let { collapsePatchActionsOnSelection.value = it }
        snapshot.patchSelectionFilterFlags?.let { patchSelectionFilterFlags.value = it }
        snapshot.patchSelectionSortAlphabetical?.let { patchSelectionSortAlphabetical.value = it }
        snapshot.patchSelectionSortSettingsMode?.let { patchSelectionSortSettingsMode.value = it }
        snapshot.patchSelectionActionOrder?.let { patchSelectionActionOrder.value = it }
        snapshot.patchSelectionHiddenActions?.let { patchSelectionHiddenActions.value = it }
        snapshot.patchSelectionShowVersionTags?.let { patchSelectionShowVersionTags.value = it }
        snapshot.acknowledgedDownloaderPlugins?.let { acknowledgedDownloaderPlugins.value = it }
        snapshot.autoSaveDownloaderApks?.let { autoSaveDownloaderApks.value = it }
        snapshot.pathSelectorFavorites?.let { favorites ->
            val sanitized = favorites.filter { path ->
                runCatching { Paths.get(path).isReadable() }.getOrDefault(false)
            }.toSet()
            pathSelectorFavorites.value = sanitized
        }
        snapshot.pathSelectorLastDirectory?.let { lastDir ->
            val resolved = runCatching { Paths.get(lastDir) }.getOrNull()
            val target = when {
                resolved == null -> null
                resolved.isDirectory() -> resolved
                resolved.parent?.isDirectory() == true -> resolved.parent
                else -> null
            }
            if (target != null && target.isReadable()) {
                pathSelectorLastDirectory.value = target.toString()
            }
        }
        snapshot.patchBundleDiscoveryShowRelease?.let { patchBundleDiscoveryShowRelease.value = it }
        snapshot.patchBundleDiscoveryShowPrerelease?.let { patchBundleDiscoveryShowPrerelease.value = it }
        snapshot.searchEngineHost?.let { searchEngineHost.value = it }
    }

}

object InstallerPreferenceTokens {
    const val INTERNAL = ":internal:"
    const val SYSTEM = ":system:"
    const val ROOT = ":root:" // Legacy value, mapped to AUTO_SAVED.
    const val AUTO_SAVED = ":auto_saved:"
    const val SHIZUKU = ":shizuku:"
    const val NONE = ":none:"
}

suspend fun PreferencesManager.hideInstallerComponent(component: ComponentName) = edit {
    val flattened = component.flattenToString()
    installerHiddenComponents.value = installerHiddenComponents.value + flattened
}

suspend fun PreferencesManager.showInstallerComponent(component: ComponentName) = edit {
    val flattened = component.flattenToString()
    installerHiddenComponents.value = installerHiddenComponents.value - flattened
}
