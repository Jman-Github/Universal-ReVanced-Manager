package app.urv.manager.domain.repository

import app.urv.manager.network.dto.GitHubAsset
import app.urv.manager.network.dto.GitHubRelease

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
