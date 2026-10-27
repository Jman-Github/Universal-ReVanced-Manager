package app.urv.manager.patcher.runtime.reseam

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReseamRuntimeFilesTest {
    private fun apk(file: File, version: String = "a"): File {
        file.setWritable(true, true)
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in listOf("lib/test-abi/libreseam-sdk-native.so" to byteArrayOf(1, 2, 3),
                "version" to version.toByteArray())) {
                zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun inDirectory(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("reseam-runtime-").toFile()
        try { test(directory) } finally {
            directory.walkTopDown().forEach { it.setWritable(true, true) }
            directory.deleteRecursively()
        }
    }

    @Test fun identicalReimportsAndTimestampChangesShareTheSameSnapshot() = inDirectory { root ->
        val source = apk(root.resolve("import-a.apk"))
        val files = prepareReseamRuntimeFiles(source, root.resolve("cache"), listOf("test-abi"))
        val alias = source.copyTo(root.resolve("import-b.apk"))
        alias.setLastModified(source.lastModified() + 10_000L)
        assertEquals(files, prepareReseamRuntimeFiles(alias, root.resolve("cache"), listOf("test-abi")))
        assertArrayEquals(byteArrayOf(1, 2, 3), files.nativeLibraryDirectory.resolve("libreseam-sdk-native.so").readBytes())
    }

    @Test fun snapshotSurvivesManagedPluginRemoval() = inDirectory { root ->
        val source = apk(root.resolve("source.apk"))
        val expected = source.readBytes()
        val files = prepareReseamRuntimeFiles(source, root.resolve("cache"), listOf("test-abi"))
        source.delete()
        assertArrayEquals(expected, files.apkFile.readBytes())
        assertThrows(IOException::class.java) {
            prepareReseamRuntimeFiles(source, root.resolve("cache"), listOf("test-abi"))
        }
    }

    @Test fun contentChangesAreDetectedEvenWhenPathSizeAndTimestampMatch() = inDirectory { root ->
        val source = apk(root.resolve("source.apk"))
        val timestamp = source.lastModified()
        val size = source.length()
        val first = prepareReseamRuntimeFiles(source, root.resolve("cache"), listOf("test-abi"))
        apk(source, "b")
        source.setLastModified(timestamp)
        assertEquals(size, source.length())
        val second = prepareReseamRuntimeFiles(source, root.resolve("cache"), listOf("test-abi"))
        assertNotEquals(first.apkFile, second.apkFile)
        assertNotEquals(first.nativeLibraryDirectory, second.nativeLibraryDirectory)
    }

    @Test fun corruptSameSizeSnapshotIsRepairedFromTheTrustedSource() = inDirectory { root ->
        val source = apk(root.resolve("source.apk"))
        val first = prepareReseamRuntimeFiles(source, root.resolve("cache"), listOf("test-abi"))
        first.apkFile.setWritable(true, true)
        val bad = first.apkFile.readBytes().also { it[0] = 0 }
        first.apkFile.writeBytes(bad)
        val repaired = prepareReseamRuntimeFiles(source, root.resolve("cache"), listOf("test-abi"))
        assertArrayEquals(source.readBytes(), repaired.apkFile.readBytes())
    }

    @Test fun unsupportedAbiFailsBeforeCreatingALoader() = inDirectory { root ->
        val source = apk(root.resolve("source.apk"))
        assertThrows(IOException::class.java) {
            prepareReseamRuntimeFiles(source, root.resolve("cache"), listOf("other-abi"))
        }
    }
}
