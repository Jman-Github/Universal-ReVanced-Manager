package app.urv.manager.domain.bundles

import app.urv.manager.network.dto.ReVancedAsset
import kotlinx.coroutines.CancellationException

internal suspend fun resolveRepositoryBundleRelease(
    requestManifest: suspend () -> ReVancedAsset,
    requestRelease: suspend (ReVancedAsset?) -> ReVancedAsset?,
    fallbackToManifestWhenReleaseMissing: Boolean = true,
): ReVancedAsset {
    val manifest = try {
        Result.success(requestManifest())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    val release = requestRelease(manifest.getOrNull())
    if (release != null) return release

    if (!fallbackToManifestWhenReleaseMissing) {
        manifest.exceptionOrNull()?.let { throw it }
        throw NoSuchElementException("No compatible repository release asset found")
    }

    // A failed release lookup must not turn a stable manifest into a downgrade.
    // Fall back only when the lookup succeeds without a compatible release.
    return manifest.getOrThrow()
}
