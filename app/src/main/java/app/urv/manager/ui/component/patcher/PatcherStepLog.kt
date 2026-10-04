package app.urv.manager.ui.component.patcher

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.universal.revanced.manager.R
import app.urv.manager.ui.component.ArrowButton
import app.urv.manager.ui.model.StepLog

@Composable
internal fun PatcherStepLog(log: StepLog) {
    val scrollState = rememberScrollState()
    var followLatest by remember(log.sessionId) { mutableStateOf(true) }
    var expanded by rememberSaveable(log.sessionId) { mutableStateOf(true) }
    val latestRevision by rememberUpdatedState(log.revision)
    val hasEntries by rememberUpdatedState(log.entries.isNotEmpty())
    val isDragged by scrollState.interactionSource.collectIsDraggedAsState()
    val showJumpToLatest by remember(scrollState, log.sessionId) {
        derivedStateOf { !followLatest && scrollState.canScrollForward }
    }

    LaunchedEffect(scrollState, log.sessionId) {
        snapshotFlow { isDragged to !scrollState.canScrollForward }
            .collect { (dragged, atLatest) ->
                when {
                    dragged -> followLatest = false
                    atLatest -> followLatest = true
                }
            }
    }
    LaunchedEffect(scrollState, log.sessionId) {
        snapshotFlow { Triple(latestRevision, followLatest, expanded) }
            .collect { (_, shouldFollow, isExpanded) ->
                if (shouldFollow && isExpanded && hasEntries) {
                    withFrameNanos { }
                    scrollState.scrollTo(scrollState.maxValue)
                }
            }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 36.dp, vertical = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                stringResource(R.string.tools_signature_metadata_injector_log_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            ArrowButton(
                modifier = Modifier.size(20.dp),
                expanded = expanded,
                onClick = null
            )
        }
        AnimatedVisibility(visible = expanded) {
            Box(Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        text = log.entries.joinToString("\n"),
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(min = 96.dp, max = 220.dp)
                            .verticalScroll(scrollState),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (showJumpToLatest) {
                    SmallFloatingActionButton(
                        onClick = { followLatest = true },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Icon(
                            Icons.Outlined.KeyboardArrowDown,
                            contentDescription = stringResource(
                                R.string.tools_signature_metadata_injector_log_jump_latest
                            ),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
