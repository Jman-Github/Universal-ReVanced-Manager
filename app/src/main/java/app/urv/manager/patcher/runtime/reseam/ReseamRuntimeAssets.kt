package app.urv.manager.patcher.runtime.reseam

import android.content.Context
import android.os.Build
import android.os.Process
import app.urv.manager.network.runtime.PatcherRuntimeKind
import app.urv.manager.patcher.runtime.PatcherRuntimePluginRegistry
import app.urv.manager.util.managerStorageContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile

object ReseamRuntimeAssets {
    fun isAvailable(context: Context): Boolean =
        PatcherRuntimePluginRegistry.runtimeFor(PatcherRuntimeKind.RESEAM) != null

    fun ensureRuntimeClassPath(context: Context): File =
        PatcherRuntimePluginRegistry.runtimeFor(PatcherRuntimeKind.RESEAM)?.apkFile
            ?: throw IOException("Reseam runtime plugin is not installed or trusted.")

    /** DexClassLoader needs an extracted, process-compatible JNI library for imported APKs. */
    @Synchronized
    fun ensureNativeLibraryDir(context: Context, apk: File): File {
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        return ZipFile(apk).use { zip ->
            val abi = abis.firstOrNull { zip.getEntry("lib/$it/libreseam-sdk-native.so") != null }
                ?: throw IOException("Reseam plugin has no native library for this process (${abis.joinToString()}).")
            val directory = File(context.managerStorageContext.codeCacheDir, "reseam-native/$hash/$abi")
            check(directory.mkdirs() || directory.isDirectory)
            val library = directory.resolve("libreseam-sdk-native.so")
            if (!library.isFile || library.length() == 0L) {
                val temporary = directory.resolve("libreseam-sdk-native.so.tmp")
                try {
                    zip.getInputStream(zip.getEntry("lib/$abi/libreseam-sdk-native.so")).use { input ->
                        temporary.outputStream().use { output -> input.copyTo(output) }
                    }
                    check(temporary.length() > 0L && temporary.renameTo(library)) {
                        "Failed to extract Reseam native library."
                    }
                } finally {
                    temporary.delete()
                }
                check(library.setReadable(true, true))
                check(library.setWritable(false, false))
            }
            directory
        }
    }
}
