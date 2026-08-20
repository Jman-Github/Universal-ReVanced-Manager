package app.urv.manager.patcher.split

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking

class SplitApkInspectorTest {
    @Test
    fun `retained split archive exposes its base apk`() = runBlocking {
        val workspace = createTempDirectory("split-inspector").toFile()
        val archive = workspace.resolve("original.apks")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("base.apk"))
            zip.write("base-content".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("split_config.en.apk"))
            zip.write("config-content".toByteArray())
            zip.closeEntry()
        }

        val extracted = assertNotNull(
            SplitApkInspector.extractRepresentativeApk(archive, workspace)
        )
        try {
            assertEquals("base-content", extracted.file.readText())
        } finally {
            extracted.cleanup()
        }
        assertFalse(extracted.file.exists())
        workspace.deleteRecursively()
        Unit
    }
}
