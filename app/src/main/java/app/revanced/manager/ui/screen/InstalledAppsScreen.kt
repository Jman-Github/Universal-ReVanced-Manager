package app.revanced.manager.ui.screen

import android.content.pm.PackageInfo
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.revanced.manager.data.room.apps.installed.InstallType
import app.revanced.manager.data.room.apps.installed.InstalledApp
import app.revanced.manager.ui.component.AppIcon
import app.revanced.manager.ui.component.AppLabel
import app.revanced.manager.ui.component.LazyColumnWithScrollbar
import app.revanced.manager.ui.component.LoadingIndicator
import app.revanced.manager.ui.component.haptics.HapticCheckbox
import app.revanced.manager.ui.viewmodel.InstalledAppsViewModel
import app.revanced.manager.ui.viewmodel.InstalledAppsViewModel.AppBundleSummary
import app.universal.revanced.manager.R
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InstalledAppsScreen(
    onAppClick: (InstalledApp) -> Unit,
    viewModel: InstalledAppsViewModel = koinViewModel(),
    showOrderDialog: Boolean = false,
    onDismissOrderDialog: () -> Unit = {},
    searchQuery: String = ""
) {
    val context = LocalContext.current
    val installedApps by viewModel.apps.collectAsStateWithLifecycle(initialValue = null)
    val selectionActive = viewModel.selectedApps.isNotEmpty()
    val normalizedQuery = searchQuery.trim().lowercase()
    val filteredApps = installedApps?.let { apps ->
        if (normalizedQuery.isBlank()) {
            apps
        } else {
            apps.filter { app ->
                val packageName = app.currentPackageName
                val packageInfo = viewModel.packageInfoMap[packageName]
                val label = packageInfo?.applicationInfo?.loadLabel(context.packageManager)?.toString().orEmpty()
                val searchText = buildString {
                    append(packageName)
                    if (label.isNotBlank()) {
                        append(' ')
                        append(label)
                    }
                }.lowercase()
                searchText.contains(normalizedQuery)
            }
        }
    }

    when {
        installedApps == null -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                LoadingIndicator()
            }
        }

        installedApps!!.isEmpty() -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_patched_apps_found),
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }

        filteredApps.isNullOrEmpty() && normalizedQuery.isNotBlank() -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.app_filter_no_results),
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }

        else -> {
            LazyColumnWithScrollbar(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(
                    filteredApps.orEmpty(),
                    key = { it.currentPackageName }
                ) { installedApp ->
                        val packageName = installedApp.currentPackageName
                        val packageInfo = viewModel.packageInfoMap[packageName]
                        val isSaved = installedApp.installType == InstallType.SAVED
                        val isMissingInstall = packageName in viewModel.missingPackages
                        val isSelectable = isSaved || isMissingInstall
                        val isSelected = packageName in viewModel.selectedApps
                        val bundleSummaries = viewModel.bundleSummaries[packageName].orEmpty()

                        InstalledAppCard(
                            installedApp = installedApp,
                            packageInfo = packageInfo,
                            isSelected = isSelected,
                            selectionActive = selectionActive,
                            isSelectable = isSelectable,
                            isMissingInstall = isMissingInstall,
                            bundleSummaries = bundleSummaries,
                            onClick = {
                                when {
                                selectionActive && isSelectable -> viewModel.toggleSelection(installedApp)
                                selectionActive -> {}
                                else -> onAppClick(installedApp)
                            }
                        },
                        onLongClick = {
                            if (isSelectable) {
                                viewModel.toggleSelection(installedApp)
                            } else {
                                onAppClick(installedApp)
                            }
                        },
                        onSelectionChange = { checked ->
                            viewModel.setSelection(installedApp, checked)
                        }
                    )
                }
            }
        }
    }

    if (showOrderDialog && installedApps != null) {
        AppsOrderDialog(
            apps = installedApps.orEmpty(),
            appInfoMap = viewModel.packageInfoMap,
            onDismissRequest = onDismissOrderDialog,
            onConfirm = { ordered ->
                viewModel.reorderApps(ordered.map { it.currentPackageName })
                onDismissOrderDialog()
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InstalledAppCard(
    installedApp: InstalledApp,
    packageInfo: PackageInfo?,
    isSelected: Boolean,
    selectionActive: Boolean,
    isSelectable: Boolean,
    isMissingInstall: Boolean,
    bundleSummaries: List<InstalledAppsViewModel.AppBundleSummary>,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectionChange: (Boolean) -> Unit,
) {
    val cardShape = RoundedCornerShape(16.dp)
    val elevation = if (isSelected) 6.dp else 2.dp
                    val formattedVersion = installedApp.version
                        .takeIf { it.isNotBlank() }
                        ?.let(::formatVersion)
                    val detailLine = listOfNotNull(
                        formattedVersion,
                        stringResource(installedApp.installType.stringResource)
                    ).joinToString(" • ")

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(cardShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = cardShape,
        tonalElevation = elevation,
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(elevation)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionActive) {
                HapticCheckbox(
                    checked = isSelected,
                    onCheckedChange = if (isSelectable) onSelectionChange else null,
                    enabled = isSelectable
                )
            }
            AppIcon(
                packageInfo = packageInfo,
                contentDescription = null,
                modifier = Modifier.size(48.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AppLabel(
                    packageInfo = packageInfo,
                    style = MaterialTheme.typography.titleMedium,
                    defaultText = installedApp.currentPackageName
                )
                Text(
                    text = installedApp.currentPackageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (detailLine.isNotBlank()) {
                    Text(
                        text = detailLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (bundleSummaries.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        bundleSummaries.forEach { summary ->
                            val versionText = summary.version?.let(::formatVersion)
                            val bundleLine = listOfNotNull(summary.title, versionText).joinToString(" • ")
                            Text(
                                text = bundleLine,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (isMissingInstall) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip(
                            text = stringResource(R.string.patches_missing),
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }
}

private fun formatVersion(raw: String): String =
    if (raw.startsWith("v", ignoreCase = true)) raw else "v$raw"

@Composable
private fun StatusChip(
    text: String,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = containerColor,
        contentColor = contentColor
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun AppsOrderDialog(
    apps: List<InstalledApp>,
    appInfoMap: Map<String, PackageInfo?>,
    onDismissRequest: () -> Unit,
    onConfirm: (List<InstalledApp>) -> Unit
) {
    val workingOrder = remember(apps) { apps.toMutableStateList() }
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        workingOrder.add(to.index, workingOrder.removeAt(from.index))
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = { onConfirm(workingOrder.toList()) }, enabled = workingOrder.isNotEmpty()) {
                Text(text = stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(R.string.cancel))
            }
        },
        title = { Text(text = stringResource(R.string.apps_reorder_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.apps_reorder_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumnWithScrollbar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    state = lazyListState
                ) {
                    itemsIndexed(workingOrder, key = { _, app -> app.currentPackageName }) { index, app ->
                        val interactionSource = remember { MutableInteractionSource() }
                        ReorderableItem(reorderableState, key = app.currentPackageName) { _ ->
                            AppsOrderRow(
                                index = index,
                                app = app,
                                packageInfo = appInfoMap[app.currentPackageName],
                                interactionSource = interactionSource
                            )
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun ReorderableCollectionItemScope.AppsOrderRow(
    index: Int,
    app: InstalledApp,
    packageInfo: PackageInfo?,
    interactionSource: MutableInteractionSource
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = (index + 1).toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Column(modifier = Modifier.weight(1f)) {
            AppLabel(
                packageInfo = packageInfo,
                style = MaterialTheme.typography.bodyLarge,
                defaultText = app.currentPackageName
            )
            val installTypeLabel = stringResource(app.installType.stringResource)
            Text(
                text = installTypeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(
            onClick = {},
            interactionSource = interactionSource,
            modifier = Modifier.longPressDraggableHandle()
        ) {
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = stringResource(R.string.drag_handle)
            )
        }
    }
}
