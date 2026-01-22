package app.revanced.manager.ui.screen

import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.revanced.manager.R
import app.revanced.manager.data.platform.Filesystem
import app.revanced.manager.data.room.apps.installed.InstallType
import app.revanced.manager.domain.installer.InstallerManager
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.domain.repository.PatchBundleRepository
import app.revanced.manager.data.room.profile.PatchProfilePayload
import app.revanced.manager.ui.component.AppInfo
import app.revanced.manager.ui.component.AppliedPatchBundleUi
import app.revanced.manager.ui.component.AppliedPatchesDialog
import app.revanced.manager.ui.component.AppTopBar
import app.revanced.manager.ui.component.ColumnWithScrollbar
import app.revanced.manager.ui.component.SegmentedButton
import app.revanced.manager.ui.component.ConfirmDialog
import app.revanced.manager.ui.component.patches.PathSelectorDialog
import app.revanced.manager.ui.component.settings.SettingsListItem
import app.revanced.manager.ui.viewmodel.InstalledAppInfoViewModel
import app.revanced.manager.ui.viewmodel.InstalledAppInfoViewModel.ReplaceSavedBundleResult
import app.revanced.manager.ui.viewmodel.InstallResult
import app.revanced.manager.ui.viewmodel.MountWarningAction
import app.revanced.manager.ui.viewmodel.MountWarningReason
import app.revanced.manager.util.EventEffect
import app.revanced.manager.util.ExportNameFormatter
import app.revanced.manager.util.PatchedAppExportData
import app.revanced.manager.util.PatchSelection
import app.revanced.manager.util.isAllowedApkFile
import app.revanced.manager.util.tag
import app.revanced.manager.util.toast
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.util.Locale
import androidx.compose.runtime.rememberCoroutineScope
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InstalledAppInfoScreen(
    onPatchClick: (
        packageName: String,
        selection: PatchSelection?,
        selectionPayload: PatchProfilePayload?,
        persistConfiguration: Boolean
    ) -> Unit,
    onBackClick: () -> Unit,
    viewModel: InstalledAppInfoViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val patchBundleRepository: PatchBundleRepository = koinInject()
    val prefs: PreferencesManager = koinInject()
    val fs: Filesystem = koinInject()
    val bundleInfo by patchBundleRepository.allBundlesInfoFlow.collectAsStateWithLifecycle(emptyMap())
    val bundleSources by patchBundleRepository.sources.collectAsStateWithLifecycle(emptyList())
    val allowUniversalPatches by prefs.disableUniversalPatchCheck.getAsState()
    val allowBundleOverride by prefs.allowPatchProfileBundleOverride.getAsState()
    val savedAppsEnabled by prefs.enableSavedApps.getAsState()
    val exportFormat by prefs.patchedAppExportFormat.getAsState()
    var showAppliedPatchesDialog by rememberSaveable { mutableStateOf(false) }
    var showUniversalBlockedDialog by rememberSaveable { mutableStateOf(false) }
    var showMixedBundleDialog by rememberSaveable { mutableStateOf(false) }
    var showLeaveInstallDialog by rememberSaveable { mutableStateOf(false) }
    var showExportPicker by rememberSaveable { mutableStateOf(false) }
    var exportFileDialogState by remember { mutableStateOf<ExportSavedApkDialogState?>(null) }
    var pendingExportConfirmation by remember { mutableStateOf<PendingSavedExportConfirmation?>(null) }
    var exportInProgress by rememberSaveable { mutableStateOf(false) }
    val appliedSelection = viewModel.appliedPatches
    val isInstalledOnDevice = viewModel.isInstalledOnDevice
    val storageRoots = remember { fs.storageRoots() }
    val (permissionContract, permissionName) = remember { fs.permissionContract() }
    val permissionLauncher =
        rememberLauncherForActivityResult(permissionContract) { granted ->
            if (granted) {
                showExportPicker = true
            }
        }
    fun openExportPicker() {
        if (fs.hasStoragePermission()) {
            showExportPicker = true
        } else {
            permissionLauncher.launch(permissionName)
        }
    }
    val selectionPayload = viewModel.installedApp?.selectionPayload
    val savedBundleVersions = remember(selectionPayload) {
        selectionPayload?.bundles.orEmpty().associate { it.bundleUid to it.version }
    }
    data class SavedBundleTarget(
        val bundleUid: Int,
        val bundleName: String,
        val requiredPatchesLowercase: Set<String>
    )
    data class SavedBundleOption(
        val uid: Int,
        val displayName: String,
        val version: String?,
        val patchCount: Int,
        val patchNamesLowercase: Set<String>
    )
    data class SavedBundleOverrideTarget(
        val bundleUid: Int,
        val targetUid: Int,
        val displayName: String,
        val requiredPatchesLowercase: Set<String>
    )
    var missingBundleTarget by remember { mutableStateOf<SavedBundleTarget?>(null) }
    var missingBundleSelectionUid by rememberSaveable { mutableStateOf<Int?>(null) }
    var missingBundleSaving by remember { mutableStateOf(false) }
    var missingBundleIncompatibleTarget by remember { mutableStateOf<SavedBundleOverrideTarget?>(null) }

    val appliedBundles = remember(appliedSelection, bundleInfo, bundleSources, context, savedBundleVersions) {
        if (appliedSelection.isNullOrEmpty()) return@remember emptyList<AppliedPatchBundleUi>()

        runCatching {
            appliedSelection.entries.mapNotNull { (bundleUid, patches) ->
                if (patches.isEmpty()) return@mapNotNull null
                val patchNames = patches.toList().sorted()
                val info = bundleInfo[bundleUid]
                val source = bundleSources.firstOrNull { it.uid == bundleUid }
                val fallbackName = if (bundleUid == 0)
                    context.getString(R.string.patches_name_default)
                else
                    context.getString(R.string.patches_name_fallback)

                val title = source?.displayTitle
                    ?: info?.name
                    ?: "$fallbackName (#$bundleUid)"

                val patchInfos = info?.patches
                    ?.filter { it.name in patches }
                    ?.distinctBy { it.name }
                    ?.sortedBy { it.name }
                    ?: emptyList()

                val missingNames = patchNames.filterNot { patchName ->
                    patchInfos.any { it.name == patchName }
                }.distinct()

                AppliedPatchBundleUi(
                    uid = bundleUid,
                    title = title,
                    version = savedBundleVersions[bundleUid]?.takeUnless { it.isNullOrBlank() } ?: info?.version,
                    patchInfos = patchInfos,
                    fallbackNames = missingNames,
                    bundleAvailable = info != null
                )
            }.sortedBy { it.title }
        }.getOrElse { error ->
            Log.e(tag, "Failed to build applied bundle summary", error)
            emptyList()
        }
    }
    val bundleOptions = remember(bundleInfo, bundleSources) {
        bundleInfo.mapNotNull { (uid, info) ->
            val source = bundleSources.firstOrNull { it.uid == uid }
            val displayName = source?.displayTitle ?: info.name
            val patchNamesLowercase = info.patches
                .mapTo(mutableSetOf()) { it.name.trim().lowercase(Locale.ROOT) }
            SavedBundleOption(
                uid = uid,
                displayName = displayName,
                version = info.version,
                patchCount = info.patches.size,
                patchNamesLowercase = patchNamesLowercase
            )
        }.sortedBy { it.displayName.lowercase(Locale.ROOT) }
    }

    val universalPatchNamesByUid = remember(bundleInfo) {
        bundleInfo.mapValues { (_, info) ->
            info.patches
                .asSequence()
                .filter { it.compatiblePackages == null }
                .mapTo(mutableSetOf()) { it.name.trim().lowercase(Locale.ROOT) }
        }
    }

    val appliedBundlesContainUniversal = remember(appliedBundles, universalPatchNamesByUid) {
        appliedBundles.any { bundle ->
            val universalNames = universalPatchNamesByUid[bundle.uid].orEmpty()
            val hasByMetadata = bundle.patchInfos.any { it.compatiblePackages == null }
            val fallbackMatch = bundle.fallbackNames.any { name ->
                universalNames.contains(name.trim().lowercase(Locale.ROOT))
            }
            hasByMetadata || fallbackMatch
        }
    }

    val appliedSelectionContainsUniversal = remember(appliedSelection, universalPatchNamesByUid) {
        appliedSelection?.any { (bundleUid, patches) ->
            val universalNames = universalPatchNamesByUid[bundleUid].orEmpty()
            patches.any { universalNames.contains(it.trim().lowercase(Locale.ROOT)) }
        } ?: false
    }

    fun handleRepatchClick(targetPackageName: String) {
        if (!allowUniversalPatches && (appliedBundlesContainUniversal || appliedSelectionContainsUniversal)) {
            showUniversalBlockedDialog = true
            return
        }

        val selection = appliedSelection ?: return
        val persistConfiguration = viewModel.installedApp?.installType != InstallType.SAVED
        scope.launch {
            if (patchBundleRepository.selectionHasMixedBundleTypes(selection)) {
                showMixedBundleDialog = true
                return@launch
            }
            onPatchClick(targetPackageName, selection, selectionPayload, persistConfiguration)
        }
    }

    val bundlesUsedSummary = remember(appliedBundles) {
        if (appliedBundles.isEmpty()) ""
        else appliedBundles.joinToString("\n") { bundle ->
            val version = bundle.version?.takeIf { it.isNotBlank() }
            if (version != null) "${bundle.title} ($version)" else bundle.title
        }
    }

    val activityLauncher = rememberLauncherForActivityResult(
        contract = StartActivityForResult(),
        onResult = viewModel::handleActivityResult
    )
    EventEffect(flow = viewModel.launchActivityFlow) { intent ->
        activityLauncher.launch(intent)
    }

    SideEffect {
        viewModel.onBackClick = onBackClick
    }

    var showUninstallDialog by rememberSaveable { mutableStateOf(false) }

    if (showUninstallDialog)
        UninstallDialog(
            onDismiss = { showUninstallDialog = false },
            onConfirm = { viewModel.uninstall() }
        )

    if (showAppliedPatchesDialog && appliedSelection != null) {
        AppliedPatchesDialog(
            bundles = appliedBundles,
            onDismissRequest = { showAppliedPatchesDialog = false }
        )
    }

    if (showUniversalBlockedDialog) {
        AlertDialog(
            onDismissRequest = { showUniversalBlockedDialog = false },
            confirmButton = {
                TextButton(onClick = { showUniversalBlockedDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            },
            title = { Text(stringResource(R.string.universal_patches_profile_blocked_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.universal_patches_app_blocked_description,
                        stringResource(R.string.universal_patches_safeguard)
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        )
    }

    if (showMixedBundleDialog) {
        AlertDialog(
            onDismissRequest = { showMixedBundleDialog = false },
            confirmButton = {
                TextButton(onClick = { showMixedBundleDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            },
            title = { Text(stringResource(R.string.mixed_patch_bundles_title)) },
            text = { Text(stringResource(R.string.mixed_patch_bundles_description)) }
        )
    }

    missingBundleTarget?.let { target ->
        val options = bundleOptions
        AlertDialog(
            onDismissRequest = {
                if (missingBundleSaving) return@AlertDialog
                missingBundleTarget = null
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selectedId = missingBundleSelectionUid ?: return@TextButton
                        val selected = options.firstOrNull { it.uid == selectedId } ?: return@TextButton
                        if (missingBundleSaving) return@TextButton
                        val isCompatible = target.requiredPatchesLowercase.all {
                            it in selected.patchNamesLowercase
                        }
                        if (!isCompatible) {
                            missingBundleIncompatibleTarget = SavedBundleOverrideTarget(
                                bundleUid = target.bundleUid,
                                targetUid = selected.uid,
                                displayName = selected.displayName,
                                requiredPatchesLowercase = target.requiredPatchesLowercase
                            )
                            return@TextButton
                        }
                        missingBundleSaving = true
                        scope.launch {
                            try {
                                when (
                                    viewModel.replaceSavedBundle(
                                        target.bundleUid,
                                        selected.uid,
                                        target.requiredPatchesLowercase,
                                        false
                                    )
                                ) {
                                    ReplaceSavedBundleResult.SUCCESS -> context.toast(
                                        context.getString(
                                            R.string.saved_app_bundle_select_success,
                                            selected.displayName
                                        )
                                    )
                                    ReplaceSavedBundleResult.INCOMPATIBLE -> {
                                        missingBundleIncompatibleTarget = SavedBundleOverrideTarget(
                                            bundleUid = target.bundleUid,
                                            targetUid = selected.uid,
                                            displayName = selected.displayName,
                                            requiredPatchesLowercase = target.requiredPatchesLowercase
                                        )
                                    }
                                    ReplaceSavedBundleResult.APP_NOT_FOUND,
                                    ReplaceSavedBundleResult.TARGET_NOT_FOUND,
                                    ReplaceSavedBundleResult.FAILED -> context.toast(
                                        context.getString(R.string.saved_app_bundle_select_error)
                                    )
                                }
                            } finally {
                                missingBundleSaving = false
                                missingBundleTarget = null
                            }
                        }
                    },
                    enabled = !missingBundleSaving && missingBundleSelectionUid != null && options.isNotEmpty()
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (missingBundleSaving) return@TextButton
                        missingBundleTarget = null
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = {
                Text(
                    stringResource(
                        R.string.saved_app_bundle_select_title,
                        target.bundleName
                    )
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = stringResource(R.string.saved_app_bundle_select_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (options.isEmpty()) {
                        Text(
                            text = stringResource(R.string.saved_app_bundle_select_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        options.forEach { option ->
                            val patchCountText = pluralStringResource(
                                R.plurals.patch_profile_bundle_patch_count,
                                option.patchCount,
                                option.patchCount
                            )
                            val versionLabel = option.version?.let { version ->
                                if (version.startsWith("v", ignoreCase = true)) version else "v$version"
                            }
                            val subtitle = if (versionLabel != null) {
                                "$versionLabel - $patchCountText"
                            } else {
                                patchCountText
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { missingBundleSelectionUid = option.uid }
                                    .padding(vertical = 6.dp)
                            ) {
                                RadioButton(
                                    selected = missingBundleSelectionUid == option.uid,
                                    onClick = { missingBundleSelectionUid = option.uid }
                                )
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = option.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    missingBundleIncompatibleTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { missingBundleIncompatibleTarget = null },
            confirmButton = {
                if (allowBundleOverride) {
                    TextButton(
                        onClick = {
                            if (missingBundleSaving) return@TextButton
                            missingBundleSaving = true
                            scope.launch {
                                try {
                                    when (
                                        viewModel.replaceSavedBundle(
                                            target.bundleUid,
                                            target.targetUid,
                                            target.requiredPatchesLowercase,
                                            true
                                        )
                                    ) {
                                        ReplaceSavedBundleResult.SUCCESS -> context.toast(
                                            context.getString(
                                                R.string.saved_app_bundle_select_success,
                                                target.displayName
                                            )
                                        )
                                        ReplaceSavedBundleResult.INCOMPATIBLE,
                                        ReplaceSavedBundleResult.TARGET_NOT_FOUND,
                                        ReplaceSavedBundleResult.APP_NOT_FOUND,
                                        ReplaceSavedBundleResult.FAILED -> context.toast(
                                            context.getString(R.string.saved_app_bundle_select_error)
                                        )
                                    }
                                } finally {
                                    missingBundleSaving = false
                                    missingBundleIncompatibleTarget = null
                                    missingBundleTarget = null
                                }
                            }
                        }
                    ) {
                        Text(stringResource(R.string.saved_app_bundle_select_override))
                    }
                } else {
                    TextButton(onClick = { missingBundleIncompatibleTarget = null }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            },
            dismissButton = {
                if (allowBundleOverride) {
                    TextButton(onClick = { missingBundleIncompatibleTarget = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            },
            title = { Text(stringResource(R.string.saved_app_bundle_select_incompatible_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.saved_app_bundle_select_incompatible_message,
                        target.displayName
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
    }

    val installResult = viewModel.installResult
    if (installResult != null) {
        val (titleRes, message) = when (installResult) {
            is InstallResult.Success -> R.string.install_app_success to installResult.message
            is InstallResult.Failure -> R.string.install_app_fail_title to installResult.message
        }
        AlertDialog(
            onDismissRequest = viewModel::clearInstallResult,
            confirmButton = {
                TextButton(onClick = viewModel::clearInstallResult) {
                    Text(stringResource(R.string.ok))
                }
            },
            title = { Text(stringResource(titleRes)) },
            text = { Text(message) }
        )
    }

    viewModel.signatureMismatchPackage?.let {
        AlertDialog(
            onDismissRequest = viewModel::dismissSignatureMismatchPrompt,
            confirmButton = {
                TextButton(onClick = viewModel::confirmSignatureMismatchInstall) {
                    Text(stringResource(R.string.installation_signature_mismatch_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissSignatureMismatchPrompt) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { Text(stringResource(R.string.installation_signature_mismatch_dialog_title)) },
            text = { Text(stringResource(R.string.installation_signature_mismatch_description)) }
        )
    }

    viewModel.mountVersionMismatchMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMountVersionMismatch,
            confirmButton = {
                TextButton(onClick = viewModel::dismissMountVersionMismatch) {
                    Text(stringResource(R.string.ok))
                }
            },
            title = { Text(stringResource(R.string.mount_version_mismatch_title)) },
            text = { Text(message) }
        )
    }

    val mountWarning = viewModel.mountWarning
    if (mountWarning != null) {
        val (descriptionRes, titleRes) = when (mountWarning.reason) {
            MountWarningReason.PRIMARY_IS_MOUNT_FOR_NON_MOUNT_APP ->
                when (mountWarning.action) {
                    MountWarningAction.INSTALL -> R.string.installer_mount_warning_install
                    MountWarningAction.UPDATE -> R.string.installer_mount_warning_update
                    MountWarningAction.UNINSTALL -> R.string.installer_mount_warning_uninstall
                } to R.string.installer_mount_warning_title

            MountWarningReason.PRIMARY_NOT_MOUNT_FOR_MOUNT_APP ->
                when (mountWarning.action) {
                    MountWarningAction.INSTALL -> R.string.installer_mount_mismatch_install
                    MountWarningAction.UPDATE -> R.string.installer_mount_mismatch_update
                    MountWarningAction.UNINSTALL -> R.string.installer_mount_mismatch_uninstall
                } to R.string.installer_mount_mismatch_title
        }

        AlertDialog(
            onDismissRequest = viewModel::clearMountWarning,
            confirmButton = {
                TextButton(onClick = viewModel::clearMountWarning) {
                    Text(stringResource(R.string.ok))
                }
            },
            title = { Text(stringResource(titleRes)) },
            text = {
                Text(
                    text = stringResource(descriptionRes),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        )
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.app_info),
                scrollBehavior = scrollBehavior,
                onBackClick = {
                    if (viewModel.isInstalling) showLeaveInstallDialog = true else onBackClick()
                }
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        BackHandler {
            if (viewModel.isInstalling) showLeaveInstallDialog = true else onBackClick()
        }
        ColumnWithScrollbar(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val installedApp = viewModel.installedApp ?: return@ColumnWithScrollbar

            AppInfo(
                appInfo = viewModel.appInfo,
                placeholderLabel = null
            ) {
                Text(installedApp.version, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)

                if (installedApp.installType == InstallType.MOUNT) {
                    val mountStatusText = when (viewModel.mountOperation) {
                        InstalledAppInfoViewModel.MountOperation.UNMOUNTING -> stringResource(R.string.unmounting)
                        InstalledAppInfoViewModel.MountOperation.MOUNTING -> stringResource(R.string.mounting_ellipsis)
                        null -> if (viewModel.isMounted) {
                            stringResource(R.string.mounted)
                        } else {
                            stringResource(R.string.not_mounted)
                        }
                    }
                    Text(
                        text = mountStatusText,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

    val exportMetadata = remember(
        installedApp.currentPackageName,
        installedApp.version,
        appliedBundles,
        viewModel.appInfo
    ) {
        val label = viewModel.appInfo?.applicationInfo?.loadLabel(context.packageManager)?.toString()
            ?: installedApp.currentPackageName
        val bundleVersions = appliedBundles.mapNotNull { it.version?.takeIf(String::isNotBlank) }
        val bundleNames = appliedBundles.map { it.title }.filter(String::isNotBlank)
        PatchedAppExportData(
            appName = label,
            packageName = installedApp.currentPackageName,
            appVersion = installedApp.version,
            patchBundleVersions = bundleVersions,
            patchBundleNames = bundleNames
        )
    }
    val exportFileName = remember(exportMetadata, exportFormat) {
        ExportNameFormatter.format(exportFormat, exportMetadata)
    }
    if (showExportPicker) {
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                if (path == null) {
                    showExportPicker = false
                }
            },
            fileFilter = ::isAllowedApkFile,
            allowDirectorySelection = false,
            fileTypeLabel = ".apk",
            confirmButtonText = stringResource(R.string.save),
            onConfirm = { directory ->
                exportFileDialogState = ExportSavedApkDialogState(directory, exportFileName)
            }
        )
    }
    exportFileDialogState?.let { state ->
        ExportSavedApkFileNameDialog(
            initialName = state.fileName,
            onDismiss = { exportFileDialogState = null },
            onConfirm = { fileName ->
                val trimmedName = fileName.trim()
                if (trimmedName.isBlank()) return@ExportSavedApkFileNameDialog
                exportFileDialogState = null
                val target = state.directory.resolve(trimmedName)
                if (Files.exists(target)) {
                    pendingExportConfirmation = PendingSavedExportConfirmation(
                        directory = state.directory,
                        fileName = trimmedName
                    )
                } else {
                    exportInProgress = true
                    viewModel.exportSavedAppToPath(target) { success ->
                        exportInProgress = false
                        if (success) {
                            showExportPicker = false
                        }
                    }
                }
            }
        )
    }
    pendingExportConfirmation?.let { state ->
        ConfirmDialog(
            onDismiss = {
                pendingExportConfirmation = null
                exportFileDialogState = ExportSavedApkDialogState(state.directory, state.fileName)
            },
            onConfirm = {
                pendingExportConfirmation = null
                exportInProgress = true
                viewModel.exportSavedAppToPath(state.directory.resolve(state.fileName)) { success ->
                    exportInProgress = false
                    if (success) {
                        showExportPicker = false
                    }
                }
            },
            title = stringResource(R.string.export_overwrite_title),
            description = stringResource(R.string.export_overwrite_description, state.fileName),
            icon = Icons.Outlined.WarningAmber
        )
    }
    if (exportInProgress) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.export)) },
            text = { Text(stringResource(R.string.patcher_step_group_saving)) },
            icon = { CircularProgressIndicator() },
            confirmButton = {},
            dismissButton = {}
        )
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(24.dp))
    ) {
        val hasRoot = viewModel.rootInstaller.hasRootAccess()
        val installType = remember(installedApp.installType, viewModel.primaryInstallerIsMount, hasRoot) {
            if (installedApp.installType == InstallType.SAVED && viewModel.primaryInstallerIsMount && hasRoot) {
                InstallType.MOUNT
            } else {
                installedApp.installType
            }
        }
        val rootRequiredText = stringResource(R.string.installer_status_requires_root)

        if (viewModel.appInfo != null) {
            key("open") {
                SegmentedButton(
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    text = stringResource(R.string.open_app),
                    onClick = viewModel::launch,
                    enabled = isInstalledOnDevice
                )
            }
        }

        when (installType) {
            InstallType.DEFAULT,
            InstallType.CUSTOM,
            InstallType.SHIZUKU -> {
                if (viewModel.hasSavedCopy) {
                    val installAction: () -> Unit = {
                        if (viewModel.primaryInstallerIsMount && installType != InstallType.MOUNT) {
                            val action = if (isInstalledOnDevice) MountWarningAction.UPDATE else MountWarningAction.INSTALL
                            viewModel.showMountWarning(action, MountWarningReason.PRIMARY_IS_MOUNT_FOR_NON_MOUNT_APP)
                        } else {
                            viewModel.installSavedApp()
                        }
                    }

                    key("export") {
                        SegmentedButton(
                            icon = Icons.Outlined.Save,
                            text = stringResource(R.string.export),
                            onClick = { openExportPicker() }
                        )
                    }
                    key("update_or_install") {
                        SegmentedButton(
                            icon = Icons.Outlined.InstallMobile,
                            text = if (isInstalledOnDevice) stringResource(R.string.update) else stringResource(R.string.install_saved_app),
                            onClick = installAction,
                            onLongClick = if (isInstalledOnDevice) viewModel::uninstall else null
                        )
                    }

                    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
                    if (showDeleteConfirmation) {
                        ConfirmDialog(
                            onDismiss = { showDeleteConfirmation = false },
                            onConfirm = {
                                showDeleteConfirmation = false
                                viewModel.deleteSavedEntry()
                            },
                            title = stringResource(R.string.delete_saved_entry_title),
                            description = stringResource(R.string.delete_saved_entry_description),
                            icon = Icons.Outlined.Delete
                        )
                    }
                    key("delete_entry") {
                        SegmentedButton(
                            icon = Icons.Outlined.Delete,
                            text = stringResource(R.string.delete),
                            onClick = { showDeleteConfirmation = true }
                        )
                    }
                } else {
                    if (isInstalledOnDevice) {
                        SegmentedButton(
                            icon = Icons.Outlined.Delete,
                            text = stringResource(R.string.uninstall),
                            onClick = viewModel::uninstall
                        )
                    }
                }

                key("repatch") {
                    SegmentedButton(
                        icon = Icons.Outlined.Update,
                        text = stringResource(R.string.repatch),
                        onClick = {
                            handleRepatchClick(installedApp.originalPackageName)
                        }
                    )
                }
            }

            InstallType.MOUNT -> {
                var showUnmountConfirmation by rememberSaveable { mutableStateOf(false) }

                if (showUnmountConfirmation) {
                    ConfirmDialog(
                        onDismiss = { showUnmountConfirmation = false },
                        onConfirm = {
                            showUnmountConfirmation = false
                            viewModel.unmountSavedInstallation()
                        },
                        title = stringResource(R.string.unmount),
                        description = stringResource(R.string.unmount_confirm_description),
                        icon = Icons.Outlined.Circle
                    )
                }

                SegmentedButton(
                    icon = Icons.Outlined.SettingsBackupRestore,
                    text = if (viewModel.isMounted) stringResource(R.string.remount_saved_app) else stringResource(R.string.mount),
                    onClick = {
                        if (!hasRoot) {
                            Toast
                                .makeText(context, rootRequiredText, Toast.LENGTH_SHORT)
                                .show()
                            return@SegmentedButton
                        }
                        if (!viewModel.primaryInstallerIsMount) {
                            val action = if (viewModel.isMounted) MountWarningAction.UPDATE else MountWarningAction.INSTALL
                            viewModel.showMountWarning(action, MountWarningReason.PRIMARY_NOT_MOUNT_FOR_MOUNT_APP)
                        } else {
                            if (viewModel.isMounted) viewModel.remountSavedInstallation() else viewModel.mountOrUnmount()
                        }
                    },
                    onLongClick = if (viewModel.isMounted) {
                        {
                            if (!hasRoot) {
                                Toast
                                    .makeText(context, rootRequiredText, Toast.LENGTH_SHORT)
                                    .show()
                            } else {
                                if (!viewModel.primaryInstallerIsMount) {
                                    viewModel.showMountWarning(MountWarningAction.UNINSTALL, MountWarningReason.PRIMARY_NOT_MOUNT_FOR_MOUNT_APP)
                                } else {
                                    showUnmountConfirmation = true
                                }
                            }
                        }
                    } else null
                )
                SegmentedButton(
                    icon = Icons.Outlined.Save,
                    text = stringResource(R.string.export),
                    onClick = { openExportPicker() }
                )
                var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
                if (showDeleteConfirmation) {
                    ConfirmDialog(
                        onDismiss = { showDeleteConfirmation = false },
                        onConfirm = {
                            showDeleteConfirmation = false
                            viewModel.deleteSavedEntry()
                        },
                        title = stringResource(R.string.delete_saved_entry_title),
                        description = stringResource(R.string.delete_saved_entry_description),
                        icon = Icons.Outlined.Delete
                    )
                }
                SegmentedButton(
                    icon = Icons.Outlined.Delete,
                    text = stringResource(R.string.delete),
                    onClick = { showDeleteConfirmation = true }
                )
                SegmentedButton(
                    icon = Icons.Outlined.Update,
                    text = stringResource(R.string.repatch),
                    onClick = {
                        handleRepatchClick(installedApp.originalPackageName)
                    }
                )
            }

            InstallType.SAVED -> {
                key("export") {
                    SegmentedButton(
                        icon = Icons.Outlined.Save,
                        text = stringResource(R.string.export),
                        onClick = { openExportPicker() }
                    )
                }

                var showSavedUninstallDialog by rememberSaveable { mutableStateOf(false) }
                if (showSavedUninstallDialog) {
                    val confirmTitle = stringResource(R.string.saved_app_uninstall_title)
                    val confirmDescription = stringResource(R.string.saved_app_uninstall_description)
                    ConfirmDialog(
                        onDismiss = { showSavedUninstallDialog = false },
                        onConfirm = {
                            showSavedUninstallDialog = false
                            viewModel.uninstallSavedInstallation()
                        },
                        title = confirmTitle,
                        description = confirmDescription,
                        icon = Icons.Outlined.Delete
                    )
                }

                val installText = if (isInstalledOnDevice) {
                    stringResource(R.string.update_saved_app)
                } else {
                    stringResource(R.string.install_saved_app)
                }
                key("update_or_install") {
                    SegmentedButton(
                        icon = Icons.Outlined.InstallMobile,
                        text = installText,
                        onClick = {
                            if (viewModel.primaryInstallerIsMount) {
                                val action = if (isInstalledOnDevice) MountWarningAction.UPDATE else MountWarningAction.INSTALL
                                viewModel.showMountWarning(action, MountWarningReason.PRIMARY_IS_MOUNT_FOR_NON_MOUNT_APP)
                            } else {
                                viewModel.installSavedApp()
                            }
                        },
                        onLongClick = if (isInstalledOnDevice) {
                            {
                                if (viewModel.primaryInstallerIsMount) {
                                    viewModel.showMountWarning(MountWarningAction.UNINSTALL, MountWarningReason.PRIMARY_IS_MOUNT_FOR_NON_MOUNT_APP)
                                } else {
                                    showSavedUninstallDialog = true
                                }
                            }
                        } else null
                    )
                }

                val deleteAction = viewModel::removeSavedApp
                val deleteTitle = stringResource(R.string.delete_saved_app_title)
                val deleteDescription = stringResource(R.string.delete_saved_app_description)
                val deleteLabel = stringResource(R.string.delete)
                var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
                if (showDeleteConfirmation) {
                    ConfirmDialog(
                        onDismiss = { showDeleteConfirmation = false },
                        onConfirm = {
                            showDeleteConfirmation = false
                            deleteAction()
                        },
                        title = deleteTitle,
                        description = deleteDescription,
                        icon = Icons.Outlined.Delete
                    )
                }
                key("delete_entry") {
                    SegmentedButton(
                        icon = Icons.Outlined.Delete,
                        text = deleteLabel,
                        onClick = { showDeleteConfirmation = true }
                    )
                }

                key("repatch") {
                    SegmentedButton(
                        icon = Icons.Outlined.Update,
                        text = stringResource(R.string.repatch),
                        onClick = {
                            handleRepatchClick(installedApp.originalPackageName)
                        }
                    )
                }
            }
        }
    }

            Column(
                modifier = Modifier.padding(vertical = 16.dp)
            ) {
                SettingsListItem(
                    modifier = Modifier.clickable(
                        enabled = appliedSelection != null
                    ) { showAppliedPatchesDialog = true },
                    headlineContent = stringResource(R.string.applied_patches),
                    supportingContent = when (val selection = appliedSelection) {
                        null -> stringResource(R.string.loading)
                        else -> {
                            val count = selection.values.sumOf { it.size }
                            pluralStringResource(
                                id = R.plurals.patch_count,
                                count,
                                count
                            )
                        }
                    },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowRight,
                            contentDescription = stringResource(R.string.view_applied_patches)
                        )
                    }
                )

                SettingsListItem(
                    headlineContent = stringResource(R.string.package_name),
                    supportingContent = installedApp.currentPackageName
                )

                if (installedApp.originalPackageName != installedApp.currentPackageName) {
                    SettingsListItem(
                        headlineContent = stringResource(R.string.original_package_name),
                        supportingContent = installedApp.originalPackageName
                    )
                }

                SettingsListItem(
                    headlineContent = stringResource(R.string.install_type),
                    supportingContent = when (installedApp.installType) {
            InstallType.MOUNT -> stringResource(R.string.install_type_mount_label)
            InstallType.SHIZUKU -> stringResource(R.string.install_type_shizuku_label)
            InstallType.DEFAULT, InstallType.CUSTOM -> when (viewModel.primaryInstallerToken) {
                InstallerManager.Token.Internal -> stringResource(R.string.install_type_system_installer)
                InstallerManager.Token.AutoSaved -> stringResource(R.string.install_type_mount_label)
                is InstallerManager.Token.Component,
                InstallerManager.Token.Shizuku,
                InstallerManager.Token.None -> stringResource(R.string.install_type_custom_installer)
                        }
                        InstallType.SAVED -> stringResource(installedApp.installType.stringResource)
                    }
                )

                val bundleSummaryText = when {
                    appliedSelection == null -> stringResource(R.string.loading)
                    bundlesUsedSummary.isNotBlank() -> bundlesUsedSummary
                    else -> stringResource(R.string.no_patch_bundles_tracked)
                }
                SettingsListItem(
                    headlineContent = stringResource(R.string.patch_bundles_used),
                    supportingContent = bundleSummaryText
                )

                val missingBundles = appliedBundles.filterNot { it.bundleAvailable }
                if (installedApp.installType == InstallType.SAVED && missingBundles.isNotEmpty()) {
                    missingBundles.forEach { bundle ->
                        SettingsListItem(
                            headlineContent = stringResource(R.string.saved_app_bundle_missing_title),
                            supportingContent = stringResource(
                                R.string.patch_profile_bundle_unavailable_suffix,
                                bundle.title
                            ),
                            trailingContent = {
                                TextButton(
                                    onClick = {
                                        val requiredPatches = buildSet {
                                            appliedSelection?.get(bundle.uid).orEmpty().forEach { add(it) }
                                            bundle.patchInfos.forEach { add(it.name) }
                                            bundle.fallbackNames.forEach { add(it) }
                                        }.map { it.trim() }
                                            .filter { it.isNotBlank() }
                                            .map { it.lowercase(Locale.ROOT) }
                                            .toSet()
                                        missingBundleSelectionUid = null
                                        missingBundleTarget = SavedBundleTarget(
                                            bundleUid = bundle.uid,
                                            bundleName = bundle.title,
                                            requiredPatchesLowercase = requiredPatches
                                        )
                                    }
                                ) {
                                    Text(stringResource(R.string.saved_app_bundle_select_action))
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showLeaveInstallDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveInstallDialog = false },
            title = { Text(stringResource(R.string.patcher_install_in_progress_title)) },
            text = {
                Text(
                    stringResource(R.string.patcher_install_in_progress),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveInstallDialog = false
                        viewModel.cancelOngoingInstall()
                        onBackClick()
                    }
                ) {
                    Text(stringResource(R.string.patcher_install_in_progress_leave))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveInstallDialog = false }) {
                    Text(stringResource(R.string.patcher_install_in_progress_stay))
                }
            }
        )
    }
}

@Composable
fun UninstallDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.unpatch_app)) },
    text = { Text(stringResource(R.string.unpatch_description)) },
    confirmButton = {
        TextButton(
            onClick = {
                onConfirm()
                onDismiss()
            }
        ) {
            Text(stringResource(R.string.ok))
        }
    },
    dismissButton = {
        TextButton(
            onClick = onDismiss
        ) {
            Text(stringResource(R.string.cancel))
        }
    }
)

private data class ExportSavedApkDialogState(
    val directory: Path,
    val fileName: String
)

private data class PendingSavedExportConfirmation(
    val directory: Path,
    val fileName: String
)

@Composable
private fun ExportSavedApkFileNameDialog(
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
