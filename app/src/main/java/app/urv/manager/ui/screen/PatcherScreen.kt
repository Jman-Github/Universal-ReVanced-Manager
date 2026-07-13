package app.urv.manager.ui.screen

import android.os.Build
import android.net.Uri
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.view.WindowManager
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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.PostAdd
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.key
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.universal.revanced.manager.R
import app.urv.manager.data.platform.Filesystem
import app.urv.manager.domain.installer.InstallerManager
import app.urv.manager.ui.component.AppScaffold
import app.urv.manager.ui.component.AppTopBar
import app.urv.manager.ui.component.ConfirmDialog
import app.urv.manager.ui.component.InterceptBackHandler
import app.urv.manager.ui.component.InstallerStatusDialog
import app.urv.manager.ui.component.ProgressPercentageBadge
import app.urv.manager.ui.component.haptics.HapticExtendedFloatingActionButton
import app.urv.manager.ui.component.haptics.HapticFloatingActionButton
import app.urv.manager.ui.component.patches.PathSelectorDialog
import app.urv.manager.ui.component.RememberedCreateDocument
import app.urv.manager.ui.component.toPickerDirectoryUri
import app.urv.manager.ui.component.patcher.InstallerPickerDialog
import app.urv.manager.ui.component.patcher.LegacyAndroidMemoryWarning
import app.urv.manager.ui.component.patcher.PatcherMemoryUsageCard
import app.urv.manager.ui.component.patcher.Steps
import app.urv.manager.ui.model.StepCategory
import app.urv.manager.ui.model.SelectedApp
import app.urv.manager.ui.viewmodel.PatcherViewModel
import app.urv.manager.data.room.apps.installed.InstallType
import app.urv.manager.util.Options
import app.urv.manager.util.PatchSelection
import app.urv.manager.domain.manager.PreferencesManager
import app.urv.manager.util.ExportNameFormatter
import app.urv.manager.util.EventEffect
import app.urv.manager.util.FilenameUtils
import app.urv.manager.util.PatchedAppExportData
import app.urv.manager.util.isAllowedApkFile
import app.urv.manager.util.mutableStateSetOf
import app.urv.manager.util.saver.snapshotStateSetSaver
import app.urv.manager.util.toast
import org.koin.compose.koinInject
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.urv.manager.ui.component.CenteredDialogTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatcherScreen(
    onBackClick: (SelectedApp) -> Unit,
    onBackToDashboard: () -> Unit,
    onReviewSelection: (SelectedApp, PatchSelection, Options, List<String>) -> Unit,
    viewModel: PatcherViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs: PreferencesManager = koinInject()
    val exportFormat by prefs.patchedAppExportFormat.getAsState()
    val useCustomFilePicker by prefs.useCustomFilePicker.getAsState()
    val patchedApkExportDirectory by prefs.patchedApkExportLastDirectory.getAsState()
    val patcherLogExportDirectory by prefs.patcherLogExportLastDirectory.getAsState()
    val pickerScope = rememberCoroutineScope()
    val autoCollapsePatcherSteps by prefs.autoCollapsePatcherSteps.getAsState()
    val showPatcherMemoryUsageGraph by prefs.showPatcherMemoryUsageGraph.getAsState()
    val autoExpandRunningSteps by prefs.autoExpandRunningSteps.getAsState()
    val autoExpandRunningStepsExclusive by prefs.autoExpandRunningStepsExclusive.getAsState()
    val chooseInstallerPerInstall by prefs.chooseInstallerPerInstall.getAsState()
    val primaryInstallerValue by prefs.installerPrimary.getAsState()
    val fallbackInstallerValue by prefs.installerFallback.getAsState()
    val continueOnPatchError by prefs.continueOnPatchError.getAsState()
    val useExclusiveAutoExpand = autoExpandRunningSteps && autoExpandRunningStepsExclusive
    val savedAppsEnabled by prefs.enableSavedApps.getAsState()
    val installerManager: InstallerManager = koinInject()
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
    val isPatchingActive by viewModel.isPatchingActive.observeAsState(false)
    val isMounting = viewModel.activeInstallType == InstallType.MOUNT
    val canInstall by remember { derivedStateOf { patcherSucceeded == true && (viewModel.installedPackageName != null || !viewModel.isInstalling) } }
    val primaryInstallerIsMount = remember(primaryInstallerValue) {
        installerManager.parseToken(primaryInstallerValue) is InstallerManager.Token.AutoSaved
    }
    val fallbackInstallerIsMount = remember(fallbackInstallerValue) {
        installerManager.parseToken(fallbackInstallerValue) is InstallerManager.Token.AutoSaved
    }
    var mountInstallerAvailable by remember { mutableStateOf(false) }
    LaunchedEffect(chooseInstallerPerInstall, primaryInstallerIsMount, fallbackInstallerIsMount) {
        mountInstallerAvailable = if (
            !chooseInstallerPerInstall && (primaryInstallerIsMount || fallbackInstallerIsMount)
        ) {
            withContext(Dispatchers.IO) {
                installerManager.describeEntry(
                    InstallerManager.Token.AutoSaved,
                    InstallerManager.InstallTarget.PATCHER
                )?.availability?.available == true
            }
        } else {
            false
        }
    }
    val showMountFallbackMenu =
        !chooseInstallerPerInstall &&
            viewModel.installedPackageName == null &&
            !viewModel.basePackageInstalled &&
            fallbackInstallerIsMount &&
            !primaryInstallerIsMount &&
            mountInstallerAvailable
    var showDismissConfirmationDialog by rememberSaveable { mutableStateOf(false) }
    var showInstallInProgressDialog by rememberSaveable { mutableStateOf(false) }
    var showSavePatchedAppDialog by rememberSaveable { mutableStateOf(false) }
    var leaveToDashboardRequested by rememberSaveable { mutableStateOf(false) }
    var exportInProgress by rememberSaveable { mutableStateOf(false) }
    var showLogActionsDialog by rememberSaveable { mutableStateOf(false) }
    var showLogExportPicker by rememberSaveable { mutableStateOf(false) }
    var logExportInProgress by rememberSaveable { mutableStateOf(false) }
    var showInstallerPicker by rememberSaveable { mutableStateOf(false) }
    var showInstallDropdown by rememberSaveable { mutableStateOf(false) }
    var pendingLogExportFileName by rememberSaveable { mutableStateOf<String?>(null) }
    val fs: Filesystem = koinInject()
    val storageRoots = remember { fs.storageRoots() }
    val (permissionContract, permissionName) = remember { fs.permissionContract() }
    var showExportPicker by rememberSaveable { mutableStateOf(false) }
    var exportFileDialogState by remember { mutableStateOf<ExportApkDialogState?>(null) }
    var pendingExportConfirmation by remember { mutableStateOf<PendingExportConfirmation?>(null) }
    var logExportFileDialogState by remember { mutableStateOf<LogExportDialogState?>(null) }
    var pendingLogExportConfirmation by remember { mutableStateOf<PendingLogExportConfirmation?>(null) }
    val permissionLauncher =
        rememberLauncherForActivityResult(permissionContract) { granted ->
            if (granted) {
                showExportPicker = true
            }
        }
    val exportDocumentLauncher = rememberLauncherForActivityResult(
        contract = RememberedCreateDocument("application/vnd.android.package-archive") {
            patchedApkExportDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        uri?.let {
            pickerScope.launch {
                prefs.patchedApkExportLastDirectory.update(it.toPickerDirectoryUri().toString())
            }
        }
        viewModel.export(uri)
        showExportPicker = false
    }
    val logExportDocumentLauncher = rememberLauncherForActivityResult(
        contract = RememberedCreateDocument("text/plain") {
            patcherLogExportDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        uri?.let {
            pickerScope.launch {
                prefs.patcherLogExportLastDirectory.update(it.toPickerDirectoryUri().toString())
            }
        }
        viewModel.exportLogsToUri(context, uri)
        showLogExportPicker = false
        pendingLogExportFileName = null
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
            pendingLogExportFileName = null
        }
    }

    fun leaveCurrentScreen() {
        viewModel.suppressInstallProgressToasts()
        viewModel.onBack(cleanupLocalInput = leaveToDashboardRequested)
        if (leaveToDashboardRequested) {
            leaveToDashboardRequested = false
            onBackToDashboard()
        } else {
            onBackClick(viewModel.currentSelectedApp)
        }
    }

    fun requestLeave(toDashboard: Boolean) = when {
        isPatchingActive -> {
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

    InterceptBackHandler(onBack = ::onPageBack)

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onHostResumed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val steps by remember {
        derivedStateOf {
            viewModel.steps.groupBy { it.category }
        }
    }

    if (isPatchingActive) {
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
                    context.toast(context.getString(R.string.patcher_log_copy_success))
                }
                showLogActionsDialog = false
            },
            onExport = {
                showLogActionsDialog = false
                pendingLogExportFileName = FilenameUtils.timestampedLogFileName("patcher")
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
            },
            lastDirectoryPreference = prefs.patchedApkExportLastDirectory
        )
    }
    if (showLogExportPicker && useCustomFilePicker) {
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                if (path == null) {
                    showLogExportPicker = false
                    pendingLogExportFileName = null
                }
            },
            fileFilter = { false },
            allowDirectorySelection = true,
            fileTypeLabel = ".txt",
            confirmButtonText = stringResource(R.string.save),
            onConfirm = { directory ->
                logExportFileDialogState = LogExportDialogState(
                    directory,
                    pendingLogExportFileName ?: FilenameUtils.timestampedLogFileName("patcher")
                )
            },
            lastDirectoryPreference = prefs.patcherLogExportLastDirectory
        )
    }
    LaunchedEffect(showExportPicker, useCustomFilePicker, exportFileName) {
        if (showExportPicker && !useCustomFilePicker) {
            exportDocumentLauncher.launch(exportFileName)
        }
    }
    LaunchedEffect(showLogExportPicker, useCustomFilePicker, pendingLogExportFileName) {
        if (showLogExportPicker && !useCustomFilePicker) {
            val logFileName = pendingLogExportFileName
                ?: FilenameUtils.timestampedLogFileName("patcher")
            logExportDocumentLauncher.launch(logFileName)
        }
    }
    logExportFileDialogState?.let { state ->
        ExportLogFileNameDialog(
            initialName = state.fileName,
            onDismiss = {
                logExportFileDialogState = null
                pendingLogExportFileName = null
            },
            onConfirm = { fileName ->
                val trimmedName = fileName.trim()
                if (trimmedName.isBlank()) return@ExportLogFileNameDialog
                logExportFileDialogState = null
                pendingLogExportFileName = null
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
            title = { CenteredDialogTitle(stringResource(R.string.installation_signature_mismatch_dialog_title)) },
            text = {
                Text(
                    text = stringResource(R.string.installation_signature_mismatch_description),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
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
            title = { CenteredDialogTitle(stringResource(R.string.keystore_missing_dialog_title)) },
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
            title = { CenteredDialogTitle(stringResource(R.string.installer_fallback_prompt_title)) },
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

    viewModel.missingPatchWarning?.let { state ->
        AlertDialog(
            onDismissRequest = {},
            title = { CenteredDialogTitle(stringResource(R.string.patcher_missing_patch_title)) },
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
                            onBackClick(viewModel.currentSelectedApp)
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
                CenteredDialogTitle(
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
                    title = {
                        Text(
                            text = stringResource(R.string.install_app_success),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    },
                    text = {
                        status.packageName?.let {
                            Text(
                                text = it,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                        }
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
                            CenteredDialogTitle(
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

    viewModel.activityPromptDialog?.let { dialog ->
        key(dialog.requestId) {
            AlertDialog(
                onDismissRequest = { viewModel.rejectInteraction(dialog.requestId) },
                confirmButton = {
                    TextButton(
                        onClick = { viewModel.allowInteraction(dialog.requestId) }
                    ) {
                        Text(stringResource(R.string.continue_))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { viewModel.rejectInteraction(dialog.requestId) }
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                },
                title = { CenteredDialogTitle(dialog.title) },
                text = {
                    Text(stringResource(R.string.plugin_activity_dialog_body))
                }
            )
        }
    }

    if (showInstallerPicker) {
        InstallerPickerDialog(
            title = stringResource(R.string.installer_choose_for_this_install_title),
            options = installerManager.listEntries(
                target = InstallerManager.InstallTarget.PATCHER,
                includeNone = false
            ),
            onDismiss = { showInstallerPicker = false },
            onConfirm = viewModel::installWithToken,
            onOpenShizuku = installerManager::openShizukuApp
        )
    }

    AppScaffold(
        topBar = { scrollBehavior ->
            AppTopBar(
                title = stringResource(R.string.patcher),
                scrollBehavior = scrollBehavior,
                onBackClick = ::onPageBack,
                onBackLongClick = ::onPageBackToDashboard,
                actions = {
                    ProgressPercentageBadge(progress = viewModel.progress)
                }
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
                IconButton(
                    onClick = ::onPageBackToDashboard,
                    enabled = canInstall
                ) {
                    Icon(Icons.Outlined.Check, stringResource(R.string.done))
                }
                },
                floatingActionButton = {
                    AnimatedVisibility(visible = canInstall) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    HapticExtendedFloatingActionButton(
                                        text = {
                                            Text(
                                                stringResource(
                                                    when {
                                                        viewModel.installedPackageName != null -> R.string.open_app
                                                        !chooseInstallerPerInstall && primaryInstallerIsMount &&
                                                            mountInstallerAvailable && !viewModel.basePackageInstalled ->
                                                            R.string.install_base_and_mount
                                                        else -> R.string.install_app
                                                    }
                                                )
                                            )
                                        },
                                        icon = {
                                            when {
                                                viewModel.installedPackageName != null -> Icon(
                                                    Icons.AutoMirrored.Outlined.OpenInNew,
                                                    stringResource(R.string.open_app)
                                                )
                                                !chooseInstallerPerInstall && primaryInstallerIsMount &&
                                                    mountInstallerAvailable && !viewModel.basePackageInstalled -> Icon(
                                                    Icons.Outlined.Layers,
                                                    stringResource(R.string.install_base_and_mount)
                                                )
                                                else -> Icon(
                                                    Icons.Outlined.FileDownload,
                                                    stringResource(R.string.install_app)
                                                )
                                            }
                                        },
                                        onClick = {
                                            when {
                                                viewModel.installedPackageName != null -> viewModel.open()
                                                chooseInstallerPerInstall -> showInstallerPicker = true
                                                else -> viewModel.install()
                                            }
                                        },
                                        shape = if (showMountFallbackMenu) {
                                            RoundedCornerShape(
                                                topStart = 16.dp,
                                                bottomStart = 16.dp,
                                                topEnd = 0.dp,
                                                bottomEnd = 0.dp
                                            )
                                        } else {
                                            RoundedCornerShape(16.dp)
                                        }
                                    )
                                    if (showMountFallbackMenu) {
                                        HapticFloatingActionButton(
                                            onClick = { showInstallDropdown = true },
                                            modifier = Modifier.size(56.dp),
                                            shape = RoundedCornerShape(
                                                topStart = 0.dp,
                                                bottomStart = 0.dp,
                                                topEnd = 16.dp,
                                                bottomEnd = 16.dp
                                            )
                                        ) {
                                            Icon(
                                                Icons.Outlined.ArrowDropDown,
                                                contentDescription = stringResource(R.string.install_base_and_mount),
                                                modifier = Modifier.size(30.dp)
                                            )
                                        }
                                    }
                                }
                                DropdownMenu(
                                    expanded = showInstallDropdown && showMountFallbackMenu,
                                    onDismissRequest = { showInstallDropdown = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.install_base_and_mount)) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Outlined.Layers,
                                                contentDescription = null
                                            )
                                        },
                                        onClick = {
                                            showInstallDropdown = false
                                            viewModel.installWithToken(InstallerManager.Token.AutoSaved)
                                        }
                                    )
                                }
                            }
                        }
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
                modifier = Modifier.fillMaxWidth(),
                drawStopIndicator = {}
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                if (showPatcherMemoryUsageGraph && viewModel.patcherMemoryUsageSamples.isNotEmpty()) {
                    item(key = "memory-usage") {
                        PatcherMemoryUsageCard(samples = viewModel.patcherMemoryUsageSamples)
                    }
                }
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
                        continueOnPatchError = continueOnPatchError,
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
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                    item(key = "legacy-android-memory-warning") {
                        LegacyAndroidMemoryWarning()
                    }
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
        title = { CenteredDialogTitle(stringResource(R.string.save_patched_app_dialog_title)) },
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
        title = { CenteredDialogTitle(stringResource(R.string.patcher_log_dialog_title)) },
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
