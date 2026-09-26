package app.urv.manager.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.revanced.manager.R
import app.urv.manager.domain.repository.PatchBundleRepository
import app.urv.manager.network.api.ExternalBundlesApi
import app.urv.manager.network.api.ExternalBundlesEndpoints
import app.urv.manager.network.dto.ExternalBundleSnapshot
import app.urv.manager.network.dto.ExternalBundlePatch
import app.urv.manager.network.service.HttpService
import app.urv.manager.network.utils.getOrNull
import app.urv.manager.util.DownloadProgressNotifier
import app.urv.manager.util.simpleMessage
import app.urv.manager.util.toast
import io.ktor.client.request.url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.net.URI
import java.io.File
import java.util.Locale
import java.nio.file.Path

class BundleDiscoveryViewModel(
    private val api: ExternalBundlesApi,
    private val patchBundleRepository: PatchBundleRepository,
    private val app: Application,
    private val http: HttpService,
    private val json: Json,
    private val downloadProgressNotifier: DownloadProgressNotifier,
) : ViewModel() {
    var bundles: List<ExternalBundleSnapshot>? by mutableStateOf(null)
        private set

    var isLoading: Boolean by mutableStateOf(false)
        private set

    var errorMessage: String? by mutableStateOf(null)
        private set

    var bundleSearchQuery: String by mutableStateOf("")
    var packageSearchQuery: String by mutableStateOf("")

    private val patchesByBundle = mutableStateMapOf<BundleInstanceKey, List<ExternalBundlePatch>>()
    private val patchesLoading = mutableStateMapOf<BundleInstanceKey, Boolean>()
    private val patchesError = mutableStateMapOf<BundleInstanceKey, String?>()
    private val bundleCache = mutableMapOf<String, BundleCacheEntry>()
    private val bundleExports = mutableStateMapOf<Int, BundleExportProgress>()
    private val cacheDir = File(app.cacheDir, "bundle_discovery").also { it.mkdirs() }
    private var refreshJob: Job? = null
    private var searchJob: Job? = null
    private var currentQueryKey: String = ""
    private var currentApiHost: String? = null
    private var nextOffset: Int = 0
    private var refreshToken: Int = 0
    private var bundleDatasetGeneration: Int = 0
    private var isFirstPageRefreshInProgress: Boolean = false
    private var searchToken: Int = 0
    private var importProgressSnapshot by mutableStateOf<Map<String, PatchBundleRepository.DiscoveryImportProgress>>(emptyMap())
    private var queuedImportSnapshot by mutableStateOf<Set<String>>(emptySet())
    private val localQueuedKeys = mutableStateMapOf<String, Boolean>()
    private var lastRefreshAt: String? = null
    var isLoadingMore: Boolean by mutableStateOf(false)
        private set
    var canLoadMore: Boolean by mutableStateOf(true)
        private set
    var isSearchingMore: Boolean by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            patchBundleRepository.discoveryImportProgress.collect { progress ->
                importProgressSnapshot = progress
            }
        }
        viewModelScope.launch {
            patchBundleRepository.discoveryImportQueued.collect { queued ->
                queuedImportSnapshot = queued
            }
        }
        refresh()
    }

    fun refreshDebounced(packageNameQuery: String? = null) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            delay(300)
            refresh(packageNameQuery)
        }
    }

    fun refresh(packageNameQuery: String? = null) {
        searchJob?.cancel()
        searchToken++
        isSearchingMore = false
        val key = packageNameQuery?.trim().orEmpty()
        currentQueryKey = key
        nextOffset = 0
        canLoadMore = true
        invalidatePatchState()
        val cached = bundleCache[key] ?: loadDiskCache(key)?.also { bundleCache[key] = it }
        if (cached != null) {
            currentApiHost = cached.apiHost.takeIf(ExternalBundlesEndpoints::isExternalBundlesHost)
                ?: cached.bundles.firstNotNullOfOrNull { bundle ->
                    bundle.apiHost.takeIf(ExternalBundlesEndpoints::isExternalBundlesHost)
                }
            bundles = applyLastRefreshed(cached.bundles)
        } else {
            currentApiHost = null
        }
        val token = ++refreshToken
        isFirstPageRefreshInProgress = true
        viewModelScope.launch {
            if (token != refreshToken) return@launch
            isLoading = cached == null
            errorMessage = null
            try {
                val page = withContext(Dispatchers.IO) {
                    api.getBundles(packageNameQuery, limit = PAGE_SIZE, offset = 0).getOrNull()
                }
                if (token != refreshToken) return@launch
                val refreshJob = page?.refreshJob
                val refreshedAt = (refreshJob?.completedAt ?: refreshJob?.startedAt)
                    ?.trim()
                    .takeIf { !it.isNullOrBlank() }
                if (refreshedAt != null && refreshedAt != lastRefreshAt) {
                    lastRefreshAt = refreshedAt
                }
                if (page == null) {
                    if (cached == null) {
                        errorMessage = app.getString(R.string.patch_bundle_discovery_error)
                    } else if (lastRefreshAt != null) {
                        val updatedBundles = applyLastRefreshed(cached.bundles)
                        val entry = BundleCacheEntry(
                            bundles = updatedBundles,
                            fingerprint = fingerprint(updatedBundles),
                            apiHost = cached.apiHost
                        )
                        bundleCache[key] = entry
                        bundles = entry.bundles
                        persistDiskCache(key, entry)
                    }
                } else {
                    currentApiHost = page.apiHost
                    val resolvedSnapshot = applyLastRefreshed(page.bundles)
                    val fingerprint = fingerprint(resolvedSnapshot)
                    if (cached == null || cached.fingerprint != fingerprint || cached.apiHost != page.apiHost) {
                        val entry = BundleCacheEntry(
                            bundles = resolvedSnapshot,
                            fingerprint = fingerprint,
                            apiHost = page.apiHost
                        )
                        bundleCache[key] = entry
                        bundles = entry.bundles
                        persistDiskCache(key, entry)
                    }
                    nextOffset = resolvedSnapshot.size
                    canLoadMore = resolvedSnapshot.size >= PAGE_SIZE
                    errorMessage = null
                }
            } finally {
                if (token == refreshToken) {
                    isLoading = false
                    isFirstPageRefreshInProgress = false
                }
            }
        }
    }

    fun loadMore() {
        if (isFirstPageRefreshInProgress || !canLoadMore || isLoadingMore) return
        viewModelScope.launch {
            loadNextPageInternal(force = false)
        }
    }

    fun ensureSearchCoverage(
        bundleQuery: String?,
        packageQuery: String?,
        allowRelease: Boolean,
        allowPrerelease: Boolean
    ) {
        val trimmedBundle = bundleQuery?.trim().orEmpty()
        val trimmedPackage = packageQuery?.trim().orEmpty()
        val shouldSearch = trimmedBundle.isNotBlank() || trimmedPackage.isNotBlank()
        if (!shouldSearch) {
            searchJob?.cancel()
            searchToken++
            isSearchingMore = false
            return
        }
        val queryKey = trimmedPackage.ifBlank { currentQueryKey }
        if (queryKey.isNotBlank() && queryKey != currentQueryKey) return
        searchJob?.cancel()
        val localToken = ++searchToken
        isSearchingMore = true
        val token = refreshToken
        searchJob = viewModelScope.launch {
            try {
                val queryLower = trimmedBundle.lowercase()
                while (token == refreshToken) {
                    if (isLoading || isFirstPageRefreshInProgress || isLoadingMore) {
                        delay(50)
                        continue
                    }
                    val found = if (queryLower.isNotBlank()) {
                        hasSearchMatches(queryLower, allowRelease, allowPrerelease)
                    } else {
                        bundles?.isNotEmpty() == true
                    }
                    if (found) break
                    if (!canLoadMore) break
                    val loaded = loadNextPageInternal(force = true)
                    if (!loaded) break
                }
            } finally {
                if (localToken == searchToken) {
                    isSearchingMore = false
                }
            }
        }
    }

    fun importBundle(
        bundle: ExternalBundleSnapshot,
        autoUpdate: Boolean,
        searchUpdate: Boolean,
        preferLatestAcrossChannels: Boolean = false
    ) {
        viewModelScope.launch {
            val key = patchBundleRepository.discoveryImportKey(bundle, preferLatestAcrossChannels)
            val result = patchBundleRepository.enqueueDiscoveryImport(
                bundle = bundle,
                searchUpdate = searchUpdate,
                autoUpdate = autoUpdate,
                preferLatestAcrossChannels = preferLatestAcrossChannels
            )
            if (result != PatchBundleRepository.DiscoveryImportEnqueueResult.Duplicate) {
                localQueuedKeys[key] = true
            }
            if (result == PatchBundleRepository.DiscoveryImportEnqueueResult.Queued) {
                app.toast(app.getString(R.string.patch_bundle_import_queued))
            }
        }
    }

    fun exportBundle(bundle: ExternalBundleSnapshot, target: Path) {
        viewModelScope.launch {
            val bundleId = bundle.bundleId
            val url = bundle.downloadUrl?.trim().takeIf { !it.isNullOrBlank() }
            if (url.isNullOrBlank()) {
                app.toast(app.getString(R.string.patch_bundle_discovery_error))
                return@launch
            }
            bundleExports[bundleId] = BundleExportProgress(0L, null)
            val progressNotification =
                downloadProgressNotifier.begin(target.fileName.toString())
            try {
                withContext(Dispatchers.IO) {
                    target.parent?.toFile()?.mkdirs()
                    http.downloadToFile(
                        saveLocation = target.toFile(),
                        builder = { url(url) },
                        onProgress = { bytesRead, bytesTotal ->
                            progressNotification.update(bytesRead, bytesTotal)
                            viewModelScope.launch(Dispatchers.Main) {
                                bundleExports[bundleId] = BundleExportProgress(bytesRead, bytesTotal)
                            }
                        }
                    )
                }
                progressNotification.complete()
                app.toast(app.getString(R.string.patch_bundle_export_success, target.fileName.toString()))
            } catch (e: CancellationException) {
                progressNotification.cancel()
                throw e
            } catch (e: Exception) {
                progressNotification.fail()
                app.toast(app.getString(R.string.patch_bundle_export_fail, e.simpleMessage()))
            } finally {
                bundleExports.remove(bundleId)
            }
        }
    }

    fun exportBundle(bundle: ExternalBundleSnapshot, target: Uri?) {
        if (target == null) return
        viewModelScope.launch {
            val bundleId = bundle.bundleId
            val url = bundle.downloadUrl?.trim().takeIf { !it.isNullOrBlank() }
            if (url.isNullOrBlank()) {
                app.toast(app.getString(R.string.patch_bundle_discovery_error))
                return@launch
            }
            bundleExports[bundleId] = BundleExportProgress(0L, null)
            val tempFile = File.createTempFile("bundle-export-$bundleId-", ".tmp", cacheDir)
            val successName = bundle.repoName.ifBlank { "bundle" }
            val progressNotification = downloadProgressNotifier.begin(successName)
            try {
                withContext(Dispatchers.IO) {
                    http.downloadToFile(
                        saveLocation = tempFile,
                        builder = { url(url) },
                        onProgress = { bytesRead, bytesTotal ->
                            progressNotification.update(bytesRead, bytesTotal)
                            viewModelScope.launch(Dispatchers.Main) {
                                bundleExports[bundleId] = BundleExportProgress(bytesRead, bytesTotal)
                            }
                        }
                    )
                    app.contentResolver.openOutputStream(target)?.use { output ->
                        tempFile.inputStream().use { input -> input.copyTo(output) }
                    } ?: error("Could not open output stream for bundle export")
                }
                progressNotification.complete()
                app.toast(app.getString(R.string.patch_bundle_export_success, successName))
            } catch (e: CancellationException) {
                progressNotification.cancel()
                throw e
            } catch (e: Exception) {
                progressNotification.fail()
                app.toast(app.getString(R.string.patch_bundle_export_fail, e.simpleMessage()))
            } finally {
                bundleExports.remove(bundleId)
                tempFile.delete()
            }
        }
    }

    fun bundleEndpoints(bundle: ExternalBundleSnapshot): Set<String> {
        val endpoints = mutableSetOf<String>()
        bundle.downloadUrl?.let { endpoints.add(it) }
        externalBundleEndpoint(bundle, useDev = false, prerelease = null)?.let { endpoints.add(it) }
        externalBundleEndpoint(bundle, useDev = true, prerelease = null)?.let { endpoints.add(it) }
        externalBundleEndpoint(bundle, useDev = false)?.let { endpoints.add(it) }
        externalBundleEndpoint(bundle, useDev = true)?.let { endpoints.add(it) }
        legacyV2Endpoint(bundle, useDev = false, prerelease = null)?.let { endpoints.add(it) }
        legacyV2Endpoint(bundle, useDev = true, prerelease = null)?.let { endpoints.add(it) }
        legacyV2Endpoint(bundle, useDev = false)?.let { endpoints.add(it) }
        legacyV2Endpoint(bundle, useDev = true)?.let { endpoints.add(it) }
        legacyEndpoint(bundle.bundleId, bundleApiHost(bundle)).let(endpoints::add)
        return endpoints
    }

    fun discoverySiteUrl(): String {
        val host = currentApiHost
            ?.takeIf(ExternalBundlesEndpoints::isExternalBundlesHost)
            ?: bundles.orEmpty().firstNotNullOfOrNull { bundle ->
                bundle.apiHost.takeIf(ExternalBundlesEndpoints::isExternalBundlesHost)
            }
        return ExternalBundlesEndpoints.siteUrl(host)
    }

    fun remoteBundleUrl(bundle: ExternalBundleSnapshot): String? {
        return ExternalBundlesEndpoints.latestBundleUrl(
            host = bundleApiHost(bundle),
            sourceUrl = bundleSourceUrl(bundle),
            prerelease = bundle.isPrerelease
        )
    }

    private fun bundleApiHost(bundle: ExternalBundleSnapshot): String {
        val apiHost = bundle.apiHost.trim().lowercase(Locale.US)
        return apiHost.takeIf(ExternalBundlesEndpoints::isExternalBundlesHost)
            ?: bundleHostFromDownload(bundle.downloadUrl)
            ?: bundleHostFromDownload(bundle.signatureDownloadUrl)
            ?: ExternalBundlesEndpoints.STABLE_HOST
    }

    private fun legacyEndpoint(bundleId: Int, apiHost: String): String =
        "https://$apiHost/bundles/id?id=$bundleId"

    private fun externalBundleEndpoint(
        bundle: ExternalBundleSnapshot,
        useDev: Boolean,
        prerelease: Boolean? = bundle.isPrerelease
    ): String? {
        val host = if (useDev) {
            ExternalBundlesEndpoints.DEV_HOST
        } else {
            ExternalBundlesEndpoints.STABLE_HOST
        }
        return ExternalBundlesEndpoints.latestBundleUrl(host, bundleSourceUrl(bundle), prerelease)
    }

    private fun legacyV2Endpoint(
        bundle: ExternalBundleSnapshot,
        useDev: Boolean,
        prerelease: Boolean? = bundle.isPrerelease
    ): String? {
        val owner = bundle.ownerName.trim()
        val repo = bundle.repoName.trim()
        if (owner.isBlank() || repo.isBlank()) return null
        val host = if (useDev) ExternalBundlesEndpoints.DEV_HOST else ExternalBundlesEndpoints.STABLE_HOST
        val channel = when (prerelease) {
            null -> "any"
            true -> "prerelease"
            false -> "stable"
        }
        return "https://$host/api/v2/bundle/$owner/$repo/latest?channel=$channel"
    }

    private fun bundleSourceUrl(bundle: ExternalBundleSnapshot): String =
        bundle.sourceUrl.trim().takeIf { it.isNotBlank() }
            ?: if (bundle.ownerName.isNotBlank() && bundle.repoName.isNotBlank()) {
                "https://github.com/${bundle.ownerName.trim()}/${bundle.repoName.trim()}"
            } else {
                ""
            }

    fun loadPatches(bundle: ExternalBundleSnapshot) {
        val key = patchStateKey(bundle)
        if (patchesByBundle.containsKey(key) || patchesLoading[key] == true) return
        val generation = bundleDatasetGeneration
        viewModelScope.launch {
            if (generation != bundleDatasetGeneration) return@launch
            patchesLoading[key] = true
            patchesError[key] = null
            try {
                val patches = withContext(Dispatchers.IO) {
                    api.getBundlePatches(bundle).getOrNull()
                }
                if (generation != bundleDatasetGeneration) return@launch
                if (patches == null) {
                    patchesError[key] = app.getString(R.string.patch_bundle_discovery_error)
                } else {
                    patchesByBundle[key] = patches
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                if (generation == bundleDatasetGeneration) {
                    patchesError[key] = app.getString(R.string.patch_bundle_discovery_error)
                }
            } finally {
                if (generation == bundleDatasetGeneration) {
                    patchesLoading[key] = false
                }
            }
        }
    }

    fun getPatches(bundle: ExternalBundleSnapshot): List<ExternalBundlePatch>? =
        patchesByBundle[patchStateKey(bundle)]

    fun isPatchesLoading(bundle: ExternalBundleSnapshot): Boolean =
        patchesLoading[patchStateKey(bundle)] == true

    fun getPatchesError(bundle: ExternalBundleSnapshot): String? =
        patchesError[patchStateKey(bundle)]

    fun getExportProgress(bundleId: Int): BundleExportProgress? = bundleExports[bundleId]

    fun getImportProgress(
        bundle: ExternalBundleSnapshot,
        isImported: Boolean
    ): PatchBundleRepository.DiscoveryImportProgress? {
        val keys = buildList {
            add(patchBundleRepository.discoveryImportKey(bundle))
            add(patchBundleRepository.discoveryImportKey(bundle, preferLatestAcrossChannels = true))
        }.distinct()

        val progressKey = keys.firstOrNull { importProgressSnapshot.containsKey(it) }
        val progress = progressKey?.let(importProgressSnapshot::get)
        if (progress != null) {
            progressKey?.let(localQueuedKeys::remove)
            return progress
        }

        val queuedKey = keys.firstOrNull { queuedImportSnapshot.contains(it) }
        val queuedFromRepo = queuedKey != null
        if (queuedFromRepo) {
            queuedKey?.let(localQueuedKeys::remove)
            return PatchBundleRepository.DiscoveryImportProgress(
                bytesRead = 0L,
                bytesTotal = null,
                status = PatchBundleRepository.DiscoveryImportStatus.Queued
            )
        }

        if (keys.any(localQueuedKeys::containsKey)) {
            return PatchBundleRepository.DiscoveryImportProgress(
                bytesRead = 0L,
                bytesTotal = null,
                status = PatchBundleRepository.DiscoveryImportStatus.Queued
            )
        }

        if (isImported) {
            keys.forEach(localQueuedKeys::remove)
        }
        return null
    }

    private suspend fun loadNextPageInternal(force: Boolean): Boolean {
        if (isFirstPageRefreshInProgress || (!canLoadMore && !force) || isLoadingMore) return false
        val key = currentQueryKey
        val query = key.takeIf { it.isNotBlank() }
        val requestedHost = currentApiHost
        val requestedOffset = nextOffset
        val requestedRefreshToken = refreshToken
        isLoadingMore = true
        return try {
            val (page, replaceCurrentPage) = withContext(Dispatchers.IO) {
                val requestedPage = api.getBundles(
                    packageNameQuery = query,
                    limit = PAGE_SIZE,
                    offset = requestedOffset,
                    apiHost = requestedHost
                ).getOrNull()
                if (requestedPage != null || requestedHost == null) {
                    requestedPage to false
                } else {
                    val alternateHost = ExternalBundlesEndpoints.alternateHost(requestedHost)
                    val fallbackPage = alternateHost?.let { host ->
                        api.getBundles(
                            packageNameQuery = query,
                            limit = PAGE_SIZE,
                            offset = 0,
                            apiHost = host
                        ).getOrNull()
                    }
                    fallbackPage to (fallbackPage != null)
                }
            }
            if (
                key != currentQueryKey ||
                requestedOffset != nextOffset ||
                requestedHost != currentApiHost ||
                requestedRefreshToken != refreshToken
            ) {
                return false
            }
            if (page != null) {
                if (replaceCurrentPage) {
                    invalidatePatchState()
                }
                val refreshedAt = (page.refreshJob?.completedAt ?: page.refreshJob?.startedAt)
                    ?.trim()
                    .takeIf { !it.isNullOrBlank() }
                if (refreshedAt != null) {
                    lastRefreshAt = refreshedAt
                }
                val resolvedSnapshot = applyLastRefreshed(page.bundles)
                val selectedHost = page.apiHost
                currentApiHost = selectedHost
                val updated = if (replaceCurrentPage) {
                    resolvedSnapshot
                } else {
                    bundles.orEmpty() + resolvedSnapshot
                }
                val cached = bundleCache[key]
                val entry = if (cached != null) {
                    cached.copy(
                        bundles = updated,
                        fingerprint = fingerprint(updated),
                        apiHost = selectedHost
                    )
                } else {
                    BundleCacheEntry(
                        bundles = updated,
                        fingerprint = fingerprint(updated),
                        apiHost = selectedHost
                    )
                }
                bundleCache[key] = entry
                bundles = entry.bundles
                persistDiskCache(key, entry)
                nextOffset = if (replaceCurrentPage) {
                    resolvedSnapshot.size
                } else {
                    requestedOffset + resolvedSnapshot.size
                }
                canLoadMore = resolvedSnapshot.size >= PAGE_SIZE
                resolvedSnapshot.isNotEmpty()
            } else {
                canLoadMore = false
                false
            }
        } finally {
            isLoadingMore = false
        }
    }

    private fun hasSearchMatches(
        queryLower: String,
        allowRelease: Boolean,
        allowPrerelease: Boolean
    ): Boolean {
        if (queryLower.isBlank()) return true
        val list = bundles.orEmpty()
        if (list.isEmpty()) return false
        val grouped = LinkedHashMap<String, SearchGroup>()
        for (bundle in list) {
            val owner = bundle.ownerName.takeIf { it.isNotBlank() }
            val repo = bundle.repoName.takeIf { it.isNotBlank() }
            val key = if (owner != null || repo != null) {
                listOfNotNull(owner, repo).joinToString("/")
            } else {
                bundle.sourceUrl
            }
            val entry = grouped.getOrPut(key) {
                SearchGroup(release = null, prerelease = null)
            }
            grouped[key] = if (bundle.isPrerelease) {
                if (entry.prerelease == null) entry.copy(prerelease = bundle) else entry
            } else {
                if (entry.release == null) entry.copy(release = bundle) else entry
            }
        }
        return grouped.values.any { group ->
            val hasRelease = group.release != null
            val hasPrerelease = group.prerelease != null
            if (!((allowRelease && hasRelease) || (allowPrerelease && hasPrerelease))) return@any false
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
            haystack.contains(queryLower)
        }
    }

    private fun fingerprint(bundles: List<ExternalBundleSnapshot>): String =
        bundles.joinToString(separator = "|") { bundle ->
            listOf(
                bundle.apiHost,
                bundle.bundleId,
                bundle.version,
                bundle.downloadUrl,
                bundle.signatureDownloadUrl,
                bundle.isPrerelease,
                bundle.isBundleV3,
                bundle.bundleType,
                bundle.repoPushedAt,
                bundle.lastRefreshedAt,
                bundle.isRepoArchived
            ).joinToString(":")
        }

    private fun applyLastRefreshed(
        bundles: List<ExternalBundleSnapshot>
    ): List<ExternalBundleSnapshot> {
        val refreshedAt = lastRefreshAt?.trim().takeIf { !it.isNullOrBlank() } ?: return bundles
        return bundles.map { bundle ->
            if (bundle.lastRefreshedAt == refreshedAt) {
                bundle
            } else {
                bundle.copy(lastRefreshedAt = refreshedAt)
            }
        }
    }

    private fun bundleHostFromDownload(url: String?): String? {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val host = runCatching { URI(trimmed).host?.lowercase() }.getOrNull() ?: return null
        return when {
            host == ExternalBundlesEndpoints.DEV_HOST -> ExternalBundlesEndpoints.DEV_HOST
            host == ExternalBundlesEndpoints.STABLE_HOST -> ExternalBundlesEndpoints.STABLE_HOST
            else -> null
        }
    }

    private fun patchStateKey(bundle: ExternalBundleSnapshot) = BundleInstanceKey(
        apiHost = bundle.apiHost.trim().lowercase(Locale.US),
        bundleId = bundle.bundleId
    )

    private fun invalidatePatchState() {
        bundleDatasetGeneration++
        patchesByBundle.clear()
        patchesLoading.clear()
        patchesError.clear()
    }

    suspend fun fetchLatestBundle(
        owner: String,
        repo: String,
        prerelease: Boolean
    ): ExternalBundleSnapshot? = withContext(Dispatchers.IO) {
        api.getLatestBundle(owner, repo, prerelease).getOrNull()
    }

    @Serializable
    private data class BundleCacheEntry(
        val bundles: List<ExternalBundleSnapshot>,
        val fingerprint: String,
        val apiHost: String = ""
    )

    data class BundleExportProgress(val bytesRead: Long, val bytesTotal: Long?)

    private data class SearchGroup(
        val release: ExternalBundleSnapshot?,
        val prerelease: ExternalBundleSnapshot?
    )

    private data class BundleInstanceKey(
        val apiHost: String,
        val bundleId: Int
    )

    private fun cacheFileForKey(key: String): File {
        val normalized = key.trim().lowercase(Locale.ROOT)
        val suffix = if (normalized.isBlank()) "all" else normalized.hashCode().toString()
        return File(cacheDir, "bundles_$suffix.json")
    }

    private fun loadDiskCache(key: String): BundleCacheEntry? {
        val file = cacheFileForKey(key)
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString<BundleCacheEntry>(file.readText())
        }.getOrNull()
    }

    private fun persistDiskCache(key: String, entry: BundleCacheEntry) {
        runCatching {
            val file = cacheFileForKey(key)
            file.writeText(json.encodeToString(entry))
        }.onFailure { error ->
            if (error is SerializationException) return@onFailure
        }
    }

    private companion object {
        const val PAGE_SIZE = 30
    }
}
