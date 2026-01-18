package app.revanced.manager.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.revanced.manager.R
import app.revanced.manager.network.dto.ExternalBundlePatch
import app.revanced.manager.ui.component.AppTopBar
import app.revanced.manager.ui.component.LazyColumnWithScrollbar
import app.revanced.manager.ui.component.LoadingIndicator
import app.revanced.manager.ui.component.settings.ExpressiveSettingsCard
import app.revanced.manager.ui.viewmodel.BundleDiscoveryViewModel
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchBundleDiscoveryPatchesScreen(
    bundleId: Int,
    onBackClick: () -> Unit,
    viewModel: BundleDiscoveryViewModel = koinViewModel(),
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val bundles = viewModel.bundles
    val isLoading = viewModel.isLoading
    val errorMessage = viewModel.errorMessage
    val bundle = remember(bundles, bundleId) {
        bundles?.firstOrNull { it.bundleId == bundleId }
    }
    val patches = viewModel.getPatches(bundleId)
    val patchesLoading = viewModel.isPatchesLoading(bundleId)
    val patchesError = viewModel.getPatchesError(bundleId)

    LaunchedEffect(bundleId) {
        viewModel.loadPatches(bundleId)
    }

    val title = bundle?.let {
        stringResource(R.string.patch_bundle_discovery_patches_title, it.ownerName, it.repoName)
    } ?: stringResource(R.string.patch_bundle_discovery_patches_title_fallback)

    Scaffold(
        topBar = {
            AppTopBar(
                title = title,
                scrollBehavior = scrollBehavior,
                onBackClick = onBackClick
            )
        },
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { paddingValues ->
        LazyColumnWithScrollbar(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when {
                (isLoading && bundle == null) || patchesLoading -> {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            LoadingIndicator()
                        }
                    }
                }

                bundle == null || patchesError != null -> {
                    item {
                        Text(
                            text = patchesError ?: errorMessage ?: stringResource(R.string.patch_bundle_discovery_patches_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                patches.isNullOrEmpty() -> {
                    item {
                        Text(
                            text = stringResource(R.string.patch_bundle_discovery_patches_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> {
                    items(patches.size, key = { index -> "${bundle.bundleId}-$index" }) { index ->
                        PatchBundlePatchItem(patches, index)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatchBundlePatchItem(
    patches: List<ExternalBundlePatch>,
    index: Int,
) {
    val patch = patches[index]
    ExpressiveSettingsCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = patch.name ?: stringResource(R.string.patch_bundle_discovery_patch_unnamed),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            patch.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (patch.compatiblePackages.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    patch.compatiblePackages.forEach { pkg ->
                        val versions = pkg.versions.filterNotNull().filter { it.isNotBlank() }
                        val versionLabel = if (versions.isEmpty()) {
                            stringResource(R.string.bundle_version_all_versions)
                        } else {
                            versions.joinToString(", ")
                        }
                        PatchPackageTag(
                            name = pkg.name,
                            versions = versionLabel
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PatchPackageTag(
    name: String,
    versions: String,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                text = versions,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
