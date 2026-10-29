package app.universal.patch.manager.domain.bundles

import app.universal.patch.manager.network.api.ExternalBundlesEndpoints
import java.net.URI
import java.net.URLDecoder

// Accepted only for existing databases and explicit old imports; never seeded.
const val LEGACY_OFFICIAL_PATCHES_URL = "https://github.com/ReVanced/revanced-patches"

fun legacyOfficialBundleRegistryUrl(prerelease: Boolean?): String =
    requireNotNull(ExternalBundlesEndpoints.latestBundleUrl(
        ExternalBundlesEndpoints.REGISTRY_HOST, LEGACY_OFFICIAL_PATCHES_URL, prerelease
    ))

/** Match old profile/selection entries across the move from "api" to discovery. */
fun normalizeLegacyBundleEndpoint(endpoint: String): String {
    val value = endpoint.trim()
    if (value.equals("api", ignoreCase = true)) return LEGACY_OFFICIAL_PATCHES_URL
    val uri = runCatching { URI(value) }.getOrNull() ?: return value
    if (!ExternalBundlesEndpoints.isExternalBundlesHost(uri.host.orEmpty()) ||
        uri.path != ExternalBundlesEndpoints.V3_BUNDLE_PATH) return value
    val source = runCatching {
        uri.rawQuery.orEmpty().split('&').firstOrNull { it.substringBefore('=') == "source_url" }
            ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }
    }.getOrNull()
    return if (source?.trimEnd('/').equals(LEGACY_OFFICIAL_PATCHES_URL, ignoreCase = true)) {
        LEGACY_OFFICIAL_PATCHES_URL
    } else value
}

/** UID 0 also identifies builds that already converted the old marker to its repository URL. */
internal fun isLegacyOfficialBundle(uid: Int, endpoint: String): Boolean =
    endpoint.equals("api", ignoreCase = true) ||
        (uid == 0 && endpoint.trimEnd('/').equals(LEGACY_OFFICIAL_PATCHES_URL, ignoreCase = true))

/** Explicit registry channels and versions remain distinct identities. */
fun bundleEndpointIdentity(endpoint: String): String =
    if (endpoint.trim().equals("api", true)) LEGACY_OFFICIAL_PATCHES_URL
    else endpoint.trim().trimEnd('/')

/** Add the old API alias only when it cannot point to a different channel/version. */
fun <T> Iterable<Pair<String, T>>.toLegacyAwareBundleIndex(): Map<String, T> {
    val entries = toList()
    val result = entries.associate { bundleEndpointIdentity(it.first) to it.second }.toMutableMap()
    val official = entries.filter { normalizeLegacyBundleEndpoint(it.first) == LEGACY_OFFICIAL_PATCHES_URL }
    if (official.size == 1) result.putIfAbsent(LEGACY_OFFICIAL_PATCHES_URL, official.single().second)
    return result
}

fun legacyBundleEndpointsMatch(first: String, second: String): Boolean =
    if (first.trim().equals("api", true) || second.trim().equals("api", true)) {
        normalizeLegacyBundleEndpoint(first) == normalizeLegacyBundleEndpoint(second)
    } else bundleEndpointIdentity(first) == bundleEndpointIdentity(second)
