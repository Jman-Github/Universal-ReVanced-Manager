package app.revanced.manager.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Source
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.revanced.manager.R
import app.revanced.manager.domain.bundles.RemotePatchBundle
import app.revanced.manager.domain.repository.PatchBundleRepository
import app.revanced.manager.network.dto.ExternalBundleSnapshot
import app.revanced.manager.ui.component.AppTopBar
import app.revanced.manager.ui.component.CheckedFilterChip
import app.revanced.manager.ui.component.LazyColumnWithScrollbar
import app.revanced.manager.ui.component.LoadingIndicator
import app.revanced.manager.ui.component.settings.ExpressiveSettingsCard
import app.revanced.manager.ui.viewmodel.BundleDiscoveryViewModel
import app.revanced.manager.util.openUrl
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Brands
import compose.icons.fontawesomeicons.brands.Github
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PatchBundleDiscoveryScreen(
    onBackClick: () -> Unit,
    onViewPatches: (Int) -> Unit,
    viewModel: BundleDiscoveryViewModel = koinViewModel(),
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val context = LocalContext.current
    val patchBundleRepository: PatchBundleRepository = koinInject()
    val sources by patchBundleRepository.sources.collectAsStateWithLifecycle(emptyList())
    val existingEndpoints = remember(sources) {
        sources.filterIsInstance<RemotePatchBundle>().map { it.endpoint }.toSet()
    }
    val bundles = viewModel.bundles
    val isLoading = viewModel.isLoading
    val errorMessage = viewModel.errorMessage
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(BundleFilter.All) }
    val filteredBundles by remember(bundles, query, filter) {
        derivedStateOf {
            if (bundles == null) return@derivedStateOf null
            val trimmedQuery = query.trim().lowercase()
            bundles.filter { bundle ->
                when (filter) {
                    BundleFilter.All -> true
                    BundleFilter.Release -> !bundle.isPrerelease
                    BundleFilter.Prerelease -> bundle.isPrerelease
                }
            }.filter { bundle ->
                if (trimmedQuery.isEmpty()) return@filter true
                val haystack = listOfNotNull(
                    bundle.sourceUrl,
                    bundle.ownerName,
                    bundle.repoName,
                    bundle.repoDescription,
                    bundle.version
                ).joinToString(" ").lowercase()
                haystack.contains(trimmedQuery)
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
        val visibleBundles = filteredBundles
        LazyColumnWithScrollbar(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.patch_bundle_discovery_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.patch_bundle_discovery_search_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth()
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CheckedFilterChip(
                            selected = filter == BundleFilter.All,
                            onClick = { filter = BundleFilter.All },
                            label = { Text(stringResource(R.string.patch_bundle_discovery_all)) }
                        )
                        CheckedFilterChip(
                            selected = filter == BundleFilter.Release,
                            onClick = { filter = BundleFilter.Release },
                            label = { Text(stringResource(R.string.patch_bundle_discovery_release)) }
                        )
                        CheckedFilterChip(
                            selected = filter == BundleFilter.Prerelease,
                            onClick = { filter = BundleFilter.Prerelease },
                            label = { Text(stringResource(R.string.patch_bundle_discovery_prerelease)) }
                        )
                    }
                }
            }

            when {
                isLoading && visibleBundles == null -> {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            LoadingIndicator()
                        }
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
                        Text(
                            text = stringResource(R.string.patch_bundle_discovery_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> {
                    items(visibleBundles, key = { it.bundleId }) { bundle ->
                        val endpoint = viewModel.bundleEndpoint(bundle.bundleId)
                        val isImported = endpoint in existingEndpoints
                        BundleDiscoveryItem(
                            bundle = bundle,
                            isImported = isImported,
                            onImport = { viewModel.importBundle(bundle.bundleId, autoUpdate = true) },
                            onViewPatches = { onViewPatches(bundle.bundleId) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BundleDiscoveryItem(
    bundle: ExternalBundleSnapshot,
    isImported: Boolean,
    onImport: () -> Unit,
    onViewPatches: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val displayName = remember(bundle.ownerName, bundle.repoName, bundle.sourceUrl) {
        listOf(bundle.ownerName, bundle.repoName)
            .filter { it.isNotBlank() }
            .joinToString("/")
            .ifBlank { bundle.sourceUrl }
    }
    val description = bundle.repoDescription?.takeIf { it.isNotBlank() }
    val releaseLabel = if (bundle.isPrerelease) {
        stringResource(R.string.patch_bundle_discovery_prerelease)
    } else {
        stringResource(R.string.patch_bundle_discovery_release)
    }
    val patchCount = bundle.patches.size
    val isSupported = !bundle.isBundleV3
    val importEnabled = isSupported && !isImported
    val viewPatchesEnabled = patchCount > 0
    val importLabel = if (isImported) {
        stringResource(R.string.patch_bundle_discovery_imported)
    } else {
        stringResource(R.string.import_)
    }

    ExpressiveSettingsCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentPadding = PaddingValues(16.dp)
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
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (bundle.version.isNotBlank()) {
                    BundleTag(
                        text = bundle.version,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BundleTag(
                    text = releaseLabel,
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
                BundleTag(
                    text = stringResource(R.string.patch_bundle_discovery_patch_count, patchCount),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
                if (isImported) {
                    BundleTag(
                        text = stringResource(R.string.patch_bundle_discovery_imported),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    enabled = viewPatchesEnabled,
                    onClick = onViewPatches
                ) {
                    Text(text = stringResource(R.string.patch_bundle_discovery_view_patches))
                }
                FilledTonalButton(
                    enabled = importEnabled,
                    onClick = onImport
                ) {
                    Text(text = importLabel)
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

private enum class BundleFilter {
    All,
    Release,
    Prerelease,
}
