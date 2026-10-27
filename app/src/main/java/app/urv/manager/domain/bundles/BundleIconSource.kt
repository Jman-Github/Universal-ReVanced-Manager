package app.urv.manager.domain.bundles

import app.urv.manager.network.api.ExternalBundlesEndpoints
import java.net.URI
import java.net.URLDecoder

internal data class RegistryBundleIconSource(val sourceUrl: String, val prerelease: Boolean?)

/** Migrated JSON sources use the same registry icon lookup as discovery objects. */
internal fun registryBundleIconSource(
    endpoint: String,
    storedSourceUrl: String? = null,
    owner: String? = null,
    repo: String? = null,
    storedPrerelease: Boolean? = null,
    downloadUrl: String? = null
): RegistryBundleIconSource? {
    val effectiveSource = storedSourceUrl ?: inferRepositorySourceUrl(downloadUrl)
    legacyDiscoveryEndpoint(endpoint)?.let { legacy ->
        return discoveryRegistryMigration(legacy, effectiveSource, owner, repo, storedPrerelease, downloadUrl)
            ?.let { RegistryBundleIconSource(it.sourceUrl, it.prerelease) }
    }
    return runCatching {
        val uri = URI(endpoint)
        if (!uri.host.equals(ExternalBundlesEndpoints.REGISTRY_HOST, true) ||
            uri.path.trimEnd('/') != ExternalBundlesEndpoints.V3_BUNDLE_PATH) return null
        val parameters = uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank).associate {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to
                URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
        val source = parameters["source_url"]?.trim()?.takeIf(String::isNotBlank)
            ?: effectiveSource?.trim()?.takeIf(String::isNotBlank)
            ?: (if (!owner.isNullOrBlank() && !repo.isNullOrBlank()) "https://github.com/${owner.trim()}/${repo.trim()}" else null)
            ?: return null
        val sourceUri = URI(source)
        if (sourceUri.scheme !in listOf("http", "https") || sourceUri.host.isNullOrBlank() ||
            sourceUri.userInfo != null) return null
        val prerelease = when (parameters["channel"]?.trim()?.lowercase()) {
            "any", "latest" -> null
            "stable", "release" -> false
            "prerelease", "dev" -> true
            else -> storedPrerelease
        }
        RegistryBundleIconSource(source.trimEnd('/'), prerelease)
    }.getOrNull()
}
