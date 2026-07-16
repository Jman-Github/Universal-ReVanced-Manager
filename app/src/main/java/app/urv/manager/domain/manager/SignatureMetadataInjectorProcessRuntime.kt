package app.urv.manager.domain.manager

import android.content.Context
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
) {
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
        onProgress: (SignatureMetadataInjectorProgress) -> Unit,
        onLog: (String) -> Unit
    ): SignatureMetadataInjectorEngineResult = coroutineScope {
        workspace.mkdirs()
        val resultFile = workspace.resolve("injector-result.properties")
        resultFile.delete()
        val command = listOf(
            resolveAppProcessBin(),
            "-Djava.io.tmpdir=${context.cacheDir.absolutePath}",
            "/",
            "--nice-name=${context.packageName}:SignatureMetadataInjector",
            SignatureMetadataInjectorProcess::class.java.name,
            metadataArchive.absolutePath,
            targetApk.absolutePath,
            outputApk.absolutePath,
            mode.name,
            resultFile.absolutePath
        )
        val process = withContext(NonCancellable + Dispatchers.IO) {
            ProcessBuilder(command)
                .directory(workspace)
                .apply {
                    environment()["CLASSPATH"] = context.applicationInfo.sourceDir
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
                        onLog("[process stderr] $line")
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
            if (exitCode != 0) {
                val detail = synchronized(stderrTail) {
                    stderrTail.lastOrNull()
                }
                throw ProcessExitException(exitCode, detail)
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

    class ProcessExitException(
        exitCode: Int,
        detail: String?
    ) : IOException(
        buildString {
            append("Signature metadata process exited with code ")
            append(exitCode)
            if (!detail.isNullOrBlank()) {
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
        internal const val LIST_SEPARATOR = "\u001f"
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
