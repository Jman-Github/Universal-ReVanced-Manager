package app.universal.patch.manager.domain.bundles

import app.universal.patch.manager.network.api.ExternalBundlesEndpoints
import app.universal.patch.manager.network.dto.ExternalBundleSnapshot
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalBundleMetadataTest {
    private val snapshot = ExternalBundleSnapshot(
        apiHost = ExternalBundlesEndpoints.REGISTRY_HOST,
        sourceUrl = "https://github.com/crimera/piko",
        ownerName = "crimera", repoName = "piko", bundleId = 123,
        downloadUrl = "https://example.com/piko.rvp", version = "v1.2.3-dev.1",
        signatureDownloadUrl = "https://example.com/piko.rvp.asc",
        createdAt = "2026-10-26T12:00:00", description = "Release notes",
        iconUrl = "https://example.com/icon.png", ownerAvatarUrl = "https://example.com/avatar.png",
        fileHash = "sha256:example", isPrerelease = true
    )

    @Test fun persistedRegistryMetadataRetainsIconsWithoutAnotherLookup() {
        val directory = Files.createTempDirectory("bundle-metadata-icons-").toFile()
        try {
            val metadata = snapshot.toExternalBundleMetadata()
            ExternalBundleMetadataStore.write(directory, metadata)
            val restored = ExternalBundleMetadataStore.read(directory)
            assertEquals(metadata, restored)
            assertEquals(snapshot.iconUrl, restored?.iconUrl)
            assertEquals(snapshot.ownerAvatarUrl, restored?.ownerAvatarUrl)
            assertEquals(snapshot.bundleId, restored?.bundleId)
            assertEquals(snapshot.fileHash, restored?.fileHash)
            assertEquals(snapshot.signatureDownloadUrl, restored?.signatureDownloadUrl)
        } finally {
            directory.resolve("external_bundle.json").delete()
            directory.delete()
        }
    }

    @Test fun latestSelectionStaysLatestWhileRetainingTheSelectedArtifact() {
        val metadata = snapshot.toExternalBundleMetadata(prerelease = null)
        assertNull(metadata.isPrerelease)
        assertEquals(snapshot.version, metadata.version)
        assertEquals(snapshot.downloadUrl, metadata.downloadUrl)
        assertEquals(snapshot.iconUrl, metadata.iconUrl)
    }

    @Test fun blankIconDoesNotHideAnAvailableAvatar() {
        val metadata = snapshot.copy(iconUrl = "  ").toExternalBundleMetadata()
        assertNull(metadata.iconUrl)
        assertEquals(snapshot.ownerAvatarUrl, metadata.ownerAvatarUrl)
    }

    @Test fun fallbackDownloadUrlUsedWhenSnapshotDownloadUrlIsBlank() {
        val metadata = snapshot.copy(downloadUrl = null)
            .toExternalBundleMetadata(fallbackDownloadUrl = "https://example.com/fallback.rvp")
        assertEquals("https://example.com/fallback.rvp", metadata.downloadUrl)
    }
}
