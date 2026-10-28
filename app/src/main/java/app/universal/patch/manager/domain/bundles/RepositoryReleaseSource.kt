package app.universal.patch.manager.domain.bundles

import app.universal.patch.manager.network.api.ExternalBundlesEndpoints
import io.ktor.http.Url
import java.net.URI

internal sealed interface RepositoryReleaseSource {
    data class GitHub(val repositoryUrl: String) : RepositoryReleaseSource
    data class GitLab(
        val repositoryPath: String,
        val origin: String = "https://gitlab.com"
    ) : RepositoryReleaseSource
    data class Forgejo(val origin: String, val repositoryPath: String) : RepositoryReleaseSource {
        val repositoryUrl: String get() = "$origin/$repositoryPath"
        val apiUrl: String get() = "$origin/api/v1/repos/$repositoryPath"
    }
}

internal object RepositoryReleaseSourceParser {
    fun parse(rawUrl: String): RepositoryReleaseSource? {
        val parsed = runCatching { Url(rawUrl.trim()) }.getOrNull() ?: return null
        if (parsed.protocol.name !in setOf("https", "http") || parsed.host.isBlank()) return null
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return null
        if (uri.rawUserInfo != null) return null
        val origin = "${parsed.protocol.name}://${uri.rawAuthority}"
        val segments = parsed.encodedPath.trim('/').split('/').filter(String::isNotBlank)

        return when (parsed.host.lowercase()) {
            "github.com", "raw.githubusercontent.com" -> parseGitHub(segments)
            "api.github.com" -> {
                val repositoriesIndex = segments.indexOf("repos")
                if (repositoriesIndex < 0) null else parseGitHub(segments.drop(repositoriesIndex + 1))
            }
            "gitlab.com" -> parseGitLab(segments)
            else -> {
                if (ExternalBundlesEndpoints.isExternalBundlesHost(parsed.host)) return null
                if (segments.lastOrNull()?.endsWith(".json", ignoreCase = true) == true &&
                    segments.getOrNull(2) !in setOf("src", "raw", "releases") &&
                    segments.indexOf("-") < 2
                ) return null
                when {
                    segments.indexOf("-") >= 2 -> parseGitLab(segments, origin)
                    segments.take(3) == listOf("api", "v1", "repos") ->
                        parseForgejo(origin, segments.drop(3))
                    segments.size == 2 || segments.drop(2) == listOf("releases") ->
                        parseForgejo(origin, segments)
                    segments.getOrNull(2) in setOf("src", "raw") &&
                        segments.getOrNull(3) in setOf("branch", "tag", "commit") ->
                        parseForgejo(origin, segments)
                    segments.getOrNull(2) == "releases" &&
                        segments.getOrNull(3) in setOf("tag", "download") ->
                        parseForgejo(origin, segments)
                    segments.size > 2 && segments.none {
                        it in setOf(".", "..", "src", "raw", "releases", "api", "issues", "pulls", "wiki", "actions", "settings", "projects")
                    } -> RepositoryReleaseSource.GitLab(
                        segments.dropLast(1).plus(segments.last().removeSuffix(".git")).joinToString("/"), origin
                    )
                    else -> null
                }
            }
        }
    }

    private fun parseGitHub(segments: List<String>): RepositoryReleaseSource.GitHub? {
        if (segments.size < 2) return null
        val owner = segments[0].removeSuffix(".git")
        val repository = segments[1].removeSuffix(".git")
        if (owner.isBlank() || repository.isBlank()) return null

        return RepositoryReleaseSource.GitHub("https://github.com/$owner/$repository")
    }

    private fun parseGitLab(
        segments: List<String>,
        origin: String = "https://gitlab.com"
    ): RepositoryReleaseSource.GitLab? {
        val separatorIndex = segments.indexOf("-")
        if (separatorIndex < 2) return null

        val repositorySegments = segments.take(separatorIndex).toMutableList()
        repositorySegments[repositorySegments.lastIndex] =
            repositorySegments.last().removeSuffix(".git")
        if (repositorySegments.any(String::isBlank)) return null

        return RepositoryReleaseSource.GitLab(repositorySegments.joinToString("/"), origin)
    }

    private fun parseForgejo(origin: String, segments: List<String>): RepositoryReleaseSource.Forgejo? {
        if (segments.size < 2) return null
        val owner = segments[0]
        val repository = segments[1].removeSuffix(".git")
        if (owner.isBlank() || repository.isBlank() || '.' == owner.first() || repository == ".") return null
        return RepositoryReleaseSource.Forgejo(origin, "$owner/$repository")
    }
}
