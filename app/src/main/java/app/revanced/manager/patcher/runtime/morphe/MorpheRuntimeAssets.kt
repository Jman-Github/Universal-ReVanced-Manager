package app.revanced.manager.patcher.runtime.morphe

import android.content.Context
import app.universal.revanced.manager.BuildConfig
import android.os.Build
import android.system.Os
import java.io.File
import java.io.IOException

object MorpheRuntimeAssets {
    private const val RUNTIME_ASSET_NAME = "morphe-runtime.apk"
    private const val OUTPUT_PREFIX = "morphe-runtime"

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
            throw IOException("Failed to extract Morphe runtime APK from assets.")
        }
        if (!temp.renameTo(output)) {
            temp.delete()
            throw IOException("Failed to finalize Morphe runtime APK.")
        }

        ensureReadOnly(output)

        outputDir.listFiles { file ->
            file.name.startsWith(OUTPUT_PREFIX) && file.name != output.name
        }?.forEach { it.delete() }

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
}
