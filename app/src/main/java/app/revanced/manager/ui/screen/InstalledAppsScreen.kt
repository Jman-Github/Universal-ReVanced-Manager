package app.revanced.manager.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.revanced.manager.R
import app.revanced.manager.data.room.apps.installed.InstallType
import app.revanced.manager.data.room.apps.installed.InstalledApp
import app.revanced.manager.ui.component.AppIcon
import app.revanced.manager.ui.component.AppLabel
import app.revanced.manager.ui.component.LazyColumnWithScrollbar
import app.revanced.manager.ui.component.LoadingIndicator
import app.revanced.manager.ui.component.haptics.HapticCheckbox
import app.revanced.manager.ui.viewmodel.InstalledAppsViewModel
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InstalledAppsScreen(
    onAppClick: (InstalledApp) -> Unit,
    viewModel: InstalledAppsViewModel = koinViewModel()
) {
    val installedApps by viewModel.apps.collectAsStateWithLifecycle(initialValue = null)
    val selectionActive = viewModel.selectedApps.isNotEmpty()

    Column {
        LazyColumnWithScrollbar(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (installedApps.isNullOrEmpty()) Arrangement.Center else Arrangement.Top,
        ) {
            installedApps?.let { installedApps ->
                if (installedApps.isNotEmpty()) {
                    items(
                        installedApps,
                        key = { it.currentPackageName }
                    ) { installedApp ->
                        val packageName = installedApp.currentPackageName
                        val packageInfo = viewModel.packageInfoMap[packageName]
                        val isSaved = installedApp.installType == InstallType.SAVED
                        val isMissingInstall = packageName in viewModel.missingPackages
                        val isSelectable = isSaved || isMissingInstall
                        val isSelected = packageName in viewModel.selectedApps

                        ListItem(
                            modifier = Modifier.combinedClickable(
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
                                }
                            ),
                            leadingContent = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    if (selectionActive) {
                                        HapticCheckbox(
                                            checked = isSelected,
                                            onCheckedChange = if (isSelectable) { checked ->
                                                viewModel.setSelection(installedApp, checked)
                                            } else null,
                                            enabled = isSelectable
                                        )
                                    }
                                    AppIcon(
                                        packageInfo,
                                        contentDescription = null,
                                        Modifier.size(36.dp)
                                    )
                                }
                            },
                            headlineContent = { AppLabel(packageInfo, defaultText = packageName) },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(installedApp.currentPackageName)
                                }
                            }
                        )
                    }
                } else {
                    item {
                        Text(
                            text = stringResource(R.string.no_patched_apps_found),
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }

            } ?: item { LoadingIndicator() }
        }
    }
}
