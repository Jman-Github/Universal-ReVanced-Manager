package app.urv.manager.ui.component.patcher

import android.graphics.drawable.Drawable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.universal.revanced.manager.R
import app.urv.manager.domain.installer.InstallerManager
import app.urv.manager.util.toast
import app.urv.manager.util.transparentListItemColors
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import app.urv.manager.ui.component.CenteredDialogTitle

@Composable
fun InstallerPickerDialog(
    title: String,
    options: List<InstallerManager.Entry>,
    initialSelection: InstallerManager.Token? = null,
    onDismiss: () -> Unit,
    onConfirm: (InstallerManager.Token) -> Unit,
    onOpenShizuku: (() -> Boolean)? = null
) {
    val context = LocalContext.current
    val shizukuPromptReasons = rememberShizukuPromptReasons()
    var selectedToken by remember(initialSelection) {
        mutableStateOf(
            initialSelection
                ?: options.firstOrNull { it.availability.available }?.token
                ?: options.firstOrNull()?.token
                ?: InstallerManager.Token.Internal
        )
    }

    LaunchedEffect(options, initialSelection) {
        val fallback = initialSelection
            ?.takeIf { selection -> options.any { it.token == selection } }
            ?: options.firstOrNull { it.availability.available }?.token
            ?: options.firstOrNull()?.token
            ?: return@LaunchedEffect
        if (options.none { it.token == selectedToken }) {
            selectedToken = fallback
        }
    }

    val confirmEnabled = options.find { it.token == selectedToken }?.availability?.available == true
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(selectedToken)
                    onDismiss()
                },
                enabled = confirmEnabled
            ) {
                Text(stringResource(R.string.install_app))
            }
        },
        title = { CenteredDialogTitle(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                options.forEach { option ->
                    val enabled = option.availability.available
                    val selected = option.token == selectedToken
                    val isShizukuOption = option.token == InstallerManager.Token.Shizuku ||
                        option.token == InstallerManager.Token.ShizukuGooglePlay
                    val showShizukuAction = isShizukuOption &&
                        option.availability.reason in shizukuPromptReasons &&
                        onOpenShizuku != null
                    val desc = option.description?.takeIf { it.isNotBlank() }
                    val statusBadges = buildList {
                        option.availability.reason?.let { add(context.getString(it)) }
                    }

                    ListItem(
                        modifier = Modifier.clickable(enabled = enabled) {
                            if (enabled) selectedToken = option.token
                        },
                        colors = transparentListItemColors,
                        leadingContent = {
                            val iconDrawable = option.icon
                            val useInstallerIcon = iconDrawable != null && when (option.token) {
                                InstallerManager.Token.Shizuku -> true
                                InstallerManager.Token.ShizukuGooglePlay -> true
                                is InstallerManager.Token.Component -> true
                                else -> false
                            }
                            if (useInstallerIcon) {
                                InstallerIcon(
                                    drawable = iconDrawable,
                                    selected = selected,
                                    enabled = enabled || selected
                                )
                            } else {
                                RadioButton(
                                    selected = selected,
                                    onClick = null,
                                    enabled = enabled
                                )
                            }
                        },
                        headlineContent = {
                            Text(
                                text = option.label,
                                color = if (enabled) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        },
                        supportingContent = {
                            if (desc != null || statusBadges.isNotEmpty() || showShizukuAction) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    desc?.let { line ->
                                        Text(
                                            text = line,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    statusBadges.forEach { status ->
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                            tonalElevation = 0.dp
                                        ) {
                                            Text(
                                                text = status,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                    if (showShizukuAction) {
                                        TextButton(onClick = {
                                            val launched = runCatching { onOpenShizuku?.invoke() ?: false }
                                                .getOrDefault(false)
                                            if (!launched) {
                                                context.toast(context.getString(R.string.installer_shizuku_launch_failed))
                                            }
                                        }) {
                                            Text(stringResource(R.string.installer_action_open_shizuku))
                                        }
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    )
}

@Composable
private fun InstallerIcon(
    drawable: Drawable?,
    selected: Boolean,
    enabled: Boolean
) {
    val colors = MaterialTheme.colorScheme
    val borderColor = if (selected) colors.primary else colors.outlineVariant
    val background = colors.surfaceVariant.copy(alpha = if (enabled) 1f else 0.6f)
    val contentAlpha = if (enabled) 1f else 0.4f

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (drawable != null) {
            Image(
                painter = rememberDrawablePainter(drawable = drawable),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                alpha = contentAlpha
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Android,
                contentDescription = null,
                tint = colors.onSurface.copy(alpha = contentAlpha)
            )
        }
    }
}

private fun rememberShizukuPromptReasons(): Set<Int> = setOf(
    R.string.installer_status_shizuku_not_running,
    R.string.installer_status_shizuku_permission
)
