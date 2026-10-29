package app.universal.patch.manager.domain.bundles

import app.universal.patch.manager.network.api.ExternalBundlesEndpoints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BundleIconSourceTest {
    private val source = "https://github.com/crimera/piko"
    private val registry = "https://${ExternalBundlesEndpoints.REGISTRY_HOST}/api/v3/bundle"

    @Test fun migratedRemoteApiUrlsResolveIconsWithoutDiscoveryMetadata() {
        for ((channel, prerelease) in listOf("any" to null, "stable" to false, "prerelease" to true)) {
            val result = assertNotNull(registryBundleIconSource(
                "$registry?source_url=https%3A%2F%2Fgithub.com%2Fcrimera%2Fpiko&version=latest&channel=$channel"))
            assertEquals(source, result.sourceUrl)
            assertEquals(prerelease, result.prerelease)
        }
    }

    @Test fun explicitLatestDoesNotBecomeTheCachedReleaseChannel() {
        val result = assertNotNull(registryBundleIconSource(
            "$registry?source_url=https%3A%2F%2Fgithub.com%2FMorpheApp%2Fmorphe-patches&version=latest&channel=any",
            storedSourceUrl = source, storedPrerelease = true))
        assertEquals("https://github.com/MorpheApp/morphe-patches", result.sourceUrl)
        assertNull(result.prerelease)
    }

    @Test fun oldApiIconsResolveBeforeMigrationToo() {
        val result = assertNotNull(registryBundleIconSource(
            "https://${ExternalBundlesEndpoints.DEV_HOST}/api/v2/bundle/crimera/piko/latest?channel=prerelease"))
        assertEquals(source, result.sourceUrl)
        assertEquals(true, result.prerelease)
    }

    @Test fun nonGithubSourceAndPinnedVersionKeepTheirRepository() {
        val result = assertNotNull(registryBundleIconSource(
            "$registry?source_url=https%3A%2F%2Fgitlab.com%2Fgroup%2Fsub%2Frepo&version=v1.2.3"))
        assertEquals("https://gitlab.com/group/sub/repo", result.sourceUrl)
    }

    @Test fun downloadUrlDerivesRegistryBundleIconSourceWhenStoredSourceIsMissing() {
        val oldIdUrl = "https://${ExternalBundlesEndpoints.DEV_HOST}/api/v1/bundle/42"
        val result = assertNotNull(registryBundleIconSource(
            oldIdUrl,
            downloadUrl = "https://github.com/crimera/piko/releases/download/v1.0.0/piko.rvp"
        ))
        assertEquals(source, result.sourceUrl)
    }

    @Test fun ownerAndRepoFallbackWhenRegistrySourceUrlParameterIsOmitted() {
        val result = assertNotNull(registryBundleIconSource(
            "$registry?version=latest&channel=prerelease",
            owner = "crimera",
            repo = "piko"
        ))
        assertEquals(source, result.sourceUrl)
        assertEquals(true, result.prerelease)
    }

    @Test fun ordinaryRepositoriesManifestsAndUnrelatedHostsAreNotRegistrySources() {
        for (url in listOf(source, "https://example.com/patches.json",
            "https://example.com/api/v3/bundle?source_url=$source",
            registry, "$registry?", "$registry?&",
            "$registry?version=latest",
            "$registry?source_url=file%3A%2F%2F%2Ftmp%2Frepo")) {
            assertNull(registryBundleIconSource(url), url)
        }
    }
}
