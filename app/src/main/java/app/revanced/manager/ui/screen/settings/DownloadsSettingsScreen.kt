package app.revanced.manager.ui.screen.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.revanced.manager.domain.manager.PreferencesManager
import app.universal.revanced.manager.R
import app.revanced.manager.data.platform.Filesystem
import app.revanced.manager.network.downloader.DownloaderPluginState
import app.revanced.manager.ui.component.AppLabel
import app.revanced.manager.ui.component.AppTopBar
import app.revanced.manager.ui.component.ExceptionViewerDialog
import app.revanced.manager.ui.component.GroupHeader
import app.revanced.manager.ui.component.LazyColumnWithScrollbar
import app.revanced.manager.ui.component.ConfirmDialog
import app.revanced.manager.ui.component.patches.PathSelectorDialog
import app.revanced.manager.ui.component.haptics.HapticCheckbox
import app.revanced.manager.ui.component.settings.BooleanItem
import app.revanced.manager.ui.component.settings.ExpressiveSettingsCard
import app.revanced.manager.ui.component.settings.ExpressiveSettingsDivider
import app.revanced.manager.ui.component.settings.ExpressiveSettingsItem
import app.revanced.manager.ui.component.settings.SettingsSearchHighlight
import app.revanced.manager.ui.model.navigation.Settings
import app.revanced.manager.ui.screen.settings.SettingsSearchState
import app.revanced.manager.ui.viewmodel.DownloadsViewModel
import app.revanced.manager.ui.component.AnnotatedLinkText // From PR #37: https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/37
import app.revanced.manager.util.isAllowedApkFile
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel
import java.security.MessageDigest
import kotlin.text.HexFormat
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalMaterial3Api::class, ExperimentalStdlibApi::class)
@Composable
fun DownloadsSettingsScreen(
    onBackClick: () -> Unit,
    viewModel: DownloadsViewModel = koinViewModel()
) {
    val prefs: PreferencesManager = koinInject()
    val useCustomFilePicker by prefs.useCustomFilePicker.getAsState()
    val downloadedApps by viewModel.downloadedApps.collectAsStateWithLifecycle(emptyList())
    val pluginStates by viewModel.downloaderPluginStates.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val searchTarget by SettingsSearchState.target.collectAsStateWithLifecycle()
    var highlightTarget by rememberSaveable { mutableStateOf<Int?>(null) }
    var showHelpDialog by rememberSaveable { mutableStateOf(false) } // From PR #37: https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/37
    val context = LocalContext.current
    val fs: Filesystem = koinInject()
    val storageRoots = remember { fs.storageRoots() }
    val (permissionContract, permissionName) = remember { fs.permissionContract() }
    var pendingExportState by remember { mutableStateOf<DownloadedAppsExportState?>(null) }
    var activeExportState by remember { mutableStateOf<DownloadedAppsExportState?>(null) }
    var pendingDocumentExportState by remember { mutableStateOf<DownloadedAppsExportState?>(null) }
    var exportFileDialogState by remember { mutableStateOf<DownloadedAppsExportDialogState?>(null) }
    var pendingExportConfirmation by remember { mutableStateOf<PendingDownloadedAppsExportConfirmation?>(null) }
    var exportInProgress by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher =
        rememberLauncherForActivityResult(permissionContract) { granted ->
            if (granted) {
                activeExportState = pendingExportState
            }
            pendingExportState = null
        }
    val exportDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val exportState = pendingDocumentExportState
        pendingDocumentExportState = null
        if (uri != null && exportState != null) {
            viewModel.exportSelectedApps(context, uri, exportState.asArchive)
        }
    }
    fun openExportPicker(state: DownloadedAppsExportState) {
        if (useCustomFilePicker) {
            if (fs.hasStoragePermission()) {
                activeExportState = state
            } else {
                pendingExportState = state
                permissionLauncher.launch(permissionName)
            }
        } else {
            pendingDocumentExportState = state
            exportDocumentLauncher.launch(state.defaultFileName)
        }
    }
    LaunchedEffect(useCustomFilePicker) {
        if (!useCustomFilePicker) {
            activeExportState = null
            pendingExportState = null
            exportFileDialogState = null
            pendingExportConfirmation = null
        }
    }

    LaunchedEffect(searchTarget) {
        val target = searchTarget
        if (target?.destination == Settings.Downloads) {
            highlightTarget = target.targetId
            SettingsSearchState.clear()
        }
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text(stringResource(R.string.plugins_help_title)) },
            text = {
                // From PR #37: https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/37
                AnnotatedLinkText(
                    text = stringResource(R.string.plugins_help_description),
                    linkLabel = stringResource(R.string.here),
                    url = "https://github.com/Jman-Github/Universal-ReVanced-Manager?tab=readme-ov-file#-supported-downloader-plugins",
                )
            },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
    activeExportState?.let { state ->
        if (!useCustomFilePicker) return@let
        val fileFilter = if (state.asArchive) ::isZipFile else ::isAllowedApkFile
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                if (path == null) {
                    activeExportState = null
                    exportFileDialogState = null
                    pendingExportConfirmation = null
                }
            },
            fileFilter = fileFilter,
            allowDirectorySelection = false,
            fileTypeLabel = state.fileTypeLabel,
            confirmButtonText = stringResource(R.string.save),
            onConfirm = { directory ->
                exportFileDialogState =
                    DownloadedAppsExportDialogState(state, directory, state.defaultFileName)
            }
        )
    }
    exportFileDialogState?.let { state ->
        ExportDownloadedAppsFileNameDialog(
            initialName = state.fileName,
            onDismiss = { exportFileDialogState = null },
            onConfirm = { fileName ->
                val trimmedName = fileName.trim()
                if (trimmedName.isBlank()) return@ExportDownloadedAppsFileNameDialog
                exportFileDialogState = null
                val target = state.directory.resolve(trimmedName)
                if (Files.exists(target)) {
                    pendingExportConfirmation = PendingDownloadedAppsExportConfirmation(
                        state.exportState,
                        state.directory,
                        trimmedName
                    )
                } else {
                    exportInProgress = true
                    viewModel.exportSelectedAppsToPath(context, target, state.exportState.asArchive) { success ->
                        exportInProgress = false
                        if (success) {
                            activeExportState = null
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
                exportFileDialogState =
                    DownloadedAppsExportDialogState(state.exportState, state.directory, state.fileName)
            },
            onConfirm = {
                pendingExportConfirmation = null
                exportInProgress = true
                viewModel.exportSelectedAppsToPath(
                    context,
                    state.directory.resolve(state.fileName),
                    state.exportState.asArchive
                ) { success ->
                    exportInProgress = false
                    if (success) {
                        activeExportState = null
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
                    stringResource(R.string.downloaded_apps_export),
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

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.downloads),
                scrollBehavior = scrollBehavior,
                onBackClick = onBackClick,
                onHelpClick = { showHelpDialog = true }, // From PR #37: https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/37
                actions = {
                    if (viewModel.appSelection.isNotEmpty()) {
                        IconButton(onClick = {
                            val selection = viewModel.appSelection.toList()
                            if (selection.size == 1) {
                                val app = selection.first()
                                val fileName =
                                    "${app.packageName}_${app.version}".replace('/', '_') + ".apk"
                                openExportPicker(
                                    DownloadedAppsExportState(
                                        asArchive = false,
                                        defaultFileName = fileName,
                                        fileTypeLabel = ".apk"
                                    )
                                )
                            } else {
                                val fileName = "downloaded-apps-${System.currentTimeMillis()}.zip"
                                openExportPicker(
                                    DownloadedAppsExportState(
                                        asArchive = true,
                                        defaultFileName = fileName,
                                        fileTypeLabel = ".zip"
                                    )
                                )
                            }
                        }) {
                            Icon(Icons.Outlined.Save, stringResource(R.string.downloaded_apps_export))
                        }
                        IconButton(onClick = { viewModel.deleteApps() }) {
                            Icon(Icons.Default.Delete, stringResource(R.string.delete))
                        }
                    }
                }
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        PullToRefreshBox(
            onRefresh = viewModel::refreshPlugins,
            isRefreshing = viewModel.isRefreshingPlugins,
            modifier = Modifier.padding(paddingValues)
        ) {
            LazyColumnWithScrollbar(
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    GroupHeader(stringResource(R.string.download_behavior_section))
                }
                item {
                    ExpressiveSettingsCard(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        SettingsSearchHighlight(
                            targetKey = R.string.downloader_auto_save_title,
                            activeKey = highlightTarget,
                            onHighlightComplete = { highlightTarget = null }
                        ) { highlightModifier ->
                            BooleanItem(
                                modifier = highlightModifier,
                                preference = prefs.autoSaveDownloaderApks,
                                headline = R.string.downloader_auto_save_title,
                                description = R.string.downloader_auto_save_description
                            )
                        }
                    }
                }
                item {
                    SettingsSearchHighlight(
                        targetKey = R.string.downloader_plugins,
                        activeKey = highlightTarget,
                        onHighlightComplete = { highlightTarget = null }
                    ) { highlightModifier ->
                        GroupHeader(
                            stringResource(R.string.downloader_plugins),
                            modifier = highlightModifier
                        )
                    }
                }
                pluginStates.forEach { (packageName, state) ->
                    item(key = packageName) {
                        var dialogType by remember { mutableStateOf<PluginDialogType?>(null) }
                        var showExceptionViewer by remember { mutableStateOf(false) }

                        val packageInfo =
                            remember(packageName) {
                                viewModel.pm.getPackageInfo(packageName)
                            } ?: return@item

                        val signature = remember(packageName) {
                            runCatching {
                                val androidSignature = viewModel.pm.getSignature(packageName)
                                val hash = MessageDigest.getInstance("SHA-256")
                                    .digest(androidSignature.toByteArray())
                                hash.toHexString(format = HexFormat.UpperCase)
                            }.getOrNull()
                        }
                        val appName = remember(packageName) {
                            packageInfo.applicationInfo?.loadLabel(context.packageManager)
                                ?.toString()
                                ?: packageName
                        }

                        when (dialogType) {
                            PluginDialogType.Trust -> {
                                PluginActionDialog(
                                    title = R.string.downloader_plugin_trust_dialog_title,
                                    body = stringResource(
                                        R.string.downloader_plugin_trust_dialog_body
                                    ),
                                    pluginName = appName,
                                    signature = signature.orEmpty(),
                                    primaryLabel = R.string.continue_,
                                    onPrimary = {
                                        viewModel.trustPlugin(packageName)
                                        dialogType = null
                                    },
                                    onUninstall = {
                                        dialogType = PluginDialogType.Uninstall
                                    },
                                    onDismiss = { dialogType = null }
                                )
                            }

                            PluginDialogType.Revoke -> {
                                PluginActionDialog(
                                    title = R.string.downloader_plugin_revoke_trust_dialog_title,
                                    body = stringResource(
                                        R.string.downloader_plugin_trust_dialog_body
                                    ),
                                    pluginName = appName,
                                    signature = signature.orEmpty(),
                                    primaryLabel = R.string.continue_,
                                    onPrimary = {
                                        viewModel.revokePluginTrust(packageName)
                                        dialogType = null
                                    },
                                    onUninstall = {
                                        dialogType = PluginDialogType.Uninstall
                                    },
                                    onDismiss = { dialogType = null }
                                )
                            }

                            PluginDialogType.Failed -> {
                                PluginFailedDialog(
                                    packageName = packageName,
                                    onDismiss = { dialogType = null },
                                    onViewDetails = {
                                        dialogType = null
                                        showExceptionViewer = true
                                    },
                                    onUninstall = { dialogType = PluginDialogType.Uninstall }
                                )
                            }

                            PluginDialogType.Uninstall -> {
                                ConfirmDialog(
                                    onDismiss = { dialogType = null },
                                    onConfirm = {
                                        viewModel.uninstallPlugin(packageName)
                                        dialogType = null
                                    },
                                    title = stringResource(R.string.downloader_plugin_uninstall_title),
                                    description = stringResource(
                                        R.string.downloader_plugin_uninstall_description,
                                        packageName
                                    ),
                                    icon = Icons.Outlined.Delete
                                )
                            }
                            null -> Unit
                        }

                        if (showExceptionViewer && state is DownloaderPluginState.Failed) {
                            ExceptionViewerDialog(
                                text = remember(state.throwable) {
                                    state.throwable.stackTraceToString()
                                },
                                onDismiss = { showExceptionViewer = false }
                            )
                        }

                        ExpressiveSettingsCard(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                        ) {
                            ExpressiveSettingsItem(
                                headlineContent = {
                                    AppLabel(
                                        packageInfo = packageInfo,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                },
                                supportingContent = stringResource(
                                    when (state) {
                                        is DownloaderPluginState.Loaded -> R.string.downloader_plugin_state_trusted
                                        is DownloaderPluginState.Failed -> R.string.downloader_plugin_state_failed
                                        is DownloaderPluginState.Untrusted -> R.string.downloader_plugin_state_untrusted
                                    }
                                ),
                                trailingContent = { Text(packageInfo.versionName!!) },
                                onClick = {
                                    dialogType = when (state) {
                                        is DownloaderPluginState.Loaded -> PluginDialogType.Revoke
                                        is DownloaderPluginState.Failed -> PluginDialogType.Failed
                                        is DownloaderPluginState.Untrusted -> PluginDialogType.Trust
                                    }
                                }
                            )
                        }
                    }
                }
                if (pluginStates.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.downloader_no_plugins_installed),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                item {
                    SettingsSearchHighlight(
                        targetKey = R.string.downloaded_apps,
                        activeKey = highlightTarget,
                        extraKeys = setOf(R.string.downloaded_apps_export),
                        onHighlightComplete = { highlightTarget = null }
                    ) { highlightModifier ->
                        GroupHeader(
                            stringResource(R.string.download_export_section),
                            modifier = highlightModifier
                        )
                    }
                }
                items(downloadedApps, key = { it.packageName to it.version }) { app ->
                    val selected = app in viewModel.appSelection

                    ExpressiveSettingsCard(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        shadowElevation = if (selected) 6.dp else 2.dp,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) {
                        ExpressiveSettingsItem(
                            headlineContent = app.packageName,
                            supportingContent = app.version,
                            leadingContent = (@Composable {
                                HapticCheckbox(
                                    checked = selected,
                                    onCheckedChange = { viewModel.toggleApp(app) }
                                )
                            }).takeIf { viewModel.appSelection.isNotEmpty() },
                            onClick = { viewModel.toggleApp(app) }
                        )
                    }
                }
                if (downloadedApps.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.downloader_settings_no_apps),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

private enum class PluginDialogType {
    Trust,
    Revoke,
    Failed,
    Uninstall
}

private data class DownloadedAppsExportState(
    val asArchive: Boolean,
    val defaultFileName: String,
    val fileTypeLabel: String
)

private data class DownloadedAppsExportDialogState(
    val exportState: DownloadedAppsExportState,
    val directory: Path,
    val fileName: String
)

private data class PendingDownloadedAppsExportConfirmation(
    val exportState: DownloadedAppsExportState,
    val directory: Path,
    val fileName: String
)

@Composable
private fun ExportDownloadedAppsFileNameDialog(
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
                stringResource(R.string.downloaded_apps_export),
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

private fun isZipFile(path: Path): Boolean {
    val name = path.fileName?.toString()?.lowercase().orEmpty()
    return name.endsWith(".zip")
}

@Composable
private fun PluginActionDialog(
    @StringRes title: Int,
    body: String,
    pluginName: String,
    signature: String,
    @StringRes primaryLabel: Int,
    onPrimary: () -> Unit,
    onUninstall: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(body)
                Card {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            stringResource(
                                R.string.downloader_plugin_trust_dialog_plugin,
                                pluginName
                            )
                        )
                        OutlinedCard(
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                        ) {
                            Text(
                                stringResource(
                                    R.string.downloader_plugin_trust_dialog_signature,
                                    signature.chunked(2).joinToString(" ")
                                ),
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onUninstall) {
                Text(stringResource(R.string.uninstall))
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(onClick = onPrimary) {
                    Text(stringResource(primaryLabel))
                }
            }
        }
    )
}

@Composable
private fun PluginFailedDialog(
    packageName: String,
    onDismiss: () -> Unit,
    onViewDetails: () -> Unit,
    onUninstall: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.downloader_plugin_state_failed)) },
        text = {
            Text(
                stringResource(
                    R.string.downloader_plugin_failed_dialog_body,
                    packageName
                )
            )
        },
        dismissButton = {
            TextButton(onClick = onUninstall) {
                Text(stringResource(R.string.uninstall))
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.dismiss))
                }
                TextButton(onClick = onViewDetails) {
                    Text(stringResource(R.string.downloader_plugin_view_error))
                }
            }
        }
    )
}
