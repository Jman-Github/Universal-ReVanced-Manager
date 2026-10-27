package app.urv.manager.domain.bundles

import app.urv.manager.network.api.ExternalBundlesEndpoints
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** IDs belong to their original catalog. Resolve a source before changing catalogs. */
internal data class LegacyDiscoveryEndpoint(
    val host: String,
    val sourceUrl: String?,
    val version: String,
    val prerelease: Boolean?,
    val bundleId: Int? = null,
    val repositoryPath: String? = null
)

internal data class DiscoveryRegistryMigration(
    val endpoint: String,
    val sourceUrl: String,
    val prerelease: Boolean?
)

internal fun legacyDiscoveryEndpoint(raw: String): LegacyDiscoveryEndpoint? = runCatching {
    val url = URI(raw.trim())
    val host = url.host?.lowercase() ?: return null
    if (host != ExternalBundlesEndpoints.STABLE_HOST && host != ExternalBundlesEndpoints.DEV_HOST) return null
    if (url.scheme !in listOf("http", "https") || url.userInfo != null ||
        (url.port != -1 && url.port != if (url.scheme == "https") 443 else 80)) return null
    val path = url.rawPath.trim('/').split('/').map { decodeQuery(it.replace("+", "%2B")) }
    val query = url.rawQuery.orEmpty().split('&').filter(String::isNotBlank)
        .map { it.split('=', limit = 2) }
        .groupBy({ decodeQuery(it[0]) }, { decodeQuery(it.getOrElse(1) { "" }) })
    // Ambiguous selectors should never silently change a user's channel.
    if (query.values.any { it.size != 1 }) return null
    fun param(key: String) = query[key]?.single()?.trim()
    fun channel(value: String?): Boolean? = when (value?.lowercase()) {
        "any", "latest" -> null
        "stable", "release" -> false
        "prerelease", "dev" -> true
        else -> throw IllegalArgumentException("Unknown channel")
    }
    if (path == listOf("api", "v3", "bundle")) {
        val source = repositorySourceUrl(param("source_url")) ?: return null
        val version = param("version")?.takeIf(String::isNotBlank) ?: return null
        val prerelease = if (param("channel").isNullOrBlank() && !version.equals("latest", true)) null
            else channel(param("channel"))
        return LegacyDiscoveryEndpoint(host, source, version, prerelease)
    }
    val id = when {
        path == listOf("bundles", "id") -> param("id")?.toIntOrNull()
        path.size == 4 && path.take(3) == listOf("api", "v1", "bundle") -> path[3].toIntOrNull()
        else -> null
    }
    if (id != null) return id.takeIf { it > 0 }?.let {
        LegacyDiscoveryEndpoint(host, null, "latest", null, it)
    }
    if (path.size != 6 || path[0] != "api" || path[1] !in listOf("v1", "v2") ||
        path[2] != "bundle" || path.drop(3).any { it.isBlank() || '/' in it }) return null
    val source = repositorySourceUrl("https://github.com/${path[3]}/${path[4]}") ?: return null
    val version = path[5]
    val prerelease = if (path[1] == "v2") {
        if (!version.equals("latest", true)) return null
        channel(param("channel"))
    } else if (version.equals("latest", true)) {
        // The v1 API defaults to stable, independently of the last cached release.
        param("prerelease")?.toBooleanStrictOrNull() ?: false
    } else null
    LegacyDiscoveryEndpoint(host, source, version, prerelease,
        repositoryPath = "${path[3]}/${path[4]}")
}.getOrNull()

internal fun discoveryRegistryMigration(
    legacy: LegacyDiscoveryEndpoint,
    sourceUrl: String? = null,
    owner: String? = null,
    repo: String? = null,
    isPrerelease: Boolean? = null,
    downloadUrl: String? = null
): DiscoveryRegistryMigration? {
    val inferredSource = downloadUrl?.let { inferRepositorySourceUrl(it) }
    val savedSource = repositorySourceUrl(sourceUrl ?: inferredSource)
    // v1/v2 encode owner/repo but not the git host. Keep a matching saved host;
    // v3 already carries an explicit source URL and must not be overridden.
    val matchingSavedSource = savedSource?.takeIf {
        legacy.repositoryPath != null &&
            runCatching { URI(it).path.trim('/').removeSuffix(".git") }.getOrNull() == legacy.repositoryPath
    }
    val source = matchingSavedSource ?: legacy.sourceUrl ?: savedSource
        ?: (if (!owner.isNullOrBlank() && !repo.isNullOrBlank()) {
            repositorySourceUrl("https://github.com/${owner.trim()}/${repo.trim()}")
        } else null)
        ?: return null
    // ID imports follow the channel saved by discovery; null means Latest.
    val prerelease = if (legacy.bundleId != null) isPrerelease else legacy.prerelease
    val channel = when (prerelease) { null -> "any"; false -> "stable"; true -> "prerelease" }
    val version = if (legacy.version.equals("latest", true)) "latest" else legacy.version
    val endpoint = "https://${ExternalBundlesEndpoints.REGISTRY_HOST}${ExternalBundlesEndpoints.V3_BUNDLE_PATH}" +
        "?source_url=${encodeQuery(source)}&version=${encodeQuery(version)}&channel=$channel"
    return DiscoveryRegistryMigration(endpoint, source, prerelease)
}

internal fun inferRepositorySourceUrl(raw: String?): String? = runCatching {
    val uri = URI(raw?.trim() ?: return null)
    if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) return null
    val host = uri.host.lowercase()
    if (ExternalBundlesEndpoints.isExternalBundlesHost(host)) return null
    val segments = uri.path.trim('/').split('/').filter(String::isNotBlank)
    when {
        host == "github.com" || host == "raw.githubusercontent.com" -> {
            if (segments.size >= 2) "https://github.com/${segments[0]}/${segments[1].removeSuffix(".git")}" else null
        }
        host == "gitlab.com" -> {
            val dashIndex = segments.indexOf("-")
            val releaseIndex = segments.indexOf("releases")
            val cutoff = when {
                dashIndex >= 2 -> dashIndex
                releaseIndex >= 2 -> releaseIndex
                segments.size >= 2 -> 2
                else -> -1
            }
            if (cutoff >= 2) "https://gitlab.com/${segments.take(cutoff).joinToString("/").removeSuffix(".git")}" else null
        }
        segments.size >= 2 -> "${uri.scheme}://$host/${segments[0]}/${segments[1].removeSuffix(".git")}"
        else -> null
    }
}.getOrNull()

private fun repositorySourceUrl(raw: String?): String? = runCatching {
    val value = raw?.trim()?.trimEnd('/') ?: return null
    val uri = URI(value)
    if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank() ||
        uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
        ExternalBundlesEndpoints.isExternalBundlesHost(uri.host) ||
        uri.path.trim('/').split('/').filter(String::isNotBlank).size < 2) return null
    value
}.getOrNull()

private fun decodeQuery(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())
private fun encodeQuery(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
