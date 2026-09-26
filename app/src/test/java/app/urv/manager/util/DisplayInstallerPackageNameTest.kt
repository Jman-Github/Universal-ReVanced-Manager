package app.urv.manager.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DisplayInstallerPackageNameTest {
    @Test
    fun `shell install displays the initiating custom installer`() {
        assertEquals(
            "com.example.installer",
            displayInstallerPackageName("com.android.shell", "com.example.installer")
        )
    }

    @Test
    fun `ordinary install displays the installing package`() {
        assertEquals(
            "com.android.vending",
            displayInstallerPackageName("com.android.vending", "com.example.downloader")
        )
    }

    @Test
    fun `shell install without a known installer uses the generic fallback`() {
        assertNull(displayInstallerPackageName("com.android.shell", null))
    }

    @Test
    fun `shell initiator without a known installer uses the generic fallback`() {
        assertNull(displayInstallerPackageName(null, "com.android.shell"))
    }

    @Test
    fun `recorded custom installer replaces shell when no initiator is available`() {
        assertEquals(
            "com.example.installer",
            displayInstallerPackageName(
                "com.android.shell",
                null,
                "com.example.installer"
            )
        )
    }

    @Test
    fun `recorded custom installer wins for an ambiguous shell transaction`() {
        assertEquals(
            "com.example.installer",
            displayInstallerPackageName(
                "com.android.shell",
                "app.universal.revanced.manager",
                "com.example.installer"
            )
        )
    }

    @Test
    fun `detected non-shell installer wins over recorded fallback`() {
        assertEquals(
            "com.android.vending",
            displayInstallerPackageName(
                "com.android.vending",
                null,
                "com.example.installer"
            )
        )
    }

    @Test
    fun `missing installing package falls back to the initiator`() {
        assertEquals(
            "com.example.installer",
            displayInstallerPackageName(null, "com.example.installer")
        )
    }
}
