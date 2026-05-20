package app.urv.manager.ui.screen.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ClipData
import android.content.ClipboardManager
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.urv.manager.domain.manager.PreferencesManager
import app.universal.revanced.manager.R
import app.urv.manager.data.platform.Filesystem
import app.urv.manager.network.downloader.LoadedDownloaderPlugin
import app.urv.manager.network.downloader.DownloaderPluginSourceState
import app.urv.manager.network.downloader.DownloaderPluginState
import app.urv.manager.network.downloader.toDownloaderMainName
import app.urv.manager.ui.component.AppTopBar
import app.urv.manager.ui.component.ExceptionViewerDialog
import app.urv.manager.ui.component.GroupHeader
import app.urv.manager.ui.component.LazyColumnWithScrollbar
import app.urv.manager.ui.component.SettingsSectionIcons
import app.urv.manager.ui.component.ConfirmDialog
import app.urv.manager.ui.component.TransparentLoadingDialog
import app.urv.manager.ui.component.patches.PathSelectorDialog
import app.urv.manager.ui.component.haptics.HapticCheckbox
import app.urv.manager.ui.component.settings.BooleanItem
import app.urv.manager.ui.component.settings.ExpressiveSettingsCard
import app.urv.manager.ui.component.settings.ExpressiveSettingsDivider
import app.urv.manager.ui.component.settings.ExpressiveSettingsItem
import app.urv.manager.ui.component.settings.ExpressiveSettingsSwitch
import app.urv.manager.ui.component.settings.SettingsSearchHighlight
import app.urv.manager.ui.model.navigation.Settings
import app.urv.manager.ui.screen.settings.SettingsSearchState
import app.urv.manager.ui.viewmodel.DownloadsViewModel
import app.urv.manager.ui.component.AnnotatedLinkText // From PR #37: https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/37
import app.urv.manager.util.isAllowedApkFile
import app.urv.manager.util.toast
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
    val autoSaveDownloaderApks by prefs.autoSaveDownloaderApks.getAsState()
    val downloadedApps by viewModel.downloadedApps.collectAsStateWithLifecycle(emptyList())
    val pluginStates by viewModel.downloaderPluginStates.collectAsStateWithLifecycle(emptyMap())
    val sourceStates by viewModel.downloaderPluginSourceStates.collectAsStateWithLifecycle(emptyMap())
    val remoteSourceBusyState = viewModel.remoteSourceBusyState
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val searchTarget by SettingsSearchState.target.collectAsStateWithLifecycle()
    var highlightTarget by rememberSaveable { mutableStateOf<Int?>(null) }
    var showHelpDialog by rememberSaveable { mutableStateOf(false) } // From PR #37: https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/37
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val fs: Filesystem = koinInject()
    val storageRoots = remember { fs.storageRoots() }
    val (permissionContract, permissionName) = remember { fs.permissionContract() }
    var pendingExportState by remember { mutableStateOf<DownloadedAppsExportState?>(null) }
    var activeExportState by remember { mutableStateOf<DownloadedAppsExportState?>(null) }
    var pendingDocumentExportState by remember { mutableStateOf<DownloadedAppsExportState?>(null) }
    var exportFileDialogState by remember { mutableStateOf<DownloadedAppsExportDialogState?>(null) }
    var pendingExportConfirmation by remember { mutableStateOf<PendingDownloadedAppsExportConfirmation?>(null) }
    var exportInProgress by rememberSaveable { mutableStateOf(false) }
    var showDeleteAppsConfirmation by rememberSaveable { mutableStateOf(false) }
    var showImportUrlDialog by rememberSaveable { mutableStateOf(false) }
    var sourceIdPendingDeletion by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceIdPendingTrustRevoke by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceIdInSettings by rememberSaveable { mutableStateOf<String?>(null) }
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
    if (showImportUrlDialog) {
        ImportDownloaderSourceDialog(
            onDismiss = { showImportUrlDialog = false },
            onImport = { url ->
                viewModel.importPluginSource(url)
                showImportUrlDialog = false
            }
        )
    }
    if (remoteSourceBusyState != null) {
        TransparentLoadingDialog()
    }
    sourceIdPendingDeletion
        ?.let(sourceStates::get)
        ?.let { source ->
            ConfirmDialog(
                onDismiss = { sourceIdPendingDeletion = null },
                onConfirm = {
                    viewModel.removePluginSource(source.entry.id)
                    sourceIdPendingDeletion = null
                },
                title = stringResource(R.string.downloader_source_delete_title),
                description = stringResource(
                    R.string.downloader_source_delete_description,
                    source.name
                ),
                icon = Icons.Outlined.Delete
            )
        }
    sourceIdPendingTrustRevoke
        ?.let(sourceStates::get)
        ?.let { source ->
            ConfirmDialog(
                onDismiss = { sourceIdPendingTrustRevoke = null },
                onConfirm = {
                    viewModel.revokePluginSourceTrust(source.entry.id)
                    sourceIdPendingTrustRevoke = null
                },
                title = stringResource(R.string.downloader_plugin_revoke_trust_dialog_title),
                description = stringResource(
                    R.string.downloader_source_revoke_trust_description,
                    source.name
                ),
                icon = Icons.Outlined.WarningAmber
            )
        }
    sourceIdInSettings
        ?.let(sourceStates::get)
        ?.let { source ->
            DownloaderSourceSettingsDialog(
                source = source,
                onDismiss = { sourceIdInSettings = null },
                onAutoUpdateChanged = {
                    viewModel.setPluginSourceAutoUpdate(source.entry.id, it)
                },
                onLatestChanged = {
                    viewModel.setPluginSourceLatest(source.entry.id, it)
                },
                onPrereleaseChanged = {
                    viewModel.setPluginSourcePrerelease(source.entry.id, it)
                },
                onCopyRepoUrl = {
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText(
                            source.repoUrl.toGitHubRepoDisplayName(),
                            source.repoUrl
                        )
                    )
                    context.toast(context.getString(R.string.toast_copied_to_clipboard))
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
    if (showDeleteAppsConfirmation && viewModel.appSelection.isNotEmpty()) {
        ConfirmDialog(
            onDismiss = { showDeleteAppsConfirmation = false },
            onConfirm = {
                viewModel.deleteApps()
                showDeleteAppsConfirmation = false
            },
            title = stringResource(R.string.downloaded_apps_delete_title),
            description = stringResource(R.string.downloaded_apps_delete_description),
            icon = Icons.Outlined.Delete
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
                        IconButton(onClick = { showDeleteAppsConfirmation = true }) {
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
                    GroupHeader(
                        stringResource(R.string.download_behavior_section),
                        icon = SettingsSectionIcons.DownloadBehavior
                    )
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
                        ExpressiveSettingsDivider()
                        SettingsSearchHighlight(
                            targetKey = R.string.downloader_auto_save_latest_only_title,
                            activeKey = highlightTarget,
                            onHighlightComplete = { highlightTarget = null }
                        ) { highlightModifier ->
                            BooleanItem(
                                modifier = highlightModifier,
                                preference = prefs.autoSaveDownloaderLatestOnly,
                                headline = R.string.downloader_auto_save_latest_only_title,
                                description = R.string.downloader_auto_save_latest_only_description,
                                enabled = autoSaveDownloaderApks
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
                            icon = SettingsSectionIcons.DownloaderPlugins,
                            modifier = highlightModifier
                        )
                    }
                }
                item {
                    ExpressiveSettingsCard(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) {
                        ExpressiveSettingsItem(
                            headlineContent = stringResource(R.string.downloader_import_url),
                            supportingContent = stringResource(R.string.downloader_import_url_description)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            FilledTonalButton(
                                onClick = { showImportUrlDialog = true },
                                enabled = remoteSourceBusyState == null
                            ) {
                                Text(stringResource(R.string.import_))
                            }
                        }
                    }
                }
                sourceStates.forEach { (sourceId, source) ->
                    item(key = "source:$sourceId") {
                        var showExceptionViewer by remember { mutableStateOf(false) }
                        var showTrustDialog by remember { mutableStateOf(false) }

                        if (showExceptionViewer && source.state is DownloaderPluginSourceState.State.Failed) {
                            ExceptionViewerDialog(
                                text = remember(source.state.throwable) {
                                    source.state.throwable.stackTraceToString()
                                },
                                onDismiss = { showExceptionViewer = false }
                            )
                        }

                        if (showTrustDialog && source.state is DownloaderPluginSourceState.State.Untrusted) {
                            PluginActionDialog(
                                title = R.string.downloader_plugin_trust_dialog_title,
                                body = stringResource(R.string.downloader_plugin_trust_dialog_body),
                                pluginName = source.name,
                                signature = source.state.signature,
                                primaryLabel = R.string.continue_,
                                secondaryLabel = R.string.delete,
                                onPrimary = {
                                    viewModel.trustPluginSource(sourceId)
                                    showTrustDialog = false
                                },
                                onSecondary = {
                                    showTrustDialog = false
                                    sourceIdPendingDeletion = sourceId
                                },
                                onDismiss = { showTrustDialog = false }
                            )
                        }

                        DownloaderPluginCard(
                            title = source.name.toDownloaderMainName(),
                            version = source.version,
                            status = stringResource(
                                when (source.state) {
                                    is DownloaderPluginSourceState.State.Loaded ->
                                        R.string.downloader_source_state_loaded

                                    is DownloaderPluginSourceState.State.Missing ->
                                        R.string.downloader_source_state_missing

                                    is DownloaderPluginSourceState.State.Untrusted ->
                                        R.string.downloader_plugin_state_untrusted

                                    is DownloaderPluginSourceState.State.Failed ->
                                        R.string.downloader_source_state_failed
                                }
                            ),
                            type = DownloaderPluginType.Remote,
                            detail = source.repoUrl.toGitHubRepoDisplayName(),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            secondaryActionLabel = stringResource(R.string.delete),
                            onSecondaryAction = { sourceIdPendingDeletion = sourceId },
                            middleActionLabel = stringResource(R.string.settings),
                            onMiddleAction = { sourceIdInSettings = sourceId },
                            primaryActionLabel = stringResource(
                                if (source.state is DownloaderPluginSourceState.State.Untrusted) {
                                    R.string.trust
                                } else {
                                    R.string.update
                                }
                            ),
                            onPrimaryAction = {
                                if (source.state is DownloaderPluginSourceState.State.Untrusted) {
                                    showTrustDialog = true
                                } else {
                                    viewModel.updatePluginSource(sourceId)
                                }
                            },
                            primaryActionEnabled = remoteSourceBusyState == null,
                            middleActionEnabled = remoteSourceBusyState == null,
                            secondaryActionEnabled = remoteSourceBusyState == null,
                            footerActionLabel = stringResource(R.string.downloader_plugin_revoke_trust)
                                .takeIf {
                                    source.state !is DownloaderPluginSourceState.State.Untrusted &&
                                        source.state !is DownloaderPluginSourceState.State.Missing
                                },
                            onFooterAction = { sourceIdPendingTrustRevoke = sourceId },
                            footerActionEnabled = remoteSourceBusyState == null,
                            extraSupportingContent = {
                                if (source.state is DownloaderPluginSourceState.State.Failed) {
                                    TextButton(
                                        modifier = Modifier.padding(horizontal = 0.dp),
                                        onClick = { showExceptionViewer = true }
                                    ) {
                                        Text(stringResource(R.string.downloader_plugin_view_error))
                                    }
                                }
                            }
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
                        val pluginTitle = remember(appName, state) {
                            when (state) {
                                is DownloaderPluginState.Loaded -> state.plugins
                                    .map(LoadedDownloaderPlugin::shortDisplayName)
                                    .distinct()
                                    .joinToString(", ")
                                    .ifBlank { appName.toDownloaderMainName() }

                                else -> appName.toDownloaderMainName()
                            }
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
                                    onSecondary = {
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
                                    onSecondary = {
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

                        DownloaderPluginCard(
                            title = pluginTitle,
                            version = packageInfo.versionName,
                            status = stringResource(
                                when (state) {
                                    is DownloaderPluginState.Loaded ->
                                        R.string.downloader_plugin_state_trusted

                                    is DownloaderPluginState.Failed ->
                                        R.string.downloader_source_state_failed

                                    is DownloaderPluginState.Untrusted ->
                                        R.string.downloader_plugin_state_untrusted
                                }
                            ),
                            type = DownloaderPluginType.Local,
                            detail = packageName,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            secondaryActionLabel = stringResource(R.string.uninstall),
                            onSecondaryAction = { dialogType = PluginDialogType.Uninstall },
                            primaryActionLabel = stringResource(
                                when (state) {
                                    is DownloaderPluginState.Loaded ->
                                        R.string.downloader_plugin_revoke_trust

                                    is DownloaderPluginState.Failed ->
                                        R.string.downloader_plugin_view_error

                                    is DownloaderPluginState.Untrusted ->
                                        R.string.trust
                                }
                            ),
                            onPrimaryAction = {
                                when (state) {
                                    is DownloaderPluginState.Loaded ->
                                        dialogType = PluginDialogType.Revoke

                                    is DownloaderPluginState.Failed ->
                                        showExceptionViewer = true

                                    is DownloaderPluginState.Untrusted ->
                                        dialogType = PluginDialogType.Trust
                                }
                            }
                        )
                    }
                }
                if (pluginStates.isEmpty() && sourceStates.isEmpty()) {
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
                            icon = SettingsSectionIcons.SavedApps,
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

private enum class DownloaderPluginType(@StringRes val labelRes: Int) {
    Local(R.string.downloader_plugin_type_legacy),
    Remote(R.string.downloader_plugin_type_modern)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloaderPluginCard(
    title: String,
    version: String?,
    status: String,
    type: DownloaderPluginType,
    detail: String,
    primaryActionLabel: String,
    secondaryActionLabel: String,
    onPrimaryAction: () -> Unit,
    onSecondaryAction: () -> Unit,
    middleActionLabel: String? = null,
    onMiddleAction: (() -> Unit)? = null,
    footerActionLabel: String? = null,
    onFooterAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    extraSupportingContent: (@Composable (() -> Unit))? = null,
    primaryActionEnabled: Boolean = true,
    secondaryActionEnabled: Boolean = true,
    middleActionEnabled: Boolean = true,
    footerActionEnabled: Boolean = true
) {
    val supportingSlot: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "${stringResource(R.string.downloader_plugin_type_label)} ${stringResource(type.labelRes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            DownloaderPluginDetailBox(detail = detail)
            extraSupportingContent?.invoke()
        }
    }

    ExpressiveSettingsCard(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
    ) {
        ExpressiveSettingsItem(
            headlineContent = title,
            supportingContentSlot = supportingSlot,
            leadingContent = {
                DownloaderPluginLeadingIcon(type = type)
            },
            trailingContent = version?.let { pluginVersion ->
                {
                    Text(pluginVersion)
                }
            }
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onSecondaryAction,
                enabled = secondaryActionEnabled,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = secondaryActionLabel,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee(),
                    textAlign = TextAlign.Center
                )
            }
            if (middleActionLabel != null && onMiddleAction != null) {
                OutlinedButton(
                    onClick = onMiddleAction,
                    enabled = middleActionEnabled,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = middleActionLabel,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .basicMarquee(),
                        textAlign = TextAlign.Center
                    )
                }
            }
            FilledTonalButton(
                onClick = onPrimaryAction,
                enabled = primaryActionEnabled,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = primaryActionLabel,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee(),
                    textAlign = TextAlign.Center
                )
            }
        }
        if (footerActionLabel != null && onFooterAction != null) {
            OutlinedButton(
                onClick = onFooterAction,
                enabled = footerActionEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            ) {
                Text(footerActionLabel)
            }
        }
    }
}

@Composable
private fun DownloaderPluginLeadingIcon(
    type: DownloaderPluginType
) {
    val icon = when (type) {
        DownloaderPluginType.Local -> Icons.Outlined.Folder
        DownloaderPluginType.Remote -> Icons.Outlined.Link
    }
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Box(
            modifier = Modifier.size(44.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun DownloaderPluginDetailBox(
    detail: String
) {
    val scrollState = rememberScrollState()
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .horizontalScroll(scrollState)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun DownloaderSourceSettingsDialog(
    source: DownloaderPluginSourceState,
    onDismiss: () -> Unit,
    onAutoUpdateChanged: (Boolean) -> Unit,
    onLatestChanged: (Boolean) -> Unit,
    onPrereleaseChanged: (Boolean) -> Unit,
    onCopyRepoUrl: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.downloader_source_settings_title, source.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = source.repoUrl.toGitHubRepoDisplayName(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                OutlinedCard(
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = stringResource(R.string.downloader_source_auto_update),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = stringResource(R.string.auto_update_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            ExpressiveSettingsSwitch(
                                checked = source.entry.autoUpdate,
                                onCheckedChange = onAutoUpdateChanged
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = stringResource(R.string.downloader_source_latest),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = stringResource(R.string.downloader_source_latest_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            ExpressiveSettingsSwitch(
                                checked = source.entry.latest,
                                onCheckedChange = onLatestChanged
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = stringResource(R.string.downloader_source_prerelease),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = stringResource(R.string.downloader_source_prerelease_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            ExpressiveSettingsSwitch(
                                checked = source.entry.prerelease && !source.entry.latest,
                                onCheckedChange = onPrereleaseChanged,
                                enabled = !source.entry.latest
                            )
                        }
                    }
                }
                OutlinedCard(
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.downloader_source_repo_url),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = source.repoUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = onCopyRepoUrl) {
                                Text(stringResource(R.string.copy_to_clipboard))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

private fun String.toGitHubRepoDisplayName(): String {
    val marker = "github.com/"
    val afterHost = substringAfter(marker, missingDelimiterValue = this)
    val parts = afterHost.trim('/').split('/').filter(String::isNotBlank)
    return if (parts.size >= 2) {
        "${parts[0]}/${parts[1]}"
    } else {
        this
    }
}

@Composable
private fun ImportDownloaderSourceDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var url by rememberSaveable { mutableStateOf("") }
    val trimmedUrl = url.trim()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        withFrameNanos { }
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.downloader_import_url)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.downloader_import_url_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text(stringResource(R.string.downloader_import_url_hint)) },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(trimmedUrl) },
                enabled = trimmedUrl.isNotEmpty()
            ) {
                Text(stringResource(R.string.import_))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

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
    @StringRes secondaryLabel: Int = R.string.uninstall,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
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
            TextButton(onClick = onSecondary) {
                Text(stringResource(secondaryLabel))
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
