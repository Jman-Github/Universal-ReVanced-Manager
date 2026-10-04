package app.urv.manager.util

import app.urv.manager.data.room.apps.installed.InstallType
import app.urv.manager.data.room.apps.installed.InstalledApp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstalledAppMountCompatibilityTest {
    private fun app(currentPackageName: String, originalPackageName: String) = InstalledApp(
        currentPackageName = currentPackageName,
        originalPackageName = originalPackageName,
        version = "1.0",
        installType = InstallType.SAVED,
        sortOrder = 0
    )

    @Test
    fun `unchanged package supports root mount`() {
        assertTrue(app("com.example.app", "com.example.app").supportsRootMount())
    }

    @Test
    fun `renamed package rejects root mount`() {
        assertFalse(app("com.example.clone", "com.example.app").supportsRootMount())
    }

    @Test
    fun `synthetic saved key uses its base package`() {
        assertTrue(
            app("com.example.app__bundle_123456789abc", "com.example.app")
                .supportsRootMount()
        )
    }

    @Test
    fun `archive package overrides a legacy saved key`() {
        assertFalse(
            app("com.example.app", "com.example.app")
                .supportsRootMount("com.example.clone")
        )
    }
}
