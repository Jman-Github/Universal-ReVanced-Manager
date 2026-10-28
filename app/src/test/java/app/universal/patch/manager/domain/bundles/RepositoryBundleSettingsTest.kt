package app.universal.patch.manager.domain.bundles

import app.universal.patch.manager.network.dto.ReVancedAsset
import java.nio.file.Files
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RepositoryBundleSettingsTest {
    private val asset = ReVancedAsset(
        "https://example.com/v1/patches.rvp", LocalDateTime(2026, 1, 1, 0, 0),
        description = "Version one", version = "v1"
    )

    @Test fun unchangedBackupRestoresWithoutTemporarilyDisablingThePin() {
        val pinned = RepositoryBundleSettings(useLatest = true,
            useSpecificVersion = true, selectedVersion = asset)
        assertEquals(pinned, pinned.withImportedSelection(false, true, true, asset))
    }

    @Test fun oldBackupsPreservePinsAndMissingChannelPreferences() {
        val pinned = RepositoryBundleSettings(usePrereleases = true,
            useSpecificVersion = true, selectedVersion = asset)
        assertEquals(pinned, pinned.withImportedSelection(null, null, null, null))
        assertEquals(pinned.copy(useLatest = true, usePrereleases = false),
            pinned.withImportedSelection(false, true, null, null))
    }

    @Test fun queuedTogglePreservesTheMostRecentlySelectedVersion() {
        val newest = asset.copy(version = "v2", downloadUrl = "https://example.com/v2/patches.rvp")
        val current = RepositoryBundleSettings(useLatest = true, selectedVersion = newest)
        val disabled = current.withVersionSelection(false)
        assertEquals(newest, disabled.selectedVersion)
        assertEquals(newest, disabled.withVersionSelection(true).selectedVersion)
        assertTrue(disabled.useLatest)
    }

    @Test fun oldSettingsRemainCompatible() {
        val settings = Json.decodeFromString<RepositoryBundleSettings>(
            """{"usePrereleases":true,"useLatest":false}"""
        )
        assertTrue(settings.usePrereleases)
        assertFalse(settings.useSpecificVersion)
        assertNull(settings.selectedVersion)
    }

    @Test fun selectedReleaseSurvivesRestartAndDisablingRestoresChannel() {
        val dir = Files.createTempDirectory("bundle-settings-test").toFile()
        try {
            val original = RepositoryBundleSettings(useLatest = true)
            RepositoryBundleSettingsStore.write(dir,
                original.copy(useSpecificVersion = true, selectedVersion = asset))
            val restored = RepositoryBundleSettingsStore.read(dir)
            assertTrue(restored.useSpecificVersion)
            assertEquals(asset, restored.selectedVersion)
            assertEquals(original.releaseChannel, restored.copy(useSpecificVersion = false).releaseChannel)
            assertTrue(restored.useLatest)
        } finally {
            dir.deleteRecursively()
        }
    }
}
