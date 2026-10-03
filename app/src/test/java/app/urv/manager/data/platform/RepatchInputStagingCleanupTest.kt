package app.urv.manager.data.platform

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepatchInputStagingCleanupTest {
    @Test
    fun removesUnreferencedRestoredCopyWithoutWaitingForItsAge() = inDirectory { directory ->
        val orphan = directory.resolve("input_orphan.apk").apply { writeText("original") }
        val cleanup = RepatchInputStagingCleanup(directory)
        assertEquals(1, cleanup.prune(emptyList()))
        assertFalse(orphan.exists())
    }

    @Test
    fun preservesPendingResultSources() = inDirectory { directory ->
        val manual = directory.resolve("input_manual.apk").apply { writeText("manual") }
        val automatic = directory.resolve("input_automatic.apks").apply { writeText("automatic") }
        val orphan = directory.resolve("input_orphan.apk").apply { writeText("orphan") }
        val cleanup = RepatchInputStagingCleanup(directory)
        assertEquals(1, cleanup.prune(listOf(manual.path, automatic.path)))
        assertTrue(manual.isFile)
        assertTrue(automatic.isFile)
        assertFalse(orphan.exists())
    }

    @Test
    fun preservesCurrentProcessCopyBeforeItsResultIsPublished() = inDirectory { directory ->
        val cleanup = RepatchInputStagingCleanup(directory)
        val pending = directory.resolve("input_pending.apk").apply { writeText("pending") }
        assertEquals(0, cleanup.prune(emptyList()))
        assertTrue(pending.isFile)
    }

    @Test
    fun fileTimestampCannotMakeCurrentProcessCopyEligible() = inDirectory { directory ->
        val cleanup = RepatchInputStagingCleanup(directory)
        val pending = directory.resolve("input_pending.apk").apply {
            writeText("pending")
            assertTrue(setLastModified(1L))
        }
        assertEquals(0, cleanup.prune(emptyList()))
        assertTrue(pending.isFile)
    }

    @Test
    fun canonicalReferencesPreserveTheSameFile() = inDirectory { directory ->
        val retained = directory.resolve("input_retained.apk").apply { writeText("retained") }
        val cleanup = RepatchInputStagingCleanup(directory)
        val alias = directory.resolve("unused/../input_retained.apk")
        assertEquals(0, cleanup.prune(listOf(alias.path)))
        assertTrue(retained.isFile)
    }

    @Test
    fun ignoresUnrelatedFilesAndNestedDirectories() = inDirectory { directory ->
        val unrelated = directory.resolve("other.apk").apply { writeText("other") }
        val nested = directory.resolve("input_folder").apply { mkdirs() }
            .resolve("input_nested.apk").apply { writeText("nested") }
        val cleanup = RepatchInputStagingCleanup(directory)
        assertEquals(0, cleanup.prune(emptyList()))
        assertTrue(unrelated.isFile)
        assertTrue(nested.isFile)
    }

    @Test
    fun repeatedCleanupDoesNotCountFilesAlreadyDeleted() = inDirectory { directory ->
        directory.resolve("input_orphan.apk").writeText("orphan")
        val cleanup = RepatchInputStagingCleanup(directory)
        assertEquals(1, cleanup.prune(emptyList()))
        assertEquals(0, cleanup.prune(emptyList()))
    }

    @Test
    fun restoredResultRemainsSafeAfterItsPersistedSnapshotChanges() = inDirectory { directory ->
        val retained = directory.resolve("input_retained.apk").apply { writeText("retained") }
        val cleanup = RepatchInputStagingCleanup(directory)
        assertEquals(0, cleanup.prune(listOf(retained.path)))
        assertEquals(0, cleanup.prune(emptyList()))
        assertTrue(retained.isFile)
        assertEquals(1, RepatchInputStagingCleanup(directory).prune(emptyList()))
        assertFalse(retained.exists())
    }

    private fun inDirectory(test: (File) -> Unit) {
        val directory = createTempDirectory("repatch-input-cleanup-test").toFile()
        try {
            test(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
