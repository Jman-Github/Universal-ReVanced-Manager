package app.universal.patch.manager.patcher.runtime.reseam

import app.universal.patch.manager.util.managerStorageContext

import android.content.Context
import app.universal.patch.manager.BuildConfig
import android.os.Build
import android.system.Os
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

object BundledReseamRuntimeAssets {
    private const val RUNTIME_ASSET_NAME = "reseam-runtime.apk"
    private const val OUTPUT_PREFIX = "reseam-runtime"
    private const val DEX_JAR_ENTRY = "assets/main.jar"

    fun isAvailable(context: Context): Boolean {
        
        return hasAsset(normalizeContext(context), RUNTIME_ASSET_NAME)
    }

    // Metadata inspection and patch preparation may request the first extraction together.
    @Synchronized
    fun ensureRuntimeApk(context: Context): File {
        val appContext = normalizeContext(context)
        requireRuntime(appContext)
        val outputDir = File(appContext.managerStorageContext.codeCacheDir, OUTPUT_PREFIX).apply { mkdirs() }
        val output = File(
            outputDir,
            "$OUTPUT_PREFIX-${BuildConfig.VERSION_CODE}-${BuildConfig.BUILD_ID}.apk"
        )
        val appUpdatedAt = appLastUpdateTime(appContext)
        if (output.exists() && output.length() > 0L && output.lastModified() >= appUpdatedAt) {
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
            throw IOException("Failed to extract Reseam runtime APK from assets.")
        }
        if (!temp.renameTo(output)) {
            temp.delete()
            throw IOException("Failed to finalize Reseam runtime APK.")
        }
        runCatching {
            output.setLastModified(maxOf(System.currentTimeMillis(), appUpdatedAt))
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
                ?: throw IOException("Missing Reseam runtime dex payload ($DEX_JAR_ENTRY).")
            zip.getInputStream(entry).use { input ->
                temp.outputStream().use { outputStream ->
                    input.copyTo(outputStream)
                }
            }
        }

        if (temp.length() <= 0L) {
            temp.delete()
            throw IOException("Failed to extract Reseam runtime dex payload.")
        }
        if (!temp.renameTo(jar)) {
            temp.delete()
            throw IOException("Failed to finalize Reseam runtime dex payload.")
        }
        runCatching {
            jar.setLastModified(runtimeApk.lastModified())
        }

        ensureReadOnly(jar)
        return jar
    }

    private fun appLastUpdateTime(context: Context): Long = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)

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
        throw IOException("Reseam runtime is not included in this build.")
    }

    private fun normalizeContext(context: Context): Context = context.applicationContext ?: context

    private fun hasAsset(context: Context, name: String): Boolean = runCatching {
        context.assets.open(name).close()
        true
    }.getOrDefault(false)
}
