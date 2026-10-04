package app.urv.manager.data.platform

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertTrue

class PatchWorkspaceCleanupTest {
    @Test
    fun removesRuntimeScratchFilesWithoutWaitingForRestart() = inDirectory { root ->
        val workspace = root.resolve("ephemeral").apply { mkdirs() }
        val batch = root.resolve("batch").apply { mkdirs() }
        val names = listOf("patched.apk", "patcher", "patch-workspace", "patcher-inputs",
            "patch-input-metadata", "apkeditor-android-data", "split-patcher-selection-1",
            "split-revanced22-1", "installed-splits-1")
        names.forEach { name ->
            if (name.endsWith(".apk")) workspace.resolve(name).writeText("output")
            else workspace.resolve(name).apply { mkdirs() }.resolve("large.apk").writeText("data")
        }
        val cleanup = PatchWorkspaceCleanup(workspace, batch)
        assertEquals(names.size, cleanup.pruneScratchFiles())
        assertTrue(workspace.isDirectory)
        assertEquals(0, cleanup.pruneScratchFiles())
    }

    @Test
    fun preservesSelectedInputsUpdaterAndUiResults() = inDirectory { root ->
        val workspace = root.resolve("ephemeral").apply { mkdirs() }
        val batch = root.resolve("batch").apply { mkdirs() }
        val input = workspace.resolve("input-1.apk").apply { writeText("selected") }
        val updater = workspace.resolve("updater.apk").apply { writeText("update") }
        val ui = root.resolve("ui_ephemeral/installer-1/output.apk").apply {
            parentFile.mkdirs()
            writeText("pending result")
        }
        val other = workspace.resolve("unrecognized").apply { mkdirs() }
            .resolve("patcher").apply { writeText("unrelated") }
        assertEquals(0, PatchWorkspaceCleanup(workspace, batch).pruneScratchFiles())
        listOf(input, updater, ui, other).forEach { assertTrue(it.isFile) }
    }

    @Test
    fun removesOnlyUnreferencedRestoredBatchOutputs() = inDirectory { root ->
        val batch = root.resolve("batch").apply { mkdirs() }
        val orphan = batch.resolve("batch_orphan.apk").apply { writeText("orphan") }
        val manual = batch.resolve("batch_manual.apk").apply { writeText("manual") }
        val automatic = batch.resolve("batch_auto.apk").apply { writeText("automatic") }
        val unrelated = batch.resolve("unknown.apk").apply { writeText("other") }
        val cleanup = PatchWorkspaceCleanup(root.resolve("ephemeral"), batch)
        assertEquals(1, cleanup.pruneRestoredBatchOutputs(listOf(manual.path, automatic.path)))
        assertFalse(orphan.exists())
        listOf(manual, automatic, unrelated).forEach { assertTrue(it.isFile) }
        assertEquals(0, cleanup.pruneRestoredBatchOutputs(emptyList()))
        assertTrue(manual.isFile)
        assertTrue(automatic.isFile)
    }

    @Test
    fun preservesUnpublishedCurrentProcessBatchOutputRegardlessOfAge() = inDirectory { root ->
        val batch = root.resolve("batch").apply { mkdirs() }
        val cleanup = PatchWorkspaceCleanup(root.resolve("ephemeral"), batch)
        val pending = batch.resolve("batch_pending.apk").apply {
            writeText("unpublished")
            assertTrue(setLastModified(1L))
        }
        assertEquals(0, cleanup.pruneRestoredBatchOutputs(emptyList()))
        assertTrue(pending.isFile)
        assertEquals(1, PatchWorkspaceCleanup(root.resolve("ephemeral"), batch)
            .pruneRestoredBatchOutputs(emptyList()))
        assertFalse(pending.exists())
    }

    @Test
    fun canonicalBatchReferencesPreserveRestoredResults() = inDirectory { root ->
        val batch = root.resolve("batch").apply { mkdirs() }
        val pending = batch.resolve("batch_pending.apk").apply { writeText("pending") }
        val cleanup = PatchWorkspaceCleanup(root.resolve("ephemeral"), batch)
        assertEquals(0, cleanup.pruneRestoredBatchOutputs(
            listOf(batch.resolve("unused/../batch_pending.apk").path)))
        assertTrue(pending.isFile)
    }

    @Test
    fun unreadableReferenceKeepsAllRestoredBatchOutputs() = inDirectory { root ->
        val batch = root.resolve("batch").apply { mkdirs() }
        val output = batch.resolve("batch_result.apk").apply { writeText("result") }
        val cleanup = PatchWorkspaceCleanup(root.resolve("ephemeral"), batch)
        assertFails { cleanup.pruneRestoredBatchOutputs(listOf("invalid\u0000path")) }
        assertTrue(output.isFile)
    }

    private fun inDirectory(test: (File) -> Unit) {
        val directory = createTempDirectory("patch-workspace-cleanup-test").toFile()
        try {
            test(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
