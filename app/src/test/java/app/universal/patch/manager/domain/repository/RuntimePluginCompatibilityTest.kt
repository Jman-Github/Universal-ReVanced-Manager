package app.universal.patch.manager.domain.repository

import org.junit.Assert.*
import org.junit.Test

class RuntimePluginCompatibilityTest {
    @Test fun olderOfficialV21BuildsRequireUpdate() {
        assertTrue(runtimePluginRequiresUpdate("app.universal.patch.manager.revanced.v21.runtime", 7))
        assertTrue(runtimePluginRequiresUpdate("app.universal.revanced.manager.revanced.v21.runtime", 7))
        assertFalse(runtimePluginRequiresUpdate("app.universal.patch.manager.revanced.v21.runtime", 8))
        assertFalse(runtimePluginRequiresUpdate("app.universal.patch.manager.revanced.v21.runtime", 9))
    }

    @Test fun otherPluginVersionSequencesAreNotRestricted() {
        assertFalse(runtimePluginRequiresUpdate("app.universal.patch.manager.revanced.v22.runtime", 1))
        assertFalse(runtimePluginRequiresUpdate("org.example.patcher.runtime", 1))
    }
}
