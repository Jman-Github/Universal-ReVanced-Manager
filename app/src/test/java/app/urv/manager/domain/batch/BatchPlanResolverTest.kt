package app.urv.manager.domain.batch

import app.urv.manager.data.room.apps.installed.InstallType
import app.urv.manager.data.room.apps.installed.InstalledApp
import app.urv.manager.data.room.profile.PatchProfilePayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatchPlanResolverTest {
    @Test
    fun `preferred record matches the newest update target`() {
        val olderInstalled = record(
            currentPackageName = "com.example.app",
            installType = InstallType.SHIZUKU,
            createdAt = 100L
        )
        val newerSaved = record(
            currentPackageName = "com.example.app__saved",
            installType = InstallType.SAVED,
            createdAt = 200L
        )

        assertEquals(
            newerSaved,
            preferredBatchRecord(listOf(olderInstalled, newerSaved), "com.example.app")
        )
    }

    @Test
    fun `preferred record ignores unrelated packages`() {
        val unrelated = record("com.other.app", InstallType.SAVED, 300L)

        assertNull(preferredBatchRecord(listOf(unrelated), "com.example.app"))
    }

    @Test
    fun `dashboard batch selection preserves the exact selected saved entry`() {
        val older = record(
            currentPackageName = "com.example.app__bundle_old",
            installType = InstallType.SAVED,
            createdAt = 100L,
            originalPackageName = "com.example.app"
        )
        val newer = record(
            currentPackageName = "com.example.app__bundle_new",
            installType = InstallType.SAVED,
            createdAt = 200L,
            originalPackageName = "com.example.app"
        )
        val other = record(
            currentPackageName = "com.other.app",
            installType = InstallType.SAVED,
            createdAt = 300L
        )

        assertEquals(
            listOf(older.currentPackageName, other.currentPackageName),
            selectedBatchTargetIdentifiers(
                records = listOf(newer, older, other),
                selectedEntryKeys = setOf(older.currentPackageName, other.currentPackageName)
            )
        )
    }

    @Test
    fun `automatic patching selects the exact saved entry`() {
        val older = record(
            currentPackageName = "com.example.app__bundle_old",
            installType = InstallType.SAVED,
            createdAt = 100L,
            originalPackageName = "com.example.app"
        )
        val newer = record(
            currentPackageName = "com.example.app__bundle_new",
            installType = InstallType.SAVED,
            createdAt = 200L,
            originalPackageName = "com.example.app"
        )

        assertEquals(
            listOf(older),
            resolveAutoPatchRecords(
                records = listOf(older, newer),
                enabledTargets = setOf(older.currentPackageName)
            )
        )
    }

    @Test
    fun `legacy automatic patch target resolves to the newest matching record`() {
        val older = record(
            currentPackageName = "com.example.app__bundle_old",
            installType = InstallType.SAVED,
            createdAt = 100L,
            originalPackageName = "com.example.app"
        )
        val newer = record(
            currentPackageName = "com.example.app__bundle_new",
            installType = InstallType.SAVED,
            createdAt = 200L,
            originalPackageName = "com.example.app"
        )

        assertEquals(
            listOf(newer),
            resolveAutoPatchRecords(
                records = listOf(older, newer),
                enabledTargets = setOf("com.example.app")
            )
        )
    }

    @Test
    fun `multiple selected variants normalize to one newest target per app`() {
        val older = record(
            currentPackageName = "com.example.app__bundle_old",
            installType = InstallType.SAVED,
            createdAt = 100L,
            originalPackageName = "com.example.app"
        )
        val newer = record(
            currentPackageName = "com.example.app__bundle_new",
            installType = InstallType.SAVED,
            createdAt = 200L,
            originalPackageName = "com.example.app"
        )

        val result = resolveAutoPatchRecords(
            records = listOf(older, newer),
            enabledTargets = setOf(older.currentPackageName, newer.currentPackageName)
        )

        assertEquals(listOf(newer), result)
        assertTrue(result.map { it.originalPackageName }.distinct().size == result.size)
    }

    @Test
    fun `exact legacy saved entry resolves its real package name`() {
        val legacy = record(
            currentPackageName = "com.example.app__bundle_variant",
            installType = InstallType.SAVED,
            createdAt = 100L,
            originalPackageName = ""
        )

        assertEquals(
            "com.example.app",
            resolveBatchPackageName(legacy, legacy.currentPackageName)
        )
    }

    @Test
    fun `saved batch target only accepts an automatically discovered source for the exact build`() {
        val saved = record(
            currentPackageName = "com.example.app__bundle_saved",
            installType = InstallType.SAVED,
            createdAt = 100L,
            originalPackageName = "com.example.app"
        )

        assertTrue(
            automaticBatchSourceMatchesTarget(
                selectedRecord = saved,
                targetVersion = "1.0",
                targetVersionCode = 100L,
                sourceVersion = "1.0",
                sourceVersionCode = 100L
            )
        )
        assertFalse(
            automaticBatchSourceMatchesTarget(
                selectedRecord = saved,
                targetVersion = "1.0",
                targetVersionCode = 100L,
                sourceVersion = "1.0",
                sourceVersionCode = 101L
            )
        )
        assertFalse(
            automaticBatchSourceMatchesTarget(
                selectedRecord = saved,
                targetVersion = "1.0",
                targetVersionCode = null,
                sourceVersion = "1.0",
                sourceVersionCode = 100L
            )
        )
    }

    @Test
    fun `stale installed record uses the currently installed version`() {
        val staleRecord = record(
            currentPackageName = "com.example.app",
            installType = InstallType.DEFAULT,
            createdAt = 100L
        )

        assertEquals(
            "2.0",
            resolveBatchTargetVersion(
                selectedRecord = staleRecord,
                currentInstalledRecord = null,
                installedVersion = "2.0"
            )
        )
        assertEquals(
            "1.0",
            resolveBatchTargetVersion(
                selectedRecord = staleRecord,
                currentInstalledRecord = staleRecord,
                installedVersion = "2.0"
            )
        )
    }

    @Test
    fun `batch save key reuses matching entry only when overwrite is allowed`() {
        val matching = "com.example.app__bundle_existing"
        assertEquals(
            matching,
            selectBatchSavedEntryKey(
                packageName = "com.example.app",
                variantIdentity = "variant",
                overwriteDisabled = false,
                matchingEntryKey = matching,
                uniqueSuffix = "12345678"
            )
        )

        val protectedKey = selectBatchSavedEntryKey(
            packageName = "com.example.app",
            variantIdentity = "variant",
            overwriteDisabled = true,
            matchingEntryKey = matching,
            uniqueSuffix = "12345678"
        )
        assertTrue(protectedKey.endsWith("__12345678"))
        assertTrue(protectedKey != matching)
    }

    @Test
    fun `manual batch preserves version mismatch until user approves it`() {
        assertEquals(
            BatchItemState.VERSION_MISMATCH,
            resolveManualBatchItemState(
                resolvedState = BatchItemState.VERSION_MISMATCH,
                hasInput = true,
                hasBundles = true,
                hasSelection = true
            )
        )
        assertEquals(
            BatchItemState.READY,
            resolveManualBatchItemState(
                resolvedState = BatchItemState.READY,
                hasInput = true,
                hasBundles = true,
                hasSelection = true
            )
        )
    }

    @Test
    fun `automatic patching upgrades legacy records without bundle versions`() {
        assertTrue(
            isAutoPatchTargetOutdated(
                payload = null,
                storedSelection = mapOf(7 to setOf("Patch")),
                currentVersions = mapOf(7 to "2.0")
            )
        )
        assertTrue(
            isAutoPatchTargetOutdated(
                payload = payload(version = null),
                storedSelection = mapOf(7 to setOf("Patch")),
                currentVersions = mapOf(7 to "2.0")
            )
        )
    }

    @Test
    fun `automatic patching compares tracked bundle versions`() {
        assertFalse(
            isAutoPatchTargetOutdated(
                payload = payload(version = "2.0"),
                storedSelection = mapOf(7 to setOf("Patch")),
                currentVersions = mapOf(7 to "2.0")
            )
        )
        assertTrue(
            isAutoPatchTargetOutdated(
                payload = payload(version = "1.0"),
                storedSelection = mapOf(7 to setOf("Patch")),
                currentVersions = mapOf(7 to "2.0")
            )
        )
    }

    private fun payload(version: String?) = PatchProfilePayload(
        bundles = listOf(
            PatchProfilePayload.Bundle(
                bundleUid = 7,
                patches = listOf("Patch"),
                options = emptyMap(),
                version = version
            )
        )
    )

    private fun record(
        currentPackageName: String,
        installType: InstallType,
        createdAt: Long,
        originalPackageName: String = currentPackageName.substringBefore("__saved")
    ) = InstalledApp(
        currentPackageName = currentPackageName,
        originalPackageName = originalPackageName,
        version = "1.0",
        installType = installType,
        sortOrder = 0,
        createdAt = createdAt
    )
}
