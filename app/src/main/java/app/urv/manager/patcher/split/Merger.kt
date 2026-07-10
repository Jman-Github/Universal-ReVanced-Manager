package app.urv.manager.patcher.split

import android.util.Log
import com.reandroid.apk.APKLogger
import java.io.File
import java.nio.file.Path
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

private class ApkEditorLogger(
    private val onProgress: ((String) -> Unit)? = null,
    private val onLog: ((String) -> Unit)? = null
) : APKLogger {
    private companion object {
        const val TAG = "APKEditor"
        val MERGE_PATTERN = Regex("Merging\\s*:?\\s*(.+)", RegexOption.IGNORE_CASE)
    }

    override fun logMessage(msg: String) {
        Log.i(TAG, msg)
        emitRawLog(msg)
        emitMergeProgress(msg)
    }

    override fun logError(msg: String, tr: Throwable?) {
        Log.e(TAG, msg, tr)
        emitRawLog(
            buildString {
                append(msg)
                tr?.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            }
        )
    }

    override fun logVerbose(msg: String) {
        Log.v(TAG, msg)
        emitRawLog(msg)
        emitMergeProgress(msg)
    }

    private fun emitRawLog(message: String) {
        message.lineSequence()
            .map(String::trimEnd)
            .filter(String::isNotBlank)
            .forEach { onLog?.invoke(it) }
    }

    private fun emitMergeProgress(message: String) {
        val match = MERGE_PATTERN.find(message) ?: return
        val moduleName = match.groupValues.getOrNull(1)?.trim().orEmpty()
        val normalized = normalizeMergeModuleName(moduleName)
        if (normalized.isBlank()) return
        onProgress?.invoke("Merging $normalized")
    }

    private fun normalizeMergeModuleName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return trimmed
        return if (trimmed.lowercase(Locale.ROOT).endsWith(".apk")) {
            trimmed
        } else {
            "$trimmed.apk"
        }
    }
}

internal object Merger {
    suspend fun merge(
        apkDir: Path,
        outputApk: File,
        skipModules: Set<String> = emptySet(),
        onProgress: ((String) -> Unit)? = null,
        onLog: ((String) -> Unit)? = null,
        sortApkEntries: Boolean = false
    ) {
        val mergeContext = coroutineContext
        mergeContext.ensureActive()
        val logger = ApkEditorLogger(onProgress, onLog)
        runInterruptible(Dispatchers.IO) {
            ApkEditorMergeProcess.merge(
                apkDir.toFile(),
                outputApk,
                skipModules,
                sortApkEntries,
                logger,
                Runnable { mergeContext.ensureActive() }
            )
        }
        mergeContext.ensureActive()
    }

    fun listMergeOrder(apkDir: Path): List<String> =
        ApkEditorMergeProcess.listMergeOrder(
            apkDir.toFile(),
            ApkEditorLogger()
        )
}
