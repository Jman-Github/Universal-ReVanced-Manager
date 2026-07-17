package app.urv.manager.patcher.split

import android.content.Context
import android.os.Build
import android.util.Log
import app.urv.manager.patcher.LibraryResolver
import app.urv.manager.patcher.runtime.MemoryLimitConfig
import app.urv.manager.patcher.runtime.PatcherMemoryMonitor
import app.urv.manager.patcher.runtime.ProcessAttemptLogSpool
import app.urv.manager.patcher.worker.PatcherMemoryUsage
import app.urv.manager.util.tag
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

class SplitMergeProcessRuntime(private val context: Context) : LibraryResolver() {
    private val activeProcessLock = Any()
    private var activeProcess: Process? = null
    private var activeCancellationRequested = false

    fun cancelActiveExecution() {
        val process = synchronized(activeProcessLock) {
            activeCancellationRequested = true
            activeProcess
        } ?: return
        destroyProcess(process)
    }

    suspend fun execute(
        inputFile: File,
        workspace: File,
        stripNativeLibs: Boolean,
        skipUnneededSplits: Boolean,
        includedModules: Set<String>? = null,
        memoryLimitMb: Int? = MemoryLimitConfig.maxLimitMb(context),
        onProgress: (String) -> Unit,
        onSubSteps: (List<String>) -> Unit,
        onLog: (String) -> Unit = {},
        onMemoryUsage: (PatcherMemoryUsage) -> Unit = {}
    ): File {
        // Code adapted from Morphe, see third-party/NOTICE for more information
        // https://github.com/MorpheApp/morphe-manager/blob/a2c3d31bd7ab42e6bc4b9dd528ed856fc72fb948/app/src/main/java/app/morphe/manager/patcher/runtime/ProcessRuntime.kt
        var attemptLimitMb = memoryLimitMb?.let {
            MemoryLimitConfig.clampLimitMb(context, it)
        }
        val priorAttemptProgressMessages = mutableSetOf<String>()
        var mostReportedSubSteps = 0
        while (true) {
            val attemptLogs = ProcessAttemptLogSpool.create(
                workspace,
                "split-merge-attempt-"
            )
            val attemptProgressMessages = mutableSetOf<String>()
            try {
                val result = try {
                    executeOnce(
                        inputFile = inputFile,
                        workspace = workspace,
                        stripNativeLibs = stripNativeLibs,
                        skipUnneededSplits = skipUnneededSplits,
                        includedModules = includedModules,
                        memoryLimitMb = attemptLimitMb,
                        onProgress = { message ->
                            attemptProgressMessages += message
                            if (message !in priorAttemptProgressMessages) {
                                onProgress(message)
                            }
                        },
                        onSubSteps = { subSteps ->
                            if (subSteps.size > mostReportedSubSteps) {
                                mostReportedSubSteps = subSteps.size
                                onSubSteps(subSteps)
                            }
                        },
                        onLog = attemptLogs::append,
                        onMemoryUsage = onMemoryUsage
                    )
                } catch (error: ProcessExitException) {
                    val failedLimitMb = attemptLimitMb
                    if (
                        !isMemoryFailureExitCode(error.exitCode) ||
                        failedLimitMb == null ||
                        failedLimitMb <= MemoryLimitConfig.PROCESS_RUNTIME_MEMORY_RETRY_MINIMUM
                    ) {
                        attemptLogs.replayTo(onLog)
                        throw error
                    }
                    priorAttemptProgressMessages += attemptProgressMessages
                    cleanupFailedAttempt(workspace)
                    attemptLimitMb = nextRetryMemoryLimitMb(failedLimitMb)
                    continue
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    attemptLogs.replayTo(onLog)
                    throw error
                }
                attemptLogs.replayTo(onLog)
                return result
            } finally {
                attemptLogs.close()
            }
        }
    }

    private suspend fun executeOnce(
        inputFile: File,
        workspace: File,
        stripNativeLibs: Boolean,
        skipUnneededSplits: Boolean,
        includedModules: Set<String>?,
        memoryLimitMb: Int?,
        onProgress: (String) -> Unit,
        onSubSteps: (List<String>) -> Unit,
        onLog: (String) -> Unit,
        onMemoryUsage: (PatcherMemoryUsage) -> Unit
    ): File = coroutineScope {
        workspace.mkdirs()
        val output = workspace.resolve("last-merged-unsigned.apk")
        if (output.exists()) {
            runCatching { output.delete() }
        }

        val managerBaseApk = context.applicationInfo.sourceDir
        val env = System.getenv().toMutableMap().apply {
            put("CLASSPATH", managerBaseApk)
        }
        val effectiveMemoryLimitMb = memoryLimitMb?.let {
            MemoryLimitConfig.clampLimitMb(context, it)
        }
        val propOverride = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            effectiveMemoryLimitMb != null
        ) {
            findLibrary(context, "prop_override")
        } else {
            null
        }
        if (propOverride != null && effectiveMemoryLimitMb != null) {
            val limit = "${effectiveMemoryLimitMb}M"
            env["LD_PRELOAD"] = propOverride.absolutePath
            env["PROP_dalvik.vm.heapgrowthlimit"] = limit
            env["PROP_dalvik.vm.heapsize"] = limit
        } else if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            effectiveMemoryLimitMb != null
        ) {
            Log.w(tag, "Split merge process: prop override library not found")
        }
        val subSteps = mutableListOf<String>()
        val selectedModulesFile = workspace.resolve("selected-modules.txt").apply {
            if (exists()) {
                runCatching { delete() }
            }
        }
        try {
            if (includedModules != null) {
                selectedModulesFile.parentFile?.mkdirs()
                selectedModulesFile.writeText(
                    includedModules
                        .sorted()
                        .joinToString(separator = "\n")
                )
            }
        } catch (error: Throwable) {
            runCatching { selectedModulesFile.delete() }
            throw error
        }

        val command = buildList {
            add(resolveAppProcessBin())
            if (effectiveMemoryLimitMb != null && propOverride == null) {
                add("-Xmx${effectiveMemoryLimitMb}m")
                add("-XX:HeapGrowthLimit=${effectiveMemoryLimitMb}m")
            }
            add("-Djava.io.tmpdir=${context.cacheDir.absolutePath}")
            add("/")
            add("--nice-name=${context.packageName}:SplitMerge")
            add(SplitMergeProcess::class.java.name)
            add(inputFile.absolutePath)
            add(workspace.absolutePath)
            add(output.absolutePath)
            add(stripNativeLibs.toString())
            add(skipUnneededSplits.toString())
            add(selectedModulesFile.absolutePath)
        }
        val process = try {
            withContext(Dispatchers.IO) {
                ProcessBuilder(command)
                    .directory(workspace)
                    .apply { environment().putAll(env) }
                    .start()
            }
        } catch (error: Throwable) {
            runCatching { selectedModulesFile.delete() }
            throw error
        }
        synchronized(activeProcessLock) {
            activeProcess = process
            activeCancellationRequested = false
        }
        val stdoutJob = launch(Dispatchers.IO) {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        when {
                            line.startsWith(PROGRESS_PREFIX) -> onProgress(line.removePrefix(PROGRESS_PREFIX))
                            line.startsWith(LOG_PREFIX) -> onLog(line.removePrefix(LOG_PREFIX))
                            line.startsWith(SUBSTEP_PREFIX) -> {
                                subSteps += line.removePrefix(SUBSTEP_PREFIX)
                                onSubSteps(subSteps.toList())
                            }

                            line.startsWith(MEMORY_PREFIX) -> {
                                parseMemoryUsageSample(line.removePrefix(MEMORY_PREFIX))
                                    ?.let(onMemoryUsage)
                            }

                            line.isNotBlank() -> Log.d(tag, "[split-merge process] $line")
                        }
                    }
                }
            } catch (error: IOException) {
                if (!isCancellationRequested(process)) throw error
            }
        }
        val stderrTail = ArrayDeque<String>(MAX_STDERR_LINES)
        val stderrJob = launch(Dispatchers.IO) {
            try {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) {
                            val limitedLine = line.take(MAX_STDERR_LINE_LENGTH)
                            synchronized(stderrTail) {
                                if (stderrTail.size == MAX_STDERR_LINES) {
                                    stderrTail.removeFirst()
                                }
                                stderrTail.addLast(limitedLine)
                            }
                            Log.w(tag, "[split-merge process] $line")
                        }
                    }
                }
            } catch (error: IOException) {
                if (!isCancellationRequested(process)) throw error
            }
        }

        try {
            val exitCode = try {
                runInterruptible(Dispatchers.IO) { process.waitFor() }
            } catch (error: CancellationException) {
                synchronized(activeProcessLock) {
                    if (activeProcess === process) {
                        activeCancellationRequested = true
                    }
                }
                destroyProcess(process)
                throw error
            }

            withContext(NonCancellable) {
                runCatching { stdoutJob.join() }
                runCatching { stderrJob.join() }
            }

            if (exitCode != 0) {
                val detail = synchronized(stderrTail) {
                    stderrTail.joinToString("\n")
                        .trim()
                        .takeLast(MAX_STDERR_DETAIL_LENGTH)
                        .takeIf(String::isNotBlank)
                }
                throw ProcessExitException(exitCode, detail)
            }
            if (!output.exists() || output.length() <= 0L) {
                throw IOException("Split merge process completed without output APK.")
            }
            output
        } finally {
            withContext(NonCancellable) {
                destroyProcess(process)
                runCatching { process.outputStream.close() }
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                stdoutJob.cancel()
                stderrJob.cancel()
                runCatching { stdoutJob.join() }
                runCatching { stderrJob.join() }
            }
            synchronized(activeProcessLock) {
                if (activeProcess === process) {
                    activeProcess = null
                    activeCancellationRequested = false
                }
            }
            runCatching { selectedModulesFile.delete() }
        }
    }

    private fun isCancellationRequested(process: Process): Boolean =
        synchronized(activeProcessLock) {
            activeProcess === process && activeCancellationRequested
        }

    private fun parseMemoryUsageSample(raw: String): PatcherMemoryUsage? {
        val parts = raw.trim().split(':')
        val usedMb = parts.getOrNull(0)?.toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val maxMb = parts.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }
            ?: MemoryLimitConfig.maxLimitMb(context).toLong()
        return PatcherMemoryUsage(
            usedMb = usedMb,
            maxMb = maxMb.coerceAtLeast(1L)
        )
    }

    private suspend fun cleanupFailedAttempt(workspace: File) {
        withContext(Dispatchers.IO) {
            workspace.listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory && it.name.startsWith("split-") }
                ?.forEach { runCatching { it.deleteRecursively() } }
        }
    }

    private fun nextRetryMemoryLimitMb(failedLimitMb: Int): Int =
        minOf(
            failedLimitMb - MemoryLimitConfig.PROCESS_RUNTIME_MEMORY_STEP,
            MemoryLimitConfig.PROCESS_RUNTIME_MEMORY_MAX_LIMIT_INITIALIZATION
        ).coerceAtLeast(MemoryLimitConfig.PROCESS_RUNTIME_MEMORY_RETRY_MINIMUM)

    class ProcessExitException(
        val exitCode: Int,
        val detail: String? = null
    ) : Exception(
        buildString {
            append("Split merge process exited with nonzero exit code ").append(exitCode)
            detail?.takeIf(String::isNotBlank)?.let { append(": ").append(it) }
        }
    )

    private fun resolveAppProcessBin(): String {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val is64Bit = nativeDir.contains("64")
        val preferred = if (is64Bit) APP_PROCESS_BIN_PATH_64 else APP_PROCESS_BIN_PATH_32
        return if (File(preferred).exists()) preferred else APP_PROCESS_BIN_PATH
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

    companion object {
        const val PROGRESS_PREFIX = "URV_SPLIT_PROGRESS:"
        const val LOG_PREFIX = "URV_SPLIT_LOG:"
        const val SUBSTEP_PREFIX = "URV_SPLIT_SUBSTEP:"
        const val MEMORY_PREFIX = "URV_SPLIT_MEMORY_MB:"
        const val OOM_EXIT_CODE = 134
        const val LOW_MEMORY_KILL_EXIT_CODE = 137
        const val SEGMENTATION_FAULT_EXIT_CODE = 139
        private const val APP_PROCESS_BIN_PATH = "/system/bin/app_process"
        private const val APP_PROCESS_BIN_PATH_64 = "/system/bin/app_process64"
        private const val APP_PROCESS_BIN_PATH_32 = "/system/bin/app_process32"
        private const val MAX_STDERR_LINES = 40
        private const val MAX_STDERR_LINE_LENGTH = 2000
        private const val MAX_STDERR_DETAIL_LENGTH = 8000

        fun isMemoryFailureExitCode(exitCode: Int): Boolean =
            exitCode == OOM_EXIT_CODE ||
                exitCode == LOW_MEMORY_KILL_EXIT_CODE ||
                exitCode == SEGMENTATION_FAULT_EXIT_CODE
    }
}

object SplitMergeProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 5) {
            "Expected args: <input> <workspace> <output> <stripNativeLibs> <skipUnneededSplits> [selectedModulesFile]"
        }

        val input = File(args[0])
        val workspace = File(args[1])
        val output = File(args[2])
        val stripNativeLibs = args[3].toBooleanStrictOrNull() ?: false
        val skipUnneededSplits = args[4].toBooleanStrictOrNull() ?: false
        val selectedModules = args.getOrNull(5)
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf(File::exists)
            ?.readLines()
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.toSet()
        val memoryMonitor = PatcherMemoryMonitor.start { usedMb, maxMb ->
            println("${SplitMergeProcessRuntime.MEMORY_PREFIX}$usedMb:$maxMb")
            System.out.flush()
        }
        runBlocking {
            try {
                val preparation = SplitApkPreparer.prepareIfNeeded(
                    source = input,
                    workspace = workspace,
                    stripNativeLibs = stripNativeLibs,
                    skipUnneededSplits = skipUnneededSplits,
                    includedModules = selectedModules,
                    onProgress = { msg ->
                        println("${SplitMergeProcessRuntime.PROGRESS_PREFIX}$msg")
                    },
                    onSubSteps = { steps ->
                        steps.forEach { step ->
                            println("${SplitMergeProcessRuntime.SUBSTEP_PREFIX}$step")
                        }
                    },
                    onLog = { message ->
                        message.lineSequence()
                            .filter(String::isNotBlank)
                            .forEach { line ->
                                println("${SplitMergeProcessRuntime.LOG_PREFIX}$line")
                            }
                    }
                )

                try {
                    preparation.file.copyTo(output, overwrite = true)
                } finally {
                    preparation.cleanup()
                }
            } finally {
                memoryMonitor.stop()
            }
        }
    }
}
