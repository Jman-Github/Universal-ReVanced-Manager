package app.urv.manager.util

internal const val MANAGER_DATABASE_VERSION_METADATA = "app.urv.manager.DATABASE_VERSION"

/** Checks the destination release profile; zero means it has no existing database. */
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
        (currentDatabaseVersion == 0 ||
            (candidateDatabaseVersion != null && candidateDatabaseVersion >= currentDatabaseVersion))

internal fun isManagerReleaseAfter(publishedAt: String?, buildTimestamp: Long?): Boolean {
    if (buildTimestamp == null) return true
    val published = publishedAt?.let {
        runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
    } ?: return false
    return published > buildTimestamp
}
