package app.universal.patch.manager.domain.bundles

import java.net.URI
import java.net.URLEncoder

internal const val OFFICIAL_PATCH_REPOSITORY = "https://github.com/ReVanced/revanced-patches"

/** Preserve the tag instead of reducing a download URL to the releases overview. */
internal fun releasePageFromArtifact(raw: String?): String? {
    val uri = raw?.let { runCatching { URI(it.trim()) }.getOrNull() } ?: return null
    if (uri.scheme !in listOf("http", "https") || uri.host == null) return null
    val path = uri.rawPath ?: return null
    val segments = path.split('/')
    val marker = segments.indices.firstOrNull { index -> segments[index] == "releases" &&
        (segments.getOrNull(index + 1) in listOf("download", "tag") || segments.getOrNull(index - 1) == "-")
    } ?: return null
    val action = segments.getOrNull(marker + 1)
    val gitlab = marker > 0 && segments[marker - 1] == "-"
    val tag = if (gitlab) action else if (action == "download" || action == "tag")
        segments.getOrNull(marker + 2) else null
    if (tag.isNullOrBlank()) return null
    val prefix = segments.take(marker + 1).joinToString("/")
    val releasePath = if (segments.getOrNull(marker - 1) == "-") "$prefix/$tag" else "$prefix/tag/$tag"
    return "${uri.scheme}://${uri.rawAuthority}$releasePath"
}

internal fun bundleRepositoryUrl(vararg candidates: String?): String? {
    for (raw in candidates) {
        if (raw.isNullOrBlank()) continue
        val github = Regex("^(?:git@|ssh://git@|https?://|git://)?github\\.com[:/]([^/]+)/([^/?#]+)",
            RegexOption.IGNORE_CASE).find(raw.trim())
        if (github != null) return "https://github.com/${github.groupValues[1]}/" +
            github.groupValues[2].removeSuffix(".git")
        when (val source = RepositoryReleaseSourceParser.parse(raw)) {
            is RepositoryReleaseSource.GitHub -> return source.repositoryUrl
            is RepositoryReleaseSource.GitLab -> return "${source.origin}/${source.repositoryPath}"
            is RepositoryReleaseSource.Forgejo -> return source.repositoryUrl
            null -> {
                // Plain GitLab repository roots have no /-/ separator.
                val uri = runCatching { URI(raw) }.getOrNull()
                if (uri?.host.equals("gitlab.com", true) && uri?.path?.trim('/')?.contains('/') == true)
                    return "https://gitlab.com/" + uri.path.trim('/').removeSuffix(".git")
            }
        }
    }
    return null
}

internal fun repositoryReleasePage(repository: String, tag: String? = null): String {
    val gitlab = URI(repository).host.equals("gitlab.com", true) ||
        RepositoryReleaseSourceParser.parse(repository) is RepositoryReleaseSource.GitLab
    val prefix = repository.trimEnd('/') + if (gitlab) "/-/releases" else "/releases"
    if (tag.isNullOrBlank()) return prefix
    val encoded = URLEncoder.encode(tag, "UTF-8").replace("+", "%20")
    return prefix + if (gitlab) "/$encoded" else "/tag/$encoded"
}

internal fun String?.safeReleasePage(): String? =
    this?.takeIf { value -> runCatching {
        val uri = URI(value)
        uri.scheme in listOf("http", "https") && uri.host != null
    }.getOrDefault(false) }

internal fun bundleReleaseVersion(signature: String?, manifestVersion: String?): String? =
    signature?.takeIf { it.isNotBlank() && !it.startsWith("sha256:") && !it.startsWith("url:") &&
        !it.matches(Regex("[a-fA-F0-9]{40,64}")) } ?: manifestVersion?.takeIf(String::isNotBlank)
