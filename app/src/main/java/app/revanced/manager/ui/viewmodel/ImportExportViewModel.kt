package app.revanced.manager.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.revanced.manager.R
import app.revanced.manager.domain.manager.KeystoreManager
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.domain.repository.PatchSelectionRepository
import app.revanced.manager.domain.repository.SerializedSelection
import app.revanced.manager.domain.repository.PatchBundleRepository
import app.revanced.manager.domain.repository.PatchProfileExportEntry
import app.revanced.manager.domain.repository.PatchProfileRepository
import app.revanced.manager.domain.repository.remapLocalBundles
import app.revanced.manager.domain.bundles.PatchBundleSource
import app.revanced.manager.domain.bundles.PatchBundleSource.Extensions.asRemoteOrNull
import app.revanced.manager.domain.bundles.PatchBundleSource.Extensions.isDefault
import app.revanced.manager.domain.repository.PatchOptionsRepository
import app.revanced.manager.data.room.bundles.Source as SourceInfo
import app.revanced.manager.util.JSON_MIMETYPE
import app.revanced.manager.util.tag
import app.revanced.manager.util.toast
import app.revanced.manager.util.uiSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.deleteExisting
import kotlin.io.path.inputStream

sealed class ResetDialogState(
    @StringRes val titleResId: Int,
    @StringRes val descriptionResId: Int,
    val onConfirm: () -> Unit,
    val dialogOptionName: String? = null
) {
    class Keystore(onConfirm: () -> Unit) : ResetDialogState(
        titleResId = R.string.regenerate_keystore,
        descriptionResId = R.string.regenerate_keystore_dialog_description,
        onConfirm = onConfirm
    )

    class PatchSelectionAll(onConfirm: () -> Unit) : ResetDialogState(
        titleResId = R.string.patch_selection_reset_all,
        descriptionResId = R.string.patch_selection_reset_all_dialog_description,
        onConfirm = onConfirm
    )

    class PatchSelectionPackage(dialogOptionName:String, onConfirm: () -> Unit) : ResetDialogState(
        titleResId = R.string.patch_selection_reset_package,
        descriptionResId = R.string.patch_selection_reset_package_dialog_description,
        onConfirm = onConfirm,
        dialogOptionName = dialogOptionName
    )

    class PatchSelectionBundle(dialogOptionName: String, onConfirm: () -> Unit) : ResetDialogState(
        titleResId = R.string.patch_selection_reset_patches,
        descriptionResId = R.string.patch_selection_reset_patches_dialog_description,
        onConfirm = onConfirm,
        dialogOptionName = dialogOptionName
    )

    class PatchOptionsAll(onConfirm: () -> Unit) : ResetDialogState(
        titleResId = R.string.patch_options_reset_all,
        descriptionResId = R.string.patch_options_reset_all_dialog_description,
        onConfirm = onConfirm
    )

    class PatchOptionPackage(dialogOptionName:String, onConfirm: () -> Unit) : ResetDialogState(
        titleResId = R.string.patch_options_reset_package,
        descriptionResId = R.string.patch_options_reset_package_dialog_description,
        onConfirm = onConfirm,
        dialogOptionName = dialogOptionName
    )

    class PatchOptionBundle(dialogOptionName: String, onConfirm: () -> Unit) : ResetDialogState(
        titleResId = R.string.patch_options_reset_patches,
        descriptionResId = R.string.patch_options_reset_patches_dialog_description,
        onConfirm = onConfirm,
        dialogOptionName = dialogOptionName
    )
}

@Serializable
data class PatchBundleExportFile(
    val bundles: List<PatchBundleSnapshot>
)

@Serializable
data class PatchBundleSnapshot(
    val endpoint: String,
    val name: String,
    val displayName: String? = null,
    val autoUpdate: Boolean = false
)

private data class PatchBundleImportSummary(
    val created: Int,
    val updated: Int
)

@Serializable
data class PatchProfileExportFile(
    val profiles: List<PatchProfileExportEntry>
)

@Serializable
data class ManagerSettingsExportFile(
    val version: Int = 1,
    val settings: PreferencesManager.SettingsSnapshot
)

@OptIn(ExperimentalSerializationApi::class)
class ImportExportViewModel(
    private val app: Application,
    private val keystoreManager: KeystoreManager,
    private val selectionRepository: PatchSelectionRepository,
    private val optionsRepository: PatchOptionsRepository,
    private val patchBundleRepository: PatchBundleRepository,
    private val patchProfileRepository: PatchProfileRepository,
    private val preferencesManager: PreferencesManager
) : ViewModel() {
    private val contentResolver = app.contentResolver
    val patchBundles = patchBundleRepository.sources
    var selectedBundle by mutableStateOf<PatchBundleSource?>(null)
        private set
    var selectionAction by mutableStateOf<SelectionAction?>(null)
        private set
    private var keystoreImportPath by mutableStateOf<Path?>(null)
    val showCredentialsDialog by derivedStateOf { keystoreImportPath != null }

    var resetDialogState by mutableStateOf<ResetDialogState?>(null)

    val packagesWithOptions = optionsRepository.getPackagesWithSavedOptions()
    val packagesWithSelection = selectionRepository.getPackagesWithSavedSelection()

    fun resetOptionsForPackage(packageName: String) = viewModelScope.launch {
        optionsRepository.resetOptionsForPackage(packageName)
        app.toast(app.getString(R.string.patch_options_reset_toast))
    }

    fun resetOptionsForBundle(patchBundle: PatchBundleSource) = viewModelScope.launch {
        optionsRepository.resetOptionsForPatchBundle(patchBundle.uid)
        app.toast(app.getString(R.string.patch_options_reset_toast))
    }

    fun resetOptions() = viewModelScope.launch {
        optionsRepository.reset()
        app.toast(app.getString(R.string.patch_options_reset_toast))
    }

    fun startKeystoreImport(content: Uri) = viewModelScope.launch {
        uiSafe(app, R.string.failed_to_import_keystore, "Failed to import keystore") {
            val path = withContext(Dispatchers.IO) {
                File.createTempFile("signing", "ks", app.cacheDir).toPath().also {
                    Files.copy(
                        contentResolver.openInputStream(content)!!,
                        it,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
            }

            aliases.forEach { alias ->
                knownPasswords.forEach { pass ->
                    if (tryKeystoreImport(alias, pass, path)) {
                        return@launch
                    }
                }
            }

            keystoreImportPath = path
        }
    }

    fun cancelKeystoreImport() {
        keystoreImportPath?.deleteExisting()
        keystoreImportPath = null
    }

    suspend fun tryKeystoreImport(alias: String, pass: String) =
        tryKeystoreImport(alias, pass, keystoreImportPath!!)

    private suspend fun tryKeystoreImport(alias: String, pass: String, path: Path): Boolean {
        path.inputStream().use { stream ->
            if (keystoreManager.import(alias, pass, stream)) {
                app.toast(app.getString(R.string.import_keystore_success))
                cancelKeystoreImport()
                return true
            }
        }

        return false
    }

    override fun onCleared() {
        super.onCleared()

        cancelKeystoreImport()
    }

    fun canExport() = keystoreManager.hasKeystore()

    fun exportKeystore(target: Uri) = viewModelScope.launch {
        keystoreManager.export(contentResolver.openOutputStream(target)!!)
        app.toast(app.getString(R.string.export_keystore_success))
    }

    fun regenerateKeystore() = viewModelScope.launch {
        keystoreManager.regenerate()
        app.toast(app.getString(R.string.regenerate_keystore_success))
    }

    fun resetSelection() = viewModelScope.launch {
        withContext(Dispatchers.Default) { selectionRepository.reset() }
        app.toast(app.getString(R.string.reset_patch_selection_success))
    }

    fun resetSelectionForPackage(packageName: String) = viewModelScope.launch {
        selectionRepository.resetSelectionForPackage(packageName)
        app.toast(app.getString(R.string.reset_patch_selection_success))
    }

    fun resetSelectionForPatchBundle(patchBundle: PatchBundleSource) = viewModelScope.launch {
        selectionRepository.resetSelectionForPatchBundle(patchBundle.uid)
        app.toast(app.getString(R.string.reset_patch_selection_success))
    }

    fun executeSelectionAction(target: Uri) = viewModelScope.launch {
        val source = selectedBundle!!
        val action = selectionAction!!
        clearSelectionAction()

        action.execute(source.uid, target)
    }

    fun selectBundle(bundle: PatchBundleSource) {
        selectedBundle = bundle
    }

    fun clearSelectionAction() {
        selectionAction = null
        selectedBundle = null
    }

    fun importSelection() = clearSelectionAction().also {
        selectionAction = Import()
    }

    fun exportSelection() = clearSelectionAction().also {
        selectionAction = Export()
    }

    fun importPatchBundles(source: Uri) = viewModelScope.launch {
        withContext(NonCancellable) {
            uiSafe(app, R.string.import_patch_bundles_fail, "Failed to import patch bundles") {
                coroutineScope {
                    val progressToast = withContext(Dispatchers.Main) {
                        Toast.makeText(
                            app,
                            app.getString(R.string.import_patch_bundles_in_progress),
                            Toast.LENGTH_SHORT
                        )
                    }

                    withContext(Dispatchers.Main) { progressToast.show() }

                    val toastRepeater = launch(Dispatchers.Main) {
                        while (isActive) {
                            delay(1_750)
                            progressToast.show()
                        }
                    }

                    val summary = try {
                        withContext(Dispatchers.Default) {
                            val exportFile = withContext(Dispatchers.IO) {
                                contentResolver.openInputStream(source)!!.use {
                                    Json.decodeFromStream<PatchBundleExportFile>(it)
                                }
                            }

                            val initialSources = patchBundleRepository.sources.first()
                                .mapNotNull { it.asRemoteOrNull }
                            val endpointToSource =
                                initialSources.associateBy { it.endpoint }.toMutableMap()

                            var createdCount = 0
                            var updatedCount = 0
                            var officialDisplayName: String? = null

                            for (snapshot in exportFile.bundles) {
                                val endpoint = snapshot.endpoint.trim()
                                if (endpoint.equals(SourceInfo.API.SENTINEL, true)) {
                                    val displayName = snapshot.displayName?.takeUnless { it.isBlank() }
                                    if (displayName != null) officialDisplayName = displayName
                                    continue
                                }
                                if (endpoint.isBlank()) continue

                                val targetDisplayName =
                                    snapshot.displayName?.takeUnless { it.isBlank() }
                                val current = endpointToSource[endpoint]
                                if (current != null) {
                                    var changed = false
                                    if (current.displayName != targetDisplayName) {
                                        patchBundleRepository.setDisplayName(current, targetDisplayName)
                                        changed = true
                                    }
                                    if (current.autoUpdate != snapshot.autoUpdate) {
                                        with(patchBundleRepository) {
                                            current.setAutoUpdate(snapshot.autoUpdate)
                                        }
                                        changed = true
                                    }
                                    if (changed) updatedCount += 1
                                    continue
                                }

                                try {
                                    patchBundleRepository.createRemote(endpoint, snapshot.autoUpdate)
                                } catch (error: Exception) {
                                    Log.e(tag, "Failed to import patch bundle $endpoint", error)
                                    continue
                                }

                                val created = patchBundleRepository.sources
                                    .map { sources -> sources.mapNotNull { it.asRemoteOrNull } }
                                    .first { sources -> sources.any { it.endpoint == endpoint } }
                                    .first { it.endpoint == endpoint }

                                createdCount += 1
                                endpointToSource[endpoint] = created

                                if (created.displayName != targetDisplayName) {
                                    patchBundleRepository.setDisplayName(created, targetDisplayName)
                                }
                                if (created.autoUpdate != snapshot.autoUpdate) {
                                    with(patchBundleRepository) {
                                        created.setAutoUpdate(snapshot.autoUpdate)
                                    }
                                }
                            }

                            officialDisplayName?.let { displayName ->
                                val normalized = displayName.trim()
                                if (normalized.isNotEmpty()) {
                                    val defaultSource = patchBundleRepository.sources.first()
                                        .firstOrNull { it.isDefault }
                                    if (defaultSource != null && defaultSource.displayName != normalized) {
                                        patchBundleRepository.setDisplayName(defaultSource, normalized)
                                    }
                                }
                            }

                            PatchBundleImportSummary(createdCount, updatedCount)
                        }
                    } finally {
                        toastRepeater.cancel()
                        withContext(Dispatchers.Main) { progressToast.cancel() }
                    }

                    when {
                        summary.created > 0 -> app.toast(app.getString(R.string.import_patch_bundles_success, summary.created))
                        summary.updated > 0 -> app.toast(app.getString(R.string.import_patch_bundles_updated, summary.updated))
                        else -> app.toast(app.getString(R.string.import_patch_bundles_none))
                    }
                }
            }
        }
    }

    fun exportPatchBundles(target: Uri) = viewModelScope.launch {
        uiSafe(app, R.string.export_patch_bundles_fail, "Failed to export patch bundles") {
            val sources = patchBundleRepository.sources.first()
            val nonDefaultSources = sources.filterNot { it.isDefault }
            val localSources = nonDefaultSources.filter { it.asRemoteOrNull == null }
            val remoteSources = nonDefaultSources.mapNotNull { it.asRemoteOrNull }

            if (remoteSources.isEmpty()) {
                if (localSources.isNotEmpty()) {
                    app.toast(app.getString(R.string.export_patch_bundles_local_not_supported))
                } else {
                    app.toast(app.getString(R.string.export_patch_bundles_empty))
                }
                return@uiSafe
            }

            val officialDisplayName = sources.firstOrNull { it.isDefault }
                ?.displayName
                ?.takeUnless { it.isBlank() }

            val exportedCount = remoteSources.size

            val bundles = buildList {
                remoteSources.mapTo(this) {
                    PatchBundleSnapshot(
                        endpoint = it.endpoint,
                        name = it.name,
                        displayName = it.displayName,
                        autoUpdate = it.autoUpdate
                    )
                }
                if (officialDisplayName != null) {
                    add(
                        PatchBundleSnapshot(
                            endpoint = SourceInfo.API.SENTINEL,
                            name = "",
                            displayName = officialDisplayName,
                            autoUpdate = false
                        )
                    )
                }
            }

            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(target, "wt")!!.use {
                    Json.Default.encodeToStream(PatchBundleExportFile(bundles), it)
                }
            }

            val message = if (localSources.isNotEmpty()) {
                R.string.export_patch_bundles_partial
            } else {
                R.string.export_patch_bundles_success
            }

            app.toast(app.getString(message, exportedCount))
        }
    }

    fun importPatchProfiles(source: Uri) = viewModelScope.launch {
        withContext(NonCancellable) {
            uiSafe(app, R.string.import_patch_profiles_fail, "Failed to import patch profiles") {
                val exportFile = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(source)!!.use {
                        Json.decodeFromStream<PatchProfileExportFile>(it)
                    }
                }

                val entries = exportFile.profiles.filter { it.name.isNotBlank() && it.packageName.isNotBlank() }
                if (entries.isEmpty()) {
                    app.toast(app.getString(R.string.import_patch_profiles_none))
                    return@uiSafe
                }

                val sourcesSnapshot = patchBundleRepository.sources.first()
                val signatureSnapshot = patchBundleRepository.bundleInfoFlow.first()
                    .mapValues { (_, info) -> info.patches.map { it.name.trim().lowercase() }.toSet() }
                val remappedEntries = entries.map { entry ->
                    val remappedPayload = entry.payload.remapLocalBundles(sourcesSnapshot, signatureSnapshot)
                    if (remappedPayload === entry.payload) entry else entry.copy(payload = remappedPayload)
                }

                val result = patchProfileRepository.importProfiles(remappedEntries)
                when {
                    result.imported > 0 && result.skipped > 0 -> {
                        app.toast(
                            app.getString(
                                R.string.import_patch_profiles_partial,
                                result.imported,
                                result.skipped
                            )
                        )
                    }
                    result.imported > 0 -> {
                        app.toast(
                            app.getString(
                                R.string.import_patch_profiles_success,
                                result.imported
                            )
                        )
                    }
                    result.skipped > 0 -> {
                        app.toast(
                            app.getString(
                                R.string.import_patch_profiles_skipped,
                                result.skipped
                            )
                        )
                    }
                    else -> app.toast(app.getString(R.string.import_patch_profiles_none))
                }
            }
        }
    }

    fun exportPatchProfiles(target: Uri) = viewModelScope.launch {
        uiSafe(app, R.string.export_patch_profiles_fail, "Failed to export patch profiles") {
            val profiles = patchProfileRepository.exportProfiles()
            if (profiles.isEmpty()) {
                app.toast(app.getString(R.string.export_patch_profiles_empty))
                return@uiSafe
            }

            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(target, "wt")!!.use {
                    Json.Default.encodeToStream(PatchProfileExportFile(profiles), it)
                }
            }

            app.toast(app.getString(R.string.export_patch_profiles_success, profiles.size))
        }
    }

    fun importManagerSettings(source: Uri) = viewModelScope.launch {
        uiSafe(app, R.string.import_manager_settings_fail, "Failed to import manager settings") {
            val exportFile = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(source)!!.use {
                    Json {
                        ignoreUnknownKeys = true
                    }.decodeFromStream<ManagerSettingsExportFile>(it)
                }
            }

            preferencesManager.importSettings(exportFile.settings)
            app.toast(app.getString(R.string.import_manager_settings_success))
        }
    }

    fun exportManagerSettings(target: Uri) = viewModelScope.launch {
        uiSafe(app, R.string.export_manager_settings_fail, "Failed to export manager settings") {
            val snapshot = preferencesManager.exportSettings()

            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(target, "wt")!!.use {
                    Json.Default.encodeToStream(
                        ManagerSettingsExportFile(settings = snapshot),
                        it
                    )
                }
            }

            app.toast(app.getString(R.string.export_manager_settings_success))
        }
    }

    sealed interface SelectionAction {
        suspend fun execute(bundleUid: Int, location: Uri)
        val activityContract: ActivityResultContract<String, Uri?>
        val activityArg: String
    }

    private inner class Import : SelectionAction {
        override val activityContract = ActivityResultContracts.GetContent()
        override val activityArg = JSON_MIMETYPE
        override suspend fun execute(bundleUid: Int, location: Uri) = uiSafe(
            app,
            R.string.import_patch_selection_fail,
            "Failed to restore patch selection"
        ) {
            val selection = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(location)!!.use {
                    Json.decodeFromStream<SerializedSelection>(it)
                }
            }

            selectionRepository.import(bundleUid, selection)
            app.toast(app.getString(R.string.import_patch_selection_success))
        }
    }

    private inner class Export : SelectionAction {
        override val activityContract = ActivityResultContracts.CreateDocument(JSON_MIMETYPE)
        override val activityArg = "selection.json"
        override suspend fun execute(bundleUid: Int, location: Uri) = uiSafe(
            app,
            R.string.export_patch_selection_fail,
            "Failed to backup patch selection"
        ) {
            val selection = selectionRepository.export(bundleUid)

            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(location, "wt")!!.use {
                    Json.Default.encodeToStream(selection, it)
                }
            }
            app.toast(app.getString(R.string.export_patch_selection_success))
        }
    }

    private companion object {
        val knownPasswords = arrayOf("ReVanced", "s3cur3p@ssw0rd")
        val aliases = arrayOf(KeystoreManager.DEFAULT, "alias", "ReVanced Key")
    }
}
