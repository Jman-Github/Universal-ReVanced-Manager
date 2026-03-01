package app.revanced.manager.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.universal.revanced.manager.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    topBar: @Composable (TopAppBarScrollBehavior) -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { topBar(scrollBehavior) },
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    onBackClick: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
    backIcon: @Composable (() -> Unit) = @Composable {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(
                R.string.back
            )
        )
    },
    actions: @Composable (RowScope.() -> Unit) = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    applyContainerColor: Boolean = false,
    onHelpClick: (() -> Unit)? = null // From PR #37: https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/37
) {
    val containerColor = if (applyContainerColor) {
        MaterialTheme.colorScheme.surfaceColorAtElevation(3.0.dp)
    } else {
        Color.Unspecified
    }

    TopAppBar(
        title = { Text(title) },
        scrollBehavior = scrollBehavior,
        navigationIcon = {
            if (onBackClick != null) {
                TopBarBackButton(
                    onBackClick = onBackClick,
                    onBackLongClick = onBackLongClick,
                    backIcon = backIcon
                )
            }
        },
        actions = {
            // From PR #37: https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/37
            if (onHelpClick != null) {
                IconButton(onClick = onHelpClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = stringResource(R.string.help)
                    )
                }
            }
            actions()
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = containerColor
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: @Composable () -> Unit,
    onBackClick: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
    backIcon: @Composable (() -> Unit) = @Composable {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(
                R.string.back
            )
        )
    },
    actions: @Composable (RowScope.() -> Unit) = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    applyContainerColor: Boolean = false,
    onHelpClick: (() -> Unit)? = null // From PR #37: https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/37
) {
    val containerColor = if (applyContainerColor) {
        MaterialTheme.colorScheme.surfaceColorAtElevation(3.0.dp)
    } else {
        Color.Unspecified
    }

    TopAppBar(
        title = title,
        scrollBehavior = scrollBehavior,
        navigationIcon = {
            if (onBackClick != null) {
                TopBarBackButton(
                    onBackClick = onBackClick,
                    onBackLongClick = onBackLongClick,
                    backIcon = backIcon
                )
            }
        },
        actions = {
            // From PR #37: https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/37
            if (onHelpClick != null) {
                IconButton(onClick = onHelpClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = stringResource(R.string.help)
                    )
                }
            }
            actions()
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = containerColor
        )
    )
}

@Composable
private fun TopBarBackButton(
    onBackClick: () -> Unit,
    onBackLongClick: (() -> Unit)?,
    backIcon: @Composable () -> Unit
) {
    if (onBackLongClick == null) {
        IconButton(onClick = onBackClick) {
            backIcon()
        }
        return
    }

    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onBackClick,
                onLongClick = onBackLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        backIcon()
    }
}
