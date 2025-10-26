package app.revanced.manager.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.universal.revanced.manager.R
import app.revanced.manager.network.dto.ReVancedAsset
import app.revanced.manager.ui.component.AppTopBar
import app.revanced.manager.ui.component.Markdown
import app.revanced.manager.ui.component.haptics.HapticExtendedFloatingActionButton
import app.revanced.manager.ui.viewmodel.UpdateViewModel
import app.revanced.manager.ui.viewmodel.UpdateViewModel.State
import app.revanced.manager.util.relativeTime
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Stable
fun UpdateScreen(
    onBackClick: () -> Unit,
    vm: UpdateViewModel = koinViewModel()
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        topBar = {
            AppTopBar(
                title = {
                    Column {
                        Text(stringResource(vm.state.title))

                        if (vm.state == State.DOWNLOADING) {
                            Text(
                                text = "${vm.downloadedSize.div(1000000)} MB /  ${
                                    vm.totalSize.div(1000000)
                                } MB (${vm.downloadProgress.times(100).toInt()}%)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                onBackClick = onBackClick
            )
        },
        floatingActionButton = {
            val buttonConfig = when (vm.state) {
                State.CAN_DOWNLOAD -> Triple(
                    { vm.downloadUpdate() },
                    R.string.download,
                    Icons.Outlined.InstallMobile
                )

                State.DOWNLOADING -> Triple(onBackClick, R.string.cancel, Icons.Outlined.Cancel)
                State.CAN_INSTALL -> Triple(
                    { vm.installUpdate() },
                    R.string.install_app,
                    Icons.Outlined.InstallMobile
                )

                else -> null
            }

            buttonConfig?.let { (onClick, textRes, icon) ->
                HapticExtendedFloatingActionButton(
                    onClick = onClick::invoke,
                    icon = { Icon(icon, null) },
                    text = { Text(stringResource(textRes)) }
                )
            }

        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues),
        ) {
            if (vm.state == State.DOWNLOADING)
                LinearProgressIndicator(
                    progress = { vm.downloadProgress },
                    modifier = Modifier.fillMaxWidth(),
                )

            AnimatedVisibility(visible = vm.showInternetCheckDialog) {
                MeteredDownloadConfirmationDialog(
                    onDismiss = { vm.showInternetCheckDialog = false },
                    onDownloadAnyways = { vm.downloadUpdate(true) }
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                vm.releaseInfo?.let { info ->
                    UpdateInfoSummary(info)
                }
            }
        }
    }
}

@Composable
private fun MeteredDownloadConfirmationDialog(
    onDismiss: () -> Unit,
    onDownloadAnyways: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        dismissButton = {
            TextButton(onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onDownloadAnyways()
                }
            ) {
                Text(stringResource(R.string.download))
            }
        },
        title = { Text(stringResource(R.string.download_update_confirmation)) },
        icon = { Icon(Icons.Outlined.Update, null) },
        text = { Text(stringResource(R.string.download_confirmation_metered)) }
    )
}

@Composable
private fun UpdateInfoSummary(
    releaseInfo: ReVancedAsset
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val published = remember(releaseInfo.createdAt) {
        releaseInfo.createdAt.relativeTime(context)
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.version) + " " + releaseInfo.version,
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = stringResource(R.string.update_published, published),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (releaseInfo.description.isNotBlank()) {
            Markdown(releaseInfo.description.replace("`", ""))
        }

        releaseInfo.pageUrl?.let { url ->
            TextButton(onClick = { uriHandler.openUri(url) }) {
                Text(stringResource(R.string.changelog))
            }
        }
    }
}
