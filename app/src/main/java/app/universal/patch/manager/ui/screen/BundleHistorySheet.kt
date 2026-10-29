package app.universal.patch.manager.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.R
import app.universal.patch.manager.network.dto.ExternalBundleSnapshot
import app.universal.patch.manager.network.dto.HistoricalRelease
import app.universal.patch.manager.ui.component.LazyColumnWithScrollbar
import app.universal.patch.manager.ui.component.Markdown
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsCard
import app.universal.patch.manager.ui.viewmodel.BundleDiscoveryViewModel
import app.universal.patch.manager.util.openUrl
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BundleHistorySheet(
    bundle: ExternalBundleSnapshot,
    viewModel: BundleDiscoveryViewModel,
    onDismiss: () -> Unit,
    onViewPatches: (ExternalBundleSnapshot) -> Unit,
) {
    var releases by remember(bundle.sourceUrl) { mutableStateOf(emptyList<HistoricalRelease>()) }
    var generation by remember(bundle.sourceUrl) { mutableStateOf<String?>(null) }
    var nextCursor by remember(bundle.sourceUrl) { mutableStateOf<String?>(null) }
    var append by remember { mutableStateOf(false) }
    var force by remember { mutableStateOf(false) }
    var request by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(bundle.sourceUrl, request) {
        loading = true
        error = null
        try {
            var page = try {
                viewModel.releaseHistory(bundle.sourceUrl, if (append) nextCursor else null, force)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (!append || !failure.message.orEmpty().contains("Catalog changed", true)) throw failure
                // Cursors pin a generation. Discard old pages before restarting.
                releases = emptyList()
                generation = null
                append = false
                viewModel.releaseHistory(bundle.sourceUrl, null, true)
            }
            if (append && generation != page.generation) {
                releases = emptyList()
                append = false
                page = viewModel.releaseHistory(bundle.sourceUrl, null, true)
            }
            releases = (if (append) releases + page.data else page.data).distinctBy { it.id }
            generation = page.generation
            nextCursor = page.nextCursor
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = failure.message
        } finally {
            loading = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.catalog_release_history),
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(enabled = !loading, onClick = {
                    append = false
                    force = true
                    releases = emptyList()
                    generation = null
                    nextCursor = null
                    loading = true
                    request++
                }) { Text(stringResource(R.string.catalog_refresh)) }
            }
            Text(bundle.sourceUrl, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            LazyColumnWithScrollbar(modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(releases, key = { it.id }) { release ->
                    generation?.let { currentGeneration ->
                        key(currentGeneration, release.id) {
                            HistoricalReleaseCard(release, currentGeneration, viewModel, onViewPatches)
                        }
                    }
                }
                item {
                    if (loading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else if (error != null) {
                        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { force = true; loading = true; request++ }) {
                            Text(stringResource(R.string.bundle_changelog_retry))
                        }
                    } else if (releases.isEmpty()) {
                        Text(stringResource(R.string.catalog_history_empty))
                    } else if (nextCursor != null) {
                        FilledTonalButton(onClick = {
                            append = true; force = false; loading = true; request++
                        }) { Text(stringResource(R.string.catalog_history_more)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoricalReleaseCard(
    release: HistoricalRelease,
    generation: String,
    viewModel: BundleDiscoveryViewModel,
    onViewPatches: (ExternalBundleSnapshot) -> Unit,
) {
    val context = LocalContext.current
    var showNotes by remember { mutableStateOf(false) }
    var showBundles by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf<HistoricalRelease?>(null) }
    var bundles by remember { mutableStateOf<List<ExternalBundleSnapshot>?>(null) }
    var notesLoading by remember { mutableStateOf(false) }
    var bundlesLoading by remember { mutableStateOf(false) }
    var notesError by remember { mutableStateOf<String?>(null) }
    var bundlesError by remember { mutableStateOf<String?>(null) }
    var notesRequest by remember { mutableIntStateOf(0) }
    var bundlesRequest by remember { mutableIntStateOf(0) }

    LaunchedEffect(showNotes, notesRequest) {
        if (!showNotes || notes != null) return@LaunchedEffect
        notesLoading = true
        notesError = null
        try {
            notes = viewModel.historicalRelease(release.id, generation)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            notesError = failure.message
        } finally {
            notesLoading = false
        }
    }
    LaunchedEffect(showBundles, bundlesRequest) {
        if (!showBundles || bundles != null) return@LaunchedEffect
        bundlesLoading = true
        bundlesError = null
        try {
            bundles = viewModel.historicalBundles(release, generation)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            bundlesError = failure.message
        } finally {
            bundlesLoading = false
        }
    }

    ExpressiveSettingsCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(release.version ?: release.tag ?: release.name ?: release.id,
                style = MaterialTheme.typography.titleMedium)
            Text(listOfNotNull(stringResource(if (release.isPrerelease)
                R.string.patch_bundle_discovery_prerelease else R.string.patch_bundle_discovery_release),
                release.publishedAt?.substringBefore('T')).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall)
            if (release.availability != null && release.availability != "available") {
                Text(stringResource(R.string.catalog_history_unavailable),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            release.bundleTypes.distinct().forEach { type ->
                app.universal.patch.manager.ui.component.bundle.BundleRuntimeNotice(type)
            }
            Row {
                TextButton(onClick = { showNotes = !showNotes }) {
                    Text(stringResource(if (showNotes) R.string.catalog_hide_changelog
                        else R.string.bundle_changelog))
                }
                if (release.bundleIds.isNotEmpty()) {
                    TextButton(onClick = { showBundles = !showBundles }) {
                        Text(stringResource(R.string.patch_bundle_discovery_view_patches))
                    }
                }
            }
            if (showNotes) {
                if (notesLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                notesError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { notesRequest++ }) {
                        Text(stringResource(R.string.bundle_changelog_retry))
                    }
                }
                notes?.let {
                    val text = it.changelog?.takeIf(String::isNotBlank) ?: it.releaseNotes.orEmpty()
                    if (text.isBlank()) Text(stringResource(R.string.catalog_history_no_changelog))
                    else Markdown(text, compactHeadings = true)
                }
            }
            if (release.bundleIds.isEmpty()) Text(stringResource(R.string.catalog_history_no_bundle))
            if (showBundles) {
                if (bundlesLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                bundlesError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { bundlesRequest++ }) {
                        Text(stringResource(R.string.bundle_changelog_retry))
                    }
                }
                bundles?.forEach { historical ->
                    FilledTonalButton(onClick = { onViewPatches(historical) }) {
                        Text(listOf(historical.bundleType, historical.version).filter(String::isNotBlank)
                            .joinToString(" · "))
                    }
                }
                if (bundles?.isEmpty() == true) Text(stringResource(R.string.catalog_history_no_bundle))
            }
            release.releaseUrl?.let { url ->
                TextButton(onClick = { context.openUrl(url) }) {
                    Text(stringResource(R.string.catalog_history_open_release))
                }
            }
        }
    }
}
