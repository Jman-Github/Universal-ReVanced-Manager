package app.urv.manager.patcher.runtime.revanced

import android.content.Context
import android.os.Build
import android.system.Os
import java.io.File
import java.io.IOException

object Revanced22RuntimeAssets {
    private const val OUTPUT_PREFIX = "revanced-runtime-v22"
    private const val APKEDITOR_JAR_ASSET = "apkeditor/APKEditor-1.4.7.jar"
    private const val APKEDITOR_MERGE_ASSET = "apkeditor/apkeditor-merge.jar"

    fun isAvailable(context: Context): Boolean = true

    fun ensureRuntimeClassPath(context: Context): File =
        File(normalizeContext(context).applicationInfo.sourceDir)

    fun ensureApkEditorJar(context: Context): File =
        ensureAsset(normalizeContext(context), APKEDITOR_JAR_ASSET, "revanced22-apkeditor.jar")

    fun ensureApkEditorMergeJar(context: Context): File =
        ensureAsset(
            normalizeContext(context),
            APKEDITOR_MERGE_ASSET,
            "revanced22-apkeditor-merge.jar"
        )

    private fun ensureAsset(context: Context, assetName: String, outputName: String): File {
        val outputDir = File(context.codeCacheDir, OUTPUT_PREFIX).apply { mkdirs() }
        val appApk = ensureRuntimeClassPath(context)
        val output = File(outputDir, outputName)
        if (output.exists() && output.length() > 0L && output.lastModified() >= appApk.lastModified()) {
            ensureReadOnly(output)
            return output
        }
        if (output.exists()) {
            output.setWritable(true, true)
            output.delete()
        }

        val temp = File(outputDir, "${output.name}.tmp")
        context.assets.open(assetName).use { input ->
            temp.outputStream().use { outputStream ->
                input.copyTo(outputStream)
            }
        }
        if (temp.length() <= 0L) {
            temp.delete()
            throw IOException("Failed to extract ReVanced v22 runtime asset ($assetName).")
        }
        if (!temp.renameTo(output)) {
            temp.delete()
            throw IOException("Failed to finalize ReVanced v22 runtime asset ($assetName).")
        }
        runCatching {
            output.setLastModified(appApk.lastModified())
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

    private fun normalizeContext(context: Context): Context = context.applicationContext ?: context
}
