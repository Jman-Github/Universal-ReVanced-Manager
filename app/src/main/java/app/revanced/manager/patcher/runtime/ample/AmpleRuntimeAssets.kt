package app.revanced.manager.patcher.runtime.ample

import android.content.Context
import app.universal.revanced.manager.BuildConfig
import android.os.Build
import android.system.Os
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

object AmpleRuntimeAssets {
    private const val RUNTIME_ASSET_NAME = "ample-runtime.apk"
    private const val OUTPUT_PREFIX = "ample-runtime"
    private const val DEX_JAR_ENTRY = "assets/main.jar"
    private const val APKEDITOR_JAR_ENTRY = "assets/apkeditor/APKEditor-1.4.7.jar"
    private const val APKEDITOR_MERGE_ENTRY = "assets/apkeditor/apkeditor-merge.jar"

    fun ensureRuntimeApk(context: Context): File {
        val appContext = context.applicationContext
        val outputDir = File(appContext.codeCacheDir, OUTPUT_PREFIX).apply { mkdirs() }
        val output = File(
            outputDir,
            "$OUTPUT_PREFIX-${BuildConfig.VERSION_CODE}-${BuildConfig.BUILD_ID}.apk"
        )
        if (output.exists() && output.length() > 0L) {
            ensureReadOnly(output)
            return output
        }

        val temp = File(outputDir, "${output.name}.tmp")
        appContext.assets.open(RUNTIME_ASSET_NAME).use { input ->
            temp.outputStream().use { outputStream ->
                input.copyTo(outputStream)
            }
        }
        if (temp.length() <= 0L) {
            temp.delete()
            throw IOException("Failed to extract Ample runtime APK from assets.")
        }
        if (!temp.renameTo(output)) {
            temp.delete()
            throw IOException("Failed to finalize Ample runtime APK.")
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
        if (jar.exists() && jar.length() > 0L) {
            ensureReadOnly(jar)
            return jar
        }

        val temp = File(runtimeApk.parentFile, "${jar.name}.tmp")
        ZipFile(runtimeApk).use { zip ->
            val entry = zip.getEntry(DEX_JAR_ENTRY)
                ?: throw IOException("Missing Ample runtime dex payload ($DEX_JAR_ENTRY).")
            zip.getInputStream(entry).use { input ->
                temp.outputStream().use { outputStream ->
                    input.copyTo(outputStream)
                }
            }
        }

        if (temp.length() <= 0L) {
            temp.delete()
            throw IOException("Failed to extract Ample runtime dex payload.")
        }
        if (!temp.renameTo(jar)) {
            temp.delete()
            throw IOException("Failed to finalize Ample runtime dex payload.")
        }

        ensureReadOnly(jar)
        return jar
    }

    fun ensureApkEditorJar(context: Context): File =
        ensureRuntimeAsset(context, APKEDITOR_JAR_ENTRY, "apkeditor.jar")

    fun ensureApkEditorMergeJar(context: Context): File =
        ensureRuntimeAsset(context, APKEDITOR_MERGE_ENTRY, "apkeditor-merge.jar")

    private fun ensureRuntimeAsset(context: Context, entryName: String, outputName: String): File {
        val runtimeApk = ensureRuntimeApk(context)
        val outputDir = runtimeApk.parentFile
        val output = File(outputDir, outputName)
        if (output.exists() && output.length() > 0L) {
            ensureReadOnly(output)
            return output
        }

        val temp = File(outputDir, "${output.name}.tmp")
        ZipFile(runtimeApk).use { zip ->
            val entry = zip.getEntry(entryName)
                ?: throw IOException("Missing Ample runtime asset ($entryName).")
            zip.getInputStream(entry).use { input ->
                temp.outputStream().use { outputStream ->
                    input.copyTo(outputStream)
                }
            }
        }

        if (temp.length() <= 0L) {
            temp.delete()
            throw IOException("Failed to extract Ample runtime asset ($entryName).")
        }
        if (!temp.renameTo(output)) {
            temp.delete()
            throw IOException("Failed to finalize Ample runtime asset ($entryName).")
        }

        ensureReadOnly(output)
        return output
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
}
