package app.universal.patch.manager.patcher.patch

import org.junit.Assert.*
import org.junit.Test

class MetadataPatchTest {
    @Test fun countsIndependentPatchesAndKeepsTieOrder() {
        val patches = setOf(
            MetadataPatch(true, setOf("app" to linkedSetOf("2", "1"))),
            MetadataPatch(true, setOf("app" to setOf("1"))),
            MetadataPatch(true, setOf("app" to setOf("3")))
        )
        val counts = patches.mostCommonCompatibleVersions().getValue("app")
        assertEquals(listOf("1", "2", "3"), counts.keys.toList())
        assertEquals(listOf(2, 1, 1), counts.values.toList())
    }
    @Test fun distinguishesUniversalAndUnsupportedPackages() {
        val counts = setOf(
            MetadataPatch(true, setOf("unrestricted" to null)),
            MetadataPatch(true, setOf("unsupported" to emptySet())),
            MetadataPatch(true, null)
        ).mostCommonCompatibleVersions()
        assertTrue(counts.containsKey("unrestricted"))
        assertTrue(counts.getValue("unrestricted").isEmpty())
        assertFalse(counts.containsKey("unsupported"))
    }
    @Test fun respectsUnusedAndPackageFilters() {
        val patches = setOf(MetadataPatch(false, setOf("app" to setOf("1"))))
        assertTrue(patches.mostCommonCompatibleVersions().isEmpty())
        assertEquals(1, patches.mostCommonCompatibleVersions(countUnusedPatches = true)["app"]?.get("1"))
        assertTrue(patches.mostCommonCompatibleVersions(setOf("other"), true).isEmpty())
    }
    @Test fun equalMetadataFromDifferentBundlesIsCountedSeparately() {
        val patches = setOf(
            MetadataPatch(true, setOf("app" to setOf("1"))),
            MetadataPatch(true, setOf("app" to setOf("1")))
        )
        assertEquals(2, patches.mostCommonCompatibleVersions()["app"]?.get("1"))
    }
}
