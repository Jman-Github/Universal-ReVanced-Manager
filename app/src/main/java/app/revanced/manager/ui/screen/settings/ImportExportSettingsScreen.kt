package app.revanced.manager.ui.screen.settings

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.universal.revanced.manager.R
import app.revanced.manager.data.platform.Filesystem
import app.revanced.manager.ui.component.AppTopBar
import app.revanced.manager.ui.component.ColumnWithScrollbar
import app.revanced.manager.ui.component.ConfirmDialog
import app.revanced.manager.ui.component.DownloadProgressBanner
import app.revanced.manager.ui.component.GroupHeader
import app.revanced.manager.ui.component.PasswordField
import app.revanced.manager.ui.component.bundle.BundleSelector
import app.revanced.manager.ui.component.patches.PathSelectorDialog
import app.revanced.manager.ui.component.settings.ExpandableSettingListItem
import app.revanced.manager.ui.component.settings.ExpressiveSettingsCard
import app.revanced.manager.ui.component.settings.ExpressiveSettingsDivider
import app.revanced.manager.ui.component.settings.ExpressiveSettingsItem
import app.revanced.manager.ui.viewmodel.ImportExportViewModel
import app.revanced.manager.ui.viewmodel.ResetDialogState
import app.revanced.manager.util.toast
import app.revanced.manager.util.uiSafe
import app.revanced.manager.domain.repository.PatchBundleRepository.BundleImportPhase
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportExportSettingsScreen(
    onBackClick: () -> Unit,
    vm: ImportExportViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectorDialog by rememberSaveable { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val fs: Filesystem = koinInject()
    val storageRoots = remember { fs.storageRoots() }
    val (permissionContract, permissionName) = remember { fs.permissionContract() }
    var pendingImportPicker by rememberSaveable { mutableStateOf<ImportPicker?>(null) }
    var activeImportPicker by rememberSaveable { mutableStateOf<ImportPicker?>(null) }
    var pendingExportPicker by rememberSaveable { mutableStateOf<ExportPicker?>(null) }
    var activeExportPicker by rememberSaveable { mutableStateOf<ExportPicker?>(null) }
    var exportFileDialogState by remember { mutableStateOf<ExportFileDialogState?>(null) }
    var pendingExportConfirmation by remember { mutableStateOf<PendingExportConfirmation?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(permissionContract) { granted ->
        val pendingImport = pendingImportPicker
        val pendingExport = pendingExportPicker
        if (granted) {
            activeImportPicker = pendingImport
            activeExportPicker = pendingExport
        } else {
            if (pendingImport == ImportPicker.PatchSelection) {
                vm.clearSelectionAction()
            }
            if (pendingExport == ExportPicker.PatchSelection) {
                vm.clearSelectionAction()
            }
        }
        pendingImportPicker = null
        pendingExportPicker = null
    }
    val openImportPicker = { target: ImportPicker ->
        if (fs.hasStoragePermission()) {
            activeImportPicker = target
        } else {
            pendingImportPicker = target
            permissionLauncher.launch(permissionName)
        }
    }
    val openExportPicker = { target: ExportPicker ->
        if (fs.hasStoragePermission()) {
            activeExportPicker = target
        } else {
            pendingExportPicker = target
            permissionLauncher.launch(permissionName)
        }
    }
    val runExport = { picker: ExportPicker, target: Path ->
        val job = when (picker) {
            ExportPicker.Keystore -> vm.exportKeystore(target)
            ExportPicker.PatchBundles -> vm.exportPatchBundles(target)
            ExportPicker.PatchProfiles -> vm.exportPatchProfiles(target)
            ExportPicker.ManagerSettings -> vm.exportManagerSettings(target)
            ExportPicker.PatchSelection -> vm.executeSelectionExport(target)
        }
        coroutineScope.launch {
            job.join()
            activeExportPicker = null
        }
    }

    val patchBundles by vm.patchBundles.collectAsStateWithLifecycle(initialValue = emptyList())
    val packagesWithSelections by vm.packagesWithSelection.collectAsStateWithLifecycle(initialValue = emptySet())
    val packagesWithOptions by vm.packagesWithOptions.collectAsStateWithLifecycle(initialValue = emptySet())
    val importProgress by vm.bundleImportProgress.collectAsStateWithLifecycle(initialValue = null)

    vm.selectionAction?.let { action ->
        if (vm.selectedBundle == null) {
            BundleSelector(patchBundles) {
                if (it == null) {
                    vm.clearSelectionAction()
                } else {
                    vm.selectBundle(it)
                    when (action) {
                        ImportExportViewModel.SelectionAction.Import -> {
                            openImportPicker(ImportPicker.PatchSelection)
                        }
                        ImportExportViewModel.SelectionAction.Export -> {
                            openExportPicker(ExportPicker.PatchSelection)
                        }
                    }
                }
            }
        }
    }

    if (vm.showCredentialsDialog) {
        KeystoreCredentialsDialog(
            onDismissRequest = vm::cancelKeystoreImport,
            onSubmit = { alias, pass ->
                vm.viewModelScope.launch {
                    uiSafe(context, R.string.failed_to_import_keystore, "Failed to import keystore") {
                        val result = vm.tryKeystoreImport(alias, pass)
                        if (!result) context.toast(context.getString(R.string.import_keystore_wrong_credentials))
                    }
                }
            }
        )
    }

    vm.resetDialogState?.let { state ->
        with(state) {
            ConfirmDialog(
                onDismiss = { vm.resetDialogState = null },
                onConfirm = {
                    vm.resetDialogState = null
                    state.onConfirm()
                },
                title = stringResource(titleResId),
                description = dialogOptionName?.let {
                    stringResource(descriptionResId, it)
                } ?: stringResource(descriptionResId),
                icon = Icons.Outlined.WarningAmber
            )
        }
    }
    pendingExportConfirmation?.let { state ->
        ConfirmDialog(
            onDismiss = {
                pendingExportConfirmation = null
                exportFileDialogState = ExportFileDialogState(state.picker, state.directory, state.fileName)
            },
            onConfirm = {
                pendingExportConfirmation = null
                runExport(state.picker, state.directory.resolve(state.fileName))
            },
            title = stringResource(R.string.export_overwrite_title),
            description = stringResource(R.string.export_overwrite_description, state.fileName),
            icon = Icons.Outlined.WarningAmber
        )
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.import_export),
                scrollBehavior = scrollBehavior,
                onBackClick = onBackClick
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        ColumnWithScrollbar(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            selectorDialog?.invoke()
            activeImportPicker?.let { picker ->
                val fileFilter = when (picker) {
                    ImportPicker.Keystore -> ::isKeystoreFile
                    ImportPicker.PatchBundles,
                    ImportPicker.PatchProfiles,
                    ImportPicker.ManagerSettings,
                    ImportPicker.PatchSelection -> ::isJsonFile
                }
                PathSelectorDialog(
                    roots = storageRoots,
                    onSelect = { path ->
                        activeImportPicker = null
                        if (path == null) {
                            if (picker == ImportPicker.PatchSelection) {
                                vm.clearSelectionAction()
                            }
                            return@PathSelectorDialog
                        }
                        when (picker) {
                            ImportPicker.Keystore -> vm.startKeystoreImport(path)
                            ImportPicker.PatchBundles -> vm.importPatchBundles(path)
                            ImportPicker.PatchProfiles -> vm.importPatchProfiles(path)
                            ImportPicker.ManagerSettings -> vm.importManagerSettings(path)
                            ImportPicker.PatchSelection -> vm.executeSelectionImport(path)
                        }
                    },
                    fileFilter = fileFilter,
                    allowDirectorySelection = false
                )
            }
            activeExportPicker?.let { picker ->
                val fileFilter = when (picker) {
                    ExportPicker.Keystore -> ::isKeystoreFile
                    ExportPicker.PatchBundles,
                    ExportPicker.PatchProfiles,
                    ExportPicker.ManagerSettings,
                    ExportPicker.PatchSelection -> ::isJsonFile
                }
                val fileTypeLabel = when (picker) {
                    ExportPicker.Keystore -> ".keystore"
                    ExportPicker.PatchBundles,
                    ExportPicker.PatchProfiles,
                    ExportPicker.ManagerSettings,
                    ExportPicker.PatchSelection -> ".json"
                }
                PathSelectorDialog(
                    roots = storageRoots,
                    onSelect = { path ->
                        if (path == null) {
                            activeExportPicker = null
                            if (picker == ExportPicker.PatchSelection) {
                                vm.clearSelectionAction()
                            }
                            return@PathSelectorDialog
                        }
                    },
                    fileFilter = fileFilter,
                    allowDirectorySelection = false,
                    fileTypeLabel = fileTypeLabel,
                    confirmButtonText = stringResource(R.string.save),
                    onConfirm = { directory ->
                        exportFileDialogState = ExportFileDialogState(picker, directory, picker.defaultName)
                    }
                )
            }
            exportFileDialogState?.let { state ->
                ExportFileNameDialog(
                    initialName = state.fileName,
                    onDismiss = {
                        exportFileDialogState = null
                        if (state.picker == ExportPicker.PatchSelection) {
                            vm.clearSelectionAction()
                        }
                    },
                    onConfirm = { fileName ->
                        val trimmedName = fileName.trim()
                        if (trimmedName.isBlank()) return@ExportFileNameDialog
                        exportFileDialogState = null
                        val target = state.directory.resolve(trimmedName)
                        if (Files.exists(target)) {
                            pendingExportConfirmation = PendingExportConfirmation(
                                picker = state.picker,
                                directory = state.directory,
                                fileName = trimmedName
                            )
                        } else {
                            runExport(state.picker, target)
                        }
                    }
                )
            }

            importProgress?.let { progress ->
                val subtitleParts = buildList {
                    val total = progress.total.coerceAtLeast(1)
                    val stepLabel = if (progress.isStepBased) {
                        val step = (progress.processed + 1).coerceAtMost(total)
                        stringResource(R.string.import_patch_bundles_banner_steps, step, total)
                    } else {
                        stringResource(R.string.import_patch_bundles_banner_subtitle, progress.processed, total)
                    }
                    add(stepLabel)
                    val name = progress.currentBundleName?.takeIf { it.isNotBlank() } ?: return@buildList
                    val phaseText = if (progress.isStepBased) {
                        when (progress.phase) {
                            BundleImportPhase.Downloading -> "Copying bundle"
                            BundleImportPhase.Processing -> "Writing bundle"
                            BundleImportPhase.Finalizing -> "Finalizing import"
                        }
                    } else {
                        when (progress.phase) {
                            BundleImportPhase.Processing -> "Processing"
                            BundleImportPhase.Downloading -> "Downloading"
                            BundleImportPhase.Finalizing -> "Finalizing"
                        }
                    }
                    val detail = buildString {
                        append(phaseText)
                        append(": ")
                        append(name)
                        if (progress.bytesTotal?.takeIf { it > 0L } != null) {
                            append(" (")
                            append(Formatter.formatShortFileSize(context, progress.bytesRead))
                            progress.bytesTotal?.takeIf { it > 0L }?.let { total ->
                                append("/")
                                append(Formatter.formatShortFileSize(context, total))
                            }
                            append(")")
                        }
                    }
                    add(detail)
                }
                DownloadProgressBanner(
                    title = stringResource(R.string.import_patch_bundles_banner_title),
                    subtitle = subtitleParts.joinToString(" • "),
                    progress = progress.ratio,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            GroupHeader(stringResource(R.string.import_))
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                GroupItem(
                    onClick = {
                        openImportPicker(ImportPicker.Keystore)
                    },
                    headline = R.string.import_keystore,
                    description = R.string.import_keystore_description
                )
                ExpressiveSettingsDivider()
                GroupItem(
                    onClick = vm::importSelection,
                    headline = R.string.import_patch_selection,
                    description = R.string.import_patch_selection_description
                )
                ExpressiveSettingsDivider()
                GroupItem(
                    onClick = {
                        openImportPicker(ImportPicker.PatchBundles)
                    },
                    headline = R.string.import_patch_bundles,
                    description = R.string.import_patch_bundles_description
                )
                ExpressiveSettingsDivider()
                GroupItem(
                    onClick = {
                        openImportPicker(ImportPicker.PatchProfiles)
                    },
                    headline = R.string.import_patch_profiles,
                    description = R.string.import_patch_profiles_description
                )
                ExpressiveSettingsDivider()
                GroupItem(
                    onClick = {
                        openImportPicker(ImportPicker.ManagerSettings)
                    },
                    headline = R.string.import_manager_settings,
                    description = R.string.import_manager_settings_description
                )
            }

            GroupHeader(stringResource(R.string.export))
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                GroupItem(
                    onClick = {
                        if (!vm.canExport()) {
                            context.toast(context.getString(R.string.export_keystore_unavailable))
                            return@GroupItem
                        }
                        openExportPicker(ExportPicker.Keystore)
                    },
                    headline = R.string.export_keystore,
                    description = R.string.export_keystore_description
                )
                ExpressiveSettingsDivider()
                GroupItem(
                    onClick = vm::exportSelection,
                    headline = R.string.export_patch_selection,
                    description = R.string.export_patch_selection_description
                )
                ExpressiveSettingsDivider()
                GroupItem(
                    onClick = {
                        openExportPicker(ExportPicker.PatchBundles)
                    },
                    headline = R.string.export_patch_bundles,
                    description = R.string.export_patch_bundles_description
                )
                ExpressiveSettingsDivider()
                GroupItem(
                    onClick = {
                        openExportPicker(ExportPicker.PatchProfiles)
                    },
                    headline = R.string.export_patch_profiles,
                    description = R.string.export_patch_profiles_description
                )
                ExpressiveSettingsDivider()
                GroupItem(
                    onClick = {
                        openExportPicker(ExportPicker.ManagerSettings)
                    },
                    headline = R.string.export_manager_settings,
                    description = R.string.export_manager_settings_description
                )
            }

            GroupHeader(stringResource(R.string.reset))
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                GroupItem(
                    onClick = {
                        vm.resetDialogState = ResetDialogState.Keystore {
                            vm.regenerateKeystore()
                        }
                    },
                    headline = R.string.regenerate_keystore,
                    description = R.string.regenerate_keystore_description
                )
                ExpressiveSettingsDivider()

                ExpandableSettingListItem(
                    headlineContent = stringResource(R.string.reset_patch_selection),
                    supportingContent = stringResource(R.string.reset_patch_selection_description),
                    expandableContent = {
                    GroupItem(
                        onClick = {
                            vm.resetDialogState = ResetDialogState.PatchSelectionAll {
                                vm.resetSelection()
                            }
                        },
                        headline = R.string.patch_selection_reset_all,
                        description = R.string.patch_selection_reset_all_description
                    )

                    GroupItem(
                        onClick = {
                            selectorDialog = {
                                PackageSelector(packages = packagesWithSelections) { packageName ->
                                    packageName?.also {
                                        vm.resetDialogState =
                                            ResetDialogState.PatchSelectionPackage(packageName) {
                                                vm.resetSelectionForPackage(packageName)
                                            }
                                    }
                                    selectorDialog = null
                                }
                            }
                        },
                        headline = R.string.patch_selection_reset_package,
                        description = R.string.patch_selection_reset_package_description
                    )

                    if (patchBundles.isNotEmpty()) {
                        GroupItem(
                            onClick = {
                                selectorDialog = {
                                    BundleSelector(sources = patchBundles) { src ->
                                        src?.also {
                                            coroutineScope.launch {
                                                vm.resetDialogState =
                                                    ResetDialogState.PatchSelectionBundle(it.displayTitle) {
                                                        vm.resetSelectionForPatchBundle(it)
                                                    }
                                            }
                                        }
                                        selectorDialog = null
                                    }
                                }
                            },
                            headline = R.string.patch_selection_reset_patches,
                            description = R.string.patch_selection_reset_patches_description
                        )
                    }
                    }
                )

                ExpressiveSettingsDivider()

                ExpandableSettingListItem(
                    headlineContent = stringResource(R.string.reset_patch_options),
                    supportingContent = stringResource(R.string.reset_patch_options_description),
                    expandableContent = {
                    GroupItem(
                        onClick = {
                            vm.resetDialogState = ResetDialogState.PatchOptionsAll {
                                vm.resetOptions()
                            }
                        }, // TODO: patch options import/export.
                        headline = R.string.patch_options_reset_all,
                        description = R.string.patch_options_reset_all_description,
                    )

                    GroupItem(
                        onClick = {
                            selectorDialog = {
                                PackageSelector(packages = packagesWithOptions) { packageName ->
                                    packageName?.also {
                                        vm.resetDialogState =
                                            ResetDialogState.PatchOptionPackage(packageName) {
                                                vm.resetOptionsForPackage(packageName)
                                            }
                                    }
                                    selectorDialog = null
                                }
                            }
                        },
                        headline = R.string.patch_options_reset_package,
                        description = R.string.patch_options_reset_package_description
                    )

                    if (patchBundles.isNotEmpty()) {
                        GroupItem(
                            onClick = {
                                selectorDialog = {
                                    BundleSelector(sources = patchBundles) { src ->
                                        src?.also {
                                            coroutineScope.launch {
                                            vm.resetDialogState =
                                                ResetDialogState.PatchOptionBundle(src.displayTitle) {
                                                    vm.resetOptionsForBundle(src)
                                                }
                                            }
                                        }
                                        selectorDialog = null
                                    }
                                }
                            },
                            headline = R.string.patch_options_reset_patches,
                            description = R.string.patch_options_reset_patches_description,
                        )
                    }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PackageSelector(packages: Set<String>, onFinish: (String?) -> Unit) {
    val context = LocalContext.current

    val noPackages = packages.isEmpty()

    LaunchedEffect(noPackages) {
        if (noPackages) {
            context.toast("No packages available.")
            onFinish(null)
        }
    }

    if (noPackages) return

    ModalBottomSheet(
        onDismissRequest = { onFinish(null) }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .height(48.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = "Select package",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            packages.forEach {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .height(48.dp)
                        .fillMaxWidth()
                        .clickable {
                            onFinish(it)
                        }
                ) {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupItem(
    onClick: () -> Unit,
    @StringRes headline: Int,
    @StringRes description: Int? = null,
    supportingContent: (@Composable () -> Unit)? = null
) {
    ExpressiveSettingsItem(
        headlineContent = stringResource(headline),
        supportingContent = description?.let { stringResource(it) },
        supportingContentSlot = supportingContent,
        onClick = onClick
    )
}

@Composable
fun KeystoreCredentialsDialog(
    onDismissRequest: () -> Unit,
    onSubmit: (String, String) -> Unit
) {
    var alias by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = {
                    onSubmit(alias, pass)
                }
            ) {
                Text(stringResource(R.string.import_keystore_dialog_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        },
        icon = {
            Icon(Icons.Outlined.Key, null)
        },
        title = {
            Text(
                text = stringResource(R.string.import_keystore_dialog_title),
                style = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.import_keystore_dialog_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text(stringResource(R.string.import_keystore_dialog_alias_field)) }
                )
                PasswordField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text(stringResource(R.string.import_keystore_dialog_password_field)) }
                )
            }
        }
    )
}

private data class ExportFileDialogState(
    val picker: ExportPicker,
    val directory: Path,
    val fileName: String
)

private data class PendingExportConfirmation(
    val picker: ExportPicker,
    val directory: Path,
    val fileName: String
)

private enum class ExportPicker(val defaultName: String) {
    Keystore("Manager.keystore"),
    PatchBundles("urv_patch_bundles.json"),
    PatchProfiles("urv_patch_profiles.json"),
    ManagerSettings("urv_settings.json"),
    PatchSelection("urv_patch_selection.json")
}

private enum class ImportPicker {
    Keystore,
    PatchBundles,
    PatchProfiles,
    ManagerSettings,
    PatchSelection
}

private fun isJsonFile(path: Path): Boolean =
    hasExtension(path, "json")

private fun isKeystoreFile(path: Path): Boolean =
    hasExtension(path, "jks", "keystore", "p12", "pfx", "bks")

private fun hasExtension(path: Path, vararg extensions: String): Boolean {
    val name = path.fileName?.toString()?.lowercase().orEmpty()
    return extensions.any { name.endsWith(".${it.lowercase()}") }
}

@Composable
private fun ExportFileNameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var fileName by rememberSaveable(initialName) { mutableStateOf(initialName) }
    val trimmedName = fileName.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmedName) },
                enabled = trimmedName.isNotEmpty()
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = { Text(stringResource(R.string.export)) },
        text = {
            OutlinedTextField(
                value = fileName,
                onValueChange = { fileName = it },
                label = { Text(stringResource(R.string.file_name)) },
                placeholder = { Text(stringResource(R.string.dialog_input_placeholder)) }
            )
        }
    )
}
