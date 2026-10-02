package app.urv.manager.network.api

import app.urv.manager.network.dto.BundleNode
import app.urv.manager.network.dto.ExternalBundleSnapshot
import app.urv.manager.network.dto.SourceNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalBundlesApiIdentityTest {
    @Test
    fun `matches the same bundle identity`() {
        val node = BundleNode(
            id = 7,
            version = "v1.2.3",
            isPrerelease = false,
            source = SourceNode(url = "https://github.com/example/patches")
        )
        val snapshot = ExternalBundleSnapshot(
            sourceUrl = "https://github.com/example/patches",
            version = "v1.2.3",
            isPrerelease = false
        )

        assertEquals(true, node.matchesSnapshotIdentity(snapshot))
    }

    @Test
    fun `ignores a trailing source url slash`() {
        val node = BundleNode(
            id = 7,
            version = "v1.2.3",
            source = SourceNode(url = "https://github.com/example/patches/")
        )
        val snapshot = ExternalBundleSnapshot(
            sourceUrl = "https://github.com/example/patches",
            version = "v1.2.3"
        )

        assertEquals(true, node.matchesSnapshotIdentity(snapshot))
    }

    @Test
    fun `rejects a colliding numeric id from another bundle`() {
        val node = BundleNode(
            id = 7,
            version = "v9.9.9",
            isPrerelease = true,
            source = SourceNode(url = "https://github.com/other/patches")
        )
        val snapshot = ExternalBundleSnapshot(
            sourceUrl = "https://github.com/example/patches",
            version = "v1.2.3",
            isPrerelease = false
        )

        assertEquals(false, node.matchesSnapshotIdentity(snapshot))
    }

    @Test
    fun `rejects a release channel mismatch even when source and version match`() {
        val node = BundleNode(
            id = 7,
            version = "v1.2.3",
            isPrerelease = true,
            source = SourceNode(url = "https://github.com/example/patches")
        )
        val snapshot = ExternalBundleSnapshot(
            sourceUrl = "https://github.com/example/patches",
            version = "v1.2.3",
            isPrerelease = false
        )

        assertEquals(false, node.matchesSnapshotIdentity(snapshot))
    }

    @Test
    fun `matches the same artifact hash exactly`() {
        val node = BundleNode(
            id = 7,
            version = "v1.2.3",
            fileHash = "sha256:abcdef1234",
            source = SourceNode(url = "https://github.com/example/patches")
        )
        val snapshot = ExternalBundleSnapshot(
            sourceUrl = "https://github.com/example/patches",
            version = "v1.2.3",
            fileHash = "sha256:abcdef1234"
        )

        assertEquals(true, node.matchesSnapshotIdentity(snapshot))
    }

    @Test
    fun `treats provider digest casing as significant`() {
        val node = BundleNode(
            id = 7,
            version = "v1.2.3",
            fileHash = "digest:ABCDEF1234",
            source = SourceNode(url = "https://github.com/example/patches")
        )
        val snapshot = ExternalBundleSnapshot(
            sourceUrl = "https://github.com/example/patches",
            version = "v1.2.3",
            fileHash = "digest:abcdef1234"
        )

        assertEquals(false, node.matchesSnapshotIdentity(snapshot))
    }

    @Test
    fun `rejects a replaced artifact under the same version`() {
        val node = BundleNode(
            id = 7,
            version = "v1.2.3",
            fileHash = "new-hash",
            source = SourceNode(url = "https://github.com/example/patches")
        )
        val snapshot = ExternalBundleSnapshot(
            sourceUrl = "https://github.com/example/patches",
            version = "v1.2.3",
            fileHash = "old-hash"
        )

        assertEquals(false, node.matchesSnapshotIdentity(snapshot))
    }

    @Test
    fun `rejects a hashless same-version artifact with a different download url`() {
        val node = BundleNode(
            id = 7,
            version = "v1.2.3",
            downloadUrl = "https://example.com/new-patches.jar",
            source = SourceNode(url = "https://gitlab.com/example/patches")
        )
        val snapshot = ExternalBundleSnapshot(
            sourceUrl = "https://gitlab.com/example/patches",
            version = "v1.2.3",
            downloadUrl = "https://example.com/old-patches.jar"
        )

        assertEquals(false, node.matchesSnapshotIdentity(snapshot))
    }

    @Test
    fun `rejects a hashless same-version artifact with a different bundle type`() {
        val node = BundleNode(
            id = 7,
            bundleType = "morphe",
            version = "v1.2.3",
            source = SourceNode(url = "https://gitea.example/example/patches")
        )
        val snapshot = ExternalBundleSnapshot(
            sourceUrl = "https://gitea.example/example/patches",
            version = "v1.2.3",
            bundleType = "revanced"
        )

        assertEquals(false, node.matchesSnapshotIdentity(snapshot))
    }

    @Test
    fun `returns unknown when an expected artifact hash is unavailable`() {
        val node = BundleNode(
            id = 7,
            version = "v1.2.3",
            source = SourceNode(url = "https://github.com/example/patches")
        )
        val snapshot = ExternalBundleSnapshot(
            sourceUrl = "https://github.com/example/patches",
            version = "v1.2.3",
            fileHash = "expected-hash"
        )

        assertNull(node.matchesSnapshotIdentity(snapshot))
    }

    @Test
    fun `returns unknown when the snapshot cannot be identified`() {
        val node = BundleNode(
            id = 7,
            version = "v1.2.3",
            source = SourceNode(url = "https://github.com/example/patches")
        )

        assertNull(node.matchesSnapshotIdentity(ExternalBundleSnapshot(version = "v1.2.3")))
        assertNull(
            node.matchesSnapshotIdentity(
                ExternalBundleSnapshot(sourceUrl = "https://github.com/example/patches")
            )
        )
    }

    @Test
    fun `exact source row cannot be bypassed by a normalized duplicate`() {
        val exactPending = BundleNode(
            id = 7,
            version = "v1.2.3",
            needPatchesUpdate = true,
            source = SourceNode(url = "https://github.com/example/patches")
        )
        val legacyCurrent = BundleNode(
            id = 8,
            version = "v1.2.3",
            source = SourceNode(url = "https://github.com/example/patches/")
        )
        val snapshot = ExternalBundleSnapshot(
            sourceUrl = "https://github.com/example/patches",
            version = "v1.2.3"
        )

        assertEquals(
            exactPending,
            listOf(legacyCurrent, exactPending).selectBestPatchIdentityCandidate(snapshot)
        )
    }

    @Test
    fun `normalized source variant is used when no exact row exists`() {
        val legacyCurrent = BundleNode(
            id = 8,
            version = "v1.2.3",
            source = SourceNode(url = "https://github.com/example/patches/")
        )
        val snapshot = ExternalBundleSnapshot(
            sourceUrl = "https://github.com/example/patches",
            version = "v1.2.3"
        )

        assertEquals(
            legacyCurrent,
            listOf(legacyCurrent).selectBestPatchIdentityCandidate(snapshot)
        )
    }

    @Test
    fun `terminal patcher failure is not current patch metadata`() {
        val node = BundleNode(
            id = 7,
            version = "v1.2.3",
            needPatchesUpdate = false,
            patcherFailureFingerprint = "runtime-fingerprint",
            source = SourceNode(url = "https://github.com/example/patches")
        )

        assertFalse(node.hasCurrentPatchMetadata())
    }

    @Test
    fun `rolling schema fallback is not current patch metadata`() {
        val node = BundleNode(
            id = 7,
            version = "v1.2.3",
            needPatchesUpdate = false,
            source = SourceNode(url = "https://github.com/example/patches"),
            patchMetadataVerified = false
        )

        assertFalse(node.hasCurrentPatchMetadata())
    }

    @Test
    fun `verified clean row is current patch metadata`() {
        val node = BundleNode(
            id = 7,
            version = "v1.2.3",
            source = SourceNode(url = "https://github.com/example/patches")
        )

        assertTrue(node.hasCurrentPatchMetadata())
    }

    @Test
    fun `current candidate beats terminal failure candidate`() {
        val terminalFailure = BundleNode(
            id = 7,
            version = "v1.2.3",
            patcherFailureFingerprint = "runtime-fingerprint",
            source = SourceNode(url = "https://github.com/example/patches")
        )
        val current = BundleNode(
            id = 8,
            version = "v1.2.3",
            source = SourceNode(url = "https://github.com/example/patches")
        )
        val snapshot = ExternalBundleSnapshot(
            sourceUrl = "https://github.com/example/patches",
            version = "v1.2.3"
        )

        assertEquals(
            current,
            listOf(terminalFailure, current).selectBestPatchIdentityCandidate(snapshot)
        )
    }
}
