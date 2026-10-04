package app.urv.manager.data.platform

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DisposableFileCleanupTest {
    @Test
    fun deletingDetachedWorkspaceKeepsFilesCreatedByTheNextOperation() = inDirectory { root ->
        val workspace = root.resolve("patcher").apply { mkdirs() }
        workspace.resolve("old.apk").writeText("old data")
        val detached = DisposableFileCleanup.detach(listOf(workspace))
        assertEquals(1, detached.size)
        assertFalse(workspace.exists())
        workspace.mkdirs()
        val newInput = workspace.resolve("new.apk").apply { writeText("new data") }
        assertEquals(8L, DisposableFileCleanup.deleteDetached(detached))
        assertTrue(newInput.isFile)
        assertTrue(detached.none(File::exists))
    }

    @Test
    fun interruptedDeletionIsRetriedWithoutMovingItsFilesAgain() = inDirectory { root ->
        val old = root.resolve("old.apk").apply { writeText("old") }
        val detached = DisposableFileCleanup.detach(listOf(old))
        assertEquals(detached, DisposableFileCleanup.detach(detached))
        assertEquals(3L, DisposableFileCleanup.deleteDetached(detached))
        assertEquals(0L, DisposableFileCleanup.deleteDetached(detached))
    }

    @Test
    fun ignoresMissingFilesAndLeavesUnselectedFilesAlone() = inDirectory { root ->
        val selected = root.resolve("old.apk").apply { writeText("old") }
        val retained = root.resolve("app_pr_profile/state").apply {
            parentFile.mkdirs()
            writeText("profile")
        }
        val detached = DisposableFileCleanup.detach(listOf(selected, root.resolve("missing")))
        assertEquals(1, detached.size)
        DisposableFileCleanup.deleteDetached(detached)
        assertTrue(retained.isFile)
    }

    @Test
    fun refusesToDeleteAFileThatHasNotBeenDetached() = inDirectory { root ->
        val source = root.resolve("input.apk").apply { writeText("input") }
        assertFailsWith<IllegalArgumentException> {
            DisposableFileCleanup.deleteDetached(listOf(source))
        }
        assertTrue(source.isFile)
    }

    private fun inDirectory(test: (File) -> Unit) {
        val directory = createTempDirectory("disposable-file-cleanup-test").toFile()
        try {
            test(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
