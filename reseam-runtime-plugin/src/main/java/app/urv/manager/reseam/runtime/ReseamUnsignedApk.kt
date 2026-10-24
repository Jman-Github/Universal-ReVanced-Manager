package app.urv.manager.reseam.runtime

import com.android.apksig.apk.ApkUtils
import com.android.apksig.util.DataSources
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.zip.ZipFile

internal object ReseamUnsignedApk {
    /**
     * SDK 0.19 strips v1 signatures and writes a v2 block. Remove just that block:
     * rewriting ZIP entries would disturb compressed bytes and native-library alignment.
     * URV owns the final signing identity, including skip-signing and root installs.
     * Only for disposable SDK output: shift its ZIP directory without another APK copy.
     */
    fun stripInPlace(file: File) {
        requireNoV1Signature(file)
        RandomAccessFile(file, "rw").use { target ->
            val sections = ApkUtils.findZipSections(DataSources.asDataSource(target))
            val block = ApkUtils.findApkSigningBlock(DataSources.asDataSource(target), sections)
            val end = sections.zipEndOfCentralDirectory
            ApkUtils.setZipEocdCentralDirectoryOffset(end, block.startOffset)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var readOffset = sections.zipCentralDirectoryOffset
            var writeOffset = block.startOffset
            var remaining = sections.zipCentralDirectorySizeBytes
            // Read forwards before writing earlier bytes; overlapping ranges are safe.
            while (remaining > 0) {
                check(!Thread.currentThread().isInterrupted) { "Unsigned conversion interrupted." }
                target.seek(readOffset)
                val count = target.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                check(count > 0) { "Truncated Reseam output." }
                target.seek(writeOffset)
                target.write(buffer, 0, count)
                readOffset += count
                writeOffset += count
                remaining -= count
            }
            target.seek(writeOffset)
            while (end.hasRemaining()) target.write(end.get().toInt())
            target.setLength(target.filePointer)
        }
    }

    private fun requireNoV1Signature(file: File) {
        ZipFile(file).use { zip ->
            require(zip.entries().asSequence().none { entry ->
                val name = entry.name.uppercase(Locale.ROOT)
                name.startsWith("META-INF/") && (
                    name == "META-INF/MANIFEST.MF" || name.endsWith(".SF") ||
                        name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC")
                    )
            }) { "Reseam output unexpectedly contains v1 signatures." }
        }
    }

    fun write(input: File, output: File) {
        requireNoV1Signature(input)
        RandomAccessFile(input, "r").use { source ->
            val apk = DataSources.asDataSource(source)
            val sections = ApkUtils.findZipSections(apk)
            val block = ApkUtils.findApkSigningBlock(apk, sections)
            val end = sections.zipEndOfCentralDirectory
            ApkUtils.setZipEocdCentralDirectoryOffset(end, block.startOffset)
            RandomAccessFile(output, "rw").use { target ->
                target.setLength(0)
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                fun copy(offset: Long, length: Long) {
                    source.seek(offset)
                    var remaining = length
                    while (remaining > 0) {
                        val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        check(count > 0) { "Truncated Reseam output." }
                        target.write(buffer, 0, count)
                        remaining -= count
                    }
                }
                copy(0, block.startOffset)
                copy(sections.zipCentralDirectoryOffset, sections.zipCentralDirectorySizeBytes)
                while (end.hasRemaining()) target.write(end.get().toInt())
            }
        }
    }
}
