package app.urv.manager.patcher.runtime.revanced

import android.content.Context
import app.universal.revanced.manager.BuildConfig
import android.os.Build
import android.system.Os
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile

object Revanced21RuntimeAssets {
    private const val RUNTIME_ASSET_NAME = "revanced-runtime-v21.apk"
    private const val OUTPUT_PREFIX = "revanced-runtime-v21"
    private const val DEX_JAR_ENTRY = "assets/main.jar"

    fun isAvailable(context: Context): Boolean {
        if (!BuildConfig.HAS_REVANCED_V21_RUNTIME) return false
        return hasAsset(normalizeContext(context), RUNTIME_ASSET_NAME)
    }

    fun ensureRuntimeApk(context: Context): File {
        val appContext = normalizeContext(context)
        requireRuntime(appContext)
        val outputDir = File(appContext.codeCacheDir, OUTPUT_PREFIX).apply { mkdirs() }
        val assetHash = runtimeAssetHash(appContext)
        val output = File(
            outputDir,
            "$OUTPUT_PREFIX-${BuildConfig.VERSION_CODE}-${assetHash}.apk"
        )
        if (output.exists() && output.length() > 0L) {
            ensureReadOnly(output)
            return output
        }
        if (output.exists()) {
            output.setWritable(true, true)
            output.delete()
        }

        val temp = File(outputDir, "${output.name}.tmp")
        appContext.assets.open(RUNTIME_ASSET_NAME).use { input ->
            temp.outputStream().use { outputStream ->
                input.copyTo(outputStream)
            }
        }
        if (temp.length() <= 0L) {
            temp.delete()
            throw IOException("Failed to extract ReVanced v21 runtime APK from assets.")
        }
        if (!temp.renameTo(output)) {
            temp.delete()
            throw IOException("Failed to finalize ReVanced v21 runtime APK.")
        }

        ensureReadOnly(output)

        val baseName = output.nameWithoutExtension
        outputDir.listFiles { file ->
            file.name.startsWith(OUTPUT_PREFIX) && !file.name.startsWith(baseName)
        }?.forEach { it.delete() }

        return output
    }

    fun ensureRuntimeClassPath(context: Context): File {
        val runtimeApk = ensureRuntimeApk(context)
        if (hasDexEntry(runtimeApk)) {
            return runtimeApk
        }

        val jar = File(runtimeApk.parentFile, "${runtimeApk.nameWithoutExtension}.jar")
        if (jar.exists() && jar.length() > 0L && jar.lastModified() >= runtimeApk.lastModified()) {
            ensureReadOnly(jar)
            return jar
        }
        if (jar.exists()) {
            jar.setWritable(true, true)
            jar.delete()
        }

        val temp = File(runtimeApk.parentFile, "${jar.name}.tmp")
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
        return jar
    }

    private fun runtimeAssetHash(context: Context): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(RUNTIME_ASSET_NAME).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
            .take(16)
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

    private fun requireRuntime(context: Context) {
        if (isAvailable(context)) return
        throw IOException("ReVanced v21 runtime is not included in this ${BuildConfig.URV_BUILD_PROFILE} build.")
    }

    private fun normalizeContext(context: Context): Context = context.applicationContext ?: context

    private fun hasAsset(context: Context, name: String): Boolean = runCatching {
        context.assets.open(name).close()
        true
    }.getOrDefault(false)
}
