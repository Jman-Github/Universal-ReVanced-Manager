package app.urv.manager.ui.component.patcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.universal.revanced.manager.R
import app.urv.manager.ui.component.CenteredDialogTitle
import app.urv.manager.util.transparentListItemColors

// Code adapted from Morphe, see third-party/NOTICE for more information
// https://github.com/MorpheApp/morphe-manager/pull/734
@Composable
fun ShizukuConfigurationDialog(
    installAsPlayStore: Boolean,
    autoInstall: Boolean,
    autoUninstallOnConflict: Boolean,
    onInstallAsPlayStoreChange: (Boolean) -> Unit,
    onAutoInstallChange: (Boolean) -> Unit,
    onAutoUninstallOnConflictChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var showPlayStoreWarning by remember { mutableStateOf(false) }
    var showAutoUninstallWarning by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            CenteredDialogTitle(
                stringResource(R.string.installer_shizuku_configure_title)
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.done))
            }
        },
        text = {
            Column {
                ConfigurationToggle(
                    title = stringResource(R.string.installer_play_store_mode),
                    description = stringResource(
                        R.string.installer_play_store_mode_description
                    ),
                    checked = installAsPlayStore,
                    onCheckedChange = { enabled ->
                        if (enabled) showPlayStoreWarning = true
                        else onInstallAsPlayStoreChange(false)
                    }
                )
                ConfigurationToggle(
                    title = stringResource(R.string.settings_auto_install_with_shizuku),
                    description = stringResource(
                        R.string.settings_auto_install_with_shizuku_description
                    ),
                    checked = autoInstall,
                    onCheckedChange = onAutoInstallChange
                )
                ConfigurationToggle(
                    title = stringResource(
                        R.string.settings_auto_uninstall_with_shizuku
                    ),
                    description = stringResource(
                        R.string.settings_auto_uninstall_with_shizuku_description
                    ),
                    checked = autoUninstallOnConflict,
                    onCheckedChange = { enabled ->
                        if (enabled) showAutoUninstallWarning = true
                        else onAutoUninstallOnConflictChange(false)
                    }
                )
            }
        }
    )

    if (showPlayStoreWarning) {
        AlertDialog(
            onDismissRequest = { showPlayStoreWarning = false },
            title = {
                CenteredDialogTitle(
                    stringResource(R.string.installer_play_store_warning_title)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.installer_play_store_warning_message))
                    Text(
                        stringResource(R.string.installer_play_store_warning_risk),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showPlayStoreWarning = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onInstallAsPlayStoreChange(true)
                    showPlayStoreWarning = false
                }) {
                    Text(stringResource(R.string.installer_play_store_warning_continue))
                }
            }
        )
    }

    if (showAutoUninstallWarning) {
        AlertDialog(
            onDismissRequest = { showAutoUninstallWarning = false },
            title = {
                CenteredDialogTitle(
                    stringResource(R.string.settings_auto_uninstall_warning_title)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.settings_auto_uninstall_warning_message))
                    Text(
                        stringResource(R.string.settings_auto_uninstall_warning_risk),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showAutoUninstallWarning = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onAutoUninstallOnConflictChange(true)
                    showAutoUninstallWarning = false
                }) {
                    Text(stringResource(R.string.enable))
                }
            }
        )
    }
}

@Composable
private fun ConfigurationToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        colors = transparentListItemColors,
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    )
}
