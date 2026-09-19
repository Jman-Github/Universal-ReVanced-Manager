package app.urv.manager.domain.bundles

import io.ktor.http.Url

internal sealed interface RepositoryReleaseSource {
    data class GitHub(val repositoryUrl: String) : RepositoryReleaseSource
    data class GitLab(val repositoryPath: String) : RepositoryReleaseSource
}

internal object RepositoryReleaseSourceParser {
    fun parse(rawUrl: String): RepositoryReleaseSource? {
        val parsed = runCatching { Url(rawUrl.trim()) }.getOrNull() ?: return null
        val segments = parsed.encodedPath.trim('/').split('/').filter(String::isNotBlank)

        return when (parsed.host.lowercase()) {
            "github.com", "raw.githubusercontent.com" -> parseGitHub(segments)
            "api.github.com" -> {
                val repositoriesIndex = segments.indexOf("repos")
                if (repositoriesIndex < 0) null else parseGitHub(segments.drop(repositoriesIndex + 1))
            }
            "gitlab.com" -> parseGitLab(segments)
            else -> null
        }
    }

    private fun parseGitHub(segments: List<String>): RepositoryReleaseSource.GitHub? {
        if (segments.size < 2) return null
        val owner = segments[0].removeSuffix(".git")
        val repository = segments[1].removeSuffix(".git")
        if (owner.isBlank() || repository.isBlank()) return null

        return RepositoryReleaseSource.GitHub("https://github.com/$owner/$repository")
    }

    private fun parseGitLab(segments: List<String>): RepositoryReleaseSource.GitLab? {
        val separatorIndex = segments.indexOf("-")
        if (separatorIndex < 2) return null

        val repositorySegments = segments.take(separatorIndex).toMutableList()
        repositorySegments[repositorySegments.lastIndex] =
            repositorySegments.last().removeSuffix(".git")
        if (repositorySegments.any(String::isBlank)) return null

        return RepositoryReleaseSource.GitLab(repositorySegments.joinToString("/"))
    }
}
