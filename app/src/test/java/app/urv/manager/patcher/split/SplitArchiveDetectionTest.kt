package app.urv.manager.patcher.split

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import org.junit.Assert.*
import org.junit.Test

class SplitArchiveDetectionTest {
    @Test
    fun nestedApkResourcesAreNotSplitArchives() = withDirectory { directory ->
        for (extension in listOf("apk", "zip", "apks", "apkm", "xapk")) {
            val archive = directory.resolve("resources.$extension")
            writeArchive(archive, "res/raw/base.apk", "assets/split_config.en.apk")
            assertFalse(extension, SplitApkPreparer.isSplitArchive(archive))
        }
    }

    @Test
    fun splitArchiveSelectionIgnoresNestedResources() = withDirectory { directory ->
        val archive = directory.resolve("bundle.apkm")
        writeArchive(archive, "base.APK", "split_config.en.apk", "res/raw/extra.apk")
        assertEquals(
            setOf("base.APK", "split_config.en.apk"),
            SplitApkPreparer.splitApkEntryNames(archive)
        )
    }

    @Test
    fun apksKeepsDirectBundletoolModules() = withDirectory { directory ->
        val archive = directory.resolve("bundle.apks")
        writeArchive(
            archive, "splits/base.apk", "splits/split_config.en.apk",
            "res/raw/base.apk", "splits/assets/nested.apk"
        )
        assertEquals(
            setOf("splits/base.apk", "splits/split_config.en.apk"),
            SplitApkPreparer.splitApkEntryNames(archive)
        )
    }

    private fun withDirectory(block: (File) -> Unit) {
        val directory = createTempDirectory("split-detection").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun writeArchive(file: File, vararg entries: String) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
    }
}
