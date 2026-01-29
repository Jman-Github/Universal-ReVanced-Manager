package app.revanced.manager.ui.screen

import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.revanced.manager.R
import app.revanced.manager.domain.bundles.PatchBundleSource.Extensions.asRemoteOrNull
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.domain.repository.PatchBundleRepository
import app.revanced.manager.network.dto.ExternalBundlePatch
import app.revanced.manager.ui.component.AppTopBar
import app.revanced.manager.ui.component.LazyColumnWithScrollbar
import app.revanced.manager.ui.component.ShimmerBox
import app.revanced.manager.ui.component.bundle.PACKAGE_ICON
import app.revanced.manager.ui.component.bundle.PatchInfoChip
import app.revanced.manager.ui.component.bundle.PatchItem
import app.revanced.manager.ui.component.bundle.VERSION_ICON
import app.revanced.manager.ui.component.settings.ExpressiveSettingsCard
import app.revanced.manager.ui.viewmodel.BundleDiscoveryViewModel
import app.revanced.manager.util.openUrl
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchBundleDiscoveryPatchesScreen(
    bundleId: Int,
    onBackClick: () -> Unit,
    viewModel: BundleDiscoveryViewModel = koinViewModel(),
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val prefs: PreferencesManager = koinInject()
    val patchBundleRepository: PatchBundleRepository = koinInject()
    val searchEngineHost by prefs.searchEngineHost.getAsState()
    val sources by patchBundleRepository.sources.collectAsStateWithLifecycle(emptyList())
    val bundleInfos by patchBundleRepository.allBundlesInfoFlow.collectAsStateWithLifecycle(emptyMap())
    val bundles = viewModel.bundles
    val isLoading = viewModel.isLoading
    val errorMessage = viewModel.errorMessage
    val bundle = remember(bundles, bundleId) {
        bundles?.firstOrNull { it.bundleId == bundleId }
    }
    val patches = viewModel.getPatches(bundleId)
    val patchesLoading = viewModel.isPatchesLoading(bundleId)
    val patchesError = viewModel.getPatchesError(bundleId)

    val importedUid = remember(bundle, sources) {
        val endpoints = bundle?.let { viewModel.bundleEndpoints(it) }.orEmpty()
        sources.firstOrNull { src ->
            src.asRemoteOrNull?.endpoint in endpoints
        }?.uid
    }
    val localPatches = importedUid?.let { bundleInfos[it]?.patches }
    val useLocalPatches = !localPatches.isNullOrEmpty()

    LaunchedEffect(bundleId, useLocalPatches) {
        if (!useLocalPatches) {
            viewModel.loadPatches(bundleId)
        }
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                (isLoading && bundle == null) || (patchesLoading && !useLocalPatches) -> {
                    items(4) {
                        PatchBundlePatchPlaceholderItem()
                    }
                }

                !useLocalPatches && (bundle == null || patchesError != null) -> {
                    item {
                        PatchBundlePatchesEmptyState(
                            message = patchesError ?: errorMessage ?: stringResource(
                                R.string.patch_bundle_discovery_patches_empty
                            )
                        )
                    }
                }

                !useLocalPatches && patches.isNullOrEmpty() -> {
                    item {
                        PatchBundlePatchesEmptyState(
                            message = stringResource(R.string.patch_bundle_discovery_patches_empty)
                        )
                    }
                }

                useLocalPatches -> {
                    itemsIndexed(
                        items = localPatches.orEmpty(),
                        key = { index, patch -> "${importedUid ?: "bundle"}-${patch.name}-$index" }
                    ) { _, patch ->
                        var expandVersions by rememberSaveable(importedUid, patch.name, "versions") {
                            mutableStateOf(false)
                        }
                        var expandOptions by rememberSaveable(importedUid, patch.name, "options") {
                            mutableStateOf(false)
                        }

                        PatchItem(
                            patch,
                            expandVersions,
                            onExpandVersions = { expandVersions = !expandVersions },
                            expandOptions,
                            onExpandOptions = { expandOptions = !expandOptions },
                            searchEngineHost = searchEngineHost
                        )
                    }
                }

                // Currently used for discovery bundles that aren't imported (API has no options)
                else -> {
                    itemsIndexed(
                        items = patches.orEmpty(),
                        key = { index, patch -> "${bundleId}-${patch.name ?: "patch"}-$index" }
                    ) { index, patch ->
                        PatchBundlePatchItem(
                            patch = patch,
                            searchEngineHost = searchEngineHost,
                            stateKey = "${bundleId}-${patch.name ?: "patch"}-$index"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PatchBundlePatchPlaceholderItem() {
    ExpressiveSettingsCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ShimmerBox(modifier = Modifier.fillMaxWidth(0.7f).height(18.dp))
            ShimmerBox(modifier = Modifier.fillMaxWidth(0.9f).height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ShimmerBox(modifier = Modifier.size(width = 120.dp, height = 28.dp))
                ShimmerBox(modifier = Modifier.size(width = 100.dp, height = 28.dp))
                ShimmerBox(modifier = Modifier.size(width = 140.dp, height = 28.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatchBundlePatchItem(
    patch: ExternalBundlePatch,
    searchEngineHost: String,
    stateKey: String,
) {
    val context = LocalContext.current
    var expandVersions by rememberSaveable(stateKey) { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = patch.name ?: stringResource(R.string.patch_bundle_discovery_patch_unnamed),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            patch.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (patch.compatiblePackages.isEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        PatchInfoChip(
                            text = "$PACKAGE_ICON ${stringResource(R.string.patches_view_any_package)}"
                        )
                        PatchInfoChip(
                            text = "$VERSION_ICON ${stringResource(R.string.patches_view_any_version)}",
                            onClick = {
                                context.openUrl(buildSearchUrl("android.app", null, searchEngineHost))
                            }
                        )
                    }
                } else {
                    patch.compatiblePackages.forEach { compatiblePackage ->
                        val packageName = compatiblePackage.name
                        val versions = compatiblePackage.versions
                            .filterNotNull()
                            .filter { it.isNotBlank() }
                            .reversed()

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            PatchInfoChip(
                                modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically),
                                text = "$PACKAGE_ICON $packageName"
                            )

                            if (versions.isNotEmpty()) {
                                if (expandVersions) {
                                    versions.forEach { version ->
                                        PatchInfoChip(
                                            modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically),
                                            text = "$VERSION_ICON $version",
                                            onClick = {
                                                context.openUrl(
                                                    buildSearchUrl(packageName, version, searchEngineHost)
                                                )
                                            }
                                        )
                                    }
                                } else {
                                    PatchInfoChip(
                                        modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically),
                                        text = "$VERSION_ICON ${versions.first()}",
                                        onClick = {
                                            context.openUrl(
                                                buildSearchUrl(packageName, versions.first(), searchEngineHost)
                                            )
                                        }
                                    )
                                }
                                if (versions.size > 1) {
                                    PatchInfoChip(
                                        onClick = { expandVersions = !expandVersions },
                                        text = if (expandVersions) stringResource(R.string.less) else "+${versions.size - 1}"
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
private fun PatchBundlePatchesEmptyState(message: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PatchBundlePatchPlaceholderItem()
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun buildSearchUrl(packageName: String, version: String?, searchEngineHost: String): String {
    val encodedPackage = Uri.encode(packageName)
    val encodedVersion = version?.takeIf { it.isNotBlank() }?.let {
        val formatted = if (it.startsWith("v", ignoreCase = true)) it else "v$it"
        Uri.encode(formatted)
    }
    val encodedArch = Build.SUPPORTED_ABIS.firstOrNull()
        ?.takeIf { it.isNotBlank() }
        ?.let(Uri::encode)
    val query = listOfNotNull(encodedPackage, encodedVersion, encodedArch).joinToString("+")
    val host = normalizeSearchHost(searchEngineHost)
    return "https://$host/search?q=$query"
}

private fun normalizeSearchHost(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return "google.com"
    val noScheme = trimmed.removePrefix("https://").removePrefix("http://")
    val noPath = noScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    return noPath.trim().trimEnd('/').ifBlank { "google.com" }
}
