package app.revanced.manager.patcher.aapt

import android.content.Context
import app.revanced.manager.patcher.LibraryResolver
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import android.os.Build.SUPPORTED_ABIS as DEVICE_ABIS

object AaptModern : LibraryResolver() {
    private val WORKING_ABIS = setOf("arm64-v8a", "x86", "x86_64", "armeabi-v7a")

    fun supportsDevice() = (DEVICE_ABIS intersect WORKING_ABIS).isNotEmpty()

    fun binary(context: Context): File? {
        val native = findLibraryExact(context, "libaapt2_modern.so")
        if (native?.exists() == true && isAndroidRunnableElf(native)) return native

        val extracted = extractFromInstalledApk(context)
        if (extracted?.exists() == true && isAndroidRunnableElf(extracted)) return extracted

        return null
    }

    private fun extractFromInstalledApk(context: Context): File? {
        val codePaths = buildList {
            context.applicationInfo.sourceDir?.let(::add)
            context.applicationInfo.splitSourceDirs?.let { addAll(it) }
        }.map(::File).filter(File::exists)

        if (codePaths.isEmpty()) return null

        val abiCandidates = DEVICE_ABIS.asSequence()
            .filter { it in WORKING_ABIS }
            .plus(sequenceOf("armeabi"))
            .distinct()
            .toList()

        val outputDir = context.codeCacheDir.resolve("aapt2")
        outputDir.mkdirs()
        val output = outputDir.resolve("libaapt2_modern.so")

        for (codePath in codePaths) {
            runCatching {
                ZipFile(codePath).use { zip ->
                    for (abi in abiCandidates) {
                        val entry = zip.getEntry("lib/$abi/libaapt2_modern.so") ?: continue
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(output).use { out ->
                                input.copyTo(out)
                            }
                        }
                        output.setReadable(true, true)
                        output.setExecutable(true, true)
                        output.setWritable(true, true)
                        return output
                    }
                }
            }
        }

        return null
    }

    private fun isAndroidRunnableElf(file: File): Boolean = runCatching {
        val bytes = file.inputStream().use { input ->
            val buffer = ByteArray(256)
            var totalRead = 0
            while (totalRead < buffer.size) {
                val count = input.read(buffer, totalRead, buffer.size - totalRead)
                if (count <= 0) break
                totalRead += count
            }
            buffer.copyOf(totalRead)
        }
        if (bytes.size < 64) return@runCatching false
        if (bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
        ) {
            return@runCatching false
        }

        val is64 = bytes[4].toInt() == 2
        val little = bytes[5].toInt() == 1
        val buffer = ByteBuffer.wrap(bytes).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        val ePhOff = if (is64) buffer.getLong(32).toInt() else buffer.getInt(28)
        val ePhEntSize = (if (is64) buffer.getShort(54) else buffer.getShort(42)).toInt() and 0xFFFF
        val ePhNum = (if (is64) buffer.getShort(56) else buffer.getShort(44)).toInt() and 0xFFFF
        if (ePhOff <= 0 || ePhEntSize <= 0 || ePhNum <= 0) return@runCatching true

        val PT_INTERP = 3
        repeat(ePhNum) { idx ->
            val off = ePhOff + idx * ePhEntSize
            if (off + ePhEntSize > bytes.size) return@repeat
            val pType = buffer.getInt(off)
            if (pType != PT_INTERP) return@repeat
            val pOffset = if (is64) buffer.getLong(off + 8).toInt() else buffer.getInt(off + 4)
            val pFileSize = if (is64) buffer.getLong(off + 32).toInt() else buffer.getInt(off + 16)
            if (pOffset < 0 || pFileSize <= 0 || pOffset + pFileSize > bytes.size) return@repeat
            val interp = bytes.copyOfRange(pOffset, pOffset + pFileSize)
                .takeWhile { it != 0.toByte() }
                .toByteArray()
                .toString(Charsets.UTF_8)
            // Reject glibc Linux loaders that do not exist on Android.
            if (interp.contains("ld-linux")) return@runCatching false
            // Accept Android loader names or no-interpreter binaries.
            return@runCatching interp.isBlank() || interp.contains("/system/bin/linker")
        }
        true
    }.getOrDefault(false)
}
