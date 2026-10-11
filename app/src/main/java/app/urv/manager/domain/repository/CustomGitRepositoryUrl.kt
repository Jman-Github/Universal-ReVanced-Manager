package app.urv.manager.domain.repository

import app.urv.manager.domain.bundles.RepositoryReleaseSource
import app.urv.manager.domain.bundles.RepositoryReleaseSourceParser
import io.ktor.http.Url

/** Normalize public Forgejo/Gitea and self-hosted GitLab links without hard-coding hosts. */
internal fun customGitRepositoryUrl(rawUrl: String): String? {
    val parsed = runCatching { Url(rawUrl.trim()) }.getOrNull() ?: return null
    if (parsed.host.lowercase() in setOf("github.com", "raw.githubusercontent.com", "api.github.com", "gitlab.com")) {
        return null
    }
    val segments = parsed.encodedPath.trim('/').split('/').filter(String::isNotBlank)
    val query = parsed.encodedQuery.takeIf(String::isNotBlank)?.let { "?$it" }.orEmpty()
    return when (val source = RepositoryReleaseSourceParser.parse(rawUrl)) {
        is RepositoryReleaseSource.Forgejo -> {
            val remainder = segments.drop(2)
            when {
                remainder.isEmpty() || remainder == listOf("releases") -> source.repositoryUrl
                remainder.firstOrNull() in setOf("raw", "releases") &&
                    segments.last().endsWith(".json", ignoreCase = true) ->
                    "${source.origin}${parsed.encodedPath}$query"
                remainder.firstOrNull() == "src" && remainder.getOrNull(1) in setOf("branch", "tag", "commit") -> {
                    if (remainder.size < 3) return null
                    val file = remainder.drop(3).joinToString("/").ifEmpty { "patches-bundle.json" }
                    if (!file.endsWith(".json", ignoreCase = true)) return null
                    "${source.repositoryUrl}/raw/${remainder[1]}/${remainder[2]}/$file"
                }
                segments.take(3) == listOf("api", "v1", "repos") &&
                    segments.drop(5) in listOf(emptyList<String>(), listOf("releases"), listOf("releases", "latest")) ->
                    source.repositoryUrl
                else -> null
            }
        }
        is RepositoryReleaseSource.GitLab -> {
            if ("-" !in segments) return "${source.origin}/${source.repositoryPath}"
            val remainder = segments.drop(segments.indexOf("-") + 1)
            if (remainder == listOf("releases")) return "${source.origin}/${source.repositoryPath}"
            if (remainder.firstOrNull() in setOf("raw", "blob") && remainder.size >= 3) {
                val file = remainder.drop(2).joinToString("/")
                if (!file.endsWith(".json", ignoreCase = true)) return null
                return "${source.origin}/${source.repositoryPath}/-/raw/${remainder[1]}/$file$query"
            }
            val reference = when {
                remainder.firstOrNull() == "tree" && remainder.size <= 2 ->
                    remainder.getOrNull(1) ?: "HEAD"
                else -> return null
            }
            "${source.origin}/${source.repositoryPath}/-/raw/$reference/patches-bundle.json"
        }
        else -> null
    }
}
