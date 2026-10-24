/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.urv.manager.patcher.runtime.jvm

// Code adapted from Morphe Manager PR #1215; see third-party/NOTICE.txt.

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * Rebuilds the image of a runtime from the image the app ships whole and the delta to it, which
 * the build makes for every ABI whose image differs (buildSrc/ImageDelta.kt). The delta is a run
 * of parts, each either a range of the base or bytes of its own, and ends with the size and CRC-32
 * of the image, so a broken one fails here rather than in the JVM.
 */
internal object JvmImageDelta {
    private const val OP_COPY = 0
    private const val OP_DATA = 1
    private const val OP_END = 2

    fun apply(base: RandomAccessFile, delta: InputStream, output: OutputStream) {
        val input = DataInputStream(delta)
        val crc = CRC32()
        val buffer = ByteArray(1 shl 16)
        var written = 0L

        fun write(length: Int, read: (ByteArray, Int) -> Unit) {
            if (length < 0) throw IOException("Negative part length in the image delta")
            var left = length
            while (left > 0) {
                val chunk = minOf(left, buffer.size)
                read(buffer, chunk)
                output.write(buffer, 0, chunk)
                crc.update(buffer, 0, chunk)
                written += chunk
                left -= chunk
            }
        }

        while (true) {
            when (val op = input.readByte().toInt()) {
                OP_COPY -> {
                    val offset = input.readLong()
                    val length = input.readInt()
                    if (offset < 0 || offset > base.length() || length < 0 || length.toLong() > base.length() - offset) {
                        throw IOException("Image delta range is outside the base image")
                    }
                    base.seek(offset)
                    write(length) { bytes, count -> base.readFully(bytes, 0, count) }
                }

                OP_DATA -> write(input.readInt()) { bytes, length -> input.readFully(bytes, 0, length) }

                OP_END -> {
                    val size = input.readLong()
                    val checksum = input.readInt()
                    if (size != written || checksum != crc.value.toInt()) {
                        throw IOException("The rebuilt image does not match the one the build made")
                    }
                    if (input.read() != -1) throw IOException("Trailing data in the image delta")
                    return
                }

                else -> throw IOException("Unknown part $op in the image delta")
            }
        }
    }
}
