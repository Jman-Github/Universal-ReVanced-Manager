package app.urv.manager.ui.component.settings

// Code adapted from Morphe, see third-party/NOTICE for more information
// https://github.com/MorpheApp/morphe-manager/blob/a2c3d31bd7ab42e6bc4b9dd528ed856fc72fb948/app/src/main/java/app/morphe/manager/ui/screen/settings/system/ProcessRuntimeDialog.kt

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.universal.revanced.manager.R
import app.urv.manager.patcher.runtime.MemoryLimitConfig

@Composable
fun ProcessMemoryLimitDialog(
    currentLimit: Int,
    onDismiss: () -> Unit,
    onLimitChange: (Int) -> Unit
) {
    val maxLimit = MemoryLimitConfig.PROCESS_RUNTIME_MEMORY_MAX_LIMIT
    var sliderValue by remember {
        mutableFloatStateOf(
            currentLimit.coerceIn(
                MemoryLimitConfig.PROCESS_RUNTIME_MEMORY_MINIMUM,
                maxLimit
            ).toFloat()
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.process_memory_limit)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(
                        R.string.process_memory_limit_subtitle
                    ),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "${sliderValue.toInt()} MB",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { onLimitChange(sliderValue.toInt()) },
                    valueRange =
                        MemoryLimitConfig.PROCESS_RUNTIME_MEMORY_MINIMUM.toFloat()..
                            maxLimit.toFloat(),
                    steps = (
                        (maxLimit - MemoryLimitConfig.PROCESS_RUNTIME_MEMORY_MINIMUM) /
                            MemoryLimitConfig.PROCESS_RUNTIME_MEMORY_STEP - 1
                        ).coerceAtLeast(0)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${MemoryLimitConfig.PROCESS_RUNTIME_MEMORY_MINIMUM} MB")
                    Text("$maxLimit MB")
                }
                Text(
                    text = stringResource(
                        R.string.process_memory_limit_description
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AnimatedVisibility(
                    visible = sliderValue <
                        MemoryLimitConfig.PROCESS_RUNTIME_MEMORY_LOW_WARNING
                ) {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = stringResource(
                                R.string.process_memory_limit_warning
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                AnimatedVisibility(
                    visible = sliderValue >
                        MemoryLimitConfig.PROCESS_RUNTIME_MEMORY_HIGH_WARNING
                ) {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = stringResource(
                                R.string.process_memory_limit_high_warning
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    )
}
