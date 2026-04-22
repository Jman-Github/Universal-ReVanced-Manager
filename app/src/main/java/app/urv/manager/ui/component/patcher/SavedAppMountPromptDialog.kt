package app.urv.manager.ui.component.patcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.universal.revanced.manager.R

enum class SavedAppMountPromptMode {
    MOUNT_OR_INSTALL,
    REMOUNT,
    UNMOUNT
}

@Composable
fun SavedAppMountPromptDialog(
    mode: SavedAppMountPromptMode,
    canMount: Boolean,
    onDismiss: () -> Unit,
    onMount: () -> Unit,
    onChooseDifferentInstaller: () -> Unit,
    onRemount: () -> Unit,
    onUnmount: () -> Unit
) {
    val titleRes = when (mode) {
        SavedAppMountPromptMode.MOUNT_OR_INSTALL -> R.string.mount
        SavedAppMountPromptMode.REMOUNT -> R.string.remount_saved_app
        SavedAppMountPromptMode.UNMOUNT -> R.string.unmount
    }
    val descriptionRes = when (mode) {
        SavedAppMountPromptMode.MOUNT_OR_INSTALL -> R.string.saved_app_mount_choice_description
        SavedAppMountPromptMode.REMOUNT -> R.string.saved_app_remount_choice_description
        SavedAppMountPromptMode.UNMOUNT -> R.string.saved_app_unmount_choice_description
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = { Text(stringResource(descriptionRes)) },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.End
            ) {
                when (mode) {
                    SavedAppMountPromptMode.MOUNT_OR_INSTALL -> {
                        TextButton(onClick = onChooseDifferentInstaller) {
                            Text(stringResource(R.string.install_with_different_installer))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onMount, enabled = canMount) {
                                Text(stringResource(R.string.mount))
                            }
                            TextButton(onClick = onDismiss) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    }

                    SavedAppMountPromptMode.REMOUNT -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onUnmount) {
                                Text(stringResource(R.string.unmount))
                            }
                            TextButton(onClick = onRemount, enabled = canMount) {
                                Text(stringResource(R.string.continue_))
                            }
                            TextButton(onClick = onDismiss) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    }

                    SavedAppMountPromptMode.UNMOUNT -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onUnmount) {
                                Text(stringResource(R.string.unmount))
                            }
                            TextButton(onClick = onDismiss) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    }
                }
            }
        }
    )
}
