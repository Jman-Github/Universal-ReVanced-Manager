package app.universal.patch.manager.patcher.runtime.reseam

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

internal data class ReseamRuntimeFiles(val apkFile: File, val nativeLibraryDirectory: File)

/** Keep code and JNI files alive independently of replaceable managed plugin paths. */
@Synchronized
internal fun prepareReseamRuntimeFiles(apk: File, cacheRoot: File, abis: List<String>): ReseamRuntimeFiles {
    val hash = reseamRuntimeHash(apk)
    val directory = cacheRoot.resolve(hash)
    if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Cannot create Reseam runtime cache")
    // Metadata inspection and disposable workers can prepare the same runtime in different processes.
    FileOutputStream(directory.resolve(".prepare.lock"), true).channel.use { channel ->
        channel.lock().use {
            val snapshot = directory.resolve("runtime.apk")
            if (!snapshot.isFile || snapshot.length() != apk.length() || reseamRuntimeHash(snapshot) != hash) {
                publishReseamRuntimeFile(snapshot, { output -> apk.inputStream().use { it.copyTo(output) } }) {
                    if (reseamRuntimeHash(it) != hash) throw IOException("Reseam runtime changed while preparing it; retry")
                }
            }
            if (!snapshot.setWritable(false, false)) throw IOException("Cannot protect Reseam runtime snapshot")
            return ZipFile(snapshot).use { zip ->
                val abi = abis.firstOrNull { zip.getEntry("lib/$it/libreseam-sdk-native.so") != null }
                    ?: throw IOException("Reseam runtime has no native library for this process (${abis.joinToString()}).")
                val nativeDirectory = directory.resolve(abi)
                if (!nativeDirectory.mkdirs() && !nativeDirectory.isDirectory) {
                    throw IOException("Cannot create Reseam native cache")
                }
                val entry = zip.getEntry("lib/$abi/libreseam-sdk-native.so")
                if (entry.size <= 0) throw IOException("Reseam native library is empty")
                val library = nativeDirectory.resolve("libreseam-sdk-native.so")
                if (!library.isFile || library.length() != entry.size) {
                    publishReseamRuntimeFile(library, { output -> zip.getInputStream(entry).use { it.copyTo(output) } })
                }
                ReseamRuntimeFiles(snapshot, nativeDirectory)
            }
        }
    }
}

private fun reseamRuntimeHash(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

private fun publishReseamRuntimeFile(
    target: File,
    write: (OutputStream) -> Unit,
    validate: (File) -> Unit = {}
) {
    val temporary = File.createTempFile(target.name, ".tmp", target.parentFile)
    try {
        temporary.outputStream().use { output ->
            // Android 14+ requires dynamically loaded code to be read-only before writing it.
            if (!temporary.setReadable(true, true) || !temporary.setWritable(false, false)) {
                throw IOException("Cannot protect Reseam runtime cache file")
            }
            write(output)
        }
        validate(temporary)
        if (target.exists() && !target.setWritable(true, true)) throw IOException("Cannot repair Reseam runtime cache")
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        temporary.setWritable(true, true)
        temporary.delete()
    }
}
