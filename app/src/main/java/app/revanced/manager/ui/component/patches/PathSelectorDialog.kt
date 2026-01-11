package app.revanced.manager.ui.component.patches

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.universal.revanced.manager.R
import app.revanced.manager.data.platform.Filesystem
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.patcher.split.SplitApkInspector
import app.revanced.manager.patcher.split.SplitApkPreparer
import app.revanced.manager.ui.component.AppTopBar
import app.revanced.manager.ui.component.AppIcon
import app.revanced.manager.ui.component.ConfirmDialog
import app.revanced.manager.ui.component.FullscreenDialog
import app.revanced.manager.ui.component.GroupHeader
import app.revanced.manager.ui.component.LazyColumnWithScrollbar
import app.revanced.manager.util.toast
import app.revanced.manager.util.APK_FILE_EXTENSIONS
import app.revanced.manager.util.PM
import app.revanced.manager.util.saver.PathSaver
import org.koin.compose.koinInject
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import kotlin.io.path.absolutePathString
import kotlin.io.path.isDirectory
import kotlin.io.path.isReadable
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PathSelectorDialog(
    roots: List<Filesystem.StorageRoot>,
    onSelect: (Path?) -> Unit,
    fileFilter: (Path) -> Boolean = { true },
    allowDirectorySelection: Boolean = true,
    fileTypeLabel: String? = null,
    confirmButtonText: String? = null,
    onConfirm: ((Path) -> Unit)? = null
) {
    val context = LocalContext.current
    val prefs = koinInject<PreferencesManager>()
    val pm = koinInject<PM>()
    val filesystem = koinInject<Filesystem>()
    val scope = rememberCoroutineScope()
    val availableRoots = remember(roots) {
        roots.filter { runCatching { it.path.isReadable() }.getOrDefault(true) }.ifEmpty { roots }
    }
    val defaultRoot = availableRoots.firstOrNull() ?: return
    val lastDirectoryValue by prefs.pathSelectorLastDirectory.getAsState()
    val (initialRootPath, initialDirectory) = remember(availableRoots, defaultRoot, lastDirectoryValue) {
        val lastPath = lastDirectoryValue.takeIf { it.isNotBlank() }
            ?.let { runCatching { Paths.get(it) }.getOrNull() }
        val resolved = lastPath
            ?.let { if (it.isDirectory()) it else it.parent }
            ?.takeIf { it.isReadable() }
        val rootForResolved = resolved?.let { dir ->
            availableRoots.firstOrNull { dir.startsWith(it.path) }
        }
        val root = rootForResolved ?: defaultRoot
        val directory = if (rootForResolved != null) resolved ?: root.path else root.path
        root.path to directory
    }
    var currentRootPath by rememberSaveable(initialRootPath, stateSaver = PathSaver) {
        mutableStateOf(initialRootPath)
    }
    val currentRoot = remember(currentRootPath, availableRoots) {
        availableRoots.firstOrNull { it.path == currentRootPath } ?: defaultRoot
    }
    var currentDirectory by rememberSaveable(initialRootPath, stateSaver = PathSaver) {
        mutableStateOf(initialDirectory)
    }
    val notAtRootDir = remember(currentDirectory, currentRoot) {
        currentDirectory != currentRoot.path
    }
    val entries = remember(currentDirectory) {
        runCatching { currentDirectory.listDirectoryEntries().filter(Path::isReadable) }
            .getOrDefault(emptyList())
    }
    val directories = remember(entries) {
        entries.filter(Path::isDirectory).sortedWith(PathNameComparator)
    }
    val files = remember(entries, fileFilter) {
        entries.filterNot(Path::isDirectory).filter(fileFilter).sortedWith(PathNameComparator)
    }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val normalizedQuery = searchQuery.trim()
    val filteredDirectories = remember(directories, normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            directories
        } else {
            directories.filter { it.name.contains(normalizedQuery, ignoreCase = true) }
        }
    }
    val filteredFiles = remember(files, normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            files
        } else {
            files.filter { it.name.contains(normalizedQuery, ignoreCase = true) }
        }
    }
    val duplicateDirectoryNames = remember(directories) {
        directories
            .groupingBy { it.name.lowercase(Locale.ROOT) }
            .eachCount()
            .filterValues { it > 1 }
            .keys
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

    LaunchedEffect(currentDirectory, lastDirectoryValue) {
        val nextValue = currentDirectory.absolutePathString()
        if (nextValue != lastDirectoryValue) {
            prefs.pathSelectorLastDirectory.update(nextValue)
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
                    },
                    actions = {
                        if (confirmButtonText != null && onConfirm != null) {
                            TextButton(onClick = { onConfirm(currentDirectory) }) {
                                Text(confirmButtonText)
                            }
                        }
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

                item(key = "search") {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text(stringResource(R.string.path_selector_search)) },
                        singleLine = true,
                        trailingIcon = {
                            if (searchQuery.isNotBlank()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.clear)
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp)
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
                            icon = if (isDir) Icons.Outlined.Folder else fileIconForPath(favorite),
                            leadingContent = if (isDir) null else {
                                {
                                    ApkFileIcon(
                                        path = favorite,
                                        pm = pm,
                                        filesystem = filesystem
                                    )
                                }
                            },
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

                if (filteredDirectories.isNotEmpty()) {
                    item(key = "dirs_header") {
                        GroupHeader(title = stringResource(R.string.path_selector_dirs))
                    }
                }
                items(filteredDirectories, key = { it.absolutePathString() }) {
                    val nameKey = it.name.lowercase(Locale.ROOT)
                    PathItem(
                        onClick = { currentDirectory = it },
                        onLongClick = { handleFavoritePress(it) },
                        icon = Icons.Outlined.Folder,
                        name = it.name,
                        supportingText = if (nameKey in duplicateDirectoryNames) {
                            it.absolutePathString()
                        } else {
                            null
                        }
                    )
                }

                if (filteredFiles.isNotEmpty()) {
                    item(key = "files_header") {
                        val header = if (!fileTypeLabel.isNullOrBlank()) {
                            "${stringResource(R.string.path_selector_files)} ($fileTypeLabel)"
                        } else {
                            stringResource(R.string.path_selector_files)
                        }
                        GroupHeader(title = header)
                    }
                }
                items(filteredFiles, key = { it.absolutePathString() }) {
                    PathItem(
                        onClick = { onSelect(it) },
                        onLongClick = { handleFavoritePress(it) },
                        icon = fileIconForPath(it),
                        leadingContent = {
                            ApkFileIcon(
                                path = it,
                                pm = pm,
                                filesystem = filesystem
                            )
                        },
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

private fun fileIconForPath(path: Path): ImageVector {
    val lowerName = path.fileName?.toString()?.lowercase().orEmpty()
    return if (lowerName.endsWith(".apk")) {
        Icons.Outlined.Android
    } else {
        Icons.AutoMirrored.Outlined.InsertDriveFile
    }
}

private val PathNameComparator = compareBy<Path> { it.name.lowercase(Locale.ROOT) }
    .thenBy { it.name }

@Composable
private fun ApkFileIcon(
    path: Path,
    pm: PM,
    filesystem: Filesystem
) {
    val fileName = path.fileName?.toString()?.lowercase().orEmpty()
    val extension = fileName.substringAfterLast('.', "")
    if (extension !in APK_FILE_EXTENSIONS) {
        Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = null)
        return
    }

    var iconInfo by remember(path) { mutableStateOf<ApkIconInfo?>(null) }
    LaunchedEffect(path) {
        iconInfo?.cleanup?.invoke()
        iconInfo = loadApkIconInfo(path, pm, filesystem)
    }
    DisposableEffect(path) {
        onDispose {
            iconInfo?.cleanup?.invoke()
        }
    }

    val packageInfo = iconInfo?.packageInfo
    if (packageInfo != null) {
        AppIcon(
            packageInfo = packageInfo,
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )
    } else {
        Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = null)
    }
}

private data class ApkIconInfo(
    val packageInfo: android.content.pm.PackageInfo?,
    val cleanup: (() -> Unit)?
)

private suspend fun loadApkIconInfo(
    path: Path,
    pm: PM,
    filesystem: Filesystem
): ApkIconInfo? = withContext(Dispatchers.IO) {
    val file = path.toFile()
    if (!file.exists()) return@withContext null
    val extension = file.extension.lowercase()
    val isSplitArchive = extension != "apk" && SplitApkPreparer.isSplitArchive(file)
    if (extension != "apk" && !isSplitArchive) return@withContext null

    if (isSplitArchive) {
        val extracted = SplitApkInspector.extractRepresentativeApk(file, filesystem.tempDir)
            ?: return@withContext null
        val pkgInfo = pm.getPackageInfo(extracted.file)
        ApkIconInfo(pkgInfo, extracted.cleanup)
    } else {
        ApkIconInfo(pm.getPackageInfo(file), null)
    }
}

@Composable
private fun PathItem(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    icon: ImageVector,
    leadingContent: (@Composable () -> Unit)? = null,
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
        leadingContent = {
            leadingContent?.invoke() ?: Icon(icon, contentDescription = null)
        }
    )
}
