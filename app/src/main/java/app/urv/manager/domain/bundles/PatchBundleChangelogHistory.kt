package app.urv.manager.domain.bundles

import app.urv.manager.network.dto.GitHubRelease
import kotlinx.datetime.Instant

internal fun PatchBundleChangelogEntry.isSameRelease(other: PatchBundleChangelogEntry): Boolean {
    val left = version.normalizedChangelogVersion()
    val right = other.version.normalizedChangelogVersion()
    // A repository or "latest" URL can be shared by different releases.
    if (left != null && right != null) return left == right
    val url = pageUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
    val otherUrl = other.pageUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
    if (url != null && url == otherUrl) return true
    return publishedAtMillis != null && publishedAtMillis > 0 &&
        publishedAtMillis == other.publishedAtMillis &&
        description.trim() == other.description.trim()
}

internal fun mergePatchBundleChangelogs(
    current: List<PatchBundleChangelogEntry>,
    incoming: List<PatchBundleChangelogEntry>,
    limit: Int
): List<PatchBundleChangelogEntry> {
    val merged = mutableListOf<PatchBundleChangelogEntry>()
    (current + incoming).forEach { candidate ->
        val index = merged.indexOfFirst { it.isSameRelease(candidate) }
        if (index < 0) {
            merged += candidate
        } else {
            val previous = merged[index]
            val useIncomingDescription = candidate.description.isNotBlank() &&
                (candidate.hasReleaseBody || !previous.hasReleaseBody || previous.description.isBlank())
            merged[index] = candidate.copy(
                version = candidate.version.ifBlank { previous.version },
                description = if (useIncomingDescription) candidate.description else previous.description,
                hasReleaseBody = if (useIncomingDescription) candidate.hasReleaseBody else previous.hasReleaseBody,
                publishedAtMillis = candidate.publishedAtMillis ?: previous.publishedAtMillis,
                pageUrl = candidate.pageUrl?.takeIf { it.isNotBlank() } ?: previous.pageUrl
            )
        }
    }
    return merged.sortedByDescending { it.publishedAtMillis ?: Long.MIN_VALUE }
        .take(limit.coerceAtLeast(1))
}

private fun String.normalizedChangelogVersion(): String? =
    trim().takeIf { it.isNotEmpty() }?.let {
        if (it.length > 1 && it[0].equals('v', ignoreCase = true) && it[1].isDigit()) {
            it.substring(1)
        } else it
    }

internal fun GitHubRelease.toChangelogEntry(repoUrl: String): PatchBundleChangelogEntry {
    val notes = body?.takeIf { it.isNotBlank() }.orEmpty()
    return PatchBundleChangelogEntry(
        version = tagName,
        // A release title is not changelog content and must not replace saved notes.
        description = notes,
        publishedAtMillis = (publishedAt ?: createdAt)?.let {
            runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull()
        },
        pageUrl = "${repoUrl.removeSuffix("/")}/releases/tag/$tagName",
        hasReleaseBody = notes.isNotEmpty()
    )
}
