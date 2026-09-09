package app.revanced.manager.util

internal const val MANAGER_DATABASE_VERSION_METADATA = "app.urv.manager.DATABASE_VERSION"

/** PR updates must preserve the app identity and never downgrade its code or database. */
internal fun isCompatibleManagerUpdate(
    currentPackage: String,
    currentVersionCode: Long,
    currentDatabaseVersion: Int,
    candidatePackage: String,
    candidateVersionCode: Long,
    candidateDatabaseVersion: Int?
): Boolean =
    candidatePackage == currentPackage &&
        candidateVersionCode >= currentVersionCode &&
        candidateDatabaseVersion != null &&
        candidateDatabaseVersion >= currentDatabaseVersion

internal fun isManagerReleaseAfter(publishedAt: String?, buildTimestamp: Long?): Boolean {
    if (buildTimestamp == null) return true
    val published = publishedAt?.let {
        runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
    } ?: return false
    return published > buildTimestamp
}
