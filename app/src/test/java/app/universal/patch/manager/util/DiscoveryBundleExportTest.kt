package app.universal.patch.manager.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryBundleExportTest {
    @Test fun usesAssetExtensionWhenRegistryTypeIsMissingOrWrong() {
        assertEquals("mpp", discoveryBundleExportExtension("", "https://example.com/a.mpp"))
        assertEquals("reseam", discoveryBundleExportExtension("ReVanced", "https://example.com/a.reseam"))
        assertEquals("rvp", discoveryBundleExportExtension("Morphe", "https://example.com/a.rvp"))
    }

    @Test fun handlesEncodedPathsCaseAndUrlParameters() {
        assertEquals("mpp", discoveryBundleExportExtension("", "https://example.com/a%20b.MPP?token=rvp#download"))
        assertEquals("reseam", discoveryBundleExportExtension("", "https://example.com/a%2Ereseam"))
        assertEquals("rvp", discoveryBundleExportExtension("ReVanced", "https://example.com/download?name=a.mpp"))
    }

    @Test fun usesEcosystemWhenDownloadUrlHasNoAssetSuffix() {
        assertEquals("mpp", discoveryBundleExportExtension(" Morphe:v1 ", "https://example.com/download/1"))
        assertEquals("reseam", discoveryBundleExportExtension("ReSeam", null))
        assertEquals("rvp", discoveryBundleExportExtension("ReVanced:v22", "invalid URL"))
        assertEquals("rvp", discoveryBundleExportExtension("Ample", null))
    }

    @Test fun replacesWrongBundleSuffixWithoutDuplicatingIt() {
        assertEquals("patches.mpp", withBundleExportExtension(" patches.rvp ", "mpp"))
        assertEquals("patches.reseam", withBundleExportExtension("patches.MPP", "reseam"))
        assertEquals("patches.rvp", withBundleExportExtension("patches.rvp", "rvp"))
        assertEquals("patches.mpp", withBundleExportExtension("patches", "mpp"))
        assertEquals("patches.1.2.reseam", withBundleExportExtension("patches.1.2", "reseam"))
    }
}
