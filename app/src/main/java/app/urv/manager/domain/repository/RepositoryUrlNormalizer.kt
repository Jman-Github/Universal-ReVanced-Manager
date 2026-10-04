package app.urv.manager.domain.repository

internal fun gitHubRepositoryManifestUrl(pathSegments: List<String>): String? {
    if (pathSegments.size < 2) return null

    val owner = pathSegments[0]
    val repository = pathSegments[1].removeSuffix(".git").takeIf { it.isNotBlank() }
        ?: return null
    val remainingSegments = pathSegments.drop(2)
    val reference = when {
        remainingSegments.isEmpty() -> "HEAD"
        remainingSegments == listOf("releases") -> "HEAD"
        remainingSegments == listOf("tree") -> "HEAD"
        remainingSegments.size == 2 && remainingSegments[0] == "tree" -> {
            remainingSegments[1].takeIf { it.isNotBlank() } ?: "HEAD"
        }
        else -> return null
    }

    return "https://raw.githubusercontent.com/$owner/$repository/$reference/patches-bundle.json"
}

internal fun gitLabRepositoryManifestUrl(pathSegments: List<String>): String? {
    if (pathSegments.size < 2) return null
    val markerIndex = pathSegments.indexOf("-")
    if (markerIndex >= 0) {
        val repositorySegments = pathSegments.take(markerIndex)
        if (repositorySegments.size < 2) return null

        val remainingSegments = pathSegments.drop(markerIndex + 1)
        val reference = when {
            remainingSegments == listOf("releases") -> "HEAD"
            remainingSegments.firstOrNull() == "tree" && remainingSegments.size <= 2 -> {
                remainingSegments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "HEAD"
            }
            else -> return null
        }
        return "https://gitlab.com/${repositorySegments.joinToString("/")}/-/raw/" +
            "$reference/patches-bundle.json"
    }

    val repositorySegments = pathSegments.toMutableList()
    repositorySegments[repositorySegments.lastIndex] =
        repositorySegments.last().removeSuffix(".git")
    if (repositorySegments.any { it.isBlank() }) return null
    return "https://gitlab.com/${repositorySegments.joinToString("/")}/-/raw/HEAD/" +
        "patches-bundle.json"
}
