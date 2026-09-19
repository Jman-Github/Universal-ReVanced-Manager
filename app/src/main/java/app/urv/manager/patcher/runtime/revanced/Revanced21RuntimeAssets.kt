package app.urv.manager.patcher.runtime.revanced

import android.content.Context
import app.urv.manager.network.runtime.PatcherRuntimeKind
import app.urv.manager.patcher.runtime.PatcherRuntimePluginRegistry
import android.os.Build
import android.system.Os
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile

object Revanced21RuntimeAssets {
    private const val DEX_JAR_ENTRY = "assets/main.jar"
    private const val OUTPUT_JAR_PREFIX = "revanced-v21-runtime-"

    fun isAvailable(context: Context): Boolean {
        return PatcherRuntimePluginRegistry.runtimeFor(PatcherRuntimeKind.REVANCED_V21) != null
    }

    fun ensureRuntimeApk(context: Context): File {
        return PatcherRuntimePluginRegistry.runtimeFor(PatcherRuntimeKind.REVANCED_V21)?.apkFile
            ?: throw IOException("ReVanced v21 runtime plugin is not installed or trusted.")
    }

    fun ensureRuntimeClassPath(context: Context): File {
        val runtimeApk = ensureRuntimeApk(context)
        if (hasDexEntry(runtimeApk)) {
            return runtimeApk
        }

        val outputDir = runtimeOutputDir(context)
        val jar = File(outputDir, "$OUTPUT_JAR_PREFIX${sha256(runtimeApk)}.jar")
        if (jar.exists() && jar.length() > 0L && jar.lastModified() >= runtimeApk.lastModified()) {
            ensureReadOnly(jar)
            deleteStaleRuntimeJars(outputDir, jar)
            return jar
        }
        if (jar.exists()) {
            jar.setWritable(true, true)
            jar.delete()
        }

        val temp = File(outputDir, "${jar.name}.tmp")
        ZipFile(runtimeApk).use { zip ->
            val entry = zip.getEntry(DEX_JAR_ENTRY)
                ?: throw IOException("Missing ReVanced v21 runtime dex payload ($DEX_JAR_ENTRY).")
            zip.getInputStream(entry).use { input ->
                temp.outputStream().use { outputStream ->
                    input.copyTo(outputStream)
                }
            }
        }

        if (temp.length() <= 0L) {
            temp.delete()
            throw IOException("Failed to extract ReVanced v21 runtime dex payload.")
        }
        if (!temp.renameTo(jar)) {
            temp.delete()
            throw IOException("Failed to finalize ReVanced v21 runtime dex payload.")
        }
        runCatching {
            jar.setLastModified(runtimeApk.lastModified())
        }

        ensureReadOnly(jar)
        deleteStaleRuntimeJars(outputDir, jar)
        return jar
    }

    private fun ensureReadOnly(file: File) {
        file.setReadable(true, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            runCatching { Os.chmod(file.absolutePath, 0b100100100) }
        }
    }

    private fun hasDexEntry(file: File): Boolean = runCatching {
        ZipFile(file).use { zip ->
            zip.entries().asSequence().any { entry ->
                entry.name.startsWith("classes") && entry.name.endsWith(".dex")
            }
        }
    }.getOrDefault(false)

    private fun runtimeOutputDir(context: Context): File =
        File(normalizeContext(context).codeCacheDir, "revanced-v21-runtime-plugin").apply { mkdirs() }

    private fun normalizeContext(context: Context): Context = context.applicationContext ?: context

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1)
        }
    }

    private fun deleteStaleRuntimeJars(outputDir: File, current: File) {
        outputDir.listFiles { file ->
            file.isFile &&
                file.name.startsWith(OUTPUT_JAR_PREFIX) &&
                file.name.endsWith(".jar") &&
                file.name != current.name
        }?.forEach { file ->
            file.setWritable(true, true)
            file.delete()
        }
    }
}
