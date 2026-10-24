/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */
package app.urv.manager.patcher.runtime.jvm

// Code adapted from Morphe Manager PR #1215; see third-party/NOTICE.txt.

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Restores the built-in hosts' shared ARSCLib resources from the manager APK. */
internal object JvmHostArchive {
    fun restore(host: InputStream, apk: File, destination: File) {
        ZipOutputStream(destination.outputStream().buffered()).use { output ->
            ZipInputStream(host).use { input ->
                generateSequence { input.nextEntry }.filter { !it.isDirectory }.forEach { entry ->
                    output.writeStored(entry.name, input.readBytes())
                }
            }
            ZipFile(apk).use { zip ->
                val frameworks = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("frameworks/") }.toList()
                if (frameworks.isEmpty()) throw IOException("The manager APK ships no Android frameworks")
                frameworks.forEach { entry ->
                    output.writeStored(entry.name, zip.getInputStream(entry).use { it.readBytes() })
                }
            }
        }
    }

    private fun ZipOutputStream.writeStored(name: String, content: ByteArray) {
        putNextEntry(ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = content.size.toLong()
            compressedSize = size
            crc = CRC32().apply { update(content) }.value
            time = 0
        })
        write(content)
        closeEntry()
    }
}
