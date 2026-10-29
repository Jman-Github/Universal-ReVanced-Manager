package app.universal.patch.manager.domain.repository

import app.universal.patch.manager.network.dto.GitHubAsset
import app.universal.patch.manager.network.dto.GitHubRelease

/**
 * Histories arrive newest first. Prefer a stable release for each plugin, while
 * allowing plugins that have only shipped in prereleases to be discovered.
 */
internal fun selectRuntimePluginAssets(
    releases: List<GitHubRelease>,
    selector: (GitHubAsset) -> String?
): List<Pair<GitHubRelease, GitHubAsset>> {
    val seen = mutableSetOf<String>()
    return releases.filterNot { it.draft }
        .sortedBy { it.prerelease }
        .flatMap { release ->
            release.assets.mapNotNull { asset ->
                val key = selector(asset) ?: return@mapNotNull null
                if (seen.add(key)) release to asset else null
            }
        }
}


// Manager APKs share releases with runtime plugins; skip both branding generations.
internal fun isRuntimePluginAssetName(name: String): Boolean =
    name.endsWith(".apk", ignoreCase = true) &&
        !name.equals("reseam-plugin.apk", ignoreCase = true) &&
        !name.contains("universal-revanced-manager", ignoreCase = true) &&
        !name.contains("universal-patch-manager", ignoreCase = true)
