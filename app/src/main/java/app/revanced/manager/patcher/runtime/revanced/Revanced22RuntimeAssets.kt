package app.revanced.manager.patcher.runtime.revanced

import android.content.Context
import app.universal.revanced.manager.BuildConfig
import android.os.Build
import android.system.Os
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

object Revanced22RuntimeAssets {
    private const val RUNTIME_ASSET_NAME = "revanced-runtime-v22.apk"
    private const val OUTPUT_PREFIX = "revanced-runtime-v22"
    private const val DEX_JAR_ENTRY = "assets/main.jar"
    private const val APKEDITOR_JAR_ENTRY = "assets/apkeditor/APKEditor-1.4.7.jar"
    private const val APKEDITOR_MERGE_ENTRY = "assets/apkeditor/apkeditor-merge.jar"

    fun ensureRuntimeApk(context: Context): File {
        val appContext = context.applicationContext ?: context
        val outputDir = File(appContext.codeCacheDir, OUTPUT_PREFIX).apply { mkdirs() }
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
            throw IOException("Failed to extract ReVanced runtime APK from assets.")
        }
        if (!temp.renameTo(output)) {
            temp.delete()
            throw IOException("Failed to finalize ReVanced runtime APK.")
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
                ?: throw IOException("Missing ReVanced runtime dex payload ($DEX_JAR_ENTRY).")
            zip.getInputStream(entry).use { input ->
                temp.outputStream().use { outputStream ->
                    input.copyTo(outputStream)
                }
            }
        }

        if (temp.length() <= 0L) {
            temp.delete()
            throw IOException("Failed to extract ReVanced runtime dex payload.")
        }
        if (!temp.renameTo(jar)) {
            temp.delete()
            throw IOException("Failed to finalize ReVanced runtime dex payload.")
        }
        runCatching {
            jar.setLastModified(runtimeApk.lastModified())
        }

        ensureReadOnly(jar)
        return jar
    }

    fun ensureApkEditorJar(context: Context): File =
        ensureRuntimeAsset(context, APKEDITOR_JAR_ENTRY, "revanced22-apkeditor.jar")

    fun ensureApkEditorMergeJar(context: Context): File =
        ensureRuntimeAsset(context, APKEDITOR_MERGE_ENTRY, "revanced22-apkeditor-merge.jar")

    private fun ensureRuntimeAsset(context: Context, entryName: String, outputName: String): File {
        val runtimeApk = ensureRuntimeApk(context)
        val outputDir = runtimeApk.parentFile
        val output = File(outputDir, outputName)
        if (output.exists() && output.length() > 0L && output.lastModified() >= runtimeApk.lastModified()) {
            ensureReadOnly(output)
            return output
        }
        if (output.exists()) {
            output.setWritable(true, true)
            output.delete()
        }

        val temp = File(outputDir, "${output.name}.tmp")
        ZipFile(runtimeApk).use { zip ->
            val entry = zip.getEntry(entryName)
                ?: throw IOException("Missing ReVanced runtime asset ($entryName).")
            zip.getInputStream(entry).use { input ->
                temp.outputStream().use { outputStream ->
                    input.copyTo(outputStream)
                }
            }
        }

        if (temp.length() <= 0L) {
            temp.delete()
            throw IOException("Failed to extract ReVanced runtime asset ($entryName).")
        }
        if (!temp.renameTo(output)) {
            temp.delete()
            throw IOException("Failed to finalize ReVanced runtime asset ($entryName).")
        }
        runCatching {
            output.setLastModified(runtimeApk.lastModified())
        }

        ensureReadOnly(output)
        return output
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
}
