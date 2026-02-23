package app.revanced.manager.ui.screen

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.PostAdd
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.universal.revanced.manager.R
import app.revanced.manager.MainActivity
import app.revanced.manager.data.platform.Filesystem
import app.revanced.manager.ui.component.AppScaffold
import app.revanced.manager.ui.component.AppTopBar
import app.revanced.manager.ui.component.ConfirmDialog
import app.revanced.manager.ui.component.InstallerStatusDialog
import app.revanced.manager.ui.component.haptics.HapticExtendedFloatingActionButton
import app.revanced.manager.ui.component.patches.PathSelectorDialog
import app.revanced.manager.ui.component.patcher.Steps
import app.revanced.manager.ui.model.StepCategory
import app.revanced.manager.ui.model.SelectedApp
import app.revanced.manager.ui.viewmodel.PatcherViewModel
import app.revanced.manager.data.room.apps.installed.InstallType
import app.revanced.manager.util.Options
import app.revanced.manager.util.PatchSelection
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.util.ExportNameFormatter
import app.revanced.manager.util.EventEffect
import app.revanced.manager.util.PatchedAppExportData
import app.revanced.manager.util.isAllowedApkFile
import app.revanced.manager.util.mutableStateSetOf
import app.revanced.manager.util.saver.snapshotStateSetSaver
import app.revanced.manager.util.toast
import org.koin.compose.koinInject
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatcherScreen(
    onBackClick: () -> Unit,
    onBackToDashboard: () -> Unit,
    onReviewSelection: (SelectedApp, PatchSelection, Options, List<String>) -> Unit,
    viewModel: PatcherViewModel
) {
    val context = LocalContext.current
    val prefs: PreferencesManager = koinInject()
    val exportFormat by prefs.patchedAppExportFormat.getAsState()
    val useCustomFilePicker by prefs.useCustomFilePicker.getAsState()
    val autoCollapsePatcherSteps by prefs.autoCollapsePatcherSteps.getAsState()
    val autoExpandRunningSteps by prefs.autoExpandRunningSteps.getAsState()
    val autoExpandRunningStepsExclusive by prefs.autoExpandRunningStepsExclusive.getAsState()
    val useExclusiveAutoExpand = autoExpandRunningSteps && autoExpandRunningStepsExclusive
    val savedAppsEnabled by prefs.enableSavedApps.getAsState()
    val exportMetadata = viewModel.exportMetadata
    val fallbackExportMetadata = remember(viewModel.packageName, viewModel.version) {
        PatchedAppExportData(
            appName = viewModel.packageName,
            packageName = viewModel.packageName,
            appVersion = viewModel.version ?: "unspecified"
        )
    }
    val exportFileName = remember(exportFormat, exportMetadata, fallbackExportMetadata) {
        ExportNameFormatter.format(exportFormat, exportMetadata ?: fallbackExportMetadata)
    }

    val patcherSucceeded by viewModel.patcherSucceeded.observeAsState(null)
    val isMounting = viewModel.activeInstallType == InstallType.MOUNT
    val canInstall by remember { derivedStateOf { patcherSucceeded == true && (viewModel.installedPackageName != null || !viewModel.isInstalling) } }
    var showDismissConfirmationDialog by rememberSaveable { mutableStateOf(false) }
    var showInstallInProgressDialog by rememberSaveable { mutableStateOf(false) }
    var showSavePatchedAppDialog by rememberSaveable { mutableStateOf(false) }
    var leaveToDashboardRequested by rememberSaveable { mutableStateOf(false) }
    var exportInProgress by rememberSaveable { mutableStateOf(false) }
    var showLogActionsDialog by rememberSaveable { mutableStateOf(false) }
    var showLogExportPicker by rememberSaveable { mutableStateOf(false) }
    var logExportInProgress by rememberSaveable { mutableStateOf(false) }
    val fs: Filesystem = koinInject()
    val storageRoots = remember { fs.storageRoots() }
    val (permissionContract, permissionName) = remember { fs.permissionContract() }
    var showExportPicker by rememberSaveable { mutableStateOf(false) }
    var exportFileDialogState by remember { mutableStateOf<ExportApkDialogState?>(null) }
    var pendingExportConfirmation by remember { mutableStateOf<PendingExportConfirmation?>(null) }
    var logExportFileDialogState by remember { mutableStateOf<LogExportDialogState?>(null) }
    var pendingLogExportConfirmation by remember { mutableStateOf<PendingLogExportConfirmation?>(null) }
    val logFileName = remember(viewModel.packageName) {
        val suffix = viewModel.packageName?.takeIf { it.isNotBlank() } ?: "patch"
        "patcher-log-$suffix.txt"
    }
    val permissionLauncher =
        rememberLauncherForActivityResult(permissionContract) { granted ->
            if (granted) {
                showExportPicker = true
            }
        }
    val exportDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri ->
        viewModel.export(uri)
        showExportPicker = false
    }
    val logExportDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        viewModel.exportLogsToUri(context, uri)
        showLogExportPicker = false
    }
    fun openExportPicker() {
        if (useCustomFilePicker) {
            if (fs.hasStoragePermission()) {
                showExportPicker = true
            } else {
                permissionLauncher.launch(permissionName)
            }
        } else {
            exportDocumentLauncher.launch(exportFileName)
        }
    }

    LaunchedEffect(useCustomFilePicker) {
        if (!useCustomFilePicker) {
            showExportPicker = false
            showLogExportPicker = false
            exportFileDialogState = null
            pendingExportConfirmation = null
            logExportFileDialogState = null
            pendingLogExportConfirmation = null
        }
    }

    fun leaveCurrentScreen() {
        viewModel.suppressInstallProgressToasts()
        viewModel.onBack()
        if (leaveToDashboardRequested) {
            leaveToDashboardRequested = false
            onBackToDashboard()
        } else {
            onBackClick()
        }
    }

    fun requestLeave(toDashboard: Boolean) = when {
        patcherSucceeded == null -> {
            leaveToDashboardRequested = toDashboard
            showDismissConfirmationDialog = true
        }
        viewModel.isInstalling -> {
            leaveToDashboardRequested = toDashboard
            showInstallInProgressDialog = true
        }
        patcherSucceeded == true &&
            viewModel.installedPackageName == null &&
            !viewModel.hasSavedPatchedApp &&
            savedAppsEnabled -> {
            leaveToDashboardRequested = toDashboard
            showSavePatchedAppDialog = true
        }
        else -> {
            leaveToDashboardRequested = toDashboard
            leaveCurrentScreen()
        }
    }

    fun onPageBack() = requestLeave(toDashboard = false)
    fun onPageBackToDashboard() = requestLeave(toDashboard = true)

    BackHandler(onBack = ::onPageBack)

    val hostActivity = LocalContext.current as? MainActivity
    DisposableEffect(hostActivity) {
        hostActivity?.setOnSystemBackLongPress(::onPageBackToDashboard)
        onDispose {
            hostActivity?.setOnSystemBackLongPress(null)
        }
    }

    val steps by remember {
        derivedStateOf {
            viewModel.steps.groupBy { it.category }
        }
    }

    if (patcherSucceeded == null) {
        DisposableEffect(Unit) {
            val window = (context as Activity).window
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    if (showDismissConfirmationDialog) {
        ConfirmDialog(
            onDismiss = { showDismissConfirmationDialog = false },
            onConfirm = {
                showDismissConfirmationDialog = false
                leaveCurrentScreen()
            },
            title = stringResource(R.string.patcher_stop_confirm_title),
            description = stringResource(R.string.patcher_stop_confirm_description),
            icon = Icons.Outlined.Cancel
        )
    }

    if (showInstallInProgressDialog) {
        AlertDialog(
            onDismissRequest = { showInstallInProgressDialog = false },
            icon = { Icon(Icons.Outlined.FileDownload, null) },
            title = {
                Text(
                    stringResource(
                        if (isMounting) R.string.patcher_mount_in_progress_title else R.string.patcher_install_in_progress_title
                    )
                )
            },
            text = {
                Text(
                    text = stringResource(
                        if (isMounting) R.string.patcher_mount_in_progress else R.string.patcher_install_in_progress
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showInstallInProgressDialog = false
                        viewModel.suppressInstallProgressToasts()
                        leaveCurrentScreen()
                    }
                ) {
                    Text(stringResource(R.string.patcher_install_in_progress_leave))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showInstallInProgressDialog = false }
                ) {
                    Text(stringResource(R.string.patcher_install_in_progress_stay))
                }
            }
        )
    }

    if (showSavePatchedAppDialog) {
        SavePatchedAppDialog(
            onDismiss = { showSavePatchedAppDialog = false },
            onLeave = {
                showSavePatchedAppDialog = false
                leaveCurrentScreen()
            },
            onSave = {
                viewModel.savePatchedAppForLater(onResult = { success ->
                    if (success) {
                        showSavePatchedAppDialog = false
                        leaveCurrentScreen()
                    }
                })
            }
        )
    }

    if (showLogActionsDialog) {
        PatchLogActionsDialog(
            onDismiss = { showLogActionsDialog = false },
            onCopy = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                if (clipboard != null) {
                    val content = viewModel.getLogContent(context)
                    clipboard.setPrimaryClip(ClipData.newPlainText("Patch log", content))
                    context.toast(context.getString(R.string.toast_copied_to_clipboard))
                }
                showLogActionsDialog = false
            },
            onExport = {
                showLogActionsDialog = false
                showLogExportPicker = true
            }
        )
    }

    if (showExportPicker && useCustomFilePicker) {
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
                exportFileDialogState = ExportApkDialogState(directory, exportFileName)
            }
        )
    }
    if (showLogExportPicker && useCustomFilePicker) {
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                if (path == null) {
                    showLogExportPicker = false
                }
            },
            fileFilter = { false },
            allowDirectorySelection = true,
            fileTypeLabel = ".txt",
            confirmButtonText = stringResource(R.string.save),
            onConfirm = { directory ->
                logExportFileDialogState = LogExportDialogState(directory, logFileName)
            }
        )
    }
    LaunchedEffect(showExportPicker, useCustomFilePicker, exportFileName) {
        if (showExportPicker && !useCustomFilePicker) {
            exportDocumentLauncher.launch(exportFileName)
        }
    }
    LaunchedEffect(showLogExportPicker, useCustomFilePicker, logFileName) {
        if (showLogExportPicker && !useCustomFilePicker) {
            logExportDocumentLauncher.launch(logFileName)
        }
    }
    logExportFileDialogState?.let { state ->
        ExportLogFileNameDialog(
            initialName = state.fileName,
            onDismiss = { logExportFileDialogState = null },
            onConfirm = { fileName ->
                val trimmedName = fileName.trim()
                if (trimmedName.isBlank()) return@ExportLogFileNameDialog
                logExportFileDialogState = null
                val target = state.directory.resolve(trimmedName)
                if (Files.exists(target)) {
                    pendingLogExportConfirmation = PendingLogExportConfirmation(
                        directory = state.directory,
                        fileName = trimmedName
                    )
                } else {
                    logExportInProgress = true
                    viewModel.exportLogsToPath(context, target) { success ->
                        logExportInProgress = false
                        if (success) {
                            showLogExportPicker = false
                        }
                    }
                }
            }
        )
    }
    pendingLogExportConfirmation?.let { state ->
        ConfirmDialog(
            onDismiss = {
                pendingLogExportConfirmation = null
                logExportFileDialogState = LogExportDialogState(state.directory, state.fileName)
            },
            onConfirm = {
                pendingLogExportConfirmation = null
                logExportInProgress = true
                viewModel.exportLogsToPath(context, state.directory.resolve(state.fileName)) { success ->
                    logExportInProgress = false
                    if (success) {
                        showLogExportPicker = false
                    }
                }
            },
            title = stringResource(R.string.export_overwrite_title),
            description = stringResource(R.string.export_overwrite_description, state.fileName),
            icon = Icons.Outlined.WarningAmber
        )
    }
    if (logExportInProgress) {
        AlertDialog(
            onDismissRequest = {},
            icon = {
                Icon(
                    Icons.Outlined.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    stringResource(R.string.save_logs),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(R.string.patcher_log_exporting),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(999.dp))
                    )
                }
            },
            confirmButton = {},
            dismissButton = {},
            shape = RoundedCornerShape(28.dp)
        )
    }
    exportFileDialogState?.let { state ->
        ExportApkFileNameDialog(
            initialName = state.fileName,
            onDismiss = { exportFileDialogState = null },
            onConfirm = { fileName ->
                val trimmedName = fileName.trim()
                if (trimmedName.isBlank()) return@ExportApkFileNameDialog
                exportFileDialogState = null
                val target = state.directory.resolve(trimmedName)
                if (Files.exists(target)) {
                    pendingExportConfirmation = PendingExportConfirmation(
                        directory = state.directory,
                        fileName = trimmedName
                    )
                } else {
                    exportInProgress = true
                    viewModel.exportToPath(target) { success ->
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
                exportFileDialogState = ExportApkDialogState(state.directory, state.fileName)
            },
            onConfirm = {
                pendingExportConfirmation = null
                exportInProgress = true
                viewModel.exportToPath(state.directory.resolve(state.fileName)) { success ->
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
            icon = {
                Icon(
                    Icons.Outlined.Save,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    stringResource(R.string.save_apk),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(R.string.patcher_step_group_saving),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(999.dp))
                    )
                }
            },
            confirmButton = {},
            dismissButton = {},
            shape = RoundedCornerShape(28.dp)
        )
    }

    viewModel.packageInstallerStatus?.let {
        if (!viewModel.shouldSuppressPackageInstallerDialog()) {
            InstallerStatusDialog(it, viewModel, viewModel::dismissPackageInstallerDialog)
        } else {
            viewModel.dismissPackageInstallerDialog()
        }
    }

    viewModel.signatureMismatchPackage?.let {
        AlertDialog(
            onDismissRequest = viewModel::dismissSignatureMismatchPrompt,
            title = { Text(stringResource(R.string.installation_signature_mismatch_dialog_title)) },
            text = { Text(stringResource(R.string.installation_signature_mismatch_description)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmSignatureMismatchInstall) {
                    Text(stringResource(R.string.installation_signature_mismatch_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissSignatureMismatchPrompt) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (viewModel.keystoreMissingDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissKeystoreMissingDialog,
            icon = { Icon(Icons.Outlined.WarningAmber, null) },
            title = { Text(stringResource(R.string.keystore_missing_dialog_title)) },
            text = {
                Text(
                    text = stringResource(R.string.keystore_missing_dialog_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissKeystoreMissingDialog) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {}
        )
    }

    viewModel.fallbackInstallPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = viewModel::dismissFallbackInstallPrompt,
            title = { Text(stringResource(R.string.installer_fallback_prompt_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.installer_fallback_prompt_failure_label),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = prompt.failureMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = stringResource(
                            R.string.installer_fallback_prompt_fallback_label,
                            prompt.fallbackLabel
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmFallbackInstallPrompt) {
                    Text(stringResource(R.string.installer_use_fallback))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissFallbackInstallPrompt) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    viewModel.memoryAdjustmentDialog?.let { state ->
        val message = if (state.adjusted) {
            stringResource(
                R.string.patcher_memory_adjustment_message_reduced,
                state.previousLimit,
                state.newLimit
            )
        } else {
            stringResource(
                R.string.patcher_memory_adjustment_message_no_change,
                state.previousLimit
            )
        }
        AlertDialog(
            onDismissRequest = viewModel::dismissMemoryAdjustmentDialog,
            title = { Text(stringResource(R.string.patcher_memory_adjustment_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::retryAfterMemoryAdjustment) {
                    Text(stringResource(R.string.patcher_memory_adjustment_retry))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissMemoryAdjustmentDialog) {
                    Text(stringResource(R.string.patcher_memory_adjustment_dismiss))
                }
            }
        )
    }

    viewModel.missingPatchWarning?.let { state ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.patcher_missing_patch_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(
                            R.string.patcher_preflight_missing_patch_message,
                            buildString {
                                append("• ")
                                append(state.patchNames.joinToString(separator = "\n• "))
                            }
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::removeMissingPatchesAndStart) {
                    Text(stringResource(R.string.patcher_preflight_missing_patch_remove))
                }
            },
            dismissButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = viewModel::proceedAfterMissingPatchWarning) {
                        Text(stringResource(R.string.patcher_preflight_missing_patch_proceed))
                    }
                    TextButton(
                        onClick = {
                            val selection = viewModel.currentSelectionSnapshot()
                            val options = viewModel.currentOptionsSnapshot()
                            val patches = state.patchNames
                            viewModel.dismissMissingPatchWarning()
                            onReviewSelection(
                                viewModel.currentSelectedApp,
                                selection,
                                options,
                                patches
                            )
                            onBackClick()
                        }
                    ) {
                        Text(stringResource(R.string.patcher_missing_patch_review))
                    }
                }
            }
        )
    }
    viewModel.installFailureMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissInstallFailureMessage,
            title = {
                Text(
                    stringResource(
                        if (viewModel.lastInstallType == InstallType.MOUNT) R.string.mount_app_fail_title else R.string.install_app_fail_title
                    )
                )
            },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissInstallFailureMessage) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    viewModel.installStatus?.let { status ->
        when (status) {
            PatcherViewModel.InstallCompletionStatus.InProgress -> {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            is PatcherViewModel.InstallCompletionStatus.Success -> {
                AlertDialog(
                    onDismissRequest = viewModel::clearInstallStatus,
                    confirmButton = {
                        TextButton(onClick = viewModel::clearInstallStatus) {
                            Text(stringResource(R.string.ok))
                        }
                    },
                    title = { Text(stringResource(R.string.install_app_success)) },
                    text = {
                        status.packageName?.let { Text(text = it) }
                    }
                )
            }

            is PatcherViewModel.InstallCompletionStatus.Failure -> {
                if (viewModel.shouldSuppressInstallFailureDialog()) {
                    viewModel.dismissInstallFailureMessage()
                    viewModel.clearInstallStatus()
                    return@let
                }
                if (!viewModel.shouldSuppressInstallFailureDialog() && viewModel.installFailureMessage == null) {
                    AlertDialog(
                        onDismissRequest = viewModel::dismissInstallFailureMessage,
                        title = {
                            Text(
                                stringResource(
                                    if (viewModel.lastInstallType == InstallType.MOUNT) R.string.mount_app_fail_title else R.string.install_app_fail_title
                                )
                            )
                        },
                        text = { Text(status.message) },
                        confirmButton = {
                            TextButton(onClick = viewModel::dismissInstallFailureMessage) {
                                Text(stringResource(R.string.ok))
                            }
                        }
                    )
                }
            }
        }
    }

    val activityLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = viewModel::handleActivityResult
    )
    EventEffect(flow = viewModel.launchActivityFlow) { intent ->
        activityLauncher.launch(intent)
    }

    viewModel.activityPromptDialog?.let { title ->
        AlertDialog(
            onDismissRequest = viewModel::rejectInteraction,
            confirmButton = {
                TextButton(
                    onClick = viewModel::allowInteraction
                ) {
                    Text(stringResource(R.string.continue_))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::rejectInteraction
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { Text(title) },
            text = {
                Text(stringResource(R.string.plugin_activity_dialog_body))
            }
        )
    }

    AppScaffold(
        topBar = { scrollBehavior ->
            AppTopBar(
                title = stringResource(R.string.patcher),
                scrollBehavior = scrollBehavior,
                onBackClick = ::onPageBack,
                onBackLongClick = ::onPageBackToDashboard
            )
        },
        bottomBar = {
            BottomAppBar(
                actions = {
                    IconButton(
                        onClick = ::openExportPicker,
                        enabled = patcherSucceeded == true
                    ) {
                    Icon(Icons.Outlined.Save, stringResource(id = R.string.save_apk))
                }
                IconButton(
                    onClick = { showLogActionsDialog = true },
                    enabled = patcherSucceeded != null
                ) {
                    Icon(Icons.Outlined.PostAdd, stringResource(id = R.string.save_logs))
                }
                },
                floatingActionButton = {
                    AnimatedVisibility(visible = canInstall) {
                        HapticExtendedFloatingActionButton(
                            text = {
                                Text(
                                    stringResource(if (viewModel.installedPackageName == null) R.string.install_app else R.string.open_app)
                                )
                            },
                            icon = {
                                viewModel.installedPackageName?.let {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.OpenInNew,
                                        stringResource(R.string.open_app)
                                    )
                                } ?: Icon(
                                    Icons.Outlined.FileDownload,
                                    stringResource(R.string.install_app)
                                )
                            },
                            onClick = {
                                if (viewModel.installedPackageName == null) {
                                    viewModel.install()
                                } else {
                                    viewModel.open()
                                }
                            }
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            val expandedCategories = rememberSaveable(
                saver = snapshotStateSetSaver()
            ) {
                mutableStateSetOf<StepCategory>()
            }

            LinearProgressIndicator(
                progress = { viewModel.progress },
                modifier = Modifier.fillMaxWidth()
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                items(
                    items = steps.toList(),
                    key = { it.first }
                ) { (category, steps) ->
                    Steps(
                        category = category,
                        steps = steps,
                        subStepsById = viewModel.stepSubSteps,
                        isExpanded = expandedCategories.contains(category),
                        autoExpandRunning = autoExpandRunningSteps,
                        autoExpandRunningMainOnly = useExclusiveAutoExpand,
                        onExpand = {
                            if (useExclusiveAutoExpand) {
                                expandedCategories.clear()
                            }
                            expandedCategories.add(category)
                        },
                        onClick = {
                            if (expandedCategories.contains(category)) {
                                expandedCategories.remove(category)
                            } else {
                                expandedCategories.add(category)
                            }
                        },
                        autoCollapseCompleted = autoCollapsePatcherSteps
                    )
                }
            }
        }
    }
}

@Composable
private fun SavePatchedAppDialog(
    onDismiss: () -> Unit,
    onLeave: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Save, null) },
        title = { Text(stringResource(R.string.save_patched_app_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = stringResource(R.string.save_patched_app_dialog_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Save,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.save_patched_app_dialog_hint_save),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ExitToApp,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.save_patched_app_dialog_hint_leave),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FilledTonalButton(
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.save_patched_app_dialog_save))
                }
                FilledTonalButton(
                    onClick = onLeave,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.save_patched_app_dialog_leave))
                }
                FilledTonalButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.save_patched_app_dialog_cancel))
                }
            }
        },
        dismissButton = {}
    )
}

private data class ExportApkDialogState(
    val directory: Path,
    val fileName: String
)

private data class PendingExportConfirmation(
    val directory: Path,
    val fileName: String
)

private data class LogExportDialogState(
    val directory: Path,
    val fileName: String
)

private data class PendingLogExportConfirmation(
    val directory: Path,
    val fileName: String
)

@Composable
private fun PatchLogActionsDialog(
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onExport: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.PostAdd, null) },
        title = { Text(stringResource(R.string.patcher_log_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.patcher_log_dialog_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onCopy)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.patcher_log_dialog_copy),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(R.string.patcher_log_dialog_copy_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onExport)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.patcher_log_dialog_export),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(R.string.patcher_log_dialog_export_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        dismissButton = {}
    )
}

@Composable
private fun ExportApkFileNameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var fileName by rememberSaveable(initialName) { mutableStateOf(initialName) }
    val trimmedName = fileName.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.save_apk),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        icon = {
            Icon(
                Icons.Outlined.Save,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(
                    onClick = { onConfirm(trimmedName) },
                    enabled = trimmedName.isNotEmpty()
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        },
        dismissButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.file_name),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    placeholder = { Text(stringResource(R.string.dialog_input_placeholder)) },
                    singleLine = true
                )
            }
        }
    )
}

@Composable
private fun ExportLogFileNameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var fileName by rememberSaveable(initialName) { mutableStateOf(initialName) }
    val trimmedName = fileName.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.save_logs),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        icon = {
            Icon(
                Icons.Outlined.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(
                    onClick = { onConfirm(trimmedName) },
                    enabled = trimmedName.isNotEmpty()
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        },
        dismissButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.file_name),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    placeholder = { Text(stringResource(R.string.dialog_input_placeholder)) },
                    singleLine = true
                )
            }
        }
    )
}
