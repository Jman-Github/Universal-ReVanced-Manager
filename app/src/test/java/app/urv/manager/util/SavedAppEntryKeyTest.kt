package app.urv.manager.util

import org.junit.Assert.assertNotEquals
import org.junit.Test

class SavedAppEntryKeyTest {
    @Test
    fun `variant identity keeps remembered patch modes distinct`() {
        val standard = buildSavedAppVariantIdentity(
            appVersion = "1.0",
            selectionPayload = null,
            useMount = false
        )
        val mount = buildSavedAppVariantIdentity(
            appVersion = "1.0",
            selectionPayload = null,
            useMount = true
        )
        val unknown = buildSavedAppVariantIdentity(
            appVersion = "1.0",
            selectionPayload = null,
            useMount = null
        )

        assertNotEquals(standard, mount)
        assertNotEquals(standard, unknown)
        assertNotEquals(mount, unknown)
    }
}
