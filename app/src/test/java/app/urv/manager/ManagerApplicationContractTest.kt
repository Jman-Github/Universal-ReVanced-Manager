package app.urv.manager

import kotlin.test.Test
import kotlin.test.assertEquals

class ManagerApplicationContractTest {
    @Test
    fun `shortcut label prefers installed original app`() {
        assertEquals(
            "YouTube",
            batchShortcutLabel(
                originalLabel = "YouTube",
                currentLabel = null,
                savedApkLabel = "Saved YouTube",
                originalPackageName = "com.google.android.youtube"
            )
        )
    }

    @Test
    fun `shortcut label falls back to saved APK label`() {
        assertEquals(
            "Saved YouTube",
            batchShortcutLabel(
                originalLabel = null,
                currentLabel = null,
                savedApkLabel = "Saved YouTube",
                originalPackageName = "com.google.android.youtube"
            )
        )
    }

    @Test
    fun `shortcut label finally falls back to package name`() {
        assertEquals(
            "com.google.android.youtube",
            batchShortcutLabel(null, null, null, "com.google.android.youtube")
        )
    }
}
