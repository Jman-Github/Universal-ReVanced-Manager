package app.revanced.manager.ui.component

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import app.universal.revanced.manager.R

@Composable
fun SafeguardDialog(
    onDismiss: () -> Unit,
    @StringRes title: Int,
    body: String,
    onConfirm: (() -> Unit)? = null,
    @StringRes confirmText: Int = R.string.ok,
    @StringRes dismissText: Int = R.string.cancel,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm ?: onDismiss) {
                Text(stringResource(confirmText))
            }
        },
        dismissButton = if (onConfirm != null) {
            {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(dismissText))
                }
            }
        } else null,
        icon = {
            Icon(Icons.Outlined.WarningAmber, null)
        },
        title = {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center)
            )
        },
        text = {
            Text(body)
        }
    )
}

@Composable
fun NonSuggestedVersionDialog(
    suggestedVersion: String?,
    requiresUniversalPatchesEnabled: Boolean = false,
    onDismiss: () -> Unit
) {
    val body = if (requiresUniversalPatchesEnabled) {
        stringResource(
            R.string.universal_patches_app_blocked_description,
            stringResource(R.string.universal_patches_safeguard)
        )
    } else {
        stringResource(
            R.string.non_suggested_version_warning_description,
            suggestedVersion.orEmpty()
        )
    }

    SafeguardDialog(
        onDismiss = onDismiss,
        title = R.string.non_suggested_version_warning_title,
        body = body,
    )
}

@Composable
fun UniversalFallbackVersionDialog(
    onContinue: () -> Unit,
    onDismiss: () -> Unit
) {
    SafeguardDialog(
        onDismiss = onDismiss,
        title = R.string.universal_fallback_warning_title,
        body = stringResource(R.string.universal_fallback_warning_description),
        onConfirm = onContinue,
        confirmText = R.string.continue_,
    )
}
