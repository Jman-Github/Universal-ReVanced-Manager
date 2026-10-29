package app.universal.patch.manager.patcher.split

import com.reandroid.apk.APKLogger
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import kotlinx.coroutines.runInterruptible

/** Runs inside the selected patcher/tool host, which owns cancellation and its memory budget. */
internal object ApkEditorMergeRuntime {
    private const val ACTION_MERGE = "merge"
    private const val ACTION_LIST = "list"
    private const val ORDER_PREFIX = "ORDER:"
    fun listMergeOrder(apkDir: File): List<String> = buildList {
        runInProcess(ACTION_LIST, apkDir, null, emptySet(), false) { line ->
            if (line.startsWith(ORDER_PREFIX)) add(line.removePrefix(ORDER_PREFIX).trim())
        }
    }
    suspend fun merge(apkDir: File, outputApk: File, skipModules: Set<String>,
        sortApkEntries: Boolean, onLine: ((String) -> Unit)? = null) = runInterruptible {
        outputApk.delete()
        runInProcess(ACTION_MERGE, apkDir, outputApk, skipModules, sortApkEntries, onLine)
    }
    private fun runInProcess(
        action: String,
        apkDir: File,
        outputApk: File?,
        skipModules: Set<String>,
        sortApkEntries: Boolean,
        onLine: ((String) -> Unit)? = null
    ) {
        val args = ArrayList<String>().apply {
            add(action)
            add(apkDir.absolutePath)
            if (action == ACTION_MERGE) {
                add(outputApk?.absolutePath ?: error("Missing output APK"))
                add(skipModules.joinToString(","))
                add(sortApkEntries.toString())
            }
        }.toTypedArray()

        val outputLines = ArrayDeque<String>(MAX_OUTPUT_LINES)
        fun recordLine(line: String) {
            if (line.isEmpty()) return
            if (outputLines.size == MAX_OUTPUT_LINES) {
                outputLines.removeFirst()
            }
            val normalized = limitLineLength(line)
            outputLines.addLast(normalized)
            if (action == ACTION_LIST || shouldEmit(line)) {
                onLine?.invoke(normalized)
            }
        }

        try {
            runInProcessDirect(args, ::recordLine)
        } catch (error: Throwable) {
            error.stackTraceToString().lineSequence().forEach { recordLine(it.trim()) }
            val output = outputLines.joinToString("\n").trim()
            val message = if (output.isNotBlank()) {
                "APKEditor merge in-process failed. $output"
            } else {
                "APKEditor merge in-process failed."
            }
            throw IOException(message, error)
        }
    }

    private fun runInProcessDirect(args: Array<String>, recordLine: (String) -> Unit) {
        val logger = object : APKLogger {
            override fun logMessage(msg: String?) {
                val line = msg?.trim().orEmpty()
                recordLine(line)
            }

            override fun logError(msg: String?, tr: Throwable?) {
                val line = msg?.trim().orEmpty()
                recordLine(line)
                if (tr != null) {
                    tr.stackTraceToString().lineSequence().forEach { recordLine(it.trim()) }
                }
            }

            override fun logVerbose(msg: String?) = logMessage(msg)
        }
        try {
            ApkEditorMergeProcess.setLogger(logger)
            ApkEditorMergeProcess.main(args)
        } finally {
            ApkEditorMergeProcess.clearLogger()
        }
    }


    private const val MAX_OUTPUT_LINES = 400
    private const val MAX_EVENT_LINE_LENGTH = 2000
    private fun shouldEmit(line: String) = line.isNotBlank() &&
        !listOf("Added:", "Added [", "Loading:", "ORDER:").any { line.trim().startsWith(it) }
    private fun limitLineLength(line: String) = line.take(MAX_EVENT_LINE_LENGTH)
}
