package app.revanced.manager.ui.viewmodel

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.util.Base64
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.revanced.manager.R
import app.revanced.manager.domain.bundles.PatchBundleSource.Extensions.asRemoteOrNull
import app.revanced.manager.domain.manager.KeystoreManager
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.domain.repository.DownloadedAppRepository
import app.revanced.manager.domain.repository.PatchBundleRepository
import app.revanced.manager.domain.repository.PatchSelectionRepository
import app.revanced.manager.domain.repository.SerializedSelection
import app.revanced.manager.data.room.profile.PatchProfilePayload
import app.revanced.manager.ui.model.SelectedApp
import app.revanced.manager.ui.model.navigation.SelectedApplicationInfo
import app.revanced.manager.ui.theme.Theme
import app.revanced.manager.util.PatchSelection
import app.revanced.manager.util.BundleDeepLink
import app.revanced.manager.util.BundleDeepLinkIntent
import app.revanced.manager.util.ManagerUpdateDeepLinkIntent
import app.revanced.manager.util.SplitArchiveIntent
import app.revanced.manager.util.SplitArchiveIntentParser
import app.revanced.manager.util.tag
import app.revanced.manager.util.toast
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MainViewModel(
    private val patchBundleRepository: PatchBundleRepository,
    private val patchSelectionRepository: PatchSelectionRepository,
    private val downloadedAppRepository: DownloadedAppRepository,
    private val keystoreManager: KeystoreManager,
    private val app: Application,
    val prefs: PreferencesManager,
    private val json: Json
) : ViewModel() {
    private val appSelectChannel = Channel<SelectedApplicationInfo.ViewModelParams>()
    val appSelectFlow = appSelectChannel.receiveAsFlow()
    private val legacyImportActivityChannel = Channel<Intent>()
    val legacyImportActivityFlow = legacyImportActivityChannel.receiveAsFlow()
    private val bundleDeepLinkChannel = Channel<BundleDeepLink>(Channel.BUFFERED)
    val bundleDeepLinkFlow = bundleDeepLinkChannel.receiveAsFlow()
    private val managerUpdateDeepLinkChannel = Channel<Unit>(Channel.BUFFERED)
    val managerUpdateDeepLinkFlow = managerUpdateDeepLinkChannel.receiveAsFlow()
    private val splitArchiveIntentChannel = Channel<SplitArchiveIntent>(Channel.BUFFERED)
    val splitArchiveIntentFlow = splitArchiveIntentChannel.receiveAsFlow()

    private suspend fun suggestedVersion(packageName: String) =
        patchBundleRepository.suggestedVersions.first()[packageName]

    private suspend fun findDownloadedApp(app: SelectedApp): SelectedApp.Local? {
        if (app !is SelectedApp.Search) return null

        val suggestedVersion = suggestedVersion(app.packageName) ?: return null

        val downloadedApp =
            downloadedAppRepository.get(app.packageName, suggestedVersion, markUsed = true)
                ?: return null
        return SelectedApp.Local(
            downloadedApp.packageName,
            downloadedApp.version,
            downloadedAppRepository.getApkFileForApp(downloadedApp),
            false
        )
    }

    fun selectApp(
        app: SelectedApp,
        patches: PatchSelection? = null,
        selectionPayload: PatchProfilePayload? = null,
        persistConfiguration: Boolean = true,
        returnToDashboard: Boolean = false
    ) = viewModelScope.launch {
        val resolved = findDownloadedApp(app) ?: app
        val selectionPayloadJson = selectionPayload?.let { json.encodeToString(it) }
        appSelectChannel.send(
            SelectedApplicationInfo.ViewModelParams(
                app = resolved,
                patches = patches,
                selectionPayloadJson = selectionPayloadJson,
                persistConfiguration = persistConfiguration,
                returnToDashboard = returnToDashboard
            )
        )
    }

    fun selectApp(app: SelectedApp) = selectApp(app, null, null, true)

    fun selectApp(
        packageName: String,
        patches: PatchSelection? = null,
        selectionPayload: PatchProfilePayload? = null,
        persistConfiguration: Boolean = true,
        returnToDashboard: Boolean = false
    ) = viewModelScope.launch {
        selectApp(
            SelectedApp.Search(packageName, suggestedVersion(packageName)),
            patches,
            selectionPayload,
            persistConfiguration,
            returnToDashboard
        )
    }

    fun selectApp(packageName: String) = selectApp(packageName, null, null, true)

    fun handleIntent(intent: Intent?) {
        if (ManagerUpdateDeepLinkIntent.shouldOpenManagerUpdate(intent)) {
            managerUpdateDeepLinkChannel.trySend(Unit)
        }
        SplitArchiveIntentParser.fromIntent(intent, app.contentResolver)?.let { splitArchiveIntent ->
            splitArchiveIntentChannel.trySend(splitArchiveIntent)
        }
        val deepLink = BundleDeepLinkIntent.fromIntent(intent) ?: return
        bundleDeepLinkChannel.trySend(deepLink)
    }

    init {
        viewModelScope.launch {
            if (!prefs.firstLaunch.get()) return@launch
            legacyImportActivityChannel.send(Intent().apply {
                setClassName(
                    "app.revanced.manager.flutter",
                    "app.revanced.manager.flutter.ExportSettingsActivity"
                )
            })
        }
    }

    fun applyLegacySettings(result: ActivityResult) {
        if (result.resultCode != Activity.RESULT_OK) {
            app.toast(app.getString(R.string.legacy_import_failed))
            Log.e(
                tag,
                "Got unknown result code while importing legacy settings: ${result.resultCode}"
            )
            return
        }

        val jsonStr = result.data?.getStringExtra("data")
        if (jsonStr == null) {
            app.toast(app.getString(R.string.legacy_import_failed))
            Log.e(tag, "Legacy settings data is null")
            return
        }
        val settings = try {
            json.decodeFromString<LegacySettings>(jsonStr)
        } catch (e: SerializationException) {
            app.toast(app.getString(R.string.legacy_import_failed))
            Log.e(tag, "Legacy settings data could not be deserialized", e)
            return
        }

        applyLegacySettings(settings)
    }

    private fun applyLegacySettings(settings: LegacySettings) = viewModelScope.launch {
        settings.themeMode?.let { theme ->
            val themeMap = mapOf(
                0 to Theme.SYSTEM,
                1 to Theme.LIGHT,
                2 to Theme.DARK
            )
            prefs.theme.update(themeMap[theme] ?: Theme.SYSTEM)
        }
        settings.useDynamicTheme?.let { dynamicColor ->
            prefs.dynamicColor.update(dynamicColor)
        }
        settings.usePrereleases?.let { prereleases ->
            prefs.useManagerPrereleases.update(prereleases)
            prefs.usePatchesPrereleases.update(prereleases)
        }
        settings.apiUrl?.let { api ->
            prefs.api.update(api.removeSuffix("/"))
        }
        settings.experimentalPatchesEnabled?.let { allowExperimental ->
            prefs.disablePatchVersionCompatCheck.update(allowExperimental)
        }
        settings.patchesAutoUpdate?.let { autoUpdate ->
            with(patchBundleRepository) {
                sources
                    .first()
                    .find { it.uid == 0 }
                    ?.asRemoteOrNull
                    ?.setAutoUpdate(autoUpdate)

                updateCheck()
            }
        }
        settings.patchesChangeEnabled?.let { disableSelectionWarning ->
            prefs.disableSelectionWarning.update(disableSelectionWarning)
        }
        settings.keystore?.let { keystore ->
            val keystoreBytes = Base64.decode(keystore, Base64.DEFAULT)
            val passwordCandidates = listOf(
                settings.keystorePassword,
                KeystoreManager.DEFAULT,
                "s3cur3p@ssw0rd"
            ).filter { it.isNotBlank() }.distinct()
            val aliasCandidates = listOf(
                KeystoreManager.DEFAULT,
                "alias",
                "ReVanced Key"
            ).distinct()
            var imported = false
            for (alias in aliasCandidates) {
                for (pass in passwordCandidates) {
                    Log.d(tag, "Trying legacy keystore import alias=$alias")
                    if (keystoreManager.import(alias, pass, pass, keystoreBytes.inputStream())) {
                        Log.i(tag, "Legacy keystore import succeeded alias=$alias")
                        imported = true
                        break
                    }
                }
                if (imported) break
            }
            if (!imported) {
                Log.w(tag, "Legacy keystore import failed for all known aliases/passwords")
            }
        }
        settings.patches?.let { selection ->
            patchSelectionRepository.import(0, selection)
        }
        Log.d(tag, "Imported legacy settings")
    }

    @Serializable
    private data class LegacySettings(
        val keystorePassword: String,
        val themeMode: Int? = null,
        val useDynamicTheme: Boolean? = null,
        val usePrereleases: Boolean? = null,
        val apiUrl: String? = null,
        val experimentalPatchesEnabled: Boolean? = null,
        val patchesAutoUpdate: Boolean? = null,
        val patchesChangeEnabled: Boolean? = null,
        val keystore: String? = null,
        val patches: SerializedSelection? = null,
    )
}
