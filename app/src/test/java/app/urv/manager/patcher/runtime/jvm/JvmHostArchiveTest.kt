package app.urv.manager.patcher.runtime.jvm

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.*

class JvmHostArchiveTest {
    private fun archive(vararg files: Pair<String, ByteArray>) = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            for ((name, content) in files) {
                zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry()
            }
        }
    }.toByteArray()

    @Test
    fun `restores exact framework and class bytes as stored entries`() {
        val dir = kotlin.io.path.createTempDirectory("host-test").toFile()
        try {
            val data = ByteArray(120_000) { it.toByte() }
            val apk = dir.resolve("manager.apk").apply { writeBytes(archive("frameworks/android.apk" to data, "unrelated" to data)) }
            val target = dir.resolve("host.jar")
            JvmHostArchive.restore(archive("Entry.class" to data).inputStream(), apk, target)
            ZipFile(target).use { zip ->
                assertEquals(2, zip.size())
                for (name in listOf("Entry.class", "frameworks/android.apk")) {
                    assertContentEquals(data, zip.getInputStream(zip.getEntry(name)).readBytes())
                    assertEquals(ZipEntry.STORED, zip.getEntry(name).method)
                }
            }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `missing frameworks fail installation`() {
        val dir = kotlin.io.path.createTempDirectory("host-test").toFile()
        try {
            val apk = dir.resolve("manager.apk").apply { writeBytes(archive("unrelated" to byteArrayOf(1))) }
            assertFailsWith<IOException> {
                JvmHostArchive.restore(archive("Entry.class" to byteArrayOf(2)).inputStream(), apk, dir.resolve("host.jar"))
            }
        } finally { dir.deleteRecursively() }
    }
}
