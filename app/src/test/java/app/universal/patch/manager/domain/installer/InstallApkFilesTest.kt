package app.universal.patch.manager.domain.installer

import app.universal.patch.manager.patcher.split.SplitApkFiles
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InstallApkFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun splits(): File = temporary.newFile("output.apk").also { source ->
        val directory = temporary.newFolder()
        val files = listOf("config.en.apk", "base.apk").map { name ->
            directory.resolve(name).apply { writeBytes(name.toByteArray()) }
        }
        SplitApkFiles.pack(files, source)
    }

    @Test fun splitComponentsLiveUntilInstallationCompletesAndAreCleanedAfterwards() = runBlocking {
        val source = splits()
        val cache = temporary.newFolder()
        var extracted = emptyList<File>()
        val result = withInstallApkFiles(source, cache) { files ->
            extracted = files
            assertEquals(listOf("base.apk", "config.en.apk"), files.map { it.name })
            assertTrue(files.all { it.isFile })
            "installed"
        }
        assertEquals("installed", result)
        assertTrue(extracted.none { it.exists() })
        assertTrue(source.isFile)
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun cancellationCleansExtractedComponentsWithoutDeletingSource() = runBlocking {
        val source = splits()
        val cache = temporary.newFolder()
        try {
            withInstallApkFiles(source, cache) { throw CancellationException("Cancelled") }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertTrue(cache.listFiles()!!.isEmpty())
            assertTrue(source.isFile)
        }
    }

    @Test fun normalApkUsesTheOriginalFileWithoutExtractingIt() = runBlocking {
        val source = temporary.newFile("single.apk").apply { writeText("single") }
        val cache = temporary.newFolder()
        withInstallApkFiles(source, cache) { files -> assertEquals(listOf(source), files) }
        assertTrue(source.isFile)
        assertTrue(cache.listFiles()!!.isEmpty())
    }
}
