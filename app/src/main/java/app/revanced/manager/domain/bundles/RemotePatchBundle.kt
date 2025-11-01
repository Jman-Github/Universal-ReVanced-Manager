package app.revanced.manager.domain.bundles

import app.revanced.manager.data.redux.ActionContext
import app.revanced.manager.network.api.ReVancedAPI
import app.revanced.manager.network.dto.ReVancedAsset
import app.revanced.manager.network.service.HttpService
import app.revanced.manager.network.utils.getOrThrow
import io.ktor.client.request.url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

sealed class RemotePatchBundle(
    name: String,
    uid: Int,
    displayName: String?,
    protected val versionHash: String?,
    error: Throwable?,
    directory: File,
    val endpoint: String,
    val autoUpdate: Boolean,
) : PatchBundleSource(name, uid, displayName, error, directory), KoinComponent {
    protected val http: HttpService by inject()

    protected abstract suspend fun getLatestInfo(): ReVancedAsset
    abstract fun copy(
        error: Throwable? = this.error,
        name: String = this.name,
        displayName: String? = this.displayName,
        autoUpdate: Boolean = this.autoUpdate
    ): RemotePatchBundle

    override fun copy(
        error: Throwable?,
        name: String,
        displayName: String?
    ): RemotePatchBundle = copy(error, name, displayName, this.autoUpdate)

    private suspend fun download(info: ReVancedAsset) = withContext(Dispatchers.IO) {
        patchBundleOutputStream().use {
            http.streamTo(it) {
                url(info.downloadUrl)
            }
        }

        info.version
    }

    /**
     * Downloads the latest version regardless if there is a new update available.
     */
    suspend fun ActionContext.downloadLatest() = download(getLatestInfo())

    suspend fun ActionContext.update(): String? = withContext(Dispatchers.IO) {
        val info = getLatestInfo()
        if (hasInstalled() && info.version == versionHash)
            return@withContext null

        download(info)
    }

    suspend fun fetchLatestReleaseInfo(): ReVancedAsset {
        val key = "$uid|$endpoint"
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

    companion object {
        const val updateFailMsg = "Failed to update patches"
        private const val CHANGELOG_CACHE_TTL = 10 * 60 * 1000L
        private val changelogCacheMutex = Mutex()
        private val changelogCache = mutableMapOf<String, CachedChangelog>()
    }
}

class JsonPatchBundle(
    name: String,
    uid: Int,
    displayName: String?,
    versionHash: String?,
    error: Throwable?,
    directory: File,
    endpoint: String,
    autoUpdate: Boolean,
) : RemotePatchBundle(name, uid, displayName, versionHash, error, directory, endpoint, autoUpdate) {
    override suspend fun getLatestInfo() = withContext(Dispatchers.IO) {
        http.request<ReVancedAsset> {
            url(endpoint)
        }.getOrThrow()
    }

    override fun copy(error: Throwable?, name: String, displayName: String?, autoUpdate: Boolean) = JsonPatchBundle(
        name,
        uid,
        displayName,
        versionHash,
        error,
        directory,
        endpoint,
        autoUpdate,
    )
}

class APIPatchBundle(
    name: String,
    uid: Int,
    displayName: String?,
    versionHash: String?,
    error: Throwable?,
    directory: File,
    endpoint: String,
    autoUpdate: Boolean,
) : RemotePatchBundle(name, uid, displayName, versionHash, error, directory, endpoint, autoUpdate) {
    private val api: ReVancedAPI by inject()

    override suspend fun getLatestInfo() = api.getPatchesUpdate().getOrThrow()
    override fun copy(error: Throwable?, name: String, displayName: String?, autoUpdate: Boolean) = APIPatchBundle(
        name,
        uid,
        displayName,
        versionHash,
        error,
        directory,
        endpoint,
        autoUpdate,
    )
}

private data class CachedChangelog(val asset: ReVancedAsset, val timestamp: Long)
