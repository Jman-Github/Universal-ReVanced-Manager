package app.universal.patch.manager.network.runtime

import app.universal.patch.manager.patcher.patch.PatchBundleType
import org.junit.Assert.*
import org.junit.Test

class RuntimePackagingPolicyTest {
    @Test fun includedEnginesNeverRequirePluginApproval() {
        assertTrue(PatchBundleType.RESEAM.requiredRuntimeKinds(false).isEmpty())
        assertTrue(PatchBundleType.MORPHE.requiredRuntimeKinds(false).isEmpty())
    }
    @Test fun eachRevancedGenerationRequiresItsOwnPlugin() {
        assertEquals(listOf(PatcherRuntimeKind.REVANCED_V21), PatchBundleType.REVANCED.requiredRuntimeKinds(false))
        assertEquals(listOf(PatcherRuntimeKind.REVANCED_V22), PatchBundleType.REVANCED.requiredRuntimeKinds(true))
        assertEquals("ReVanced v22", PatcherRuntimeKind.REVANCED_V22.displayName)
        assertNull(PatcherRuntimeKind.fromId("reseam"))
    }
}
