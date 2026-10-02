package app.urv.manager.util

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import org.junit.Assert.*
import org.junit.Test

class ApkAbiLabelTest {
    @Test
    fun unchangedApkReusesAbisAndRewrittenApkIsRescanned() = withApk { apk ->
        writeApk(apk, "lib/arm64-v8a/libsample.so")
        val original = apk.savedApkAbisOrNull()
        assertEquals(setOf("arm64-v8a"), original)
        assertSame(original, apk.savedApkAbisOrNull())
        val timestamp = apk.lastModified()
        writeApk(apk, "lib/x86/libsample.so")
        assertTrue(apk.setLastModified(timestamp + 5_000))
        assertEquals(setOf("x86"), apk.savedApkAbisOrNull())
    }

    @Test
    fun apkWithoutNativeLibrariesIsCached() = withApk { apk ->
        writeApk(apk, "classes.dex")
        val abis = apk.savedApkAbisOrNull()
        assertEquals(emptySet<String>(), abis)
        assertSame(abis, apk.savedApkAbisOrNull())
    }

    @Test
    fun missingAndCorruptApksAreRetried() = withApk { apk ->
        assertNull(apk.savedApkAbisOrNull())
        apk.writeText("not an APK")
        assertNull(apk.savedApkAbisOrNull())
        writeApk(apk, "lib/armeabi-v7a/libsample.so")
        assertEquals(setOf("armeabi-v7a"), apk.savedApkAbisOrNull())
        assertTrue(apk.delete())
        assertNull(apk.savedApkAbisOrNull())
    }

    private fun withApk(block: (File) -> Unit) {
        val directory = createTempDirectory("abi-cache").toFile()
        try {
            block(directory.resolve("saved.apk"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun writeApk(apk: File, name: String) {
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
    }
}
