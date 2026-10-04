package app.urv.manager.domain.batch

import app.urv.manager.ui.model.SelectedApp
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ManualBatchPatchQueueTest {
    @Test
    fun `temporary local apk is copied into queue owned storage`() {
        val root = Files.createTempDirectory("manual-batch-queue").toFile()
        try {
            val source = root.resolve("input.apk").apply { writeText("apk-data") }
            val input = SelectedApp.Local(
                packageName = "com.example.app",
                version = "1.0",
                file = source,
                temporary = true
            )
            val (prepared, ownedPath) = prepareManualBatchInput(
                input = input,
                targetDirectory = root.resolve("queue"),
                uniqueSuffix = 42L
            )
            val local = assertIs<SelectedApp.Local>(prepared)

            assertFalse(local.temporary)
            assertNotEquals(source.canonicalPath, local.file.canonicalPath)
            assertTrue(source.delete())
            assertTrue(local.file.isFile)
            assertEquals("apk-data", local.file.readText())
            assertEquals(local.file.canonicalPath, ownedPath)
        } finally {
            root.deleteRecursively()
        }
    }
}
