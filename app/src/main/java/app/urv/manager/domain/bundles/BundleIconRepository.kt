package app.urv.manager.domain.bundles

import android.app.Application
import app.urv.manager.network.api.ExternalBundlesApi
import app.urv.manager.network.api.ExternalBundlesEndpoints
import app.urv.manager.network.service.HttpService
import app.urv.manager.network.utils.getOrNull
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest

@Serializable
data class BundleIcon(val url: String? = null, val avatar: String? = null, val checkedAt: Long = 0)

/** Repository metadata and missing root images are checked at most once per day. */
class BundleIconRepository(
    private val app: Application,
    private val http: HttpService,
    private val api: ExternalBundlesApi
) {
    private val mutex = Mutex()
    private val memory = mutableMapOf<String, BundleIcon>()
    private val cacheDir = File(app.cacheDir, "bundle_icons").also { it.mkdirs() }

    suspend fun resolve(source: PatchBundleSource): BundleIcon = withContext(Dispatchers.IO) {
        if (source.uid == 0 || source is APIPatchBundle)
            return@withContext BundleIcon(OFFICIAL_ICON_URL)
        val remote = source as? RemotePatchBundle ?: return@withContext BundleIcon()
        mutex.withLock {
            val external = ExternalBundleMetadataStore.read(source.bundleDirectory)
            if (bundleRepositoryUrl(external?.sourceUrl, remote.endpoint)
                    .equals(OFFICIAL_PATCH_REPOSITORY, true)) return@withLock BundleIcon(OFFICIAL_ICON_URL)
            if (external?.iconUrl != null || external?.ownerAvatarUrl != null) {
                return@withLock BundleIcon(external.iconUrl, external.ownerAvatarUrl)
            }
            val key = remote.endpoint
            val file = File(cacheDir, MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
                .joinToString("") { "%02x".format(it) } + ".json")
            val cached = memory[key] ?: runCatching {
                http.json.decodeFromString<BundleIcon>(file.readText())
            }.getOrNull()
            val now = System.currentTimeMillis()
            if (cached != null && now - cached.checkedAt in 0 until CACHE_TTL_MS) return@withLock cached
            var fallback = cached
            val icon = try {
                if (source is ExternalGraphqlPatchBundle) {
                    val sourceUrl = external?.sourceUrl ?: Url(remote.endpoint).parameters["source_url"]
                        ?: external?.let { "https://github.com/${it.ownerName}/${it.repoName}" }
                    val bundle = sourceUrl?.let { api.getLatestBundleForSource(it, external?.isPrerelease).getOrNull() }
                    if (bundle == null) cached?.copy(checkedAt = now - CACHE_TTL_MS + FAILURE_TTL_MS)
                        ?: BundleIcon(checkedAt = now - CACHE_TTL_MS + FAILURE_TTL_MS)
                    else BundleIcon(bundle.iconUrl, bundle.ownerAvatarUrl, now)
                } else {
                    withTimeoutOrNull(ExternalBundlesEndpoints.HOST_QUERY_TIMEOUT_MS) {
                        fromRepository(if (source is APIPatchBundle)
                            "https://github.com/ReVanced/revanced-patches" else remote.endpoint, now) { avatar ->
                            // Keep the fetched avatar if the root-image probe fails or times out.
                            fallback = BundleIcon(cached?.url, avatar ?: cached?.avatar)
                        } ?: BundleIcon(checkedAt = now)
                    } ?: throw IllegalStateException("Repository image lookup timed out")
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                fallback?.copy(checkedAt = now - CACHE_TTL_MS + FAILURE_TTL_MS)
                    ?: BundleIcon(checkedAt = now - CACHE_TTL_MS + FAILURE_TTL_MS)
            }
            memory[key] = icon
            runCatching { file.writeText(http.json.encodeToString(icon)) }
            icon
        }
    }

    private suspend fun fromRepository(
        endpoint: String,
        now: Long,
        onAvatar: (String?) -> Unit
    ): BundleIcon? {
        val parsed = RepositoryReleaseSourceParser.parse(endpoint) ?: run {
            val url = runCatching { Url(endpoint) }.getOrNull()
            if (url?.host == "gitlab.com") RepositoryReleaseSource.GitLab(
                url.encodedPath.trim('/').removeSuffix(".git")
            ) else null
        }
        val repoUrl = when (parsed) {
            is RepositoryReleaseSource.GitHub -> parsed.repositoryUrl
            is RepositoryReleaseSource.GitLab -> "https://gitlab.com/${parsed.repositoryPath}"
            null -> return null
        }
        val repositoryPath = Url(repoUrl).encodedPath.trim('/')
        val metadataUrl = if (parsed is RepositoryReleaseSource.GitHub) {
            "https://api.github.com/repos/$repositoryPath"
        } else {
            "https://gitlab.com/api/v4/projects/" + URLEncoder.encode(repositoryPath, "UTF-8")
        }
        val response = http.http.get(metadataUrl)
        check(response.status.isSuccess()) { "Repository metadata unavailable" }
        val data = http.json.parseToJsonElement(response.bodyAsText()).jsonObject
        val branch = data["default_branch"]?.jsonPrimitive?.contentOrNull ?: return null
        val avatar = if (parsed is RepositoryReleaseSource.GitHub) {
            data["owner"]?.jsonObject?.get("avatar_url")?.jsonPrimitive?.contentOrNull
        } else {
            data["namespace"]?.jsonObject?.get("avatar_url")?.jsonPrimitive?.contentOrNull
                ?: data["avatar_url"]?.jsonPrimitive?.contentOrNull
        }
        onAvatar(avatar)
        val encodedBranch = URLEncoder.encode(branch, "UTF-8").replace("+", "%20")
        val rootImage = if (parsed is RepositoryReleaseSource.GitHub) {
            "https://raw.githubusercontent.com/$repositoryPath/$encodedBranch/patches-bundle.png"
        } else "$repoUrl/-/raw/$encodedBranch/patches-bundle.png"
        val imageResponse = http.http.head(rootImage)
        if (!imageResponse.status.isSuccess() && imageResponse.status != HttpStatusCode.NotFound) {
            throw IllegalStateException("Repository image temporarily unavailable")
        }
        return BundleIcon(rootImage.takeIf { imageResponse.status.isSuccess() }, avatar, now)
    }

    companion object {
        // ReVanced/revanced-branding@73b3a5605e20f140994ecc08d232d6d921fa4bcf
        // assets/revanced-logo/revanced-logo.svg
        const val OFFICIAL_ICON_URL = "file:///android_asset/revanced-bundle.svg"
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
        private const val FAILURE_TTL_MS = 60 * 60 * 1000L
    }
}
