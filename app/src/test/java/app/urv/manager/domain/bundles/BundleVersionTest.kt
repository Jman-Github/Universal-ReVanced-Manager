package app.urv.manager.domain.bundles

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BundleVersionTest {
    @Test
    fun latestDoesNotOfferTheOlderStableVersionsFromIssue780() {
        assertFalse(isRemoteBundleVersionUpdate("4.3.0", "v4.4.0-dev.2", null, true))
        assertFalse(isRemoteBundleVersionUpdate("1.47.0", "v1.47.1-dev.1", null, true))
    }

    @Test
    fun latestStillOffersNewPrereleasesAndStablePromotions() {
        assertTrue(isRemoteBundleVersionUpdate("v4.4.0-dev.10", "4.4.0-dev.2", null, true))
        assertTrue(isRemoteBundleVersionUpdate("4.4.0", "v4.4.0-dev.2", null, true))
        assertTrue(isRemoteBundleVersionUpdate("4.5.0-dev.1", "4.4.0", null, true))
    }

    @Test
    fun latestRejectsOlderPrereleasesAndUsesManifestWhenSignatureIsMissing() {
        assertFalse(isRemoteBundleVersionUpdate("4.4.0-dev.1", "4.4.0-dev.2", null, true))
        assertFalse(isRemoteBundleVersionUpdate("4.3.0", null, "4.4.0-dev.2", true))
        assertFalse(isRemoteBundleVersionUpdate("4.4.0-dev.2", "4.4.0", null, true))
    }

    @Test
    fun releaseTagOrderingDoesNotMixInInternalBundleVersions() {
        assertTrue(isRemoteBundleVersionUpdate("2.1.0", "2.0.0", "100.0.0", true))
        assertFalse(isRemoteBundleVersionUpdate("1.9.0", "2.0.0", "1.0.0", true))
        assertFalse(isRemoteBundleVersionUpdate("2.1.0", null, "100.0.0", true))
    }

    @Test
    fun explicitReleaseChannelCanSwitchBackToStable() {
        assertTrue(isRemoteBundleVersionUpdate("4.3.0", "4.4.0-dev.2", null))
    }

    @Test
    fun matchingTagsAndBuildMetadataDoNotCauseRepeatUpdates() {
        assertFalse(isRemoteBundleVersionUpdate("v4.4.0+new", "V4.4.0+old", null, true))
        assertFalse(isRemoteBundleVersionUpdate("4.4.0", "release-tag", "v4.4.0", true))
        assertFalse(isRemoteBundleVersionUpdate("", "4.4.0", null, true))
    }

    @Test
    fun pinUsesTheExactReleaseTagRatherThanTheInternalManifest() {
        assertTrue(isRemoteBundleVersionUpdate("v4.4.0", "v4.4.0-dev.2", "4.4.0", pinnedVersion = true))
        assertTrue(isRemoteBundleVersionUpdate("4.4.0+new", "4.4.0+old", "4.4.0", pinnedVersion = true))
        assertFalse(isRemoteBundleVersionUpdate("v4.4.0", "v4.4.0", "100.0.0", pinnedVersion = true))
    }

    @Test
    fun pinDownloadsWhenTheInstalledReleaseTagIsUnknown() {
        assertTrue(isRemoteBundleVersionUpdate("v4.4.0", null, "4.4.0", pinnedVersion = true))
        assertFalse(isRemoteBundleVersionUpdate("", null, null, pinnedVersion = true))
    }

    @Test
    fun newInstallsAndNonNumericTagsRetainUpdateSupport() {
        assertTrue(isRemoteBundleVersionUpdate("4.3.0", null, null, true))
        assertTrue(isRemoteBundleVersionUpdate("new-tag", "old-tag", null, true))
        assertFalse(isRemoteBundleVersionUpdate("same-tag", "same-tag", null, true))
    }
}
