package app.revanced.manager.patcher.split

import com.reandroid.apk.APKLogger
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal object ApkEditorMergeRuntime {
    private const val ACTION_MERGE = "merge"
    private const val ACTION_LIST = "list"
    private const val ORDER_PREFIX = "ORDER:"
    private const val MERGE_CLASS = "app.revanced.manager.patcher.split.ApkEditorMergeProcess"

    @Volatile
    private var apkEditorJarPath: String? = null

    @Volatile
    private var mergeJarPath: String? = null

    @Volatile
    private var propOverridePath: String? = null

    @Volatile
    private var memoryLimitMb: Int? = null

    @Volatile
    private var appProcessPath: String? = null

    @Volatile
    private var androidDataDir: String? = null

    @Volatile
    private var runtimeClassPath: String? = null

    private val activeProcessLock = Any()

    @Volatile
    private var activeProcess: Process? = null

    fun configure(
        apkEditorJar: String?,
        mergeJar: String?,
        propOverridePath: String? = null,
        memoryLimitMb: Int? = null,
        appProcessPath: String? = null,
        androidDataDir: String? = null,
        runtimeClassPath: String? = null
    ) {
        apkEditorJarPath = apkEditorJar
        mergeJarPath = mergeJar
        this.propOverridePath = propOverridePath
        this.memoryLimitMb = memoryLimitMb
        this.appProcessPath = appProcessPath
        this.androidDataDir = androidDataDir
        this.runtimeClassPath = runtimeClassPath
    }

    fun cancelActiveExecution() {
        val process = synchronized(activeProcessLock) { activeProcess } ?: return
        destroyProcess(process)
    }

    private fun resolveClasspath(): String {
        val apkEditor = apkEditorJarPath?.takeIf { it.isNotBlank() } ?: ""
        val mergeJar = mergeJarPath?.takeIf { it.isNotBlank() } ?: ""
        if (apkEditor.isBlank() || mergeJar.isBlank()) {
            throw IllegalStateException("ApkEditor merge runtime is not configured")
        }
        val apkFile = File(apkEditor)
        val mergeFile = File(mergeJar)
        if (!apkFile.exists() || !mergeFile.exists()) {
            throw IllegalStateException("ApkEditor merge runtime assets missing")
        }
        val runtimePath = runtimeClassPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf(File::exists)
            ?.absolutePath
        return buildList {
            if (!runtimePath.isNullOrBlank()) add(runtimePath)
            add(mergeFile.absolutePath)
            add(apkFile.absolutePath)
        }
            .joinToString(File.pathSeparator)
    }

    fun listMergeOrder(apkDir: File): List<String> {
        val lines = ArrayList<String>()
        try {
            runInProcess(
                action = ACTION_LIST,
                apkDir = apkDir,
                outputApk = null,
                skipModules = emptySet(),
                sortApkEntries = false
            ) { line ->
                lines.add(line)
            }
        } catch (error: Throwable) {
            runProcess(
                action = ACTION_LIST,
                apkDir = apkDir,
                outputApk = null,
                skipModules = emptySet(),
                sortApkEntries = false,
                onLine = { line ->
                lines.add(line)
            })
        }
        return lines
            .filter { it.startsWith(ORDER_PREFIX) }
            .map { it.removePrefix(ORDER_PREFIX).trim() }
            .filter { it.isNotBlank() }
    }

    suspend fun merge(
        apkDir: File,
        outputApk: File,
        skipModules: Set<String>,
        sortApkEntries: Boolean,
        onLine: ((String) -> Unit)? = null
    ) = coroutineScope {
        runCatching { outputApk.delete() }
        val cancellationWatcher = launch(Dispatchers.IO) {
            try {
                awaitCancellation()
            } finally {
                cancelActiveExecution()
            }
        }
        try {
            runMergeProcess(
                apkDir = apkDir,
                outputApk = outputApk,
                skipModules = skipModules,
                sortApkEntries = sortApkEntries,
                onLine = onLine
            )
        } finally {
            cancellationWatcher.cancel()
        }
    }

    private suspend fun runMergeProcess(
        apkDir: File,
        outputApk: File,
        skipModules: Set<String>,
        sortApkEntries: Boolean,
        onLine: ((String) -> Unit)? = null,
        heapLimitMb: Int? = memoryLimitMb,
        allowHeapFallback: Boolean = true
    ): Unit = coroutineScope {
        val classpath = resolveClasspath()
        val appProcess = appProcessPath?.takeIf { it.isNotBlank() } ?: resolveAppProcessBin()
        val androidData = androidDataDir?.takeIf { it.isNotBlank() }
        if (androidData != null) {
            val dataDir = File(androidData)
            dataDir.mkdirs()
            File(dataDir, "dalvik-cache").mkdirs()
            File(dataDir, "cache").mkdirs()
        }
        val args = ArrayList<String>().apply {
            add(appProcess)
            heapLimitMb?.takeIf { it > 0 && propOverridePath.isNullOrBlank() }?.let { limit ->
                add("-Xmx${limit}m")
                add("-XX:HeapGrowthLimit=${limit}m")
            }
            add("-Djava.io.tmpdir=${apkDir.parentFile?.absolutePath ?: apkDir.absolutePath}")
            add("/")
            add("--nice-name=${MERGE_CLASS}")
            add(MERGE_CLASS)
            add(ACTION_MERGE)
            add(apkDir.absolutePath)
            add(outputApk.absolutePath)
            add(skipModules.joinToString(","))
            add(sortApkEntries.toString())
        }

        val process = withContext(Dispatchers.IO) {
            ProcessBuilder(args)
                .redirectErrorStream(true)
                .apply {
                    val env = environment()
                    env["CLASSPATH"] = classpath
                    if (androidData != null) {
                        env["ANDROID_DATA"] = androidData
                        env["ANDROID_CACHE"] = File(androidData, "cache").absolutePath
                    }
                    val overridePath = propOverridePath
                    val limitMb = heapLimitMb
                    if (!overridePath.isNullOrBlank() && limitMb != null) {
                        val limit = "${limitMb}M"
                        env["LD_PRELOAD"] = overridePath
                        env["PROP_dalvik.vm.heapgrowthlimit"] = limit
                        env["PROP_dalvik.vm.heapsize"] = limit
                    }
                }
                .start()
        }
        synchronized(activeProcessLock) {
            activeProcess = process
        }

        val outputLines = ArrayDeque<String>(MAX_OUTPUT_LINES)
        val outputRef = AtomicReference(outputLines)
        var completed = false
        val stdoutJob = launch(Dispatchers.IO) {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val buffer = outputRef.get()
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        if (buffer.size == MAX_OUTPUT_LINES) {
                            buffer.removeFirst()
                        }
                        buffer.addLast(limitLineLength(trimmed))
                        if (shouldEmit(trimmed)) {
                            onLine?.invoke(limitLineLength(trimmed))
                        }
                    }
                }
            }
        }

        try {
            val exitCode = try {
                runInterruptible(Dispatchers.IO) { process.waitFor() }
            } catch (error: CancellationException) {
                destroyProcess(process)
                throw error
            }

            withContext(NonCancellable) {
                runCatching { stdoutJob.join() }
            }

            if (exitCode != 0) {
                if (allowHeapFallback && exitCode == 137) {
                    val configured = heapLimitMb ?: memoryLimitMb ?: 0
                    if (configured > SAFE_RETRY_HEAP_MB) {
                        onLine?.invoke(
                            "APKEditor: merge process killed (137), retrying with ${SAFE_RETRY_HEAP_MB}MB heap."
                        )
                        runMergeProcess(
                            apkDir = apkDir,
                            outputApk = outputApk,
                            skipModules = skipModules,
                            sortApkEntries = sortApkEntries,
                            onLine = onLine,
                            heapLimitMb = SAFE_RETRY_HEAP_MB,
                            allowHeapFallback = false
                        )
                        return@coroutineScope
                    }
                }
                val output = outputRef.get().joinToString("\n").trim()
                throw IOException("APKEditor merge process failed ($exitCode). ${output.takeIf { it.isNotBlank() } ?: ""}")
            }
            completed = true
        } finally {
            synchronized(activeProcessLock) {
                if (activeProcess === process) {
                    activeProcess = null
                }
            }
            withContext(NonCancellable) {
                destroyProcess(process)
                runCatching { process.outputStream.close() }
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                stdoutJob.cancel()
                runCatching { stdoutJob.join() }
            }
            if (!completed) {
                runCatching { outputApk.delete() }
            }
        }
    }

    private fun canRunInProcess(): Boolean = hasDirectApkEditorClasses()

    private fun hasDirectApkEditorClasses(): Boolean = runCatching {
        val loader = ApkEditorMergeProcess::class.java.classLoader
            ?: return@runCatching false
        loader.loadClass("com.reandroid.apk.ApkBundle")
        true
    }.getOrDefault(false)

    private fun runProcess(
        action: String,
        apkDir: File,
        outputApk: File?,
        skipModules: Set<String>,
        sortApkEntries: Boolean,
        onLine: ((String) -> Unit)? = null,
        heapLimitMb: Int? = memoryLimitMb,
        allowHeapFallback: Boolean = true
    ) {
        val classpath = resolveClasspath()
        val appProcess = appProcessPath?.takeIf { it.isNotBlank() } ?: resolveAppProcessBin()
        val androidData = androidDataDir?.takeIf { it.isNotBlank() }
        if (androidData != null) {
            val dataDir = File(androidData)
            dataDir.mkdirs()
            File(dataDir, "dalvik-cache").mkdirs()
            File(dataDir, "cache").mkdirs()
        }
        val args = ArrayList<String>().apply {
            add(appProcess)
            heapLimitMb?.takeIf { it > 0 && propOverridePath.isNullOrBlank() }?.let { limit ->
                add("-Xmx${limit}m")
                add("-XX:HeapGrowthLimit=${limit}m")
            }
            add("-Djava.io.tmpdir=${apkDir.parentFile?.absolutePath ?: apkDir.absolutePath}")
            add("/")
            add("--nice-name=${MERGE_CLASS}")
            add(MERGE_CLASS)
            add(action)
            add(apkDir.absolutePath)
            if (action == ACTION_MERGE) {
                add(outputApk?.absolutePath ?: error("Missing output APK"))
                add(skipModules.joinToString(","))
                add(sortApkEntries.toString())
            }
        }

        val process = ProcessBuilder(args)
            .redirectErrorStream(true)
            .apply {
                val env = environment()
                env["CLASSPATH"] = classpath
                if (androidData != null) {
                    env["ANDROID_DATA"] = androidData
                    env["ANDROID_CACHE"] = File(androidData, "cache").absolutePath
                }
                val overridePath = propOverridePath
                val limitMb = heapLimitMb
                if (!overridePath.isNullOrBlank() && limitMb != null) {
                    val limit = "${limitMb}M"
                    env["LD_PRELOAD"] = overridePath
                    env["PROP_dalvik.vm.heapgrowthlimit"] = limit
                    env["PROP_dalvik.vm.heapsize"] = limit
                }
            }
            .start()

        val outputLines = ArrayDeque<String>(MAX_OUTPUT_LINES)
        val outputRef = AtomicReference(outputLines)
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val buffer = outputRef.get()
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    if (buffer.size == MAX_OUTPUT_LINES) {
                        buffer.removeFirst()
                    }
                    buffer.addLast(limitLineLength(trimmed))
                    if (shouldEmit(trimmed)) {
                        onLine?.invoke(limitLineLength(trimmed))
                    }
                }
            }
        }

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            if (allowHeapFallback && exitCode == 137) {
                val configured = heapLimitMb ?: memoryLimitMb ?: 0
                if (configured > SAFE_RETRY_HEAP_MB) {
                    onLine?.invoke(
                        "APKEditor: merge process killed (137), retrying with ${SAFE_RETRY_HEAP_MB}MB heap."
                    )
                    runProcess(
                        action = action,
                        apkDir = apkDir,
                        outputApk = outputApk,
                        skipModules = skipModules,
                        sortApkEntries = sortApkEntries,
                        onLine = onLine,
                        heapLimitMb = SAFE_RETRY_HEAP_MB,
                        allowHeapFallback = false
                    )
                    return
                }
            }
            val output = outputRef.get().joinToString("\n").trim()
            throw IOException("APKEditor merge process failed ($exitCode). ${output.takeIf { it.isNotBlank() } ?: ""}")
        }
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
            if (shouldEmit(line)) {
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

    private fun resolveAppProcessBin(): String {
        val paths = arrayOf("/system/bin/app_process64", "/system/bin/app_process32", "/system/bin/app_process")
        return paths.firstOrNull { File(it).exists() } ?: paths.last()
    }

    private fun destroyProcess(process: Process) {
        runCatching { process.destroy() }
        runCatching {
            if (!process.waitFor(150, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(1500, TimeUnit.MILLISECONDS)
            }
        }
        runCatching { process.destroyForcibly() }
    }

    private const val MAX_OUTPUT_LINES = 400
    private const val MAX_EVENT_LINE_LENGTH = 2000
    private const val SAFE_RETRY_HEAP_MB = 320

    private fun shouldEmit(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        return !(trimmed.startsWith("Added:") ||
            trimmed.startsWith("Added [") ||
            trimmed.startsWith("Loading:") ||
            trimmed.startsWith("ORDER:"))
    }

    private fun limitLineLength(line: String): String {
        return if (line.length <= MAX_EVENT_LINE_LENGTH) {
            line
        } else {
            line.take(MAX_EVENT_LINE_LENGTH) + "…"
        }
    }

}
