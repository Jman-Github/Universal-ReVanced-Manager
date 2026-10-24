package app.urv.manager.domain.repository

import app.urv.manager.patcher.split.SplitApkPreparer
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import org.junit.Assert.*
import org.junit.Test

class DownloadedAppFilesTest {
    @Test
    fun savesSplitDownloadWithoutChangingItsComponents() = withDirectory { directory ->
        val incoming = directory.resolve("base.apk")
        writeArchive(incoming, "base.apk", "split_config.en.apk", "feature_chat.apk")
        val original = incoming.readBytes()

        val saved = DownloadedAppFiles.finishDownload(incoming, SplitApkPreparer.isSplitArchive(incoming))

        assertEquals("base.apks", saved.name)
        assertFalse(incoming.exists())
        assertArrayEquals(original, saved.readBytes())
        assertTrue(SplitApkPreparer.isSplitArchive(saved))
        assertEquals(saved, DownloadedAppFiles.source(directory))
    }

    @Test
    fun ordinaryDownloadRemainsAnApk() = withDirectory { directory ->
        val incoming = directory.resolve("base.apk")
        writeArchive(incoming, "AndroidManifest.xml", "classes.dex")
        val original = incoming.readBytes()

        val saved = DownloadedAppFiles.finishDownload(incoming, SplitApkPreparer.isSplitArchive(incoming))

        assertEquals(incoming, saved)
        assertEquals("apk", DownloadedAppFiles.extension(saved))
        assertArrayEquals(original, saved.readBytes())
    }

    @Test
    fun exportingLegacySplitNamedApkPreservesSavedSource() = withDirectory { directory ->
        val source = directory.resolve("base.apk")
        writeArchive(source, "base.apk", "split_config.arm64_v8a.apk")
        val original = source.readBytes()
        val output = ByteArrayOutputStream()

        DownloadedAppFiles.export(listOf("com.example_1" to source), output, asArchive = false)

        assertEquals("apks", DownloadedAppFiles.extension(source))
        assertArrayEquals(original, output.toByteArray())
        assertArrayEquals(original, source.readBytes())
        assertTrue(SplitApkPreparer.isSplitArchive(source))
    }

    @Test
    fun batchExportKeepsEachOriginalFormatAndFile() = withDirectory { directory ->
        val apk = directory.resolve("regular.apk")
        val splits = directory.resolve("legacy.apk")
        writeArchive(apk, "AndroidManifest.xml", "classes.dex")
        writeArchive(splits, "base.apk", "split_config.en.apk")
        val apkBytes = apk.readBytes()
        val splitBytes = splits.readBytes()
        val export = directory.resolve("downloads.zip")

        export.outputStream().use {
            DownloadedAppFiles.export(
                listOf("com.regular_1" to apk, "com.split_2/3" to splits),
                it,
                asArchive = true
            )
        }

        ZipFile(export).use { zip ->
            assertEquals(2, zip.size())
            assertArrayEquals(apkBytes, zip.getInputStream(zip.getEntry("com.regular_1.apk")).use { it.readBytes() })
            assertArrayEquals(splitBytes, zip.getInputStream(zip.getEntry("com.split_2_3.apks")).use { it.readBytes() })
        }
        assertArrayEquals(apkBytes, apk.readBytes())
        assertArrayEquals(splitBytes, splits.readBytes())
        assertTrue(SplitApkPreparer.isSplitArchive(splits))
    }

    @Test
    fun savedSourceLookupIgnoresInspectionWorkspace() = withDirectory { directory ->
        directory.resolve("inspection").mkdirs()
        directory.resolve("metadata.tmp").writeText("not the download")
        val source = directory.resolve("base.apks")
        writeArchive(source, "base.apk", "split_config.en.apk")
        assertEquals(source, DownloadedAppFiles.source(directory))
    }

    private fun withDirectory(block: (File) -> Unit) {
        val directory = createTempDirectory("downloaded-app-files").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun writeArchive(file: File, vararg names: String) {
        ZipOutputStream(file.outputStream()).use { zip ->
            names.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(name.toByteArray())
                zip.closeEntry()
            }
        }
    }
}
