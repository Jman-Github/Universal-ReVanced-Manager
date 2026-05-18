package app.urv.manager.ui.screen.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.revanced.manager.R
import app.urv.manager.network.runtime.PatcherRuntimeKind
import app.urv.manager.network.runtime.PatcherRuntimePluginSourceState
import app.urv.manager.network.runtime.PatcherRuntimePluginState
import app.urv.manager.ui.component.AppTopBar
import app.urv.manager.ui.component.ConfirmDialog
import app.urv.manager.ui.component.ExceptionViewerDialog
import app.urv.manager.ui.component.GroupHeader
import app.urv.manager.ui.component.LazyColumnWithScrollbar
import app.urv.manager.ui.component.SettingsSectionIcons
import app.urv.manager.ui.component.TransparentLoadingDialog
import app.urv.manager.ui.component.settings.ExpressiveSettingsCard
import app.urv.manager.ui.component.settings.ExpressiveSettingsDivider
import app.urv.manager.ui.component.settings.ExpressiveSettingsItem
import app.urv.manager.ui.component.settings.ExpressiveSettingsSwitch
import app.urv.manager.ui.viewmodel.PatcherRuntimePluginsViewModel
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatcherRuntimePluginsSettingsScreen(
    onBackClick: () -> Unit,
    viewModel: PatcherRuntimePluginsViewModel = koinViewModel()
) {
    val topAppBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val pluginStates by viewModel.runtimePluginStates.collectAsStateWithLifecycle(emptyMap())
    val sourceStates by viewModel.runtimePluginSourceStates.collectAsStateWithLifecycle(emptyMap())
    val loadedRuntimes by viewModel.loadedRuntimes.collectAsStateWithLifecycle(emptyMap())
    val remoteSourceBusyState = viewModel.remoteSourceBusyState
    var showImportUrlDialog by rememberSaveable { mutableStateOf(false) }

    if (showImportUrlDialog) {
        ImportRuntimeSourceDialog(
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

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.patcher_runtime_plugins),
                onBackClick = onBackClick,
                scrollBehavior = topAppBarScrollBehavior
            )
        },
        modifier = Modifier.nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
    ) { padding ->
        PullToRefreshBox(
            onRefresh = viewModel::refreshPlugins,
            isRefreshing = viewModel.isRefreshingPlugins,
            modifier = Modifier.padding(padding)
        ) {
            LazyColumnWithScrollbar(
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    GroupHeader(
                        title = stringResource(R.string.patcher_runtime_available),
                        icon = SettingsSectionIcons.PatchingEngine
                    )
                }
                item {
                    RuntimeSummaryCard(
                        loadedRuntimes = loadedRuntimes.keys,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
                item {
                    GroupHeader(
                        title = stringResource(R.string.patcher_runtime_plugins_section),
                        icon = SettingsSectionIcons.DownloaderPlugins
                    )
                }
                item {
                    RuntimeImportCard(
                        enabled = remoteSourceBusyState == null,
                        onImportClick = { showImportUrlDialog = true },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
                sourceStates.forEach { (sourceId, source) ->
                    item(key = "source:$sourceId") {
                        ManagedRuntimeCard(
                            source = source,
                            viewModel = viewModel,
                            remoteSourceBusyState = remoteSourceBusyState,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }
                pluginStates.forEach { (packageName, state) ->
                    item(key = packageName) {
                        InstalledRuntimeCard(
                            packageName = packageName,
                            state = state,
                            viewModel = viewModel,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }
                if (sourceStates.isEmpty() && pluginStates.isEmpty()) {
                    item {
                        EmptyRuntimeText(stringResource(R.string.patcher_runtime_no_installed_plugins))
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun RuntimeSummaryCard(
    loadedRuntimes: Set<PatcherRuntimeKind>,
    modifier: Modifier = Modifier
) {
    ExpressiveSettingsCard(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
    ) {
        ExpressiveSettingsItem(
            headlineContent = stringResource(R.string.patcher_runtime_available),
            supportingContent = stringResource(R.string.patcher_runtime_plugins_description),
            leadingContent = {
                RuntimePluginLeadingIcon(
                    icon = Icons.Outlined.Security
                )
            }
        )
        ExpressiveSettingsDivider()
        RuntimeAvailabilityRow(name = "ReVanced v22", available = true, builtIn = true)
        RuntimeAvailabilityRow(name = "Morphe", available = true, builtIn = true)
        PatcherRuntimeKind.entries.forEach { kind ->
            RuntimeAvailabilityRow(
                name = kind.displayName,
                available = kind in loadedRuntimes
            )
        }
    }
}

@Composable
private fun RuntimeAvailabilityRow(
    name: String,
    available: Boolean,
    builtIn: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        RuntimeStatusPill(
            text = if (builtIn) {
                stringResource(R.string.patcher_runtime_built_in)
            } else {
                stringResource(
                    if (available) R.string.patcher_runtime_available_yes
                    else R.string.patcher_runtime_available_no
                )
            },
            icon = when {
                builtIn -> Icons.Outlined.Build
                available -> Icons.Filled.Check
                else -> Icons.Filled.Close
            },
            emphasized = available || builtIn
        )
    }
}

@Composable
private fun RuntimeStatusPill(
    text: String,
    icon: ImageVector,
    emphasized: Boolean
) {
    val containerColor = if (emphasized) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = if (emphasized) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(containerColor)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor
        )
    }
}

@Composable
private fun RuntimeImportCard(
    enabled: Boolean,
    onImportClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ExpressiveSettingsCard(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
    ) {
        ExpressiveSettingsItem(
            headlineContent = stringResource(R.string.patcher_runtime_import_url),
            supportingContent = stringResource(R.string.patcher_runtime_import_url_description)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.End
        ) {
            FilledTonalButton(
                onClick = onImportClick,
                enabled = enabled
            ) {
                Text(stringResource(R.string.import_button))
            }
        }
    }
}

@Composable
private fun ManagedRuntimeCard(
    source: PatcherRuntimePluginSourceState,
    viewModel: PatcherRuntimePluginsViewModel,
    remoteSourceBusyState: PatcherRuntimePluginsViewModel.RemoteSourceBusyState?,
    modifier: Modifier = Modifier
) {
    var showTrustDialog by remember { mutableStateOf(false) }
    var showErrorDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    val loaded = source.state as? PatcherRuntimePluginSourceState.State.Loaded
    val untrusted = source.state as? PatcherRuntimePluginSourceState.State.Untrusted
    val failed = source.state as? PatcherRuntimePluginSourceState.State.Failed
    val actionsEnabled = remoteSourceBusyState == null

    RuntimePluginCard(
        title = source.name,
        version = source.version,
        status = when (source.state) {
            is PatcherRuntimePluginSourceState.State.Loaded ->
                stringResource(R.string.downloader_source_state_loaded)
            is PatcherRuntimePluginSourceState.State.Untrusted ->
                stringResource(R.string.downloader_plugin_state_untrusted)
            is PatcherRuntimePluginSourceState.State.Failed ->
                stringResource(R.string.downloader_source_state_failed)
            PatcherRuntimePluginSourceState.State.Missing ->
                stringResource(R.string.patcher_runtime_missing)
        },
        type = RuntimePluginType.Remote,
        detail = loaded?.plugin?.kind?.displayName ?: source.repoUrl.toGitHubRepoDisplayName(),
        modifier = modifier,
        secondaryActionLabel = stringResource(R.string.delete),
        onSecondaryAction = { showDeleteDialog = true },
        middleActionLabel = stringResource(R.string.settings),
        onMiddleAction = { showSettingsDialog = true },
        primaryActionLabel = stringResource(
            if (untrusted != null) R.string.trust else R.string.update
        ),
        onPrimaryAction = {
            if (untrusted != null) {
                showTrustDialog = true
            } else {
                viewModel.updatePluginSource(source.entry.id)
            }
        },
        primaryActionEnabled = actionsEnabled,
        middleActionEnabled = actionsEnabled,
        secondaryActionEnabled = actionsEnabled,
        footerActionLabel = stringResource(R.string.downloader_plugin_revoke_trust)
            .takeIf {
                source.state !is PatcherRuntimePluginSourceState.State.Untrusted &&
                    source.state !is PatcherRuntimePluginSourceState.State.Missing
            },
        onFooterAction = { viewModel.revokePluginSourceTrust(source.entry.id) },
        footerActionEnabled = actionsEnabled,
        extraSupportingContent = {
            if (failed != null) {
                TextButton(
                    modifier = Modifier.padding(horizontal = 0.dp),
                    onClick = { showErrorDialog = true }
                ) {
                    Text(stringResource(R.string.downloader_plugin_view_error))
                }
            }
        }
    )

    if (showTrustDialog && untrusted != null) {
        TrustRuntimeDialog(
            title = source.name,
            packageName = untrusted.packageName,
            signature = untrusted.signature,
            onDismiss = { showTrustDialog = false },
            onTrust = {
                showTrustDialog = false
                viewModel.trustPluginSource(source.entry.id)
            }
        )
    }
    if (showErrorDialog && failed != null) {
        ExceptionViewerDialog(
            text = remember(failed.throwable) {
                failed.throwable.stackTraceToString()
            },
            onDismiss = { showErrorDialog = false }
        )
    }
    if (showDeleteDialog) {
        ConfirmDialog(
            title = stringResource(R.string.patcher_runtime_source_delete_title),
            description = stringResource(R.string.patcher_runtime_source_delete_description, source.name),
            icon = Icons.Outlined.Delete,
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                showDeleteDialog = false
                viewModel.removePluginSource(source.entry.id)
            }
        )
    }
    if (showSettingsDialog) {
        RuntimeSourceSettingsDialog(
            source = source,
            onDismiss = { showSettingsDialog = false },
            onAutoUpdateChanged = { viewModel.setPluginSourceAutoUpdate(source.entry.id, it) },
            onLatestChanged = { viewModel.setPluginSourceLatest(source.entry.id, it) },
            onPrereleaseChanged = { viewModel.setPluginSourcePrerelease(source.entry.id, it) }
        )
    }
}

@Composable
private fun InstalledRuntimeCard(
    packageName: String,
    state: PatcherRuntimePluginState,
    viewModel: PatcherRuntimePluginsViewModel,
    modifier: Modifier = Modifier
) {
    var showTrustDialog by remember { mutableStateOf(false) }
    var showErrorDialog by remember { mutableStateOf(false) }
    var showUninstallDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val loaded = state as? PatcherRuntimePluginState.Loaded
    val failed = state as? PatcherRuntimePluginState.Failed
    val packageInfo = remember(packageName) {
        viewModel.pm.getPackageInfo(packageName)
    }
    val appName = remember(packageName, packageInfo) {
        packageInfo?.applicationInfo?.loadLabel(context.packageManager)?.toString()
            ?: packageName
    }

    RuntimePluginCard(
        title = appName,
        version = packageInfo?.versionName ?: loaded?.plugin?.version,
        status = when (state) {
            is PatcherRuntimePluginState.Loaded -> stringResource(R.string.downloader_plugin_state_trusted)
            is PatcherRuntimePluginState.Failed -> stringResource(R.string.downloader_source_state_failed)
            PatcherRuntimePluginState.Untrusted -> stringResource(R.string.downloader_plugin_state_untrusted)
        },
        type = RuntimePluginType.Local,
        detail = packageName,
        modifier = modifier,
        secondaryActionLabel = stringResource(R.string.uninstall),
        onSecondaryAction = { showUninstallDialog = true },
        primaryActionLabel = stringResource(
            when (state) {
                is PatcherRuntimePluginState.Loaded ->
                    R.string.downloader_plugin_revoke_trust
                is PatcherRuntimePluginState.Failed ->
                    R.string.downloader_plugin_view_error
                PatcherRuntimePluginState.Untrusted ->
                    R.string.trust
            }
        ),
        onPrimaryAction = {
            when (state) {
                is PatcherRuntimePluginState.Loaded ->
                    viewModel.revokePluginTrust(packageName)
                is PatcherRuntimePluginState.Failed ->
                    showErrorDialog = true
                PatcherRuntimePluginState.Untrusted ->
                    showTrustDialog = true
            }
        }
    )

    if (showTrustDialog) {
        TrustRuntimeDialog(
            title = appName,
            packageName = packageName,
            signature = null,
            onDismiss = { showTrustDialog = false },
            onTrust = {
                showTrustDialog = false
                viewModel.trustPlugin(packageName)
            }
        )
    }
    if (showErrorDialog && failed != null) {
        ExceptionViewerDialog(
            text = remember(failed.throwable) {
                failed.throwable.stackTraceToString()
            },
            onDismiss = { showErrorDialog = false }
        )
    }
    if (showUninstallDialog) {
        ConfirmDialog(
            title = stringResource(R.string.patcher_runtime_uninstall_title),
            description = stringResource(R.string.patcher_runtime_uninstall_description, appName),
            icon = Icons.Outlined.Delete,
            onDismiss = { showUninstallDialog = false },
            onConfirm = {
                showUninstallDialog = false
                viewModel.uninstallPlugin(packageName)
            }
        )
    }
}

private enum class RuntimePluginType(@StringRes val labelRes: Int) {
    Local(R.string.downloader_plugin_type_legacy),
    Remote(R.string.downloader_plugin_type_modern)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RuntimePluginCard(
    title: String,
    version: String?,
    status: String,
    type: RuntimePluginType,
    detail: String,
    primaryActionLabel: String,
    secondaryActionLabel: String,
    onPrimaryAction: () -> Unit,
    onSecondaryAction: () -> Unit,
    modifier: Modifier = Modifier,
    middleActionLabel: String? = null,
    onMiddleAction: (() -> Unit)? = null,
    footerActionLabel: String? = null,
    onFooterAction: (() -> Unit)? = null,
    extraSupportingContent: (@Composable (() -> Unit))? = null,
    primaryActionEnabled: Boolean = true,
    secondaryActionEnabled: Boolean = true,
    middleActionEnabled: Boolean = true,
    footerActionEnabled: Boolean = true
) {
    val supportingSlot: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "${stringResource(R.string.downloader_plugin_type_label)} " +
                    stringResource(type.labelRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            RuntimePluginDetailBox(detail = detail)
            extraSupportingContent?.invoke()
        }
    }

    ExpressiveSettingsCard(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
    ) {
        ExpressiveSettingsItem(
            headlineContent = {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            },
            supportingContentSlot = supportingSlot,
            leadingContent = {
                RuntimePluginLeadingIcon(
                    icon = when (type) {
                        RuntimePluginType.Local -> Icons.Outlined.Folder
                        RuntimePluginType.Remote -> Icons.Outlined.Link
                    }
                )
            },
            trailingContent = version?.let { pluginVersion ->
                {
                    Text(
                        text = pluginVersion,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                RuntimeActionText(secondaryActionLabel)
            }
            if (middleActionLabel != null && onMiddleAction != null) {
                OutlinedButton(
                    onClick = onMiddleAction,
                    enabled = middleActionEnabled,
                    modifier = Modifier.weight(1f)
                ) {
                    RuntimeActionText(middleActionLabel)
                }
            }
            FilledTonalButton(
                onClick = onPrimaryAction,
                enabled = primaryActionEnabled,
                modifier = Modifier.weight(1f)
            ) {
                RuntimeActionText(primaryActionLabel)
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
                RuntimeActionText(footerActionLabel)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RuntimeActionText(text: String) {
    Text(
        text = text,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .basicMarquee(),
        textAlign = TextAlign.Center
    )
}

@Composable
private fun RuntimePluginLeadingIcon(icon: ImageVector) {
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
private fun RuntimePluginDetailBox(detail: String) {
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
private fun RuntimeSourceSettingsDialog(
    source: PatcherRuntimePluginSourceState,
    onDismiss: () -> Unit,
    onAutoUpdateChanged: (Boolean) -> Unit,
    onLatestChanged: (Boolean) -> Unit,
    onPrereleaseChanged: (Boolean) -> Unit
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
                        RuntimeSourceSwitchRow(
                            title = stringResource(R.string.auto_update),
                            checked = source.entry.autoUpdate,
                            onCheckedChange = onAutoUpdateChanged
                        )
                        RuntimeSourceSwitchRow(
                            title = stringResource(R.string.use_latest_release),
                            checked = source.entry.latest,
                            onCheckedChange = onLatestChanged
                        )
                        RuntimeSourceSwitchRow(
                            title = stringResource(R.string.use_prereleases),
                            checked = source.entry.prerelease,
                            onCheckedChange = onPrereleaseChanged
                        )
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

@Composable
private fun RuntimeSourceSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        ExpressiveSettingsSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun EmptyRuntimeText(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun TrustRuntimeDialog(
    title: String,
    packageName: String,
    signature: String?,
    onDismiss: () -> Unit,
    onTrust: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.patcher_runtime_trust_dialog_title))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.patcher_runtime_trust_dialog_body),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.downloader_plugin_trust_dialog_plugin,
                                title
                            )
                        )
                        if (packageName != title) {
                            Text(
                                text = packageName,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (signature != null) {
                            OutlinedCard(
                                colors = CardDefaults.outlinedCardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                )
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.downloader_plugin_trust_dialog_signature,
                                        signature.chunked(2).joinToString(" ")
                                    ),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    modifier = Modifier
                                        .padding(12.dp)
                                        .heightIn(max = 320.dp)
                                        .verticalScroll(rememberScrollState())
                                )
                            }
                        } else {
                            Text(
                                text = stringResource(
                                    R.string.patcher_runtime_signature_after_trust
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(onClick = onTrust) {
                    Text(stringResource(R.string.continue_))
                }
            }
        }
    )
}

@Composable
private fun ImportRuntimeSourceDialog(
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
        title = { Text(stringResource(R.string.patcher_runtime_import_url)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.patcher_runtime_import_url_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text(stringResource(R.string.patcher_runtime_import_url_hint)) },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(trimmedUrl) },
                enabled = trimmedUrl.isNotEmpty()
            ) {
                Text(stringResource(R.string.import_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
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
