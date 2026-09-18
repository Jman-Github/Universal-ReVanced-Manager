package app.urv.manager.domain.bundles

import app.urv.manager.network.dto.ReVancedAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class RepositoryBundleReleaseResolverTest {
    private val stable = asset("v1.0.0")
    private val prerelease = asset("v2.0.0-dev.1")

    @Test
    fun prefersRepositoryReleaseOverStableManifest() = runBlocking {
        val result = resolveRepositoryBundleRelease(
            requestManifest = { stable },
            requestRelease = { manifest ->
                assertSame(stable, manifest)
                prerelease
            }
        )
        assertSame(prerelease, result)
    }

    @Test
    fun releaseLookupFailureDoesNotReturnStableManifest() = runBlocking {
        val failure = IOException("GitHub release lookup failed")
        val thrown = assertFailsWith<IOException> {
            resolveRepositoryBundleRelease(
                requestManifest = { stable },
                requestRelease = { throw failure }
            )
        }
        assertSame(failure, thrown)
    }

    @Test
    fun missingManifestDoesNotPreventPrereleaseResolution() = runBlocking {
        val result = resolveRepositoryBundleRelease(
            requestManifest = { throw IOException("Manifest not found") },
            requestRelease = { manifest ->
                assertNull(manifest)
                prerelease
            }
        )
        assertSame(prerelease, result)
    }

    @Test
    fun successfulLookupWithoutBundleUsesManifest() = runBlocking {
        assertSame(stable, resolveRepositoryBundleRelease({ stable }, { null }))
    }

    @Test
    fun missingManifestAndNoReleasePreservesOriginalFailure() = runBlocking {
        val failure = IOException("Manifest not found")
        val thrown = assertFailsWith<IOException> {
            resolveRepositoryBundleRelease({ throw failure }, { null })
        }
        assertSame(failure, thrown)
    }

    @Test
    fun cancelledManifestRequestDoesNotStartReleaseLookup() = runBlocking {
        val cancellation = CancellationException("Cancelled")
        val thrown = assertFailsWith<CancellationException> {
            resolveRepositoryBundleRelease(
                requestManifest = { throw cancellation },
                requestRelease = { error("Release lookup must not run") }
            )
        }
        assertSame(cancellation, thrown)
    }

    @Test
    fun cancelledReleaseRequestDoesNotReturnStableManifest() = runBlocking {
        val cancellation = CancellationException("Cancelled")
        val thrown = assertFailsWith<CancellationException> {
            resolveRepositoryBundleRelease({ stable }, { throw cancellation })
        }
        assertSame(cancellation, thrown)
    }

    private fun asset(version: String) = ReVancedAsset(
        downloadUrl = "https://github.com/owner/patches/releases/download/$version/patches.mpp",
        createdAt = LocalDateTime(2026, 9, 16, 0, 0),
        description = version,
        version = version
    )
}
