package app.urv.manager.domain.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RepositoryUrlNormalizerTest {
    @Test
    fun normalizesGitHubRepositoryReleasesPage() {
        assertEquals(
            "https://raw.githubusercontent.com/rushiranpise/morphe-patches/HEAD/patches-bundle.json",
            gitHubRepositoryManifestUrl(
                listOf("rushiranpise", "morphe-patches", "releases")
            )
        )
    }

    @Test
    fun doesNotTreatSpecificGitHubReleaseAsRepositoryPage() {
        assertNull(
            gitHubRepositoryManifestUrl(
                listOf("owner", "patches", "releases", "tag", "v1.0.0")
            )
        )
        assertNull(
            gitHubRepositoryManifestUrl(
                listOf("owner", "patches", "releases", "download", "v1.0.0", "bundle.mpp")
            )
        )
    }

    @Test
    fun normalizesGitLabRepositoryReleasesPage() {
        assertEquals(
            "https://gitlab.com/group/patches/-/raw/HEAD/patches-bundle.json",
            gitLabRepositoryManifestUrl(
                listOf("group", "patches", "-", "releases")
            )
        )
    }

    @Test
    fun doesNotTreatSpecificGitLabReleaseAsRepositoryPage() {
        assertNull(
            gitLabRepositoryManifestUrl(
                listOf("group", "patches", "-", "releases", "v1.0.0")
            )
        )
    }
}
