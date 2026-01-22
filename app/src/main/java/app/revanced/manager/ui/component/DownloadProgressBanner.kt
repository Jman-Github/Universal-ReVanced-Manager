package app.revanced.manager.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore

@Composable
fun DownloadProgressBanner(
    title: String,
    subtitle: String? = null,
    progress: Float?,
    collapsed: Boolean = false,
    onToggleCollapsed: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val clampedProgress = progress?.coerceIn(0f, 1f)
    val contentPadding = if (collapsed) {
        PaddingValues(horizontal = 6.dp, vertical = 2.dp)
    } else {
        PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    }
    val headerHeight = 24.dp
    val collapsedHeaderHeight = 20.dp
    Surface(
        modifier = modifier.then(
            if (onToggleCollapsed != null) {
                Modifier.clickable(onClick = onToggleCollapsed)
            } else {
                Modifier
            }
        ),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),
        tonalElevation = 2.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(contentPadding)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (collapsed) collapsedHeaderHeight else headerHeight)
            ) {
                if (collapsed) {
                    if (onToggleCollapsed != null) {
                        IconButton(
                            onClick = onToggleCollapsed,
                            modifier = Modifier.align(Alignment.Center)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                } else {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                    if (onToggleCollapsed != null) {
                        IconButton(
                            onClick = onToggleCollapsed,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .height(headerHeight)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ExpandLess,
                                contentDescription = null
                            )
                        }
                    }
                }
            }
            if (!collapsed) {
                subtitle?.let { sub ->
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val progressModifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(4.dp)
                if (clampedProgress == null) {
                    LinearProgressIndicator(modifier = progressModifier)
                } else {
                    LinearProgressIndicator(
                        progress = { clampedProgress },
                        modifier = progressModifier
                    )
                }
            }
        }
    }
}
