package app.urv.manager.revanced.runtime

import app.revanced.patcher.PatcherResult
import app.revanced.patcher.data.ResourceContext
import app.revanced.patcher.fingerprint.MethodFingerprint
import app.revanced.patcher.patch.Patch
import app.revanced.patcher.patch.ResourcePatch
import app.revanced.patcher.patch.options.PatchOption.PatchExtensions.stringArrayPatchOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyPatchApiTest {
    private class ResourceFixture : ResourcePatch(
        name = "Legacy resource patch",
        description = "Class-based fixture",
        compatiblePackages = setOf(Patch.CompatiblePackage("example.app", setOf("1.0"))),
        use = false
    ) {
        val entries = stringArrayPatchOption("entries", default = arrayOf("first"))
        override fun execute(context: ResourceContext) = Unit
    }

    @Test
    fun loadsSubclassAndFingerprintFromLegacySdk() {
        val patch = ResourceFixture()
        val fingerprint = object : MethodFingerprint() {}
        assertEquals("Legacy resource patch", patch.name)
        assertEquals(MethodFingerprint::class.java.name, fingerprint.javaClass.superclass.name)
        assertFalse(java.lang.reflect.Modifier.isFinal(ResourcePatch::class.java.modifiers))
    }

    @Test
    fun mapsLegacyPackageAndArrayOptionsWithoutLosingTheirTypes() {
        val patch = ResourceFixture()
        val metadata = patchMetadata(patch)
        assertEquals(false, metadata["use"])
        val pkg = (metadata["compatiblePackages"] as List<*>).single() as Map<*, *>
        assertEquals("example.app", pkg["packageName"])
        assertEquals(listOf("1.0"), pkg["versions"])
        val option = (metadata["options"] as List<*>).single() as Map<*, *>
        assertEquals("List<String>", option["type"])
        assertEquals(listOf("first"), option["default"])
        setPatchOption(patch, "entries", listOf("second", "third"))
        assertEquals(listOf("second", "third"), patch.entries.value?.toList())
    }

    @Test
    fun appliesResourceDeletionPredicates() {
        val resources = PatcherResult.PatchedResources::class.java.constructors.single().newInstance(
            null, null, emptySet<String>(), setOf({ name: String -> name.startsWith("res/raw/remove") })
        ) as PatcherResult.PatchedResources
        assertTrue(shouldDeleteResource(resources, "res/raw/remove.xml"))
        assertFalse(shouldDeleteResource(resources, "res/raw/keep.xml"))
    }
}
