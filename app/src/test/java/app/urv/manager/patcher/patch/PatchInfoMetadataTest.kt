package app.urv.manager.patcher.patch

import org.junit.Assert.*
import org.junit.Test

class PatchInfoMetadataTest {
    @Test fun readableNamesPreserveSelectionAndOptionKeys() {
        val metadata = mapOf<String, Any?>(
            "name" to "Change package name [app.reseam.patches.universal.changePackageName]",
            "displayName" to "Change package name",
            "options" to listOf(mapOf("key" to "packageName", "type" to "kotlin.String"))
        )
        val patch = PatchInfo.fromMorpheMetadata(metadata, preserveDouble = true)
        assertEquals(metadata["name"], patch.name)
        assertEquals("Change package name", patch.displayName)
        assertEquals("packageName", patch.options!!.single().key)
    }

    @Test fun existingRuntimesKeepTheirNames() {
        val patch = PatchInfo.fromMorpheMetadata(mapOf("name" to "GmsCore support"))
        assertEquals("GmsCore support", patch.name)
        assertEquals(patch.name, patch.displayName)
    }

    @Test fun blankReadableNameFallsBackToSelectionName() {
        val patch = PatchInfo.fromMorpheMetadata(mapOf("name" to "Patch", "displayName" to " "))
        assertEquals("Patch", patch.displayName)
    }
}
