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

    fun preferredHost(stableVersion: String?, devVersion: String?): String {
        val stable = ApiVersion.parse(stableVersion)
        val dev = ApiVersion.parse(devVersion)
        return when {
            stable == null && dev == null -> DEV_HOST
            stable == null -> DEV_HOST
            dev == null -> STABLE_HOST
            dev > stable -> DEV_HOST
            else -> STABLE_HOST
        }
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

    private data class ApiVersion(
        val core: List<Long>,
        val prerelease: List<String>
    ) : Comparable<ApiVersion> {
        override fun compareTo(other: ApiVersion): Int {
            val coreSize = maxOf(core.size, other.core.size)
            for (index in 0 until coreSize) {
                val comparison = core.getOrElse(index) { 0 }
                    .compareTo(other.core.getOrElse(index) { 0 })
                if (comparison != 0) return comparison
            }

            if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0
            if (prerelease.isEmpty()) return 1
            if (other.prerelease.isEmpty()) return -1

            val prereleaseSize = maxOf(prerelease.size, other.prerelease.size)
            for (index in 0 until prereleaseSize) {
                val left = prerelease.getOrNull(index) ?: return -1
                val right = other.prerelease.getOrNull(index) ?: return 1
                val leftNumber = left.toLongOrNull()
                val rightNumber = right.toLongOrNull()
                val comparison = when {
                    leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                    leftNumber != null -> -1
                    rightNumber != null -> 1
                    else -> left.compareTo(right, ignoreCase = true)
                }
                if (comparison != 0) return comparison
            }
            return 0
        }

        companion object {
            private val pattern = Regex(
                "^[vV]?(\\d+(?:\\.\\d+)*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$"
            )

            fun parse(value: String?): ApiVersion? {
                val match = value?.trim()?.let(pattern::matchEntire) ?: return null
                val core = match.groupValues[1]
                    .split('.')
                    .map { it.toLongOrNull() ?: return null }
                val prerelease = match.groupValues[2]
                    .takeIf(String::isNotBlank)
                    ?.split('.')
                    .orEmpty()
                return ApiVersion(core, prerelease)
            }
        }
    }
}
