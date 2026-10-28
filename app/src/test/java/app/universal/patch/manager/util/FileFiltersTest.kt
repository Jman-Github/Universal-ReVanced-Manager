package app.universal.patch.manager.util

import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileFiltersTest {
    @Test
    fun acceptsAllSupportedBundleExtensionsRegardlessOfCase() {
        listOf("patches.rvp", "patches.mpp", "patches.reseam", "PATCHES.RESEAM")
            .forEach { assertTrue(it, isAllowedPatchBundleFile(Paths.get(it))) }
    }

    @Test
    fun rejectsFilesThatAreNotPatchBundles() {
        listOf("reseam", "patches.reseam.apk", "patches.zip", "patches.txt")
            .forEach { assertFalse(it, isAllowedPatchBundleFile(Paths.get(it))) }
    }
}
