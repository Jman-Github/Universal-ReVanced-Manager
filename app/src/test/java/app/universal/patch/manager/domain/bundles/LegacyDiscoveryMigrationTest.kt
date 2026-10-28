package app.universal.patch.manager.domain.bundles

import app.universal.patch.manager.network.api.ExternalBundlesEndpoints
import java.net.URI
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LegacyDiscoveryMigrationTest {
    private val old = "https://${ExternalBundlesEndpoints.STABLE_HOST}"
    private val source = "https://github.com/crimera/piko"

    private fun params(url: String) = URI(url).rawQuery.split('&').associate {
        val parts = it.split('=', limit = 2)
        parts[0] to URLDecoder.decode(parts[1], "UTF-8")
    }

    @Test fun v1LatestPreservesStableDefaultAndExplicitPrereleases() {
        for ((query, expected) in listOf("" to "stable", "?prerelease=false" to "stable",
            "?prerelease=true" to "prerelease")) {
            val legacy = assertNotNull(legacyDiscoveryEndpoint("$old/api/v1/bundle/crimera/piko/latest$query"))
            val result = assertNotNull(discoveryRegistryMigration(legacy, isPrerelease = true))
            assertEquals(expected, params(result.endpoint)["channel"])
            assertEquals(source, params(result.endpoint)["source_url"])
            assertEquals(ExternalBundlesEndpoints.REGISTRY_HOST, URI(result.endpoint).host)
        }
    }

    @Test fun v2AndV3PreserveEachChannelOnBothOldHosts() {
        for (host in listOf(ExternalBundlesEndpoints.STABLE_HOST, ExternalBundlesEndpoints.DEV_HOST)) {
            for (channel in listOf("any", "stable", "prerelease")) {
                val urls = listOf(
                    "https://$host/api/v2/bundle/crimera/piko/latest?channel=$channel",
                    "https://$host/api/v3/bundle?source_url=https%3A%2F%2Fgithub.com%2Fcrimera%2Fpiko&version=latest&channel=$channel"
                )
                for (url in urls) {
                    val result = assertNotNull(discoveryRegistryMigration(assertNotNull(legacyDiscoveryEndpoint(url))))
                    assertEquals(channel, params(result.endpoint)["channel"])
                    assertEquals(source, params(result.endpoint)["source_url"])
                    assertEquals("latest", params(result.endpoint)["version"])
                }
            }
        }
    }

    @Test fun exactTagsStayExactIncludingEncodedSlashesAndPlusSigns() {
        for ((url, version) in listOf(
            "$old/api/v1/bundle/crimera/piko/v1.2.3" to "v1.2.3",
            "$old/api/v1/bundle/crimera/piko/v1.2.3+build" to "v1.2.3+build",
            "$old/api/v3/bundle?source_url=https%3A%2F%2Fgitlab.com%2Fgroup%2Fsub%2Frepo&version=release%2F1.2" to "release/1.2"
        )) {
            val result = assertNotNull(discoveryRegistryMigration(assertNotNull(legacyDiscoveryEndpoint(url))))
            assertEquals(version, params(result.endpoint)["version"])
        }
    }

    @Test fun numericIdsUseSavedIdentityAndChannelNeverTheForeignId() {
        for (url in listOf("$old/bundles/id?id=42", "$old/api/v1/bundle/42")) {
            val legacy = assertNotNull(legacyDiscoveryEndpoint(url))
            assertEquals(42, legacy.bundleId)
            assertNull(discoveryRegistryMigration(legacy))
            for ((prerelease, channel) in listOf(null to "any", true to "prerelease", false to "stable")) {
                val result = assertNotNull(discoveryRegistryMigration(legacy, owner = "crimera", repo = "piko",
                    isPrerelease = prerelease))
                assertEquals(source, params(result.endpoint)["source_url"])
                assertEquals(channel, params(result.endpoint)["channel"])
                assertEquals("latest", params(result.endpoint)["version"])
                assertNull(params(result.endpoint)["id"])
            }
        }
    }

    @Test fun nonGithubSourcesKeepTheirIdentityAndEndpointIdentityWins() {
        val legacy = assertNotNull(legacyDiscoveryEndpoint("$old/bundles/id?id=12"))
        val result = assertNotNull(discoveryRegistryMigration(legacy,
            sourceUrl = "https://gitlab.com/group/subgroup/patches/", owner = "wrong", repo = "wrong"))
        assertEquals("https://gitlab.com/group/subgroup/patches", params(result.endpoint)["source_url"])
        val explicit = assertNotNull(legacyDiscoveryEndpoint("$old/api/v2/bundle/crimera/piko/latest?channel=any"))
        assertEquals(source, params(assertNotNull(discoveryRegistryMigration(explicit,
            sourceUrl = "https://github.com/wrong/repo")).endpoint)["source_url"])
    }

    @Test fun repositoryPathImportsKeepTheSavedGitHost() {
        for (version in listOf("v1", "v2")) {
            val legacy = assertNotNull(legacyDiscoveryEndpoint(
                "$old/api/$version/bundle/group/patches/latest?channel=stable"))
            val result = assertNotNull(discoveryRegistryMigration(legacy,
                sourceUrl = "https://gitlab.com/group/patches/"))
            assertEquals("https://gitlab.com/group/patches", params(result.endpoint)["source_url"])
            val gitResult = assertNotNull(discoveryRegistryMigration(legacy,
                sourceUrl = "https://gitlab.com/group/patches.git"))
            assertEquals("https://gitlab.com/group/patches.git", params(gitResult.endpoint)["source_url"])
        }
        // An explicit v3 source is authoritative even if cached metadata disagrees.
        val explicit = assertNotNull(legacyDiscoveryEndpoint(
            "$old/api/v3/bundle?source_url=https%3A%2F%2Fgithub.com%2Fgroup%2Fpatches&version=latest&channel=any"))
        val result = assertNotNull(discoveryRegistryMigration(explicit,
            sourceUrl = "https://gitlab.com/group/patches"))
        assertEquals("https://github.com/group/patches", params(result.endpoint)["source_url"])
    }

    @Test fun downloadUrlDerivesRepositoryWhenSourceUrlIsMissing() {
        val legacy = assertNotNull(legacyDiscoveryEndpoint("$old/bundles/id?id=42"))
        val result = assertNotNull(discoveryRegistryMigration(legacy,
            downloadUrl = "https://github.com/crimera/piko/releases/download/v1.0.0/piko.rvp"))
        assertEquals(source, params(result.endpoint)["source_url"])

        val gitlab = assertNotNull(discoveryRegistryMigration(legacy,
            downloadUrl = "https://gitlab.com/group/subgroup/patches/-/releases/v1.0.0/downloads/patches.rvp"))
        assertEquals("https://gitlab.com/group/subgroup/patches", params(gitlab.endpoint)["source_url"])

        assertNull(inferRepositorySourceUrl("https://${ExternalBundlesEndpoints.REGISTRY_HOST}/api/v3/bundle"))
        assertNull(inferRepositorySourceUrl("https://${ExternalBundlesEndpoints.STABLE_HOST}/api/v1/bundle/42"))
    }

    @Test fun onlyRecognizedOldApiEndpointsAreEligible() {
        for (url in listOf(
            source, "https://raw.githubusercontent.com/owner/repo/main/patches.json",
            "https://other.example.com/api/v1/bundle/crimera/piko/latest",
            "https://${ExternalBundlesEndpoints.REGISTRY_HOST}/api/v1/bundle/crimera/piko/latest",
            "$old/patches.json", "$old/bundles/42", "$old/bundles/id-extra?id=42",
            "$old/api/v2/bundle/crimera/piko/latest", "$old/api/v3/bundle?version=latest",
            "$old/api/v2/bundle/crimera/piko/latest?channel=wrong",
            "$old/api/v2/bundle/crimera/piko/latest?channel=any&channel=stable",
            "$old.evil.example/api/v1/bundle/crimera/piko/latest",
            "https://user:password@${ExternalBundlesEndpoints.STABLE_HOST}/api/v1/bundle/crimera/piko/latest",
            "https://${ExternalBundlesEndpoints.STABLE_HOST}:444/api/v1/bundle/crimera/piko/latest"
        )) assertNull(legacyDiscoveryEndpoint(url), url)
    }
}
