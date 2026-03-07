package app.revanced.manager.domain.bundles

import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.network.api.ExternalBundlesApi
import app.revanced.manager.network.api.ReVancedAPI
import app.revanced.manager.network.dto.ExternalBundleSnapshot
import app.revanced.manager.network.dto.GitHubRelease
import app.revanced.manager.network.dto.ReVancedAsset
import app.revanced.manager.network.service.HttpService
import app.revanced.manager.network.utils.getOrNull
import app.revanced.manager.network.utils.getOrThrow
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.http.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.toInstant
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import okhttp3.Protocol

data class PatchBundleDownloadResult(
    val versionSignature: String,
    val assetCreatedAtMillis: Long?
)

typealias PatchBundleDownloadProgress = (bytesRead: Long, bytesTotal: Long?) -> Unit

sealed class RemotePatchBundle(
    name: String,
    uid: Int,
    displayName: String?,
    createdAt: Long?,
    updatedAt: Long?,
    private val installedVersionSignatureInternal: String?,
    error: Throwable?,
    directory: File,
    val endpoint: String,
    val autoUpdate: Boolean,
    val searchUpdate: Boolean,
    val lastNotifiedVersion: String?,
    enabled: Boolean,
) : PatchBundleSource(name, uid, displayName, createdAt, updatedAt, error, directory, enabled), KoinComponent {
    protected val http: HttpService by inject()

    protected abstract suspend fun getLatestInfo(): ReVancedAsset
    abstract fun copy(
        error: Throwable? = this.error,
        name: String = this.name,
        displayName: String? = this.displayName,
        createdAt: Long? = this.createdAt,
        updatedAt: Long? = this.updatedAt,
        autoUpdate: Boolean = this.autoUpdate,
        searchUpdate: Boolean = this.searchUpdate,
        lastNotifiedVersion: String? = this.lastNotifiedVersion,
        enabled: Boolean = this.enabled
    ): RemotePatchBundle

    override fun copy(
        error: Throwable?,
        name: String,
        displayName: String?,
        createdAt: Long?,
        updatedAt: Long?,
        enabled: Boolean
    ): RemotePatchBundle = copy(
        error,
        name,
        displayName,
        createdAt,
        updatedAt,
        this.autoUpdate,
        this.searchUpdate,
        this.lastNotifiedVersion,
        enabled
    )

    // PR #35: https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/35
    protected open suspend fun download(info: ReVancedAsset, onProgress: PatchBundleDownloadProgress? = null) =
        withContext(Dispatchers.IO) {
            try {
                patchesFile.parentFile?.mkdirs()
                patchesFile.setWritable(true, true)
                http.downloadToFile(
                    saveLocation = patchesFile,
                    builder = { url(info.downloadUrl) },
                    onProgress = onProgress
                )
                patchesFile.setReadOnly()
                requireNonEmptyPatchesFile("Downloading patch bundle")
            } catch (t: Throwable) {
                runCatching { patchesFile.setWritable(true, true) }
                runCatching { patchesFile.delete() }
                throw t
            }

            PatchBundleDownloadResult(
                versionSignature = info.version,
                assetCreatedAtMillis = runCatching {
                    info.createdAt.toInstant(TimeZone.UTC).toEpochMilliseconds()
                }.getOrNull()
            )
        }

    /**
     * Downloads the latest version regardless if there is a new update available.
     */
    suspend fun downloadLatest(onProgress: PatchBundleDownloadProgress? = null): PatchBundleDownloadResult =
        download(fetchLatestReleaseInfo(), onProgress)

    suspend fun update(onProgress: PatchBundleDownloadProgress? = null): PatchBundleDownloadResult? =
        withContext(Dispatchers.IO) {
        val info = fetchLatestReleaseInfo()
        val latestSignature = normalizeVersionForCompare(info.version)
            ?: return@withContext null
        val installedSignature = normalizeVersionForCompare(installedVersionSignatureInternal)
        val manifestSignature = normalizeVersionForCompare(version)
        if (
            hasInstalled() &&
            (
                (installedSignature != null && latestSignature == installedSignature) ||
                    (manifestSignature != null && latestSignature == manifestSignature)
                )
        ) {
            return@withContext null
        }

        download(info, onProgress)
    }

    suspend fun fetchLatestReleaseInfo(): ReVancedAsset {
        val key = "$uid|${latestInfoCacheIdentity()}"
        val now = System.currentTimeMillis()
        val cached = changelogCacheMutex.withLock {
            changelogCache[key]?.takeIf { now - it.timestamp <= CHANGELOG_CACHE_TTL }
        }
        if (cached != null) return cached.asset

        val asset = getLatestInfo()
        changelogCacheMutex.withLock {
            changelogCache[key] = CachedChangelog(asset, now)
        }
        return asset
    }

    suspend fun fetchHistoricalChangelogEntries(
        limit: Int = DEFAULT_CHANGELOG_HISTORY_LIMIT
    ): List<PatchBundleChangelogEntry> {
        val key = "$uid|$limit|${historicalInfoCacheIdentity()}"
        val now = System.currentTimeMillis()
        val cached = changelogHistoryCacheMutex.withLock {
            changelogHistoryCache[key]?.takeIf { now - it.timestamp <= CHANGELOG_CACHE_TTL }
        }
        if (cached != null) return cached.entries

        val entries = getHistoricalChangelogEntries(limit)
        changelogHistoryCacheMutex.withLock {
            changelogHistoryCache[key] = CachedChangelogHistory(entries, now)
        }
        return entries
    }

    protected open suspend fun latestInfoCacheIdentity(): String = endpoint

    protected open suspend fun historicalInfoCacheIdentity(): String = latestInfoCacheIdentity()

    protected open suspend fun getHistoricalChangelogEntries(limit: Int): List<PatchBundleChangelogEntry> =
        emptyList()

    protected suspend fun fetchGitHubChangelogHistory(
        limit: Int,
        prerelease: Boolean? = null,
        vararg candidates: String?
    ): List<PatchBundleChangelogEntry> {
        val repoUrl = inferGitHubRepoUrl(*candidates) ?: return emptyList()
        val api: ReVancedAPI by inject()
        return api.getRepositoryReleaseHistory(repoUrl, prerelease = prerelease, limit = limit)
            .getOrThrow()
            .map { release -> release.toChangelogEntry(repoUrl) }
    }

    protected fun inferGitHubRepoUrl(vararg candidates: String?): String? =
        candidates.asSequence()
            .mapNotNull(::parseGitHubRepoUrl)
            .firstOrNull()

    private fun parseGitHubRepoUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val parsed = runCatching { Url(trimmed) }.getOrNull() ?: return null
        val host = parsed.host.lowercase()
        val segments = parsed.encodedPath.trim('/').split('/').filter { it.isNotBlank() }
        if (segments.size < 2) return null

        val ownerRepo = when {
            host == "github.com" -> segments[0] to segments[1]
            host == "raw.githubusercontent.com" -> segments[0] to segments[1]
            host == "api.github.com" -> {
                val reposIndex = segments.indexOf("repos")
                if (reposIndex < 0 || segments.size <= reposIndex + 2) return null
                segments[reposIndex + 1] to segments[reposIndex + 2]
            }
            else -> return null
        }

        val owner = ownerRepo.first.removeSuffix(".git")
        val repo = ownerRepo.second.removeSuffix(".git")
        if (owner.isBlank() || repo.isBlank()) return null
        return "https://github.com/$owner/$repo"
    }

    private fun GitHubRelease.toChangelogEntry(repoUrl: String): PatchBundleChangelogEntry {
        val publishedAtMillis = (publishedAt ?: createdAt)
            ?.let { timestamp -> runCatching { Instant.parse(timestamp).toEpochMilliseconds() }.getOrNull() }
        val description = body?.ifBlank { name.orEmpty() } ?: name.orEmpty()
        return PatchBundleChangelogEntry(
            version = tagName,
            description = description,
            publishedAtMillis = publishedAtMillis,
            pageUrl = "${repoUrl.removeSuffix("/")}/releases/tag/$tagName"
        )
    }

    companion object {
        const val updateFailMsg = "Failed to update patches"
        private const val CHANGELOG_CACHE_TTL = 10 * 60 * 1000L
        private const val DEFAULT_CHANGELOG_HISTORY_LIMIT =
            PreferencesManager.DEFAULT_BUNDLE_CHANGELOG_FETCH_LIMIT
        private val changelogCacheMutex = Mutex()
        private val changelogCache = mutableMapOf<String, CachedChangelog>()
        private val changelogHistoryCacheMutex = Mutex()
        private val changelogHistoryCache = mutableMapOf<String, CachedChangelogHistory>()
    }

    val installedVersionSignature: String? get() = installedVersionSignatureInternal

    private fun normalizeVersionForCompare(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val noPrefix = trimmed.removePrefix("v").removePrefix("V")
        val noBuild = noPrefix.substringBefore('+')
        return noBuild.ifBlank { null }
    }
}

class JsonPatchBundle(
    name: String,
    uid: Int,
    displayName: String?,
    createdAt: Long?,
    updatedAt: Long?,
    installedVersionSignature: String?,
    error: Throwable?,
    directory: File,
    endpoint: String,
    autoUpdate: Boolean,
    searchUpdate: Boolean,
    lastNotifiedVersion: String?,
    enabled: Boolean,
) : RemotePatchBundle(
    name,
    uid,
    displayName,
    createdAt,
    updatedAt,
    installedVersionSignature,
    error,
    directory,
    endpoint,
    autoUpdate,
    searchUpdate,
    lastNotifiedVersion,
    enabled
) {
    override suspend fun getLatestInfo() = withContext(Dispatchers.IO) {
        http.request<ReVancedAsset> {
            url(endpoint)
        }.getOrThrow()
    }

    override suspend fun getHistoricalChangelogEntries(limit: Int) = withContext(Dispatchers.IO) {
        val latest = runCatching { fetchLatestReleaseInfo() }.getOrNull()
        fetchGitHubChangelogHistory(limit, null, latest?.pageUrl, latest?.downloadUrl, endpoint)
    }

    override fun copy(
        error: Throwable?,
        name: String,
        displayName: String?,
        createdAt: Long?,
        updatedAt: Long?,
        autoUpdate: Boolean,
        searchUpdate: Boolean,
        lastNotifiedVersion: String?,
        enabled: Boolean
    ) = JsonPatchBundle(
        name,
        uid,
        displayName,
        createdAt,
        updatedAt,
        installedVersionSignature,
        error,
        directory,
        endpoint,
        autoUpdate,
        searchUpdate,
        lastNotifiedVersion,
        enabled
    )
}

class APIPatchBundle(
    name: String,
    uid: Int,
    displayName: String?,
    createdAt: Long?,
    updatedAt: Long?,
    installedVersionSignature: String?,
    error: Throwable?,
    directory: File,
    endpoint: String,
    autoUpdate: Boolean,
    searchUpdate: Boolean,
    lastNotifiedVersion: String?,
    enabled: Boolean,
) : RemotePatchBundle(
    name,
    uid,
    displayName,
    createdAt,
    updatedAt,
    installedVersionSignature,
    error,
    directory,
    endpoint,
    autoUpdate,
    searchUpdate,
    lastNotifiedVersion,
    enabled
) {
    private val api: ReVancedAPI by inject()
    private val prefs: PreferencesManager by inject()

    override suspend fun getLatestInfo() = withContext(Dispatchers.IO) {
        val includePrerelease = prefs.usePatchesPrereleases.get()
        api.getPatchesUpdate(prerelease = includePrerelease).getOrThrow()
    }

    override suspend fun getHistoricalChangelogEntries(limit: Int) = withContext(Dispatchers.IO) {
        val includePrerelease = prefs.usePatchesPrereleases.get()
        val latest = runCatching { fetchLatestReleaseInfo() }.getOrNull()
        fetchGitHubChangelogHistory(
            limit,
            includePrerelease,
            latest?.pageUrl,
            latest?.downloadUrl,
            endpoint
        )
    }

    override suspend fun latestInfoCacheIdentity(): String {
        val includePrerelease = prefs.usePatchesPrereleases.get()
        return "$endpoint|prerelease=$includePrerelease"
    }

    override suspend fun historicalInfoCacheIdentity(): String {
        val includePrerelease = prefs.usePatchesPrereleases.get()
        return "$endpoint|history|prerelease=$includePrerelease"
    }

    override fun copy(
        error: Throwable?,
        name: String,
        displayName: String?,
        createdAt: Long?,
        updatedAt: Long?,
        autoUpdate: Boolean,
        searchUpdate: Boolean,
        lastNotifiedVersion: String?,
        enabled: Boolean
    ) = APIPatchBundle(
        name,
        uid,
        displayName,
        createdAt,
        updatedAt,
        installedVersionSignature,
        error,
        directory,
        endpoint,
        autoUpdate,
        searchUpdate,
        lastNotifiedVersion,
        enabled
    )

}

// PR #35: https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/35
class GitHubPullRequestBundle(
    name: String,
    uid: Int,
    displayName: String?,
    createdAt: Long?,
    updatedAt: Long?,
    installedVersionSignature: String?,
    error: Throwable?,
    directory: File,
    endpoint: String,
    autoUpdate: Boolean,
    searchUpdate: Boolean,
    lastNotifiedVersion: String?,
    enabled: Boolean
) : RemotePatchBundle(
    name,
    uid,
    displayName,
    createdAt,
    updatedAt,
    installedVersionSignature,
    error,
    directory,
    endpoint,
    autoUpdate,
    searchUpdate,
    lastNotifiedVersion,
    enabled
) {

    private val api: ReVancedAPI by inject()

    override suspend fun getLatestInfo() = withContext(Dispatchers.IO) {
        val (owner, repo, prNumber) = endpoint.split("/").let { parts ->
            Triple(parts[3], parts[4], parts[6])
        }

        api.getAssetFromPullRequest(owner, repo, prNumber)
    }

    override suspend fun download(info: ReVancedAsset, onProgress: PatchBundleDownloadProgress?) = withContext(Dispatchers.IO) {
        val prefs: PreferencesManager by inject()
        val gitHubPat = prefs.gitHubPat.get().also {
            if (it.isBlank()) throw RuntimeException("PAT is required.")
        }

        val customHttpClient = HttpClient(OkHttp) {
            engine {
                config {
                    // Force HTTP/1.1 to avoid HTTP/2 PROTOCOL_ERROR stream resets when fetching
                    // PR artifacts from GitHub.
                    protocols(listOf(Protocol.HTTP_1_1))
                    followRedirects(true)
                    followSslRedirects(true)
                }
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 10_000
                requestTimeoutMillis = 5 * 60_000
            }
        }

        try {
            with(customHttpClient) {
                prepareGet {
                    url(info.downloadUrl)
                    header("Authorization", "Bearer $gitHubPat")
                }.execute { httpResponse ->
                    patchBundleOutputStream().use { patchOutput ->
                        ZipInputStream(httpResponse.bodyAsChannel().toInputStream()).use { zis ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var copiedBytes = 0L
                            var lastReportedBytes = 0L
                            var lastReportedAt = 0L
                            var extractedTotal: Long? = null

                            var entry = zis.nextEntry
                            while (entry != null) {
                                val entryName = entry.name.lowercase()
                                if (!entry.isDirectory && (entryName.endsWith(".rvp") || entryName.endsWith(".mpp") || entryName.endsWith(".arp"))) {
                                    extractedTotal = entry.size.takeIf { it > 0 }
                                    while (true) {
                                        val read = zis.read(buffer)
                                        if (read == -1) break
                                        patchOutput.write(buffer, 0, read)
                                        copiedBytes += read.toLong()
                                        val now = System.currentTimeMillis()
                                        if (copiedBytes - lastReportedBytes >= 64 * 1024 || now - lastReportedAt >= 200) {
                                            lastReportedBytes = copiedBytes
                                            lastReportedAt = now
                                            onProgress?.invoke(copiedBytes, extractedTotal)
                                        }
                                    }
                                    break
                                }
                                zis.closeEntry()
                                entry = zis.nextEntry
                            }

                            if (copiedBytes <= 0L) {
                                throw IOException("No .rvp, .mpp, or .arp file found in the pull request artifact.")
                            }
                            onProgress?.invoke(copiedBytes, extractedTotal)
                        }
                    }
                }
            }
            requireNonEmptyPatchesFile("Downloading patch bundle")
        } catch (t: Throwable) {
            runCatching { patchesFile.delete() }
            throw t
        } finally {
            runCatching { customHttpClient.close() }
        }

        PatchBundleDownloadResult(
            versionSignature = info.version,
            assetCreatedAtMillis = runCatching {
                info.createdAt.toInstant(TimeZone.UTC).toEpochMilliseconds()
            }.getOrNull()
        )
    }

    override fun copy(
        error: Throwable?,
        name: String,
        displayName: String?,
        createdAt: Long?,
        updatedAt: Long?,
        autoUpdate: Boolean,
        searchUpdate: Boolean,
        lastNotifiedVersion: String?,
        enabled: Boolean
    ) = GitHubPullRequestBundle(
        name,
        uid,
        displayName,
        createdAt,
        updatedAt,
        installedVersionSignature,
        error,
        directory,
        endpoint,
        autoUpdate,
        searchUpdate,
        lastNotifiedVersion,
        enabled
    )
}

class ExternalGraphqlPatchBundle(
    name: String,
    uid: Int,
    displayName: String?,
    createdAt: Long?,
    updatedAt: Long?,
    installedVersionSignature: String?,
    error: Throwable?,
    directory: File,
    endpoint: String,
    autoUpdate: Boolean,
    searchUpdate: Boolean,
    lastNotifiedVersion: String?,
    enabled: Boolean,
    private var metadata: ExternalBundleMetadata
) : RemotePatchBundle(
    name,
    uid,
    displayName,
    createdAt,
    updatedAt,
    installedVersionSignature,
    error,
    directory,
    endpoint,
    autoUpdate,
    searchUpdate,
    lastNotifiedVersion,
    enabled
) {
    private val api: ExternalBundlesApi by inject()
    private val officialApi: ReVancedAPI by inject()
    private data class EndpointMetadata(
        val owner: String?,
        val repo: String?,
        val prerelease: Boolean?,
        val hasExplicitChannel: Boolean,
        val isV2LatestEndpoint: Boolean
    )
    private data class HistorySource(
        val owner: String,
        val repo: String,
        val repoUrl: String?,
        val sourceUrl: String?
    )

    override suspend fun getLatestInfo(): ReVancedAsset = withContext(Dispatchers.IO) {
        val endpointMetadata = parseEndpointMetadata()
        val owner = endpointMetadata.owner?.trim().takeIf { !it.isNullOrBlank() }
            ?: metadata.ownerName?.trim().takeIf { !it.isNullOrBlank() }
            ?: ""
        val repo = endpointMetadata.repo?.trim().takeIf { !it.isNullOrBlank() }
            ?: metadata.repoName?.trim().takeIf { !it.isNullOrBlank() }
            ?: ""
        val prerelease = if (endpointMetadata.hasExplicitChannel) {
            endpointMetadata.prerelease
        } else {
            metadata.isPrerelease ?: endpointMetadata.prerelease
        }
        val endpointAsset = if (endpointMetadata.hasExplicitChannel && endpointMetadata.isV2LatestEndpoint) {
            http.request<ReVancedAsset> { url(endpoint) }.getOrNull()
        } else {
            null
        }
        if (endpointAsset != null) {
            metadata = metadata.copy(
                downloadUrl = endpointAsset.downloadUrl,
                signatureDownloadUrl = endpointAsset.signatureDownloadUrl,
                version = endpointAsset.version,
                createdAt = endpointAsset.createdAt.toString(),
                description = endpointAsset.description.ifBlank { metadata.description },
                ownerName = owner.takeIf { it.isNotBlank() } ?: metadata.ownerName,
                repoName = repo.takeIf { it.isNotBlank() } ?: metadata.repoName,
                isPrerelease = prerelease
            )
            ExternalBundleMetadataStore.write(directory, metadata)
            return@withContext endpointAsset
        }
        if (owner.equals("ReVanced", ignoreCase = true) && repo.equals("revanced-patches", ignoreCase = true)) {
            val officialAsset = if (prerelease == null) {
                val latestRelease = officialApi.getPatchesUpdate(prerelease = false).getOrNull()
                val latestPrerelease = officialApi.getPatchesUpdate(prerelease = true).getOrNull()
                pickNewestOfficialAsset(latestRelease, latestPrerelease)
            } else {
                officialApi.getPatchesUpdate(prerelease = prerelease).getOrNull()
            }
            if (officialAsset != null) {
                metadata = metadata.copy(
                    downloadUrl = officialAsset.downloadUrl,
                    signatureDownloadUrl = officialAsset.signatureDownloadUrl,
                    version = officialAsset.version,
                    createdAt = officialAsset.createdAt.toString(),
                    description = officialAsset.description.ifBlank { metadata.description },
                    isPrerelease = prerelease
                )
                ExternalBundleMetadataStore.write(directory, metadata)
                return@withContext officialAsset
            }
        }
        val trackLatestAcrossChannels = owner.isNotBlank() && repo.isNotBlank() && prerelease == null
        val latest = if (owner.isNotBlank() && repo.isNotBlank()) {
            if (prerelease != null) {
                api.getLatestBundle(owner, repo, prerelease).getOrNull()
            } else {
                api.getLatestBundleAny(owner, repo).getOrNull()
                    ?: run {
                        val latestRelease = api.getLatestBundle(owner, repo, prerelease = false).getOrNull()
                        val latestPrerelease = api.getLatestBundle(owner, repo, prerelease = true).getOrNull()
                        pickLatestSnapshot(latestRelease, latestPrerelease)
                    }
            }
        } else {
            null
        } ?: api.getBundleById(metadata.bundleId).getOrNull()
        if (latest != null) {
            metadata = metadataFromSnapshot(
                snapshot = latest,
                preserveChannelSelection = trackLatestAcrossChannels
            )
            ExternalBundleMetadataStore.write(directory, metadata)
        }
        snapshotToAsset(latest)
    }

    override suspend fun getHistoricalChangelogEntries(limit: Int) = withContext(Dispatchers.IO) {
        val endpointMetadata = parseEndpointMetadata()
        val historySource = resolveHistorySource(endpointMetadata)
        val prerelease = if (endpointMetadata.hasExplicitChannel) {
            endpointMetadata.prerelease
        } else {
            metadata.isPrerelease ?: endpointMetadata.prerelease
        }
        var history = fetchExternalHistory(
            source = historySource,
            prerelease = prerelease,
            limit = limit
        )
        if (history.size < limit && prerelease != null) {
            history = mergeHistoryEntries(
                history,
                fetchExternalHistory(
                    source = historySource,
                    prerelease = null,
                    limit = limit
                ),
                limit
            )
        }
        if (history.size < limit && !historySource.repoUrl.isNullOrBlank()) {
            history = mergeHistoryEntries(
                history,
                fetchGitHubChangelogHistory(
                    limit,
                    prerelease,
                    historySource.repoUrl,
                    historySource.sourceUrl,
                    endpoint,
                    metadata.downloadUrl
                ),
                limit
            )
        }
        if (history.size < limit && prerelease != null && !historySource.repoUrl.isNullOrBlank()) {
            history = mergeHistoryEntries(
                history,
                fetchGitHubChangelogHistory(
                    limit,
                    null,
                    historySource.repoUrl,
                    historySource.sourceUrl,
                    endpoint,
                    metadata.downloadUrl
                ),
                limit
            )
        }
        if (history.isNotEmpty()) {
            return@withContext history
        }
        val latest = runCatching { fetchLatestReleaseInfo() }.getOrNull()
        fetchGitHubChangelogHistory(
            limit,
            prerelease,
            historySource.repoUrl,
            historySource.sourceUrl,
            latest?.pageUrl,
            latest?.downloadUrl,
            endpoint
        )
    }

    override suspend fun historicalInfoCacheIdentity(): String {
        val endpointMetadata = parseEndpointMetadata()
        val owner = endpointMetadata.owner?.trim().takeIf { !it.isNullOrBlank() }
            ?: metadata.ownerName?.trim().takeIf { !it.isNullOrBlank() }
            ?: ""
        val repo = endpointMetadata.repo?.trim().takeIf { !it.isNullOrBlank() }
            ?: metadata.repoName?.trim().takeIf { !it.isNullOrBlank() }
            ?: ""
        val prerelease = if (endpointMetadata.hasExplicitChannel) {
            endpointMetadata.prerelease
        } else {
            metadata.isPrerelease ?: endpointMetadata.prerelease
        }
        return "$endpoint|history|owner=$owner|repo=$repo|prerelease=$prerelease"
    }

    override fun copy(
        error: Throwable?,
        name: String,
        displayName: String?,
        createdAt: Long?,
        updatedAt: Long?,
        autoUpdate: Boolean,
        searchUpdate: Boolean,
        lastNotifiedVersion: String?,
        enabled: Boolean
    ) = ExternalGraphqlPatchBundle(
        name,
        uid,
        displayName,
        createdAt,
        updatedAt,
        installedVersionSignature,
        error,
        directory,
        endpoint,
        autoUpdate,
        searchUpdate,
        lastNotifiedVersion,
        enabled,
        metadata
    )

    private fun metadataFromSnapshot(
        snapshot: ExternalBundleSnapshot,
        preserveChannelSelection: Boolean
    ) = ExternalBundleMetadata(
        bundleId = metadata.bundleId,
        downloadUrl = safeArtifactUrl(snapshot.downloadUrl) ?: metadata.downloadUrl,
        signatureDownloadUrl = snapshot.signatureDownloadUrl ?: metadata.signatureDownloadUrl,
        version = snapshot.version.ifBlank { metadata.version },
        createdAt = snapshot.createdAt.ifBlank { metadata.createdAt },
        description = snapshot.description ?: metadata.description,
        ownerName = snapshot.ownerName.takeIf { it.isNotBlank() } ?: metadata.ownerName,
        repoName = snapshot.repoName.takeIf { it.isNotBlank() } ?: metadata.repoName,
        isPrerelease = if (preserveChannelSelection) null else snapshot.isPrerelease
    )

    private fun snapshotToAsset(snapshot: ExternalBundleSnapshot?): ReVancedAsset {
        val downloadUrl = safeArtifactUrl(snapshot?.downloadUrl)
            ?: safeArtifactUrl(metadata.downloadUrl)
            ?: throw IllegalStateException("External bundle metadata did not contain a downloadable artifact URL")
        val signatureUrl = snapshot?.signatureDownloadUrl ?: metadata.signatureDownloadUrl
        val version = snapshot?.version?.ifBlank { null } ?: metadata.version
        val description = snapshot?.description ?: metadata.description ?: ""
        val createdAtRaw = snapshot?.createdAt ?: metadata.createdAt
        val createdAt = parseCreatedAt(createdAtRaw)

        return ReVancedAsset(
            downloadUrl = downloadUrl,
            createdAt = createdAt,
            signatureDownloadUrl = signatureUrl,
            pageUrl = snapshot?.sourceUrl,
            description = description,
            version = version
        )
    }

    private fun ExternalBundleSnapshot.toChangelogEntry(): PatchBundleChangelogEntry {
        val repoUrl = when {
            ownerName.isNotBlank() && repoName.isNotBlank() ->
                "https://github.com/$ownerName/$repoName"
            else -> inferGitHubRepoUrl(sourceUrl)
        }
        val pageUrl = when {
            !repoUrl.isNullOrBlank() && version.isNotBlank() ->
                "${repoUrl.removeSuffix("/")}/releases/tag/$version"
            sourceUrl.isNotBlank() -> sourceUrl
            else -> null
        }
        return PatchBundleChangelogEntry(
            version = version,
            description = description?.takeIf { it.isNotBlank() } ?: version,
            publishedAtMillis = parseInstant(createdAt)?.toEpochMilliseconds(),
            pageUrl = pageUrl
        )
    }

    private suspend fun resolveHistorySource(endpointMetadata: EndpointMetadata): HistorySource {
        val endpointOwner = endpointMetadata.owner?.trim().takeIf { !it.isNullOrBlank() }
        val endpointRepo = endpointMetadata.repo?.trim().takeIf { !it.isNullOrBlank() }
        val metadataOwner = metadata.ownerName?.trim().takeIf { !it.isNullOrBlank() }
        val metadataRepo = metadata.repoName?.trim().takeIf { !it.isNullOrBlank() }
        val snapshot = metadata.bundleId.takeIf { it > 0 }?.let { bundleId ->
            api.getBundleById(bundleId).getOrNull()
        }
        val snapshotOwner = snapshot?.ownerName?.trim().takeIf { !it.isNullOrBlank() }
        val snapshotRepo = snapshot?.repoName?.trim().takeIf { !it.isNullOrBlank() }
        val owner = endpointOwner ?: metadataOwner ?: snapshotOwner ?: ""
        val repo = endpointRepo ?: metadataRepo ?: snapshotRepo ?: ""
        val sourceUrl = snapshot?.sourceUrl?.trim()?.takeIf { it.isNotBlank() }
        val repoUrl = when {
            owner.isNotBlank() && repo.isNotBlank() -> "https://github.com/$owner/$repo"
            else -> inferGitHubRepoUrl(sourceUrl, endpoint, metadata.downloadUrl)
        }

        if (snapshot != null && (metadata.ownerName.isNullOrBlank() || metadata.repoName.isNullOrBlank())) {
            metadata = metadata.copy(
                ownerName = snapshotOwner ?: metadata.ownerName,
                repoName = snapshotRepo ?: metadata.repoName
            )
            ExternalBundleMetadataStore.write(directory, metadata)
        }

        return HistorySource(
            owner = owner,
            repo = repo,
            repoUrl = repoUrl,
            sourceUrl = sourceUrl
        )
    }

    private suspend fun fetchExternalHistory(
        source: HistorySource,
        prerelease: Boolean?,
        limit: Int
    ): List<PatchBundleChangelogEntry> {
        if (source.owner.isBlank() || source.repo.isBlank()) return emptyList()
        return api.getBundleHistory(source.owner, source.repo, prerelease = prerelease, limit = limit)
            .getOrNull()
            .orEmpty()
            .map { snapshot -> snapshot.toChangelogEntry() }
    }

    private fun mergeHistoryEntries(
        current: List<PatchBundleChangelogEntry>,
        incoming: List<PatchBundleChangelogEntry>,
        limit: Int
    ): List<PatchBundleChangelogEntry> {
        val targetLimit = limit.coerceAtLeast(1)
        if (incoming.isEmpty()) return current.take(targetLimit)
        return (current + incoming)
            .sortedWith(compareByDescending<PatchBundleChangelogEntry> { it.publishedAtMillis ?: Long.MIN_VALUE })
            .distinctBy { entry ->
                entry.version.trim().lowercase().takeIf { it.isNotBlank() }
                    ?: "${entry.publishedAtMillis ?: Long.MIN_VALUE}|${entry.description.trim()}"
            }
            .take(targetLimit)
    }

    private fun parseEndpointMetadata(): EndpointMetadata {
        val candidates = listOfNotNull(endpoint, metadata.downloadUrl)
        for (candidate in candidates) {
            val parsed = runCatching { Url(candidate) }.getOrNull() ?: continue
            val segments = parsed.encodedPath.trim('/').split('/').filter { it.isNotBlank() }
            if (segments.size >= 5 &&
                segments[0].equals("api", ignoreCase = true) &&
                (segments[1].equals("v1", ignoreCase = true) || segments[1].equals("v2", ignoreCase = true)) &&
                segments[2].equals("bundle", ignoreCase = true)
            ) {
                val owner = segments[3]
                val repo = segments[4]
                val (hasExplicitChannel, prerelease) = if (segments[1].equals("v2", ignoreCase = true)) {
                    when (parsed.parameters["channel"]?.lowercase()) {
                        "any" -> true to null
                        "stable" -> true to false
                        "prerelease" -> true to true
                        else -> false to null
                    }
                } else {
                    when (parsed.parameters["prerelease"]?.lowercase()) {
                        "true" -> true to true
                        "false" -> true to false
                        else -> false to null
                    }
                }
                return EndpointMetadata(
                    owner = owner,
                    repo = repo,
                    prerelease = prerelease,
                    hasExplicitChannel = hasExplicitChannel,
                    isV2LatestEndpoint = segments[1].equals("v2", ignoreCase = true) &&
                        segments.getOrNull(5).equals("latest", ignoreCase = true)
                )
            }
        }
        return EndpointMetadata(
            owner = null,
            repo = null,
            prerelease = null,
            hasExplicitChannel = false,
            isV2LatestEndpoint = false
        )
    }

    private fun safeArtifactUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (isExternalBundleApiEndpoint(trimmed)) return null
        return trimmed
    }

    private fun isExternalBundleApiEndpoint(raw: String): Boolean {
        val parsed = runCatching { Url(raw) }.getOrNull() ?: return false
        val host = parsed.host.lowercase()
        val isExternalBundlesHost = host == "revanced-external-bundles.brosssh.com" ||
            host == "revanced-external-bundles-dev.brosssh.com"
        if (!isExternalBundlesHost) return false
        val pathNoQuery = parsed.encodedPath.substringBefore('?').substringBefore('#')
        return pathNoQuery.startsWith("/api/v1/bundle/") ||
            pathNoQuery.startsWith("/api/v2/bundle/") ||
            pathNoQuery.startsWith("/bundles/id")
    }

    private fun parseCreatedAt(raw: String?): LocalDateTime {
        val trimmed = raw?.trim().orEmpty()
        val instantParsed = runCatching { Instant.parse(trimmed).toLocalDateTime(TimeZone.UTC) }.getOrNull()
        if (instantParsed != null) return instantParsed
        val localParsed = runCatching { LocalDateTime.parse(trimmed) }.getOrNull()
        return localParsed ?: Clock.System.now().toLocalDateTime(TimeZone.UTC)
    }

    private fun pickLatestSnapshot(
        release: ExternalBundleSnapshot?,
        prerelease: ExternalBundleSnapshot?
    ): ExternalBundleSnapshot? {
        if (release == null) return prerelease
        if (prerelease == null) return release

        val releaseInstant = snapshotInstant(release)
        val prereleaseInstant = snapshotInstant(prerelease)
        if (releaseInstant == null && prereleaseInstant == null) return prerelease
        if (releaseInstant == null) return prerelease
        if (prereleaseInstant == null) return release
        return if (prereleaseInstant > releaseInstant) prerelease else release
    }

    private fun snapshotInstant(snapshot: ExternalBundleSnapshot): Instant? =
        parseInstant(snapshot.repoPushedAt)
            ?: parseInstant(snapshot.lastRefreshedAt)
            ?: parseInstant(snapshot.createdAt)

    private fun parseInstant(raw: String?): Instant? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return runCatching { Instant.parse(trimmed) }.getOrNull()
    }

    private fun pickNewestOfficialAsset(
        release: ReVancedAsset?,
        prerelease: ReVancedAsset?
    ): ReVancedAsset? {
        if (release == null) return prerelease
        if (prerelease == null) return release
        return if (prerelease.createdAt > release.createdAt) prerelease else release
    }
}

private data class CachedChangelog(val asset: ReVancedAsset, val timestamp: Long)
private data class CachedChangelogHistory(
    val entries: List<PatchBundleChangelogEntry>,
    val timestamp: Long
)
