package app.revanced.manager.ui.screen

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.text.format.Formatter
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Source
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.revanced.manager.R
import app.revanced.manager.domain.bundles.RemotePatchBundle
import app.revanced.manager.data.platform.Filesystem
import app.revanced.manager.domain.repository.PatchBundleRepository
import app.revanced.manager.network.dto.ExternalBundleSnapshot
import app.revanced.manager.ui.component.AppTopBar
import app.revanced.manager.ui.component.CheckedFilterChip
import app.revanced.manager.ui.component.ConfirmDialog
import app.revanced.manager.ui.component.LazyColumnWithScrollbar
import app.revanced.manager.ui.component.ShimmerBox
import app.revanced.manager.ui.component.bundle.LinkOptionRow
import app.revanced.manager.ui.component.patches.PathSelectorDialog
import app.revanced.manager.ui.component.settings.ExpressiveSettingsCard
import app.revanced.manager.ui.viewmodel.BundleDiscoveryViewModel
import app.revanced.manager.util.isAllowedPatchBundleFile
import app.revanced.manager.util.openUrl
import app.revanced.manager.util.relativeTime
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.util.toast
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Brands
import compose.icons.fontawesomeicons.brands.Github
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import coil.compose.AsyncImage
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PatchBundleDiscoveryScreen(
    onBackClick: () -> Unit,
    onViewPatches: (Int) -> Unit,
    viewModel: BundleDiscoveryViewModel = koinViewModel(),
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val context = LocalContext.current
    val prefs: PreferencesManager = koinInject()
    val patchBundleRepository: PatchBundleRepository = koinInject()
    val filesystem: Filesystem = koinInject()
    val storageRoots = remember { filesystem.storageRoots() }
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val sources by patchBundleRepository.sources.collectAsStateWithLifecycle(emptyList())
    val existingEndpoints = remember(sources) {
        sources.filterIsInstance<RemotePatchBundle>().map { it.endpoint }.toSet()
    }
    val bundles = viewModel.bundles
    val isLoading = viewModel.isLoading
    val errorMessage = viewModel.errorMessage
    val isSearchingMore = viewModel.isSearchingMore
    val listState = rememberLazyListState()
    val query = viewModel.bundleSearchQuery
    val packageQuery = viewModel.packageSearchQuery
    val showReleasePref by prefs.patchBundleDiscoveryShowRelease.getAsState()
    val showPrereleasePref by prefs.patchBundleDiscoveryShowPrerelease.getAsState()
    var showRelease by remember { mutableStateOf(showReleasePref) }
    var showPrerelease by remember { mutableStateOf(showPrereleasePref) }
    LaunchedEffect(showRelease, showPrerelease) {
        if (!showRelease && !showPrerelease) {
            showRelease = true
            showPrerelease = true
        }
    }
    LaunchedEffect(showReleasePref) {
        if (showRelease != showReleasePref) {
            showRelease = showReleasePref
        }
    }
    LaunchedEffect(showPrereleasePref) {
        if (showPrerelease != showPrereleasePref) {
            showPrerelease = showPrereleasePref
        }
    }
    LaunchedEffect(showRelease, showReleasePref) {
        if (showRelease != showReleasePref) {
            prefs.patchBundleDiscoveryShowRelease.update(showRelease)
        }
    }
    LaunchedEffect(showPrerelease, showPrereleasePref) {
        if (showPrerelease != showPrereleasePref) {
            prefs.patchBundleDiscoveryShowPrerelease.update(showPrerelease)
        }
    }
    val groupedBundles by remember(bundles, query, showRelease, showPrerelease) {
        derivedStateOf {
            if (bundles == null) return@derivedStateOf null
            val trimmedQuery = query.trim().lowercase()
            val grouped = LinkedHashMap<String, BundleGroup>()
            val order = mutableListOf<String>()

            bundles.forEach { bundle ->
                val owner = bundle.ownerName.takeIf { it.isNotBlank() }
                val repo = bundle.repoName.takeIf { it.isNotBlank() }
                val key = if (owner != null || repo != null) {
                    listOfNotNull(owner, repo).joinToString("/")
                } else {
                    bundle.sourceUrl
                }
                val entry = grouped.getOrPut(key) {
                    order.add(key)
                    BundleGroup(key = key, release = null, prerelease = null)
                }
                grouped[key] = if (bundle.isPrerelease) {
                    if (entry.prerelease == null) entry.copy(prerelease = bundle) else entry
                } else {
                    if (entry.release == null) entry.copy(release = bundle) else entry
                }
            }

            val allowRelease = showRelease
            val allowPrerelease = showPrerelease
            val filteredByType = order.mapNotNull { grouped[it] }.filter { group ->
                val hasRelease = group.release != null
                val hasPrerelease = group.prerelease != null

                (allowRelease && hasRelease) || (allowPrerelease && hasPrerelease)
            }

            filteredByType.filter { group ->
                if (trimmedQuery.isEmpty()) return@filter true
                val haystack = listOfNotNull(group.release, group.prerelease)
                    .flatMap {
                        listOfNotNull(
                            it.sourceUrl,
                            it.ownerName,
                            it.repoName,
                            it.repoDescription,
                            it.version
                        )
                    }
                    .joinToString(" ")
                    .lowercase()
                haystack.contains(trimmedQuery)
            }
        }
    }

    LaunchedEffect(query, packageQuery, showRelease, showPrerelease, bundles) {
        viewModel.ensureSearchCoverage(
            bundleQuery = query,
            packageQuery = packageQuery,
            allowRelease = showRelease,
            allowPrerelease = showPrerelease
        )
    }

    LaunchedEffect(listState, groupedBundles, viewModel.canLoadMore, viewModel.isLoadingMore) {
        snapshotFlow {
            val total = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible to total
        }.collect { (lastVisible, totalItems) ->
            if (groupedBundles.isNullOrEmpty()) return@collect
            if (totalItems > 0 && lastVisible >= totalItems - 3) {
                viewModel.loadMore()
            }
        }
    }
    val variantOverrides = remember { mutableStateMapOf<String, Boolean>() }
    var activeBundleMenu by remember { mutableStateOf<BundleMenuState?>(null) }
    var activeExportBundle by remember { mutableStateOf<ExternalBundleSnapshot?>(null) }
    var exportFileDialogState by remember { mutableStateOf<BundleExportFileDialogState?>(null) }
    var pendingExportConfirmation by remember { mutableStateOf<PendingBundleExportConfirmation?>(null) }

    activeExportBundle?.let { bundle ->
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                if (path == null) activeExportBundle = null
            },
            fileFilter = { isAllowedPatchBundleFile(it) },
            allowDirectorySelection = false,
            fileTypeLabel = bundleExportExtension(bundle),
            confirmButtonText = stringResource(R.string.save),
            onConfirm = { directory ->
                exportFileDialogState = BundleExportFileDialogState(
                    bundle = bundle,
                    directory = directory,
                    fileName = defaultBundleExportName(bundle)
                )
            }
        )
    }

    exportFileDialogState?.let { state ->
        ExportBundleFileNameDialog(
            initialName = state.fileName,
            onDismiss = {
                exportFileDialogState = null
            },
            onConfirm = { fileName ->
                val trimmedName = fileName.trim()
                if (trimmedName.isBlank()) return@ExportBundleFileNameDialog
                exportFileDialogState = null
                val resolvedName = ensureBundleExportExtension(trimmedName, state.bundle)
                val target = state.directory.resolve(resolvedName)
                if (Files.exists(target)) {
                    pendingExportConfirmation = PendingBundleExportConfirmation(
                        bundle = state.bundle,
                        directory = state.directory,
                        fileName = resolvedName
                    )
                } else {
                    viewModel.exportBundle(state.bundle, target)
                    activeExportBundle = null
                }
            }
        )
    }

    pendingExportConfirmation?.let { state ->
        ConfirmDialog(
            onDismiss = {
                pendingExportConfirmation = null
                exportFileDialogState = BundleExportFileDialogState(
                    bundle = state.bundle,
                    directory = state.directory,
                    fileName = state.fileName
                )
            },
            onConfirm = {
                pendingExportConfirmation = null
                viewModel.exportBundle(state.bundle, state.directory.resolve(state.fileName))
                activeExportBundle = null
            },
            title = stringResource(R.string.export_overwrite_title),
            description = stringResource(R.string.export_overwrite_description, state.fileName),
            icon = Icons.Outlined.FileDownload
        )
    }
    activeBundleMenu?.let { menu ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val coroutineScope = rememberCoroutineScope()
        val toggleLabel = stringResource(
            if (menu.showPrerelease) {
                R.string.patch_bundle_discovery_show_release
            } else {
                R.string.patch_bundle_discovery_show_prerelease
            }
        )
        ModalBottomSheet(
            onDismissRequest = { activeBundleMenu = null },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = menu.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                LinkOptionRow(
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    text = stringResource(R.string.patch_bundle_copy_remote_url)
                ) {
                    coroutineScope.launch {
                        sheetState.hide()
                        activeBundleMenu = null
                        val url = viewModel.remoteBundleUrl(menu.bundle)
                        if (url.isNullOrBlank()) {
                            context.toast(context.getString(R.string.patch_bundle_discovery_error))
                        } else {
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText(
                                    context.getString(R.string.patch_bundle_remote_url_label),
                                    url
                                )
                            )
                            context.toast(context.getString(R.string.toast_copied_to_clipboard))
                        }
                    }
                }
                LinkOptionRow(
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    text = stringResource(R.string.patch_bundle_export)
                ) {
                    coroutineScope.launch {
                        sheetState.hide()
                        activeBundleMenu = null
                        activeExportBundle = menu.bundle
                    }
                }
                if (menu.toggleVisible) {
                    LinkOptionRow(
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        text = toggleLabel,
                        enabled = menu.toggleEnabled
                    ) {
                        val newValue = !menu.showPrerelease
                        variantOverrides[menu.groupKey] = newValue
                        activeBundleMenu = menu.copy(showPrerelease = newValue)
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.patch_bundle_discovery_title),
                scrollBehavior = scrollBehavior,
                onBackClick = onBackClick,
                actions = {
                    IconButton(
                        onClick = { context.openUrl("https://revanced-external-bundles.brosssh.com/") }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Link,
                            modifier = Modifier.size(24.dp),
                            contentDescription = stringResource(R.string.patch_bundle_discovery_open_site)
                        )
                    }
                    IconButton(
                        onClick = { context.openUrl("https://github.com/brosssh/revanced-external-bundles") }
                    ) {
                        Icon(
                            imageVector = FontAwesomeIcons.Brands.Github,
                            modifier = Modifier.size(24.dp),
                            contentDescription = stringResource(R.string.patch_bundle_discovery_open_github)
                        )
                    }
                }
            )
        },
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { paddingValues ->
        val visibleBundles = groupedBundles
        LazyColumnWithScrollbar(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                ExpressiveSettingsCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Public,
                                    contentDescription = null,
                                    modifier = Modifier.padding(8.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.patch_bundle_discovery_title),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = stringResource(R.string.patch_bundle_discovery_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        OutlinedTextField(
                            value = packageQuery,
                            onValueChange = {
                                viewModel.packageSearchQuery = it
                                val trimmed = it.trim()
                                viewModel.refreshDebounced(trimmed.ifBlank { null })
                            },
                            label = { Text(stringResource(R.string.patch_bundle_discovery_package_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Search,
                                    contentDescription = null
                                )
                            },
                            trailingIcon = {
                                if (packageQuery.isNotBlank()) {
                                    IconButton(onClick = {
                                        viewModel.packageSearchQuery = ""
                                        viewModel.refreshDebounced(null)
                                    }) {
                                        Icon(
                                            imageVector = Icons.Outlined.Close,
                                            contentDescription = null
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = query,
                            onValueChange = { viewModel.bundleSearchQuery = it },
                            label = { Text(stringResource(R.string.patch_bundle_discovery_search_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Search,
                                    contentDescription = null
                                )
                            },
                            trailingIcon = {
                                if (query.isNotBlank()) {
                                    IconButton(onClick = { viewModel.bundleSearchQuery = "" }) {
                                        Icon(
                                            imageVector = Icons.Outlined.Close,
                                            contentDescription = null
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CheckedFilterChip(
                                selected = showRelease,
                                onClick = {
                                    val newValue = !showRelease
                                    if (!newValue && !showPrerelease) return@CheckedFilterChip
                                    showRelease = newValue
                                },
                                label = { Text(stringResource(R.string.patch_bundle_discovery_release)) }
                            )
                            CheckedFilterChip(
                                selected = showPrerelease,
                                onClick = {
                                    val newValue = !showPrerelease
                                    if (!newValue && !showRelease) return@CheckedFilterChip
                                    showPrerelease = newValue
                                },
                                label = { Text(stringResource(R.string.patch_bundle_discovery_prerelease)) }
                            )
                        }
                    }
                }
            }

            when {
                isLoading && visibleBundles == null -> {
                    items(3) {
                        BundleDiscoveryPlaceholderItem()
                    }
                }

                !errorMessage.isNullOrBlank() -> {
                    item {
                        Text(
                            text = errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                visibleBundles.isNullOrEmpty() -> {
                    item {
                        if (isSearchingMore && (query.isNotBlank() || packageQuery.isNotBlank())) {
                            ExpressiveSettingsCard(
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ShimmerBox(
                                        modifier = Modifier.size(20.dp),
                                        shape = CircleShape
                                    )
                                    Text(
                                        text = stringResource(R.string.patch_bundle_discovery_searching),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.patch_bundle_discovery_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                else -> {
                    items(visibleBundles, key = { it.key }) { group ->
                        BundleDiscoveryItem(
                            groupKey = group.key,
                            releaseBundle = group.release,
                            prereleaseBundle = group.prerelease,
                            allowRelease = showRelease,
                            allowPrerelease = showPrerelease,
                            showPrereleaseOverride = variantOverrides[group.key],
                            isImported = { bundle ->
                                viewModel.bundleEndpoints(bundle).any { it in existingEndpoints }
                            },
                            onImport = { bundle ->
                                viewModel.importBundle(
                                    bundle,
                                    autoUpdate = true,
                                    searchUpdate = true
                                )
                            },
                            onViewPatches = { bundle ->
                                onViewPatches(bundle.bundleId)
                            },
                            onMenuRequest = { activeBundleMenu = it },
                            exportProgressFor = viewModel::getExportProgress,
                            importProgressFor = { bundle, isImported ->
                                viewModel.getImportProgress(bundle, isImported)
                            }
                        )
                    }
                    if (viewModel.isLoadingMore) {
                        item(key = "bundle_loading_more") {
                            ExpressiveSettingsCard(
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ShimmerBox(
                                        modifier = Modifier.size(20.dp),
                                        shape = CircleShape
                                    )
                                    Text(
                                        text = stringResource(R.string.patch_bundle_discovery_loading_more),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BundleDiscoveryPlaceholderItem() {
    ExpressiveSettingsCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ShimmerBox(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ShimmerBox(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(16.dp)
                    )
                    ShimmerBox(
                        modifier = Modifier
                            .fillMaxWidth(0.4f)
                            .height(12.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ShimmerBox(modifier = Modifier.size(28.dp))
                    ShimmerBox(modifier = Modifier.size(28.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShimmerBox(modifier = Modifier.size(width = 70.dp, height = 22.dp))
                ShimmerBox(modifier = Modifier.size(width = 90.dp, height = 22.dp))
                ShimmerBox(modifier = Modifier.size(width = 62.dp, height = 22.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BundleDiscoveryItem(
    groupKey: String,
    releaseBundle: ExternalBundleSnapshot?,
    prereleaseBundle: ExternalBundleSnapshot?,
    allowRelease: Boolean,
    allowPrerelease: Boolean,
    showPrereleaseOverride: Boolean?,
    isImported: (ExternalBundleSnapshot) -> Boolean,
    onImport: (ExternalBundleSnapshot) -> Unit,
    onViewPatches: (ExternalBundleSnapshot) -> Unit,
    onMenuRequest: (BundleMenuState) -> Unit,
    exportProgressFor: (Int) -> BundleDiscoveryViewModel.BundleExportProgress?,
    importProgressFor: (
        ExternalBundleSnapshot,
        Boolean
    ) -> PatchBundleRepository.DiscoveryImportProgress?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val hasRelease = releaseBundle != null
    val hasPrerelease = prereleaseBundle != null
    val releaseAllowed = allowRelease && hasRelease
    val prereleaseAllowed = allowPrerelease && hasPrerelease
    val toggleVisible = allowRelease && allowPrerelease
    val toggleEnabled = toggleVisible && hasRelease && hasPrerelease
    val effectiveShowPrerelease = when {
        releaseAllowed && !prereleaseAllowed -> false
        prereleaseAllowed && !releaseAllowed -> true
        else -> showPrereleaseOverride ?: (!hasRelease && hasPrerelease)
    }
    val bundle = if (effectiveShowPrerelease && prereleaseBundle != null) {
        prereleaseBundle
    } else {
        releaseBundle ?: prereleaseBundle ?: return
    }
    val displayName = remember(bundle.ownerName, bundle.repoName, bundle.sourceUrl) {
        listOf(bundle.ownerName, bundle.repoName)
            .filter { it.isNotBlank() }
            .joinToString("/")
            .ifBlank { bundle.sourceUrl }
    }
    val description = bundle.repoDescription?.takeIf { it.isNotBlank() }
    val patchCount = if (bundle.patches.isNotEmpty()) bundle.patches.size else bundle.patchCount
    val isSupported = !bundle.isBundleV3
    val lastUpdatedLabel = remember(bundle.repoPushedAt) {
        formatRepoUpdatedLabel(context, bundle.repoPushedAt)
    }
    val lastRefreshedLabel = remember(bundle.lastRefreshedAt) {
        formatBundleRefreshedLabel(context, bundle.lastRefreshedAt)
    }
    val releaseImported = releaseBundle?.let(isImported) ?: false
    val prereleaseImported = prereleaseBundle?.let(isImported) ?: false
    val releaseProgress = releaseBundle?.let { importProgressFor(it, releaseImported) }
    val prereleaseProgress = prereleaseBundle?.let { importProgressFor(it, prereleaseImported) }
    val groupProgress = listOfNotNull(releaseProgress, prereleaseProgress)
        .firstOrNull { it.status == PatchBundleRepository.DiscoveryImportStatus.Importing }
        ?: listOfNotNull(releaseProgress, prereleaseProgress)
            .firstOrNull { it.status == PatchBundleRepository.DiscoveryImportStatus.Queued }
    val isImportQueuedForGroup =
        groupProgress?.status == PatchBundleRepository.DiscoveryImportStatus.Queued
    val isImportingForGroup =
        groupProgress?.status == PatchBundleRepository.DiscoveryImportStatus.Importing
    val viewPatchesEnabled = patchCount > 0
    val importEnabled = isSupported &&
        !isImported(bundle) &&
        !isImportQueuedForGroup &&
        !isImportingForGroup
    val importLabel = when {
        isImported(bundle) -> stringResource(R.string.patch_bundle_discovery_imported)
        isImportQueuedForGroup -> stringResource(R.string.patch_bundle_import_queued_label)
        isImportingForGroup -> stringResource(R.string.patch_bundle_importing)
        else -> stringResource(R.string.import_)
    }
    val exportProgress = exportProgressFor(bundle.bundleId)
    val menuState = BundleMenuState(
        groupKey = groupKey,
        bundle = bundle,
        release = releaseBundle,
        prerelease = prereleaseBundle,
        showPrerelease = effectiveShowPrerelease,
        toggleVisible = toggleVisible,
        toggleEnabled = toggleEnabled,
        displayName = displayName
    )

    ExpressiveSettingsCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!bundle.ownerAvatarUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = bundle.ownerAvatarUrl,
                        contentDescription = displayName,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                    )
                } else {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Source,
                            contentDescription = null,
                            modifier = Modifier.padding(8.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (description != null) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (bundle.sourceUrl.isNotBlank()) {
                        Text(
                            text = bundle.sourceUrl,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { context.openUrl(bundle.sourceUrl) }
                        )
                    }
                }
                IconButton(onClick = { onMenuRequest(menuState) }) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.more_options)
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (bundle.bundleType.isNotBlank()) {
                    val typeLabel = remember(bundle.bundleType) {
                        formatBundleTypeLabel(bundle.bundleType)
                    }
                    BundleTag(
                        text = typeLabel,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BundleTag(
                    text = stringResource(
                        if (bundle.isPrerelease) {
                            R.string.patch_bundle_discovery_prerelease
                        } else {
                            R.string.patch_bundle_discovery_release
                        }
                    ),
                    containerColor = if (bundle.isPrerelease) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    contentColor = if (bundle.isPrerelease) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    }
                )
                if (bundle.version.isNotBlank()) {
                    BundleTag(
                        text = bundle.version,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BundleTag(
                    text = stringResource(R.string.patch_bundle_discovery_patch_count, patchCount),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
                if (!lastUpdatedLabel.isNullOrBlank()) {
                    BundleTag(
                        text = lastUpdatedLabel,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!lastRefreshedLabel.isNullOrBlank()) {
                    BundleTag(
                        text = lastRefreshedLabel,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (bundle.isRepoArchived) {
                    BundleTag(
                        text = stringResource(R.string.patch_bundle_discovery_archived_badge),
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                if (isImported(bundle)) {
                    BundleTag(
                        text = stringResource(R.string.patch_bundle_discovery_imported),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (bundle.isRepoArchived) {
                Text(
                    text = stringResource(R.string.patch_bundle_discovery_archived_notice),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (!isSupported) {
                Text(
                    text = stringResource(R.string.patch_bundle_discovery_v3_warning),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    enabled = viewPatchesEnabled,
                    onClick = { onViewPatches(bundle) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = stringResource(R.string.patch_bundle_discovery_view_patches),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip
                    )
                }
                FilledTonalButton(
                    enabled = importEnabled,
                    onClick = { onImport(bundle) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = importLabel,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip
                    )
                }
            }
            if (exportProgress != null) {
                val totalBytes = exportProgress.bytesTotal?.takeIf { it > 0L }
                val fraction = exportProgress.bytesTotal?.takeIf { it > 0L }?.let { total ->
                    exportProgress.bytesRead.toFloat() / total.toFloat()
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.patch_bundle_exporting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val progressLabel = if (totalBytes != null) {
                        val percent = (exportProgress.bytesRead.toDouble() / totalBytes.toDouble()) * 100
                        context.getString(
                            R.string.patch_bundle_export_progress,
                            Formatter.formatShortFileSize(context, exportProgress.bytesRead),
                            Formatter.formatShortFileSize(context, totalBytes),
                            percent
                        )
                    } else {
                        context.getString(
                            R.string.patch_bundle_export_progress_indeterminate,
                            Formatter.formatShortFileSize(context, exportProgress.bytesRead)
                        )
                    }
                    Text(
                        text = progressLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(
                        progress = { fraction ?: 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (groupProgress != null) {
                val totalBytes = groupProgress.bytesTotal?.takeIf { it > 0L }
                val fraction = groupProgress.bytesTotal?.takeIf { it > 0L }?.let { total ->
                    groupProgress.bytesRead.toFloat() / total.toFloat()
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.patch_bundle_importing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val progressLabel = if (isImportQueuedForGroup) {
                        stringResource(R.string.patch_bundle_import_queued_label)
                    } else if (totalBytes != null) {
                        val percent = (groupProgress.bytesRead.toDouble() / totalBytes.toDouble()) * 100
                        context.getString(
                            R.string.patch_bundle_import_progress,
                            Formatter.formatShortFileSize(context, groupProgress.bytesRead),
                            Formatter.formatShortFileSize(context, totalBytes),
                            percent
                        )
                    } else {
                        context.getString(
                            R.string.patch_bundle_import_progress_indeterminate,
                            Formatter.formatShortFileSize(context, groupProgress.bytesRead)
                        )
                    }
                    Text(
                        text = progressLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (fraction == null || isImportQueuedForGroup) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BundleTag(
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ExportBundleFileNameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(stringResource(R.string.file_name)) }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private fun defaultBundleExportName(bundle: ExternalBundleSnapshot): String {
    val base = listOf(bundle.ownerName, bundle.repoName)
        .filter { it.isNotBlank() }
        .joinToString("-")
        .ifBlank { "patch-bundle" }
    val version = bundle.version.takeIf { it.isNotBlank() }?.let { "-$it" }.orEmpty()
    val rawName = sanitizeFileName("$base$version")
    return ensureBundleExportExtension(rawName, bundle)
}

private fun ensureBundleExportExtension(name: String, bundle: ExternalBundleSnapshot): String {
    val trimmed = name.trim()
    val ext = bundleExportExtension(bundle)
    return if (trimmed.lowercase().endsWith(".$ext")) trimmed else "$trimmed.$ext"
}

private fun bundleExportExtension(bundle: ExternalBundleSnapshot): String {
    val type = bundle.bundleType.trim()
    return if (type.startsWith("Morphe", ignoreCase = true)) "mpp" else "rvp"
}

private fun sanitizeFileName(value: String): String =
    value.replace(Regex("[\\\\/:*?\"<>|]"), "_")

private fun formatBundleTypeLabel(rawType: String): String {
    val trimmed = rawType.trim()
    if (trimmed.startsWith("Morphe", ignoreCase = true)) {
        return "Morphe"
    }
    if (trimmed.startsWith("ReVanced", ignoreCase = true)) {
        val version = trimmed.substringAfter(':', "").ifBlank {
            trimmed.substringAfter(' ', "")
        }.trim()
        return if (version.isBlank()) {
            "ReVanced"
        } else {
            "ReVanced $version"
        }
    }
    return trimmed.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun formatRepoUpdatedLabel(context: Context, raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val relative = runCatching {
        Instant.parse(trimmed).toLocalDateTime(TimeZone.UTC).relativeTime(context)
    }.getOrNull() ?: return null
    return context.getString(R.string.bundle_updated_at, relative)
}

private fun formatBundleRefreshedLabel(context: Context, raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val relative = runCatching {
        Instant.parse(trimmed).toLocalDateTime(TimeZone.UTC).relativeTime(context)
    }.getOrNull() ?: return null
    return context.getString(R.string.bundle_refreshed_at, relative)
}

private data class BundleGroup(
    val key: String,
    val release: ExternalBundleSnapshot?,
    val prerelease: ExternalBundleSnapshot?
)

private data class BundleExportFileDialogState(
    val bundle: ExternalBundleSnapshot,
    val directory: Path,
    val fileName: String
)

private data class PendingBundleExportConfirmation(
    val bundle: ExternalBundleSnapshot,
    val directory: Path,
    val fileName: String
)

private data class BundleMenuState(
    val groupKey: String,
    val bundle: ExternalBundleSnapshot,
    val release: ExternalBundleSnapshot?,
    val prerelease: ExternalBundleSnapshot?,
    val showPrerelease: Boolean,
    val toggleVisible: Boolean,
    val toggleEnabled: Boolean,
    val displayName: String
)
