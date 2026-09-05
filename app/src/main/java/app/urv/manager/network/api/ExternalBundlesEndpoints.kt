package app.urv.manager.network.api

import java.net.URLEncoder
import java.util.Locale

object ExternalBundlesEndpoints {
    const val STABLE_HOST = "revanced-external-bundles.brosssh.com"
    const val DEV_HOST = "revanced-external-bundles-dev.brosssh.com"
    const val V3_BUNDLE_PATH = "/api/v3/bundle"
    const val HOST_QUERY_TIMEOUT_MS = 8_000L

    fun latestBundleUrl(
        host: String,
        sourceUrl: String,
        prerelease: Boolean?
    ): String? {
        val normalizedHost = host.trim().lowercase(Locale.US)
        if (normalizedHost != STABLE_HOST && normalizedHost != DEV_HOST) return null
        val normalizedSourceUrl = sourceUrl.trim().removeSuffix("/")
        if (normalizedSourceUrl.isBlank()) return null
        val channel = when (prerelease) {
            null -> "any"
            true -> "prerelease"
            false -> "stable"
        }
        val encodedSourceUrl = URLEncoder.encode(normalizedSourceUrl, Charsets.UTF_8.name())
        return "https://$normalizedHost$V3_BUNDLE_PATH" +
            "?source_url=$encodedSourceUrl&version=latest&channel=$channel"
    }

    fun isExternalBundlesHost(host: String): Boolean =
        host.equals(STABLE_HOST, ignoreCase = true) ||
            host.equals(DEV_HOST, ignoreCase = true)

    fun alternateHost(host: String?): String? = when (host?.trim()?.lowercase(Locale.US)) {
        STABLE_HOST -> DEV_HOST
        DEV_HOST -> STABLE_HOST
        else -> null
    }

    fun siteUrl(host: String?): String {
        val normalizedHost = host
            ?.trim()
            ?.lowercase(Locale.US)
            ?.takeIf(::isExternalBundlesHost)
            ?: STABLE_HOST
        return "https://$normalizedHost/"
    }

    fun isBundleApiPath(path: String): Boolean =
        path.startsWith("/api/v1/bundle/") ||
            path.startsWith("/api/v2/bundle/") ||
            path.equals(V3_BUNDLE_PATH, ignoreCase = true) ||
            path.startsWith("/bundles/id")
}
