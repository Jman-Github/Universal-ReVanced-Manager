package app.revanced.manager.ui.component.patches

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.universal.revanced.manager.R
import app.revanced.manager.data.platform.Filesystem
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.ui.component.AppTopBar
import app.revanced.manager.ui.component.ConfirmDialog
import app.revanced.manager.ui.component.FullscreenDialog
import app.revanced.manager.ui.component.GroupHeader
import app.revanced.manager.ui.component.LazyColumnWithScrollbar
import app.revanced.manager.util.toast
import app.revanced.manager.util.saver.PathSaver
import org.koin.compose.koinInject
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolutePathString
import kotlin.io.path.isDirectory
import kotlin.io.path.isReadable
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PathSelectorDialog(
    roots: List<Filesystem.StorageRoot>,
    onSelect: (Path?) -> Unit,
    fileFilter: (Path) -> Boolean = { true },
    allowDirectorySelection: Boolean = true
) {
    val context = LocalContext.current
    val prefs = koinInject<PreferencesManager>()
    val scope = rememberCoroutineScope()
    val availableRoots = remember(roots) {
        roots.filter { runCatching { it.path.isReadable() }.getOrDefault(true) }.ifEmpty { roots }
    }
    val defaultRoot = availableRoots.firstOrNull() ?: return
    var currentRootPath by rememberSaveable(defaultRoot.path, stateSaver = PathSaver) { mutableStateOf(defaultRoot.path) }
    val currentRoot = remember(currentRootPath, availableRoots) {
        availableRoots.firstOrNull { it.path == currentRootPath } ?: defaultRoot
    }
    var currentDirectory by rememberSaveable(currentRootPath, stateSaver = PathSaver) {
        mutableStateOf(currentRoot.path)
    }
    val notAtRootDir = remember(currentDirectory, currentRoot) {
        currentDirectory != currentRoot.path
    }
    val entries = remember(currentDirectory) {
        runCatching { currentDirectory.listDirectoryEntries().filter(Path::isReadable) }
            .getOrDefault(emptyList())
    }
    val directories = remember(entries) {
        entries.filter(Path::isDirectory)
    }
    val files = remember(entries, fileFilter) {
        entries.filterNot(Path::isDirectory).filter(fileFilter)
    }
    val favoriteSet: Set<String> by prefs.pathSelectorFavorites.getAsState()
    val favorites: List<Path> = remember(favoriteSet, fileFilter) {
        favoriteSet.mapNotNull { runCatching { Paths.get(it) }.getOrNull() }
            .filter { it.isReadable() }
            .sortedBy { it.absolutePathString() }
            .filter { it.isDirectory() || fileFilter(it) }
    }
    var favoritesExpanded by rememberSaveable { mutableStateOf(false) }
    var pendingRemoveFavorite by remember { mutableStateOf<Path?>(null) }

    fun addFavorite(path: Path) {
        val key = path.absolutePathString()
        scope.launch {
            prefs.pathSelectorFavorites.update(favoriteSet + key)
            context.toast(context.getString(R.string.path_selector_favorite_added))
        }
    }

    fun removeFavorite(path: Path) {
        val key = path.absolutePathString()
        scope.launch {
            prefs.pathSelectorFavorites.update(favoriteSet - key)
            context.toast(context.getString(R.string.path_selector_favorite_removed))
        }
    }

    fun handleFavoritePress(path: Path) {
        if (favoriteSet.contains(path.absolutePathString())) {
            pendingRemoveFavorite = path
        } else {
            addFavorite(path)
        }
    }

    FullscreenDialog(
        onDismissRequest = { onSelect(null) },
    ) {
        Scaffold(
            topBar = {
                AppTopBar(
                    title = stringResource(R.string.path_selector),
                    onBackClick = { onSelect(null) },
                    backIcon = {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
                    }
                )
            },
        ) { paddingValues ->
            BackHandler(enabled = notAtRootDir) {
                currentDirectory = currentDirectory.parent
            }

            LazyColumnWithScrollbar(
                modifier = Modifier.padding(paddingValues)
            ) {
                item(key = "current") {
                    PathItem(
                        onClick = { onSelect(currentDirectory) },
                        onLongClick = { handleFavoritePress(currentDirectory) },
                        icon = Icons.Outlined.Folder,
                        name = currentDirectory.toString(),
                        enabled = allowDirectorySelection
                    )
                }

                item(key = "favorites_header") {
                    val headerColor = MaterialTheme.colorScheme.primary
                    val icon = if (favoritesExpanded) {
                        Icons.Outlined.ExpandLess
                    } else {
                        Icons.Outlined.ChevronRight
                    }
                    val description = if (favoritesExpanded) {
                        stringResource(R.string.collapse_content)
                    } else {
                        stringResource(R.string.expand_content)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)
                            .semantics { heading() }
                            .clickable { favoritesExpanded = !favoritesExpanded },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.path_selector_favorites),
                            color = headerColor,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(icon, contentDescription = description, tint = headerColor)
                    }
                }
                if (favoritesExpanded && favorites.isNotEmpty()) {
                    items(favorites, key = { "fav_${it.absolutePathString()}" }) { favorite ->
                        val isDir = favorite.isDirectory()
                        PathItem(
                            onClick = {
                                if (isDir) {
                                    val matchingRoot = availableRoots.firstOrNull {
                                        favorite.startsWith(it.path)
                                    }
                                    matchingRoot?.let { currentRootPath = it.path }
                                    currentDirectory = favorite
                                } else {
                                    onSelect(favorite)
                                }
                            },
                            onLongClick = { handleFavoritePress(favorite) },
                            icon = if (isDir) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile,
                            name = favorite.name.ifBlank { favorite.absolutePathString() },
                            supportingText = favorite.absolutePathString()
                        )
                    }
                }

                if (availableRoots.size > 1) {
                    item(key = "roots_header") {
                        GroupHeader(title = stringResource(R.string.storage))
                    }
                    items(availableRoots, key = { it.path.toString() }) { root ->
                        val icon = if (root.isRemovable) Icons.Outlined.SdCard else Icons.Outlined.Storage
                        PathItem(
                            onClick = {
                                currentRootPath = root.path
                                currentDirectory = root.path
                            },
                            onLongClick = { handleFavoritePress(root.path) },
                            icon = icon,
                            name = root.label
                        )
                    }
                }

                if (notAtRootDir) {
                    item(key = "parent") {
                        PathItem(
                            onClick = { currentDirectory = currentDirectory.parent },
                            icon = Icons.AutoMirrored.Outlined.ArrowBack,
                            name = stringResource(R.string.path_selector_parent_dir)
                        )
                    }
                }

                if (directories.isNotEmpty()) {
                    item(key = "dirs_header") {
                        GroupHeader(title = stringResource(R.string.path_selector_dirs))
                    }
                }
                items(directories, key = { it.absolutePathString() }) {
                    PathItem(
                        onClick = { currentDirectory = it },
                        onLongClick = { handleFavoritePress(it) },
                        icon = Icons.Outlined.Folder,
                        name = it.name
                    )
                }

                if (files.isNotEmpty()) {
                    item(key = "files_header") {
                        GroupHeader(title = stringResource(R.string.path_selector_files))
                    }
                }
                items(files, key = { it.absolutePathString() }) {
                    PathItem(
                        onClick = { onSelect(it) },
                        onLongClick = { handleFavoritePress(it) },
                        icon = Icons.AutoMirrored.Outlined.InsertDriveFile,
                        name = it.name
                    )
                }
            }
        }

        pendingRemoveFavorite?.let { target ->
            val displayName = target.name.ifBlank { target.absolutePathString() }
            ConfirmDialog(
                onDismiss = { pendingRemoveFavorite = null },
                onConfirm = {
                    pendingRemoveFavorite = null
                    removeFavorite(target)
                },
                title = stringResource(R.string.path_selector_favorite_remove_title),
                description = stringResource(R.string.path_selector_favorite_remove_description, displayName),
                icon = Icons.Outlined.Star
            )
        }
    }
}

@Composable
private fun PathItem(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    icon: ImageVector,
    name: String,
    enabled: Boolean = true,
    supportingText: String? = null
) {
    val hasLongClick = onLongClick != null
    val clickEnabled = enabled || hasLongClick
    ListItem(
        modifier = if (clickEnabled) {
            Modifier.combinedClickable(
                enabled = clickEnabled,
                onClick = { if (enabled) onClick() },
                onLongClick = onLongClick
            )
        } else {
            Modifier
        },
        headlineContent = { Text(name) },
        supportingContent = supportingText?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null) }
    )
}
