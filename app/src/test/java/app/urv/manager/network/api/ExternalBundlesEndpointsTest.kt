package app.urv.manager.network.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalBundlesEndpointsTest {
    @Test
    fun buildsLatestV3BundleUrl() {
        assertEquals(
            "https://${ExternalBundlesEndpoints.DEV_HOST}/api/v3/bundle" +
                "?source_url=https%3A%2F%2Fgithub.com%2Fcrimera%2Fpiko" +
                "&version=latest&channel=prerelease",
            ExternalBundlesEndpoints.latestBundleUrl(
                host = ExternalBundlesEndpoints.DEV_HOST,
                sourceUrl = "https://github.com/crimera/piko/",
                prerelease = true
            )
        )
    }

    @Test
    fun acceptsV3AndLegacyBundlePaths() {
        assertTrue(ExternalBundlesEndpoints.isBundleApiPath("/api/v3/bundle"))
        assertTrue(ExternalBundlesEndpoints.isBundleApiPath("/api/v2/bundle/owner/repo/latest"))
        assertTrue(ExternalBundlesEndpoints.isBundleApiPath("/bundles/id"))
    }

    @Test
    fun buildsSiteUrlForActiveHost() {
        assertEquals(
            "https://${ExternalBundlesEndpoints.DEV_HOST}/",
            ExternalBundlesEndpoints.siteUrl(ExternalBundlesEndpoints.DEV_HOST)
        )
        assertEquals(
            "https://${ExternalBundlesEndpoints.STABLE_HOST}/",
            ExternalBundlesEndpoints.siteUrl("unknown.example.com")
        )
    }

    @Test
    fun returnsAlternateHost() {
        assertEquals(
            ExternalBundlesEndpoints.DEV_HOST,
            ExternalBundlesEndpoints.alternateHost(ExternalBundlesEndpoints.STABLE_HOST)
        )
        assertEquals(
            ExternalBundlesEndpoints.STABLE_HOST,
            ExternalBundlesEndpoints.alternateHost(ExternalBundlesEndpoints.DEV_HOST.uppercase())
        )
        assertNull(ExternalBundlesEndpoints.alternateHost("unknown.example.com"))
        assertNull(ExternalBundlesEndpoints.alternateHost(null))
    }

    @Test
    fun selectsHostWithNewestApiRelease() {
        assertEquals(
            ExternalBundlesEndpoints.DEV_HOST,
            ExternalBundlesEndpoints.preferredHost(
                stableVersion = "1.2.0",
                devVersion = "1.3.0-dev.3"
            )
        )
        assertEquals(
            ExternalBundlesEndpoints.STABLE_HOST,
            ExternalBundlesEndpoints.preferredHost(
                stableVersion = "1.3.0",
                devVersion = "1.3.0-dev.3"
            )
        )
    }

    @Test
    fun selectsWorkingHostWhenVersionMetadataIsUnavailable() {
        assertEquals(
            ExternalBundlesEndpoints.DEV_HOST,
            ExternalBundlesEndpoints.preferredHost(stableVersion = null, devVersion = "1.3.0-dev.3")
        )
        assertEquals(
            ExternalBundlesEndpoints.STABLE_HOST,
            ExternalBundlesEndpoints.preferredHost(stableVersion = "1.2.0", devVersion = null)
        )
        assertEquals(
            ExternalBundlesEndpoints.DEV_HOST,
            ExternalBundlesEndpoints.preferredHost(stableVersion = null, devVersion = null)
        )
    }
}
