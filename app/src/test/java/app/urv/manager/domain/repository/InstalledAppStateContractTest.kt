package app.urv.manager.domain.repository

import app.urv.manager.data.room.apps.installed.InstallType
import app.urv.manager.data.room.apps.installed.InstalledApp
import app.urv.manager.domain.manager.InstallerPreferenceTokens
import app.urv.manager.ui.screen.settings.retainedOriginalPackageName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstalledAppStateContractTest {
    @Test
    fun `automatic patch target moves to the new saved entry`() {
        val old = record("com.example.app__bundle_old", 100L)
        val sibling = record("com.example.app__bundle_other", 150L)
        val replacement = record("com.example.app__bundle_new", 200L)

        val migrated = migratedAutoPatchTargets(
            records = listOf(old, sibling, replacement),
            enabledTargets = setOf(old.currentPackageName),
            oldKey = old.currentPackageName,
            newKey = replacement.currentPackageName
        )

        assertEquals(setOf(replacement.currentPackageName), migrated)
    }

    @Test
    fun `unrelated automatic patch target is not moved`() {
        val old = record("com.example.app__bundle_old", 100L)
        val replacement = record("com.example.app__bundle_new", 200L)

        val migrated = migratedAutoPatchTargets(
            records = listOf(old, replacement),
            enabledTargets = setOf("com.other.app"),
            oldKey = old.currentPackageName,
            newKey = replacement.currentPackageName
        )

        assertEquals(setOf("com.other.app"), migrated)
    }

    @Test
    fun `newer external reinstall makes installed record stale`() {
        val record = record("com.example.app", 1_000L, InstallType.DEFAULT)

        assertFalse(
            installedRecordMatchesCurrentPackage(
                record = record,
                installedVersion = "1.0",
                installedLastUpdateTime = 70_001L,
                managedPatchedFileAvailable = false,
                managedPatchedFileMatchesRecord = false
            )
        )
    }

    @Test
    fun `managed patched APK keeps installed record current`() {
        val record = record("com.example.app", 1_000L, InstallType.DEFAULT)

        assertTrue(
            installedRecordMatchesCurrentPackage(
                record = record,
                installedVersion = "1.0",
                installedLastUpdateTime = 500_000L,
                managedPatchedFileAvailable = true,
                managedPatchedFileMatchesRecord = true
            )
        )
    }

    @Test
    fun `managed APK from another version does not match the record`() {
        val record = record("com.example.app", 1_000L, InstallType.DEFAULT)

        assertFalse(
            installedRecordMatchesCurrentPackage(
                record = record,
                installedVersion = "0.9",
                installedLastUpdateTime = 500_000L,
                managedPatchedFileAvailable = true,
                managedPatchedFileMatchesRecord = true
            )
        )
    }

    @Test
    fun `same version stock reinstall is stale when the managed APK differs`() {
        val record = record("com.example.app", 1_000L, InstallType.DEFAULT)

        assertFalse(
            installedRecordMatchesCurrentPackage(
                record = record,
                installedVersion = "1.0",
                installedLastUpdateTime = 1_001L,
                managedPatchedFileAvailable = true,
                managedPatchedFileMatchesRecord = false
            )
        )
    }

    @Test
    fun `legacy saved entry keeps its original package during storage cleanup`() {
        val legacy = record("com.example.app__bundle_old", 100L).copy(
            originalPackageName = ""
        )

        assertEquals("com.example.app", retainedOriginalPackageName(legacy))
    }

    @Test
    fun `legacy profile Play Store installer normalizes to Shizuku`() {
        assertEquals(
            InstallerPreferenceTokens.SHIZUKU,
            normalizePatchProfileInstallerToken(
                InstallerPreferenceTokens.SHIZUKU_GOOGLE_PLAY
            )
        )
    }

    @Test
    fun `non legacy profile installer remains unchanged`() {
        assertEquals(
            InstallerPreferenceTokens.INTERNAL,
            normalizePatchProfileInstallerToken(InstallerPreferenceTokens.INTERNAL)
        )
    }

    private fun record(
        currentPackageName: String,
        createdAt: Long,
        installType: InstallType = InstallType.SAVED
    ) = InstalledApp(
        currentPackageName = currentPackageName,
        originalPackageName = "com.example.app",
        version = "1.0",
        installType = installType,
        sortOrder = 0,
        createdAt = createdAt
    )
}
