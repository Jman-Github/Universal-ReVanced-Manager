package app.urv.manager.domain.bundles

import app.urv.manager.network.dto.ReVancedAsset
import kotlinx.coroutines.CancellationException

internal suspend fun resolveRepositoryBundleRelease(
    requestManifest: suspend () -> ReVancedAsset,
    requestRelease: suspend (ReVancedAsset?) -> ReVancedAsset?
): ReVancedAsset {
    val manifest = try {
        Result.success(requestManifest())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    // A failed release lookup must not turn a stable manifest into a downgrade.
    // Fall back only when the lookup succeeds without a compatible release.
    return requestRelease(manifest.getOrNull()) ?: manifest.getOrThrow()
}
