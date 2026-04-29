package app.urv.manager.ui.screen.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.revanced.manager.R
import app.urv.manager.data.room.apps.installed.InstallType
import app.urv.manager.domain.manager.PreferencesManager
import app.urv.manager.domain.repository.DownloadedAppRepository
import app.urv.manager.domain.repository.DownloaderPluginRepository
import app.urv.manager.domain.repository.InstalledAppRepository
import app.urv.manager.domain.repository.PatchBundleRepository
import app.urv.manager.domain.repository.PatchProfileRepository
import app.urv.manager.ui.component.AppTopBar
import app.urv.manager.ui.component.ColumnWithScrollbar
import app.urv.manager.ui.component.GroupHeader
import app.urv.manager.ui.component.ShimmerBox
import app.urv.manager.ui.component.settings.ExpressiveSettingsCard
import app.urv.manager.ui.component.settings.ExpressiveSettingsDivider
import app.urv.manager.ui.component.settings.ExpressiveSettingsItem
import app.urv.manager.ui.component.settings.SettingsSearchHighlight
import app.urv.manager.ui.model.navigation.Settings
import app.urv.manager.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSettingsScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val prefs: PreferencesManager = koinInject()
    val downloadedAppRepository: DownloadedAppRepository = koinInject()
    val downloaderPluginRepository: DownloaderPluginRepository = koinInject()
    val installedAppRepository: InstalledAppRepository = koinInject()
    val patchBundleRepository: PatchBundleRepository = koinInject()
    val patchProfileRepository: PatchProfileRepository = koinInject()
    val coroutineScope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val searchTarget by SettingsSearchState.target.collectAsStateWithLifecycle()
    var highlightTarget by rememberSaveable { mutableStateOf<Int?>(null) }
    var snapshot by remember { mutableStateOf<StorageSnapshot?>(null) }
    var isLoading by rememberSaveable { mutableStateOf(false) }
    var showClearCacheDialog by rememberSaveable { mutableStateOf(false) }
    var pendingClearTarget by rememberSaveable { mutableStateOf<StorageClearTarget?>(null) }

    fun refreshStorageUsage() {
        coroutineScope.launch {
            val loadingStartedAt = SystemClock.elapsedRealtime()
            isLoading = true
            try {
                snapshot = loadStorageSnapshot(context)
                val remainingShimmerTime = STORAGE_REFRESH_SHIMMER_MIN_MS -
                    (SystemClock.elapsedRealtime() - loadingStartedAt)
                if (remainingShimmerTime > 0L) {
                    delay(remainingShimmerTime)
                }
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshStorageUsage()
    }

    LaunchedEffect(searchTarget) {
        val target = searchTarget
        if (target?.destination == Settings.Storage) {
            highlightTarget = target.targetId
            SettingsSearchState.clear()
        }
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearCacheDialog = false
                        coroutineScope.launch {
                            isLoading = true
                            try {
                                val clearedBytes = clearAppCache(context)
                                snapshot = loadStorageSnapshot(context)
                                context.toast(
                                    context.getString(
                                        R.string.storage_cache_cleared,
                                        Formatter.formatShortFileSize(context, clearedBytes)
                                    )
                                )
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.storage_clear_cache_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { Text(stringResource(R.string.storage_clear_cache_dialog_title)) },
            text = { Text(stringResource(R.string.storage_clear_cache_dialog_description)) }
        )
    }

    pendingClearTarget?.let { target ->
        val areaName = target.title(context)
        AlertDialog(
            onDismissRequest = { pendingClearTarget = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingClearTarget = null
                        coroutineScope.launch {
                            isLoading = true
                            try {
                                val clearedBytes = clearStorageTarget(
                                    context = context,
                                    target = target,
                                    prefs = prefs,
                                    downloadedAppRepository = downloadedAppRepository,
                                    downloaderPluginRepository = downloaderPluginRepository,
                                    installedAppRepository = installedAppRepository,
                                    patchBundleRepository = patchBundleRepository,
                                    patchProfileRepository = patchProfileRepository
                                )
                                snapshot = loadStorageSnapshot(context)
                                context.toast(
                                    context.getString(
                                        R.string.storage_area_cleared,
                                        areaName,
                                        Formatter.formatShortFileSize(context, clearedBytes)
                                    )
                                )
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.storage_clear_area_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { Text(stringResource(R.string.storage_clear_area_dialog_title, areaName)) },
            text = { Text(stringResource(R.string.storage_clear_area_dialog_description, areaName)) }
        )
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.storage_cache_management),
                scrollBehavior = scrollBehavior,
                onBackClick = onBackClick
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        ColumnWithScrollbar(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            SettingsSearchHighlight(
                targetKey = R.string.storage_overview_section,
                activeKey = highlightTarget,
                onHighlightComplete = { highlightTarget = null }
            ) { highlightModifier ->
                GroupHeader(
                    stringResource(R.string.storage_overview_section),
                    icon = Icons.Outlined.Storage,
                    modifier = highlightModifier
                )
            }
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SettingsSearchHighlight(
                    targetKey = R.string.storage_total_app_storage,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    StorageOverviewItem(
                        modifier = highlightModifier,
                        sizeText = snapshot?.let { Formatter.formatShortFileSize(context, it.totalBytes) },
                        isLoading = isLoading
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.storage_refresh_usage,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsItem(
                        headlineContent = stringResource(R.string.storage_refresh_usage),
                        supportingContent = stringResource(R.string.storage_refresh_usage_description),
                        leadingContent = {
                            Icon(Icons.Outlined.Refresh, null)
                        },
                        enabled = !isLoading,
                        onClick = ::refreshStorageUsage,
                        modifier = highlightModifier
                    )
                }
            }

            SettingsSearchHighlight(
                targetKey = R.string.storage_usage_section,
                activeKey = highlightTarget,
                onHighlightComplete = { highlightTarget = null }
            ) { highlightModifier ->
                GroupHeader(
                    stringResource(R.string.storage_usage_section),
                    icon = Icons.Outlined.Folder,
                    modifier = highlightModifier
                )
            }
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                val areas = snapshot?.areas.orEmpty()
                val showUsageShimmer = isLoading || snapshot == null
                if (showUsageShimmer) {
                    val placeholderCount = snapshot?.areas?.size ?: STORAGE_AREA_PLACEHOLDER_COUNT
                    repeat(placeholderCount) { index ->
                        StorageAreaPlaceholderItem()
                        if (index != placeholderCount - 1) {
                            ExpressiveSettingsDivider()
                        }
                    }
                } else if (areas.isEmpty()) {
                    ExpressiveSettingsItem(
                        headlineContent = stringResource(R.string.storage_calculating),
                        supportingContent = stringResource(R.string.storage_usage_section_description)
                    )
                } else {
                    areas.forEachIndexed { index, area ->
                        SettingsSearchHighlight(
                            targetKey = area.targetKey,
                            activeKey = highlightTarget,
                            onHighlightComplete = { highlightTarget = null }
                        ) { highlightModifier ->
                            StorageAreaItem(
                                area = area,
                                totalBytes = snapshot?.totalBytes ?: 0L,
                                isLoading = isLoading,
                                formatSize = { Formatter.formatShortFileSize(context, it) },
                                onClear = { clearTarget -> pendingClearTarget = clearTarget },
                                onManage = {
                                    if (!context.openAppStorageSettings()) {
                                        context.toast(context.getString(R.string.storage_open_app_storage_settings_failed))
                                    }
                                },
                                modifier = highlightModifier
                            )
                        }
                        if (index != areas.lastIndex) {
                            ExpressiveSettingsDivider()
                        }
                    }
                }
            }

            SettingsSearchHighlight(
                targetKey = R.string.storage_cache_actions_section,
                activeKey = highlightTarget,
                onHighlightComplete = { highlightTarget = null }
            ) { highlightModifier ->
                GroupHeader(
                    stringResource(R.string.storage_cache_actions_section),
                    icon = Icons.Outlined.Delete,
                    modifier = highlightModifier
                )
            }
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SettingsSearchHighlight(
                    targetKey = R.string.storage_clear_app_cache,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsItem(
                        headlineContent = stringResource(R.string.storage_clear_app_cache),
                        supportingContent = stringResource(R.string.storage_clear_app_cache_description),
                        leadingContent = {
                            Icon(Icons.Outlined.Delete, null)
                        },
                        enabled = !isLoading,
                        onClick = { showClearCacheDialog = true },
                        modifier = highlightModifier
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.storage_open_app_storage_settings,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsItem(
                        headlineContent = stringResource(R.string.storage_open_app_storage_settings),
                        supportingContent = stringResource(R.string.storage_open_app_storage_settings_description),
                        leadingContent = {
                            Icon(Icons.Outlined.Settings, null)
                        },
                        onClick = {
                            if (!context.openAppStorageSettings()) {
                                context.toast(context.getString(R.string.storage_open_app_storage_settings_failed))
                            }
                        },
                        modifier = highlightModifier
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageOverviewItem(
    sizeText: String?,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    ExpressiveSettingsItem(
        headlineContent = stringResource(R.string.storage_total_app_storage),
        supportingContent = stringResource(R.string.storage_total_app_storage_description),
        leadingContent = {
            Icon(Icons.Outlined.Storage, null)
        },
        trailingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isLoading || sizeText == null) {
                    ShimmerBox(
                        modifier = Modifier
                            .width(92.dp)
                            .height(22.dp),
                        shape = RoundedCornerShape(999.dp)
                    )
                } else {
                    Text(
                        text = sizeText,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        modifier = modifier
    )
}

@Composable
private fun StorageAreaItem(
    area: StorageAreaUsage,
    totalBytes: Long,
    isLoading: Boolean,
    formatSize: (Long) -> String,
    onClear: (StorageClearTarget) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val percent = if (totalBytes > 0L) {
        (area.stats.bytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    ExpressiveSettingsItem(
        headlineContent = area.title,
        supportingContentSlot = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = area.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StorageUsageBar(progress = percent)
                Text(
                    text = stringResource(
                        R.string.storage_item_count,
                        area.stats.fileCount,
                        area.stats.directoryCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        trailingContent = {
            Column(
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = if (area.stats.bytes > 0L) formatSize(area.stats.bytes) else stringResource(R.string.storage_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.End,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                when (val clearTarget = area.clearTarget) {
                    null -> StorageAreaActionButton(
                        text = stringResource(R.string.storage_manage_area),
                        onClick = onManage
                    )
                    else -> StorageAreaActionButton(
                        text = stringResource(R.string.clear),
                        enabled = !isLoading && area.stats.bytes > 0L
                    ) { onClear(clearTarget) }
                }
            }
        },
        modifier = modifier
    )
}

@Composable
private fun StorageAreaPlaceholderItem(
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp)
    ) {
        ShimmerBox(
            modifier = Modifier
                .fillMaxWidth(0.68f)
                .height(18.dp),
            shape = RoundedCornerShape(999.dp)
        )
    }
}

@Composable
private fun StorageAreaActionButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.width(72.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun StorageUsageBar(progress: Float) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (progress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                    .fillMaxHeight()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.75f))
            )
        }
    }
}

private data class StorageSnapshot(
    val areas: List<StorageAreaUsage>,
    val totalBytes: Long
)

private const val STORAGE_AREA_PLACEHOLDER_COUNT = 16
private const val STORAGE_REFRESH_SHIMMER_MIN_MS = 1200L

private data class StorageAreaUsage(
    val targetKey: Int,
    val title: String,
    val description: String,
    val stats: DirectoryStats,
    val clearTarget: StorageClearTarget?
)

private enum class StorageClearTarget {
    InternalCache,
    CodeCache,
    CustomBackgrounds,
    DownloadedApps,
    PatchBundles,
    SigningFiles,
    DownloaderPlugins,
    PatchedApps,
    PatchProfileInputs,
    TemporaryWorkspace,
    UiTemporaryWorkspace,
    ExternalCache,
    ExternalFiles;

    fun title(context: Context): String = context.getString(
        when (this) {
            InternalCache -> R.string.storage_internal_cache
            CodeCache -> R.string.storage_code_cache
            CustomBackgrounds -> R.string.storage_custom_backgrounds
            DownloadedApps -> R.string.storage_downloaded_apps
            PatchBundles -> R.string.storage_patch_bundles
            SigningFiles -> R.string.storage_signing_files
            DownloaderPlugins -> R.string.storage_downloader_plugins
            PatchedApps -> R.string.storage_patched_apps
            PatchProfileInputs -> R.string.storage_patch_profile_inputs
            TemporaryWorkspace -> R.string.storage_temporary_workspace
            UiTemporaryWorkspace -> R.string.storage_ui_temporary_workspace
            ExternalCache -> R.string.storage_external_cache
            ExternalFiles -> R.string.storage_external_files
        }
    )
}

private data class DirectoryStats(
    val bytes: Long = 0L,
    val fileCount: Long = 0L,
    val directoryCount: Long = 0L
) {
    operator fun plus(other: DirectoryStats) = DirectoryStats(
        bytes = bytes + other.bytes,
        fileCount = fileCount + other.fileCount,
        directoryCount = directoryCount + other.directoryCount
    )

    operator fun minus(other: DirectoryStats) = DirectoryStats(
        bytes = (bytes - other.bytes).coerceAtLeast(0L),
        fileCount = (fileCount - other.fileCount).coerceAtLeast(0L),
        directoryCount = (directoryCount - other.directoryCount).coerceAtLeast(0L)
    )
}

private suspend fun loadStorageSnapshot(context: Context): StorageSnapshot = withContext(Dispatchers.IO) {
    val dataRoot = File(context.applicationInfo.dataDir)
    val customBackgroundsDir = context.filesDir.resolve("custom_background")
    val downloadedAppsDir = context.privateAppDir("downloaded-apps")
    val patchBundlesDir = context.privateAppDir("patch_bundles")
    val signingDir = context.privateAppDir("signing")
    val downloaderPluginsDir = context.privateAppDir("managed_downloader_plugins")
    val patchedAppsDir = context.privateAppDir("patched-apps")
    val patchProfileInputsDir = context.privateAppDir("patch-profile-inputs")
    val temporaryWorkspaceDir = context.privateAppDir("ephemeral")
    val uiTemporaryWorkspaceDir = context.privateAppDir("ui_ephemeral")

    val internalCacheStats = context.cacheDir.directoryStats()
    val codeCacheStats = context.codeCacheDir.directoryStats()
    val customBackgroundsStats = customBackgroundsDir.directoryStats()
    val internalFilesStats = context.filesDir.directoryStats() - customBackgroundsStats
    val noBackupStats = context.noBackupFilesDir.directoryStats()
    val downloadedAppsStats = downloadedAppsDir.directoryStats()
    val patchBundlesStats = patchBundlesDir.directoryStats()
    val signingStats = signingDir.directoryStats()
    val downloaderPluginsStats = downloaderPluginsDir.directoryStats()
    val patchedAppsStats = patchedAppsDir.directoryStats()
    val patchProfileInputsStats = patchProfileInputsDir.directoryStats()
    val temporaryWorkspaceStats = temporaryWorkspaceDir.directoryStats()
    val uiTemporaryWorkspaceStats = uiTemporaryWorkspaceDir.directoryStats()
    val externalCacheStats = context.externalCacheDirs.filterNotNull().combinedStats()
    val externalFilesStats = context.getExternalFilesDirs(null).filterNotNull().combinedStats()
    val knownInternalStats = listOf(
        internalCacheStats,
        codeCacheStats,
        internalFilesStats,
        noBackupStats,
        customBackgroundsStats,
        downloadedAppsStats,
        patchBundlesStats,
        signingStats,
        downloaderPluginsStats,
        patchedAppsStats,
        patchProfileInputsStats,
        temporaryWorkspaceStats,
        uiTemporaryWorkspaceStats
    ).fold(DirectoryStats()) { total, stats -> total + stats }
    val otherInternalStats = dataRoot.directoryStats() - knownInternalStats

    val areas = listOf(
        StorageAreaUsage(
            targetKey = R.string.storage_internal_cache,
            title = context.getString(R.string.storage_internal_cache),
            description = context.getString(R.string.storage_internal_cache_description),
            stats = internalCacheStats,
            clearTarget = StorageClearTarget.InternalCache
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_code_cache,
            title = context.getString(R.string.storage_code_cache),
            description = context.getString(R.string.storage_code_cache_description),
            stats = codeCacheStats,
            clearTarget = StorageClearTarget.CodeCache
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_internal_files,
            title = context.getString(R.string.storage_internal_files),
            description = context.getString(R.string.storage_internal_files_description),
            stats = internalFilesStats,
            clearTarget = null
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_no_backup_files,
            title = context.getString(R.string.storage_no_backup_files),
            description = context.getString(R.string.storage_no_backup_files_description),
            stats = noBackupStats,
            clearTarget = null
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_downloaded_apps,
            title = context.getString(R.string.storage_downloaded_apps),
            description = context.getString(R.string.storage_downloaded_apps_description),
            stats = downloadedAppsStats,
            clearTarget = StorageClearTarget.DownloadedApps
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_patch_bundles,
            title = context.getString(R.string.storage_patch_bundles),
            description = context.getString(R.string.storage_patch_bundles_description),
            stats = patchBundlesStats,
            clearTarget = StorageClearTarget.PatchBundles
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_signing_files,
            title = context.getString(R.string.storage_signing_files),
            description = context.getString(R.string.storage_signing_files_description),
            stats = signingStats,
            clearTarget = StorageClearTarget.SigningFiles
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_downloader_plugins,
            title = context.getString(R.string.storage_downloader_plugins),
            description = context.getString(R.string.storage_downloader_plugins_description),
            stats = downloaderPluginsStats,
            clearTarget = StorageClearTarget.DownloaderPlugins
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_patched_apps,
            title = context.getString(R.string.storage_patched_apps),
            description = context.getString(R.string.storage_patched_apps_description),
            stats = patchedAppsStats,
            clearTarget = StorageClearTarget.PatchedApps
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_patch_profile_inputs,
            title = context.getString(R.string.storage_patch_profile_inputs),
            description = context.getString(R.string.storage_patch_profile_inputs_description),
            stats = patchProfileInputsStats,
            clearTarget = StorageClearTarget.PatchProfileInputs
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_custom_backgrounds,
            title = context.getString(R.string.storage_custom_backgrounds),
            description = context.getString(R.string.storage_custom_backgrounds_description),
            stats = customBackgroundsStats,
            clearTarget = StorageClearTarget.CustomBackgrounds
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_temporary_workspace,
            title = context.getString(R.string.storage_temporary_workspace),
            description = context.getString(R.string.storage_temporary_workspace_description),
            stats = temporaryWorkspaceStats,
            clearTarget = StorageClearTarget.TemporaryWorkspace
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_ui_temporary_workspace,
            title = context.getString(R.string.storage_ui_temporary_workspace),
            description = context.getString(R.string.storage_ui_temporary_workspace_description),
            stats = uiTemporaryWorkspaceStats,
            clearTarget = StorageClearTarget.UiTemporaryWorkspace
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_other_internal_data,
            title = context.getString(R.string.storage_other_internal_data),
            description = context.getString(R.string.storage_other_internal_data_description),
            stats = otherInternalStats,
            clearTarget = null
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_external_cache,
            title = context.getString(R.string.storage_external_cache),
            description = context.getString(R.string.storage_external_cache_description),
            stats = externalCacheStats,
            clearTarget = StorageClearTarget.ExternalCache
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_external_files,
            title = context.getString(R.string.storage_external_files),
            description = context.getString(R.string.storage_external_files_description),
            stats = externalFilesStats,
            clearTarget = StorageClearTarget.ExternalFiles
        )
    )
    StorageSnapshot(
        areas = areas,
        totalBytes = areas.fold(0L) { total, area -> total + area.stats.bytes }
    )
}

private suspend fun clearAppCache(context: Context): Long = withContext(Dispatchers.IO) {
    listOf(context.cacheDir, context.codeCacheDir)
        .plus(context.externalCacheDirs.filterNotNull())
        .sumOf { it.deleteContentsAndReturnBytes() }
}

private suspend fun clearStorageTarget(
    context: Context,
    target: StorageClearTarget,
    prefs: PreferencesManager,
    downloadedAppRepository: DownloadedAppRepository,
    downloaderPluginRepository: DownloaderPluginRepository,
    installedAppRepository: InstalledAppRepository,
    patchBundleRepository: PatchBundleRepository,
    patchProfileRepository: PatchProfileRepository
): Long = when (target) {
    StorageClearTarget.InternalCache -> clearStorageDirectories(context.cacheDir)
    StorageClearTarget.CodeCache -> clearStorageDirectories(context.codeCacheDir)
    StorageClearTarget.CustomBackgrounds -> measureClearedStorage(context.filesDir.resolve("custom_background")) {
        withContext(Dispatchers.IO) {
            context.filesDir.resolve("custom_background").deleteContentsAndReturnBytes()
        }
        prefs.customBackgroundImageUri.update("")
    }
    StorageClearTarget.DownloadedApps -> measureClearedStorage(context.privateAppDir("downloaded-apps")) {
        val downloadedApps = downloadedAppRepository.getAll().first()
        if (downloadedApps.isNotEmpty()) {
            downloadedAppRepository.delete(downloadedApps)
        }
    }
    StorageClearTarget.PatchBundles -> measureClearedStorage(context.privateAppDir("patch_bundles")) {
        val sources = patchBundleRepository.sources.first()
        if (sources.isNotEmpty()) {
            patchBundleRepository.remove(*sources.toTypedArray())
        }
    }
    StorageClearTarget.SigningFiles -> clearStorageDirectories(context.privateAppDir("signing"))
    StorageClearTarget.DownloaderPlugins -> measureClearedStorage(context.privateAppDir("managed_downloader_plugins")) {
        downloaderPluginRepository.reload()
        downloaderPluginRepository.sourceStates.value.keys.toList().forEach { sourceId ->
            downloaderPluginRepository.removeSource(sourceId)
        }
    }
    StorageClearTarget.PatchedApps -> measureClearedStorage(context.privateAppDir("patched-apps")) {
        val savedApps = installedAppRepository.getByInstallType(InstallType.SAVED)
        savedApps.forEach { app ->
            installedAppRepository.delete(app)
        }
        withContext(Dispatchers.IO) {
            context.privateAppDir("patched-apps").deleteContentsAndReturnBytes()
        }
    }
    StorageClearTarget.PatchProfileInputs -> measureClearedStorage(context.privateAppDir("patch-profile-inputs")) {
        patchProfileRepository.profilesFlow().first()
            .filter { it.apkPath != null }
            .forEach { profile ->
                File(profile.apkPath!!).delete()
                patchProfileRepository.updateProfileApk(
                    uid = profile.uid,
                    apkPath = null,
                    apkVersion = null,
                    apkSourcePath = null,
                    appVersion = if (profile.useSelectedApkVersion) null else profile.appVersion,
                    useSelectedApkVersion = false
                )
            }
        withContext(Dispatchers.IO) {
            context.privateAppDir("patch-profile-inputs").deleteContentsAndReturnBytes()
        }
    }
    StorageClearTarget.TemporaryWorkspace -> clearStorageDirectories(context.privateAppDir("ephemeral"))
    StorageClearTarget.UiTemporaryWorkspace -> clearStorageDirectories(context.privateAppDir("ui_ephemeral"))
    StorageClearTarget.ExternalCache -> clearStorageDirectories(context.externalCacheDirs.filterNotNull())
    StorageClearTarget.ExternalFiles -> clearStorageDirectories(context.getExternalFilesDirs(null).filterNotNull())
}

private suspend fun clearStorageDirectories(vararg directories: File): Long =
    clearStorageDirectories(directories.toList())

private suspend fun clearStorageDirectories(directories: List<File>): Long = withContext(Dispatchers.IO) {
    directories.sumOf { it.deleteContentsAndReturnBytes() }
}

private suspend fun measureClearedStorage(
    vararg directories: File,
    clear: suspend () -> Unit
): Long {
    val before = withContext(Dispatchers.IO) { directories.toList().combinedStats().bytes }
    clear()
    val after = withContext(Dispatchers.IO) { directories.toList().combinedStats().bytes }
    return (before - after).coerceAtLeast(0L)
}

private fun Context.privateAppDir(name: String): File =
    File(applicationInfo.dataDir, "app_$name")

private fun List<File>.combinedStats(): DirectoryStats =
    fold(DirectoryStats()) { total, file -> total + file.directoryStats() }

private fun File?.directoryStats(): DirectoryStats {
    if (this == null || !exists()) return DirectoryStats()
    if (isFile) return DirectoryStats(bytes = length(), fileCount = 1L)

    var bytes = 0L
    var fileCount = 0L
    var directoryCount = 0L
    listFiles().orEmpty().forEach { child ->
        val childStats = child.directoryStats()
        bytes += childStats.bytes
        fileCount += childStats.fileCount
        directoryCount += childStats.directoryCount + if (child.isDirectory) 1L else 0L
    }
    return DirectoryStats(
        bytes = bytes,
        fileCount = fileCount,
        directoryCount = directoryCount
    )
}

private fun File.deleteContentsAndReturnBytes(): Long {
    if (!exists()) return 0L
    if (isFile) {
        val bytes = length()
        val deleted = runCatching { delete() }.getOrDefault(false)
        return if (deleted) bytes else 0L
    }
    if (!isDirectory) return 0L
    return listFiles().orEmpty().sumOf { child ->
        val bytes = child.directoryStats().bytes
        val deleted = runCatching { child.deleteRecursively() }.getOrDefault(false)
        if (deleted) bytes else 0L
    }
}

private fun Context.openAppStorageSettings(): Boolean {
    val uri = Uri.fromParts("package", packageName, null)
    val intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
    return try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
