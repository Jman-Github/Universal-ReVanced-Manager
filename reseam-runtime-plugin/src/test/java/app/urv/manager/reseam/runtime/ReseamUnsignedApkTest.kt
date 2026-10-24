package app.urv.manager.reseam.runtime

import com.android.apksig.apk.ApkUtils
import com.android.apksig.util.DataSources
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class ReseamUnsignedApkTest {
    @Test fun stripPreservesOriginalZipBytesAndStoredEntries() {
        val directory = Files.createTempDirectory("reseam-signing-test").toFile()
        try {
            val original = directory.resolve("original.apk")
            ZipOutputStream(original.outputStream()).use { zip ->
                val data = byteArrayOf(1, 2, 3, 4)
                val entry = ZipEntry("lib/arm64-v8a/libexample.so").apply {
                    method = ZipEntry.STORED
                    size = data.size.toLong()
                    crc = CRC32().apply { update(data) }.value
                    extra = byteArrayOf(0, 0, 0, 0) // Preserve header padding byte for byte.
                }
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write(ByteArray(300) { it.toByte() })
                zip.closeEntry()
                // Exercise an overlapping central-directory shift spanning several buffers.
                repeat(600) { index ->
                    zip.putNextEntry(ZipEntry("assets/entry-$index.txt"))
                    zip.write(index)
                    zip.closeEntry()
                }
            }
            val bytes = original.readBytes()
            val sections = ApkUtils.findZipSections(DataSources.asDataSource(ByteBuffer.wrap(bytes)))
            val cd = sections.zipCentralDirectoryOffset.toInt()
            val eocd = sections.zipEndOfCentralDirectoryOffset.toInt()
            val block = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(24).putLong(24).put("APK Sig Block 42".toByteArray()).array()
            val signed = directory.resolve("signed.apk")
            val altered = ByteBuffer.wrap(bytes.copyOf()).order(ByteOrder.LITTLE_ENDIAN)
            altered.putInt(eocd + 16, cd + block.size)
            signed.writeBytes(altered.array().copyOfRange(0, cd) + block + altered.array().copyOfRange(cd, bytes.size))
            val output = directory.resolve("unsigned.apk")
            ReseamUnsignedApk.write(signed, output)
            assertArrayEquals(bytes, output.readBytes())
            ReseamUnsignedApk.stripInPlace(signed)
            assertArrayEquals(bytes, signed.readBytes())
            ZipFile(output).use { zip ->
                assertEquals(ZipEntry.STORED, zip.getEntry("lib/arm64-v8a/libexample.so").method)
                assertEquals(300, zip.getInputStream(zip.getEntry("classes.dex")).readBytes().size)
            }
        } finally { directory.deleteRecursively() }
    }
}
