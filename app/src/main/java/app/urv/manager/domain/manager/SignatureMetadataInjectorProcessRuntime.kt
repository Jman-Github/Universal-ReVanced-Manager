package app.urv.manager.domain.manager

import android.content.Context
import android.os.Build
import app.urv.manager.patcher.LibraryResolver
import app.urv.manager.patcher.runtime.MemoryLimitConfig
import app.urv.manager.patcher.runtime.ProcessAttemptLogSpool
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal class SignatureMetadataInjectorProcessRuntime(
    private val context: Context
) : LibraryResolver() {
    private val activeProcessLock = Any()
    private var activeProcess: Process? = null
    private var activeCancellationRequested = false

    fun cancelActiveExecution() {
        val process = synchronized(activeProcessLock) {
            activeProcess?.also { activeCancellationRequested = true }
        } ?: return
        runCatching { process.destroy() }
    }

    suspend fun execute(
        metadataArchive: File,
        targetApk: File,
        outputApk: File,
        workspace: File,
        mode: SignatureMetadataInjectionMode,
        memoryLimitMb: Int,
        onProgress: (SignatureMetadataInjectorProgress) -> Unit,
        onLog: (String) -> Unit
    ): SignatureMetadataInjectorEngineResult {
        var attemptLimitMb = MemoryLimitConfig.clampLimitMb(context, memoryLimitMb)
        var highestProgressStageOrdinal = -1
        while (true) {
            val attemptLogs = ProcessAttemptLogSpool.create(
                workspace,
                "signature-injector-attempt-"
            )
            try {
                val result = try {
                    executeOnce(
                        metadataArchive = metadataArchive,
                        targetApk = targetApk,
                        outputApk = outputApk,
                        workspace = workspace,
                        mode = mode,
                        memoryLimitMb = attemptLimitMb,
                        onProgress = { progress ->
                            val ordinal = progress.stage.ordinal
                            if (ordinal > highestProgressStageOrdinal) {
                                highestProgressStageOrdinal = ordinal
                                onProgress(progress)
                            }
                        },
                        onLog = attemptLogs::append
                    )
                } catch (error: ProcessExitException) {
                    val canRetry =
                        isMemoryFailureExitCode(error.exitCode) &&
                            attemptLimitMb >
                                MemoryLimitConfig.PROCESS_RUNTIME_MEMORY_RETRY_MINIMUM
                    if (!canRetry) {
                        attemptLogs.replayTo(onLog)
                        error.stderrLines.forEach { line ->
                            onLog("[process stderr] $line")
                        }
                        throw error
                    }
                    attemptLimitMb = nextRetryMemoryLimitMb(attemptLimitMb)
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
        metadataArchive: File,
        targetApk: File,
        outputApk: File,
        workspace: File,
        mode: SignatureMetadataInjectionMode,
        memoryLimitMb: Int,
        onProgress: (SignatureMetadataInjectorProgress) -> Unit,
        onLog: (String) -> Unit
    ): SignatureMetadataInjectorEngineResult = coroutineScope {
        workspace.mkdirs()
        val resultFile = workspace.resolve("injector-result.properties")
        resultFile.delete()
        outputApk.delete()
        // Code adapted from Morphe, see third-party/NOTICE for more information
        // https://github.com/MorpheApp/morphe-manager/blob/a2c3d31bd7ab42e6bc4b9dd528ed856fc72fb948/app/src/main/java/app/morphe/manager/patcher/runtime/ProcessRuntime.kt
        val effectiveMemoryLimitMb = MemoryLimitConfig.clampLimitMb(
            context,
            memoryLimitMb
        )
        val propOverride = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            findLibrary(context, "prop_override")
        } else {
            null
        }
        val command = buildList {
            add(resolveAppProcessBin())
            if (propOverride == null) {
                add("-Xmx${effectiveMemoryLimitMb}m")
                add("-XX:HeapGrowthLimit=${effectiveMemoryLimitMb}m")
            }
            add("-Djava.io.tmpdir=${context.cacheDir.absolutePath}")
            add("/")
            add("--nice-name=${context.packageName}:SignatureMetadataInjector")
            add(SignatureMetadataInjectorProcess::class.java.name)
            add(metadataArchive.absolutePath)
            add(targetApk.absolutePath)
            add(outputApk.absolutePath)
            add(mode.name)
            add(resultFile.absolutePath)
        }
        val process = withContext(NonCancellable + Dispatchers.IO) {
            ProcessBuilder(command)
                .directory(workspace)
                .apply {
                    environment()["CLASSPATH"] = context.applicationInfo.sourceDir
                    if (propOverride != null) {
                        val limit = "${effectiveMemoryLimitMb}M"
                        environment()["LD_PRELOAD"] = propOverride.absolutePath
                        environment()["PROP_dalvik.vm.heapgrowthlimit"] = limit
                        environment()["PROP_dalvik.vm.heapsize"] = limit
                    }
                }
                .start()
                .also { startedProcess ->
                    synchronized(activeProcessLock) {
                        activeProcess = startedProcess
                        activeCancellationRequested = false
                    }
                }
        }
        val stderrTail = ArrayDeque<String>()
        val stdoutJob = launch(Dispatchers.IO) {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        when {
                            line.startsWith(PROGRESS_PREFIX) -> {
                                val stage = line.removePrefix(PROGRESS_PREFIX)
                                    .let { raw ->
                                        runCatching {
                                            SignatureMetadataInjectorStage.valueOf(raw)
                                        }.getOrNull()
                                    }
                                if (stage != null) {
                                    onProgress(SignatureMetadataInjectorProgress(stage))
                                }
                            }
                            line.startsWith(LOG_PREFIX) ->
                                onLog(line.removePrefix(LOG_PREFIX))
                            line.isNotBlank() -> onLog("[process] $line")
                        }
                    }
                }
            } catch (error: IOException) {
                if (!isCancellationRequested(process)) throw error
            }
        }
        val stderrJob = launch(Dispatchers.IO) {
            try {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isBlank()) return@forEach
                        synchronized(stderrTail) {
                            stderrTail.addLast(line)
                            while (stderrTail.size > STDERR_TAIL_LIMIT) {
                                stderrTail.removeFirst()
                            }
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
            if (isCancellationRequested(process)) {
                throw CancellationException("Signature metadata process was cancelled")
            }
            val stderrLines = synchronized(stderrTail) {
                stderrTail.toList()
            }
            if (exitCode != 0) {
                throw ProcessExitException(exitCode, stderrLines)
            }
            stderrLines.forEach { line ->
                onLog("[process stderr] $line")
            }
            if (!outputApk.isFile || outputApk.length() <= 0L || !resultFile.isFile) {
                throw IOException("Signature metadata process completed without a result.")
            }
            readResult(resultFile)
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
        }
    }

    private fun readResult(file: File): SignatureMetadataInjectorEngineResult {
        val properties = Properties()
        file.inputStream().use { input -> properties.load(input) }
        return SignatureMetadataInjectorEngineResult(
            injectedEntries = properties.getProperty("injected").decodeList(),
            skippedEntries = properties.getProperty("skipped").decodeList(),
            removedSignatureEntryCount = properties.getProperty("removed")
                ?.toIntOrNull()
                ?: 0
        )
    }

    private fun isCancellationRequested(process: Process): Boolean =
        synchronized(activeProcessLock) {
            activeProcess === process && activeCancellationRequested
        }

    private fun resolveAppProcessBin(): String {
        val is64Bit = context.applicationInfo.nativeLibraryDir.contains("64")
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

    private fun nextRetryMemoryLimitMb(failedLimitMb: Int): Int =
        minOf(
            failedLimitMb - MemoryLimitConfig.PROCESS_RUNTIME_MEMORY_STEP,
            MemoryLimitConfig.PROCESS_RUNTIME_MEMORY_MAX_LIMIT_INITIALIZATION
        ).coerceAtLeast(MemoryLimitConfig.PROCESS_RUNTIME_MEMORY_RETRY_MINIMUM)

    class ProcessExitException(
        val exitCode: Int,
        val stderrLines: List<String>
    ) : IOException(
        buildString {
            append("Signature metadata process exited with code ")
            append(exitCode)
            stderrLines.lastOrNull()?.takeIf(String::isNotBlank)?.let { detail ->
                append(": ")
                append(detail)
            }
        }
    )

    companion object {
        const val PROGRESS_PREFIX = "URV_SIGNATURE_PROGRESS:"
        const val LOG_PREFIX = "URV_SIGNATURE_LOG:"
        private const val APP_PROCESS_BIN_PATH = "/system/bin/app_process"
        private const val APP_PROCESS_BIN_PATH_64 = "/system/bin/app_process64"
        private const val APP_PROCESS_BIN_PATH_32 = "/system/bin/app_process32"
        private const val STDERR_TAIL_LIMIT = 50
        private const val OOM_EXIT_CODE = 134
        private const val LOW_MEMORY_KILL_EXIT_CODE = 137
        private const val SEGMENTATION_FAULT_EXIT_CODE = 139
        internal const val LIST_SEPARATOR = "\u001f"

        private fun isMemoryFailureExitCode(exitCode: Int): Boolean =
            exitCode == OOM_EXIT_CODE ||
                exitCode == LOW_MEMORY_KILL_EXIT_CODE ||
                exitCode == SEGMENTATION_FAULT_EXIT_CODE
    }
}

internal object SignatureMetadataInjectorProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 5) {
            "Expected metadata, target, output, mode, and result paths."
        }
        val metadataArchive = File(args[0])
        val targetApk = File(args[1])
        val outputApk = File(args[2])
        val mode = SignatureMetadataInjectionMode.valueOf(args[3])
        val resultFile = File(args[4])

        try {
            val result = SignatureMetadataInjectorArscEngine.execute(
                metadataArchive = metadataArchive,
                targetApk = targetApk,
                outputApk = outputApk,
                mode = mode,
                onProgress = { progress ->
                    println(
                        SignatureMetadataInjectorProcessRuntime.PROGRESS_PREFIX +
                            progress.stage.name
                    )
                    System.out.flush()
                },
                onLog = { message ->
                    message.lineSequence()
                        .filter(String::isNotBlank)
                        .forEach { line ->
                            println(SignatureMetadataInjectorProcessRuntime.LOG_PREFIX + line)
                        }
                    System.out.flush()
                }
            )
            val properties = Properties().apply {
                setProperty("injected", result.injectedEntries.encodeList())
                setProperty("skipped", result.skippedEntries.encodeList())
                setProperty("removed", result.removedSignatureEntryCount.toString())
            }
            resultFile.parentFile?.mkdirs()
            resultFile.outputStream().use { output ->
                properties.store(output, null)
            }
        } catch (error: Throwable) {
            System.err.println("Signature metadata injection failed: ${error.message}")
            error.printStackTrace(System.err)
            System.err.flush()
            throw error
        }
    }
}

private fun List<String>.encodeList(): String =
    joinToString(SignatureMetadataInjectorProcessRuntime.LIST_SEPARATOR)

private fun String?.decodeList(): List<String> =
    this?.takeIf(String::isNotBlank)
        ?.split(SignatureMetadataInjectorProcessRuntime.LIST_SEPARATOR)
        .orEmpty()
