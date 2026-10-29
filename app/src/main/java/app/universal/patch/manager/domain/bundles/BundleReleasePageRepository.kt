package app.universal.patch.manager.domain.bundles

import app.universal.patch.manager.network.api.ExternalBundlesApi
import app.universal.patch.manager.network.api.UpmAPI
import app.universal.patch.manager.network.utils.getOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Resolve only on tap; reuse results across the details and quick-action buttons. */
class BundleReleasePageRepository(
    private val api: ExternalBundlesApi,
    private val github: UpmAPI,
) {
    private data class Cached(val url: String?, val expiresAt: Long)
    private val cache = linkedMapOf<String, Cached>()
    private val mutex = Mutex()

    suspend fun resolve(source: PatchBundleSource, online: Boolean): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val remote = source as? RemotePatchBundle
            val metadata = ExternalBundleMetadataStore.read(source.bundleDirectory)
            val version = bundleReleaseVersion(remote?.installedVersionSignature, source.version)
            val key = "${source.uid}|${source.updatedAt}|$version|${remote?.endpoint}|$online"
            val now = System.currentTimeMillis()
            cache[key]?.takeIf { it.expiresAt > now }?.let { return@withLock it.url }
            val installedUrl = runCatching {
                source.bundleDirectory.resolve("installed_artifact_url").readText().trim()
            }.getOrNull()
            val matchingMetadata = metadata?.takeIf { version == null ||
                it.version.removePrefix("v") == version.removePrefix("v") }
            val repository = bundleRepositoryUrl(metadata?.sourceUrl, source.patchBundle?.manifestAttributes?.source,
                    remote?.endpoint, installedUrl, matchingMetadata?.downloadUrl,
                    metadata?.takeIf { !it.ownerName.isNullOrBlank() && !it.repoName.isNullOrBlank() }
                        ?.let { "https://github.com/${it.ownerName}/${it.repoName}" })
            suspend fun exact(): String? {
                releasePageFromArtifact(installedUrl)?.let { return it }
                releasePageFromArtifact(matchingMetadata?.downloadUrl)?.let { return it }
                if (online && repository != null) {
                    // Registry history knows upstream tags even for archived artifacts.
                    api.getBundleReleasePage(repository, version.orEmpty(), installedUrl ?: matchingMetadata?.downloadUrl)
                        .getOrNull()?.safeReleasePage()?.let { return it }
                    if (repository.startsWith("https://github.com/") && !version.isNullOrBlank()) {
                        val tags = listOf(version, if (version.startsWith("v")) version.drop(1) else "v$version").distinct()
                        for (tag in tags) {
                            val release = github.getRepositoryReleaseByTag(repository, tag).getOrNull()
                            if (release != null) return repositoryReleasePage(repository, release.tagName)
                        }
                    }
                }
                if (online && remote != null && version.isNullOrBlank()) {
                    val asset = remote.fetchLatestReleaseInfo()
                    return releasePageFromArtifact(asset.downloadUrl)
                        ?: asset.pageUrl?.safeReleasePage()
                }
                return null
            }
            val exactUrl = try { exact() } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) { null }
            val result = exactUrl ?: releasePageFromArtifact(remote?.endpoint)
                ?: repository?.let { repositoryReleasePage(it) }
            cache[key] = Cached(result, now + if (exactUrl != null) 24 * 60 * 60 * 1000L else 5 * 60 * 1000L)
            while (cache.size > 64) cache.remove(cache.keys.first())
            result
        }
    }
}
