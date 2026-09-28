package app.urv.manager.domain.installer.root

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Include PackageManager reads in the deadline, not just the gaps between reads. */
internal suspend fun awaitStableRootPackageState(
    expected: RootPackageState,
    consecutiveReads: Int,
    timeoutMs: Long = 30_000,
    pollIntervalMs: Long = 500,
    read: suspend () -> RootPackageState
): RootPackageState {
    require(consecutiveReads > 0) { "At least one stable PackageManager read is required" }
    require(timeoutMs > 0 && pollIntervalMs > 0)
    return withTimeoutOrNull(timeoutMs) {
        var stable = 0
        var previous: RootPackageState? = null
        var verified: RootPackageState? = null
        while (verified == null) {
            val current = read()
            val matches = current.installed &&
                current.packageName == expected.packageName &&
                current.userId == expected.userId &&
                (expected.versionName == null || current.versionName == expected.versionName) &&
                (expected.versionCode == null || current.versionCode == expected.versionCode) &&
                (expected.signerSha256 == null || current.signerSha256 == expected.signerSha256) &&
                (expected.baseSha256 == null || current.baseSha256 == expected.baseSha256) &&
                current.basePath != null && current.splitPaths == expected.splitPaths &&
                current.splitSha256 == expected.splitSha256 &&
                current.enabled == expected.enabled &&
                current.launcherResolvable == expected.launcherResolvable
            if (matches && current == previous) stable++ else stable = if (matches) 1 else 0
            if (stable >= consecutiveReads) verified = current else delay(pollIntervalMs)
            previous = current
        }
        verified
    } ?: throw IllegalStateException(
        "PackageManager did not reach a stable verified state within ${timeoutMs / 1000} seconds"
    )
}
