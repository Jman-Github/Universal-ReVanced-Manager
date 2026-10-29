package app.universal.patch.manager.ui.viewmodel

import app.universal.patch.manager.domain.bundles.LEGACY_OFFICIAL_PATCHES_URL
import app.universal.patch.manager.domain.bundles.legacyOfficialBundleRegistryUrl
import app.universal.patch.manager.domain.bundles.isLegacyOfficialBundle
import app.universal.patch.manager.domain.bundles.normalizeLegacyBundleEndpoint
import app.universal.patch.manager.domain.bundles.RepositoryBundleSettings
import app.universal.patch.manager.domain.bundles.bundleEndpointIdentity
import app.universal.patch.manager.domain.bundles.toLegacyAwareBundleIndex
import org.junit.Assert.*
import org.junit.Test

class PatchBundleImportCompatibilityTest {
    @Test fun explicitRegistryChannelsRemainSeparateInRestoredSelections() {
        val stable = legacyOfficialBundleRegistryUrl(false)
        val dev = legacyOfficialBundleRegistryUrl(true)
        val latest = legacyOfficialBundleRegistryUrl(null)
        val pinned = stable.replace("version=latest", "version=v6.2.1")
        val index = listOf(stable to 1, dev to 2, latest to 3, pinned to 4).toLegacyAwareBundleIndex()
        assertEquals(1, index[bundleEndpointIdentity(stable)])
        assertEquals(2, index[bundleEndpointIdentity(dev)])
        assertEquals(3, index[bundleEndpointIdentity(latest)])
        assertEquals(4, index[bundleEndpointIdentity(pinned)])
        assertNull(index[bundleEndpointIdentity("api")])
    }

    @Test fun unambiguousLegacyApiSelectionStillFindsMigratedPrereleaseSource() {
        val index = listOf(legacyOfficialBundleRegistryUrl(true) to 12).toLegacyAwareBundleIndex()
        assertEquals(12, index[bundleEndpointIdentity("api")])
    }

    @Test fun transitionalRepositoryRetainsItsChannelAndVersionSelection() {
        val original = RepositoryBundleSettings(useLatest = true, useSpecificVersion = true)
        val migrated = original.forLegacyOfficialMigration(false, false)
        assertSame(original, migrated)
        assertTrue(migrated.useSpecificVersion)
        assertTrue(legacyOfficialBundleRegistryUrl(if (migrated.useLatest) null else migrated.usePrereleases)
            .contains("channel=any"))
    }

    @Test fun originalApiSourceMigratesItsGlobalChannelWithoutDiscardingOtherSettings() {
        val original = RepositoryBundleSettings(useSpecificVersion = true)
        val migrated = original.forLegacyOfficialMigration(true, true)
        assertTrue(migrated.usePrereleases)
        assertFalse(migrated.useLatest)
        assertTrue(migrated.useSpecificVersion)
    }

    @Test fun emptyExportDoesNotCreateSources() {
        val restored = PatchBundleExportFile(emptyList()).bundles.mapNotNull { it.asUserManagedBundle() }
        assertTrue(restored.isEmpty())
    }

    @Test fun explicitLegacyBundleBecomesNormalRepositoryWithItsSettings() {
        val legacy = PatchBundleSnapshot(
            endpoint = " api ", name = "ReVanced", displayName = "My patches",
            officialState = OfficialBundleState.PRESENT,
            officialAutoUpdate = true, officialUsePrereleases = true,
            enabled = false, position = 3, createdAt = 12L, updatedAt = 24L
        )
        val restored = requireNotNull(legacy.asUserManagedBundle())
        assertEquals(legacyOfficialBundleRegistryUrl(true), restored.endpoint)
        assertEquals("My patches", restored.displayName)
        assertTrue(restored.autoUpdate)
        assertEquals(true, restored.usePrereleases)
        assertFalse(restored.enabled)
        assertEquals(3, restored.position)
        assertEquals(12L, restored.createdAt)
        assertEquals(24L, restored.updatedAt)
        assertNull(restored.officialState)
        assertNull(restored.officialAutoUpdate)
        assertNull(restored.officialUsePrereleases)
    }

    @Test fun removedLegacyBundleDoesNotGetRecreated() {
        assertNull(PatchBundleSnapshot("api", "", officialState = OfficialBundleState.ABSENT)
            .asUserManagedBundle())
    }

    @Test fun regularSourceIsUnchanged() {
        val source = PatchBundleSnapshot("https://example.com/patches.json", "Custom",
            useLatest = true, enabled = false)
        assertSame(source, source.asUserManagedBundle())
    }

    @Test fun onlyHistoricalDefaultSourcesAreMigrated() {
        assertTrue(isLegacyOfficialBundle(0, "api"))
        assertTrue(isLegacyOfficialBundle(0, LEGACY_OFFICIAL_PATCHES_URL))
        assertFalse(isLegacyOfficialBundle(12, LEGACY_OFFICIAL_PATCHES_URL))
        assertFalse(isLegacyOfficialBundle(0, legacyOfficialBundleRegistryUrl(false)))
        assertFalse(isLegacyOfficialBundle(0, "https://example.com/patches.json"))
    }

    @Test fun registryChannelsMatchOldProfileIdentity() {
        for (prerelease in listOf(false, true)) {
            val url = legacyOfficialBundleRegistryUrl(prerelease)
            assertTrue(url.startsWith("https://patch-bundles.api.jmancentral.com/api/v3/bundle?"))
            assertTrue(url.contains("channel=" + if (prerelease) "prerelease" else "stable"))
            assertEquals(LEGACY_OFFICIAL_PATCHES_URL, normalizeLegacyBundleEndpoint(url))
        }
    }

    @Test fun oldProfileEndpointMatchesTheMigratedRepository() {
        assertEquals(LEGACY_OFFICIAL_PATCHES_URL, normalizeLegacyBundleEndpoint(" API "))
        assertEquals("https://example.com/patches.json",
            normalizeLegacyBundleEndpoint("https://example.com/patches.json"))
    }
}
