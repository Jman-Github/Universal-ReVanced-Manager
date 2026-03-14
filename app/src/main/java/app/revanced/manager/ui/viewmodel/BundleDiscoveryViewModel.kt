package app.revanced.manager.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.revanced.manager.R
import app.revanced.manager.domain.repository.PatchBundleRepository
import app.revanced.manager.network.api.ExternalBundlesApi
import app.revanced.manager.network.dto.ExternalBundleSnapshot
import app.revanced.manager.network.dto.ExternalBundlePatch
import app.revanced.manager.network.service.HttpService
import app.revanced.manager.network.utils.getOrNull
import app.revanced.manager.util.simpleMessage
import app.revanced.manager.util.toast
import io.ktor.client.request.url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
) : ViewModel() {
    var bundles: List<ExternalBundleSnapshot>? by mutableStateOf(null)
        private set

    var isLoading: Boolean by mutableStateOf(false)
        private set

    var errorMessage: String? by mutableStateOf(null)
        private set

    var bundleSearchQuery: String by mutableStateOf("")
    var packageSearchQuery: String by mutableStateOf("")

    private val patchesByBundle = mutableStateMapOf<Int, List<ExternalBundlePatch>>()
    private val patchesLoading = mutableStateMapOf<Int, Boolean>()
    private val patchesError = mutableStateMapOf<Int, String?>()
    private val bundleCache = mutableMapOf<String, BundleCacheEntry>()
    private val bundleExports = mutableStateMapOf<Int, BundleExportProgress>()
    private val cacheDir = File(app.cacheDir, "bundle_discovery").also { it.mkdirs() }
    private var refreshJob: Job? = null
    private var searchJob: Job? = null
    private var currentQueryKey: String = ""
    private var nextOffset: Int = 0
    private var refreshToken: Int = 0
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
        val cached = bundleCache[key] ?: loadDiskCache(key)?.also { bundleCache[key] = it }
        if (cached != null) {
            bundles = applyLastRefreshed(cached.bundles)
        }
        val token = ++refreshToken
        viewModelScope.launch {
            if (token != refreshToken) return@launch
            isLoading = cached == null
            errorMessage = null
            val (snapshot, refreshJob) = withContext(Dispatchers.IO) {
                coroutineScope {
                    val bundlesDeferred = async {
                        api.getBundles(packageNameQuery, limit = PAGE_SIZE, offset = 0).getOrNull()
                    }
                    val refreshDeferred = async {
                        api.getLatestRefreshJob().getOrNull()
                    }
                    bundlesDeferred.await() to refreshDeferred.await()
                }
            }
            if (token != refreshToken) return@launch
            val refreshedAt = refreshJob?.startedAt?.trim().takeIf { !it.isNullOrBlank() }
            if (refreshedAt != null && refreshedAt != lastRefreshAt) {
                lastRefreshAt = refreshedAt
            }
            val resolvedSnapshot = snapshot?.let { applyLastRefreshed(it) }
            if (resolvedSnapshot == null) {
                if (cached == null) {
                    errorMessage = app.getString(R.string.patch_bundle_discovery_error)
                } else if (lastRefreshAt != null) {
                    val updatedBundles = applyLastRefreshed(cached.bundles)
                    val entry = BundleCacheEntry(updatedBundles, fingerprint(updatedBundles))
                    bundleCache[key] = entry
                    bundles = entry.bundles
                    persistDiskCache(key, entry)
                }
            } else {
                val fingerprint = fingerprint(resolvedSnapshot)
                if (cached == null || cached.fingerprint != fingerprint) {
                    val entry = BundleCacheEntry(resolvedSnapshot, fingerprint)
                    bundleCache[key] = entry
                    bundles = entry.bundles
                    persistDiskCache(key, entry)
                }
                nextOffset = resolvedSnapshot.size
                canLoadMore = resolvedSnapshot.size >= PAGE_SIZE
                errorMessage = null
            }
            isLoading = false
        }
    }

    fun loadMore() {
        if (!canLoadMore || isLoadingMore) return
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
                    if (isLoading || isLoadingMore) {
                        delay(50)
                        continue
                    }
                    val found = if (queryLower.isNotBlank()) {
                        hasSearchMatches(queryLower, allowRelease, allowPrerelease)
                    } else {
                        bundles?.isNotEmpty() == true
                    }
                    if (found) break
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
            try {
                withContext(Dispatchers.IO) {
                    target.parent?.toFile()?.mkdirs()
                    http.downloadToFile(
                        saveLocation = target.toFile(),
                        builder = { url(url) },
                        onProgress = { bytesRead, bytesTotal ->
                            viewModelScope.launch(Dispatchers.Main) {
                                bundleExports[bundleId] = BundleExportProgress(bytesRead, bytesTotal)
                            }
                        }
                    )
                }
                app.toast(app.getString(R.string.patch_bundle_export_success, target.fileName.toString()))
            } catch (e: Exception) {
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
            try {
                withContext(Dispatchers.IO) {
                    http.downloadToFile(
                        saveLocation = tempFile,
                        builder = { url(url) },
                        onProgress = { bytesRead, bytesTotal ->
                            viewModelScope.launch(Dispatchers.Main) {
                                bundleExports[bundleId] = BundleExportProgress(bytesRead, bytesTotal)
                            }
                        }
                    )
                    app.contentResolver.openOutputStream(target)?.use { output ->
                        tempFile.inputStream().use { input -> input.copyTo(output) }
                    } ?: error("Could not open output stream for bundle export")
                }
                val successName = bundle.repoName.ifBlank { "bundle" }
                app.toast(app.getString(R.string.patch_bundle_export_success, successName))
            } catch (e: Exception) {
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
        graphqlBundleEndpoint(bundle, useDev = false, prerelease = null)?.let { endpoints.add(it) }
        graphqlBundleEndpoint(bundle, useDev = true, prerelease = null)?.let { endpoints.add(it) }
        graphqlBundleEndpoint(bundle, useDev = false)?.let { endpoints.add(it) }
        graphqlBundleEndpoint(bundle, useDev = true)?.let { endpoints.add(it) }
        legacyEndpoint(bundle.bundleId)?.let { endpoints.add(it) }
        return endpoints
    }

    fun remoteBundleUrl(bundle: ExternalBundleSnapshot): String? {
        val host = bundleHostFromDownload(bundle.downloadUrl)
            ?: bundleHostFromDownload(bundle.signatureDownloadUrl)
            ?: STABLE_BUNDLES_HOST
        val owner = bundle.ownerName.trim()
        val repo = bundle.repoName.trim()
        return if (owner.isNotBlank() && repo.isNotBlank()) {
            val channel = if (bundle.isPrerelease) "prerelease" else "stable"
            "https://$host/api/v2/bundle/$owner/$repo/latest?channel=$channel"
        } else if (bundle.bundleId > 0) {
            "https://$host/bundles/id?id=${bundle.bundleId}"
        } else {
            null
        }
    }

    private fun legacyEndpoint(bundleId: Int): String? =
        "https://revanced-external-bundles.brosssh.com/bundles/id?id=$bundleId"

    private fun graphqlBundleEndpoint(
        bundle: ExternalBundleSnapshot,
        useDev: Boolean,
        prerelease: Boolean? = bundle.isPrerelease
    ): String? {
        val owner = bundle.ownerName.trim()
        val repo = bundle.repoName.trim()
        if (owner.isBlank() || repo.isBlank()) return null
        val host = if (useDev) {
            "revanced-external-bundles-dev.brosssh.com"
        } else {
            "revanced-external-bundles.brosssh.com"
        }
        val channel = when (prerelease) {
            null -> "any"
            true -> "prerelease"
            false -> "stable"
        }
        return "https://$host/api/v2/bundle/$owner/$repo/latest?channel=$channel"
    }

    fun loadPatches(bundleId: Int) {
        if (patchesByBundle.containsKey(bundleId) || patchesLoading[bundleId] == true) return
        viewModelScope.launch {
            patchesLoading[bundleId] = true
            patchesError[bundleId] = null
            try {
                val patches = withContext(Dispatchers.IO) {
                    api.getBundlePatches(bundleId).getOrNull()
                }
                if (patches == null) {
                    patchesError[bundleId] = app.getString(R.string.patch_bundle_discovery_error)
                } else {
                    patchesByBundle[bundleId] = patches
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                patchesError[bundleId] = app.getString(R.string.patch_bundle_discovery_error)
            } finally {
                patchesLoading[bundleId] = false
            }
        }
    }

    fun getPatches(bundleId: Int): List<ExternalBundlePatch>? = patchesByBundle[bundleId]

    fun isPatchesLoading(bundleId: Int): Boolean = patchesLoading[bundleId] == true

    fun getPatchesError(bundleId: Int): String? = patchesError[bundleId]

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
        if ((!canLoadMore && !force) || isLoadingMore) return false
        val key = currentQueryKey
        val query = key.takeIf { it.isNotBlank() }
        isLoadingMore = true
        return try {
            val snapshot = withContext(Dispatchers.IO) {
                api.getBundles(query, limit = PAGE_SIZE, offset = nextOffset).getOrNull()
            }
            val resolvedSnapshot = snapshot?.let { applyLastRefreshed(it) }
            if (!resolvedSnapshot.isNullOrEmpty()) {
                val current = bundles.orEmpty()
                val updated = current + resolvedSnapshot
                val cached = bundleCache[key]
                val entry = if (cached != null) {
                    cached.copy(bundles = updated)
                } else {
                    BundleCacheEntry(updated, fingerprint(updated))
                }
                bundleCache[key] = entry
                bundles = entry.bundles
                persistDiskCache(key, entry)
                nextOffset += resolvedSnapshot.size
                canLoadMore = resolvedSnapshot.size >= PAGE_SIZE
                true
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
            host == DEV_BUNDLES_HOST -> DEV_BUNDLES_HOST
            host == STABLE_BUNDLES_HOST -> STABLE_BUNDLES_HOST
            else -> null
        }
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
        val fingerprint: String
    )

    data class BundleExportProgress(val bytesRead: Long, val bytesTotal: Long?)

    private data class SearchGroup(
        val release: ExternalBundleSnapshot?,
        val prerelease: ExternalBundleSnapshot?
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
        const val STABLE_BUNDLES_HOST = "revanced-external-bundles.brosssh.com"
        const val DEV_BUNDLES_HOST = "revanced-external-bundles-dev.brosssh.com"
        const val PAGE_SIZE = 30
    }
}
