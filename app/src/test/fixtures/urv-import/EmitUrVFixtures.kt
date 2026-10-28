// Frozen URV wire fixtures from commit 88c51f70717d14a8d54755c3cf2fdaf9d0a9e7c5.
// Regenerate with urv-import-compat-test.py --generate-fixtures.
package fixture

import app.urv.manager.data.room.options.Option
import app.urv.manager.data.room.profile.PatchProfilePayload
import app.urv.manager.domain.manager.*
import app.urv.manager.domain.repository.PatchProfileExportEntry
import app.urv.manager.network.dto.ReVancedAsset
import app.urv.manager.patcher.logger.PatcherLogMode
import app.urv.manager.ui.theme.Theme
import app.urv.manager.ui.viewmodel.*
import java.io.File
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

fun main(args: Array<String>) {
    val output = File(args.single()).apply { mkdirs() }
    val json = Json { encodeDefaults = true; prettyPrint = true }
    val oldRepo = "https://github.com/Jman-Github/Universal-ReVanced-Manager"
    val options = mapOf("Patch" to mapOf(
        "enabled" to Option.SerializedValue(JsonPrimitive(true)),
        "count" to Option.SerializedValue(JsonPrimitive(42)),
        "large" to Option.SerializedValue(JsonPrimitive(9007199254740993L)),
        "ratio" to Option.SerializedValue(JsonPrimitive(0.75)),
        "customPackage" to Option.SerializedValue(JsonPrimitive("app.urv.manager.keep.this")),
        "names" to Option.SerializedValue(buildJsonArray { add("URV"); add("UPM") }),
        "unset" to Option.SerializedValue(JsonNull)
    ))
    val selection = PatchSelectionBundleExport(42, "URV custom bundle", "My bundle",
        "https://example.com/patches.json", mapOf("com.example.app" to listOf("Patch")),
        mapOf("com.example.app" to options))
    val selections = PatchSelectionExportFile(bundles = listOf(selection))
    val payload = PatchProfilePayload(listOf(PatchProfilePayload.Bundle(
        42, listOf("Patch"), options, displayName = "URV custom bundle",
        sourceEndpoint = "https://example.com/patches.json", sourceName = "Example", version = "v1"
    )), PatchProfilePayload.SignatureWorkflow(enabled = true, remembered = true))
    val profiles = PatchProfileExportFile(listOf(PatchProfileExportEntry(
        name = "URV user profile", packageName = "com.example.app", appVersion = "1.0",
        useSelectedApkVersion = true, autoPatch = true, installerToken = ":shizuku:",
        autoInstall = true, createdAt = 1700000000000L, payload = payload
    )))
    val sources = """[{"id":"legacy-source","repoUrl":"$oldRepo","assetSelector":"reseam-plugin","autoUpdate":false,"trustedSignatureHex":"fixture-pin"}]"""
    val trust = """{"app.universal.revanced.manager.reseam.runtime":"fixture-pin"}"""
    val settings = ManagerSettingsExportFile(settings = PreferencesManager.SettingsSnapshot(
        api = oldRepo, theme = Theme.DARK, patcherLogMode = PatcherLogMode.VERBOSE,
        searchForUpdatesBackgroundInterval = SearchForUpdatesBackgroundInterval.HOUR,
        autoClearCacheInterval = AutoClearCacheInterval.WEEK,
        bundleUpdateDeliveryMode = BundleUpdateDeliveryMode.WEBSOCKET_PREFERRED,
        installerPrimary = ":shizuku:", installerCustomComponents = setOf("com.example/.Installer"),
        autoPatchEnabled = true, autoPatchEnabledPackages = setOf("com.example.app"),
        keystoreAlias = "fixture-key", keystorePass = "NOT_A_REAL_PASSWORD",
        keystoreKeyPass = "NOT_A_REAL_PASSWORD", customBackgroundImageUri = "content://urv-provider/image",
        patchedAppExportFormat = "URV-{app-name}", patcherRuntimePluginSourcesJson = sources,
        trustedPatcherRuntimePluginsJson = trust,
        acknowledgedPatcherRuntimePlugins = setOf("app.universal.revanced.manager.reseam.runtime"),
        pathSelectorFavorites = setOf("/storage/emulated/0/Download"),
        filePickerLastDirectories = mapOf("patches" to "/storage/emulated/0/Download")
    ))
    val bundles = PatchBundleExportFile(listOf(PatchBundleSnapshot(
        endpoint = "https://example.com/patches.json", name = "URV custom bundle",
        displayName = "My bundle", autoUpdate = false, usePrereleases = true,
        useLatest = false, useSpecificVersion = true,
        selectedVersion = ReVancedAsset("https://example.com/patches.rvp",
            LocalDateTime(2026, 10, 20, 12, 0), description = "Release", version = "v1"),
        position = 3, createdAt = 1700000000000L, updatedAt = 1700000001000L
    )))
    File(output, "urv_everything.json").writeText(json.encodeToString(EverythingExportFile(
        patchBundles = bundles, patchProfiles = profiles, managerSettings = settings,
        patchSelection = selections
    )))
    File(output, "urv_manager_settings.json").writeText(json.encodeToString(settings))
    File(output, "urv_patch_bundles.json").writeText(json.encodeToString(bundles))
    File(output, "urv_patch_profiles.json").writeText(json.encodeToString(profiles))
    File(output, "urv_patch_selection_all.json").writeText(json.encodeToString(selections))
    File(output, "urv_patch_selection_v1.json").writeText(json.encodeToString(
        selections.copy(version = 1, bundles = listOf(selection.copy(options = null)))))
    File(output, "urv_patch_selection_bundle.json").writeText(json.encodeToString(selection))
    File(output, "urv_legacy_selection_only.json").writeText(json.encodeToString(selection.selection))
    File(output, "urv_minimal_settings.json").writeText("""{"settings":{"pureBlackTheme":true,"keystoreAlias":"fixture-key"}}""")
    println("Generated 9 URV import fixtures.")
}
