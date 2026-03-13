package app.revanced.manager.patcher.split

import android.content.Context
import android.os.Build
import android.util.Log
import app.revanced.manager.patcher.LibraryResolver
import app.revanced.manager.patcher.runtime.MemoryLimitConfig
import app.revanced.manager.util.tag
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

    fun cancelActiveExecution() {
        val process = synchronized(activeProcessLock) { activeProcess } ?: return
        destroyProcess(process)
    }

    suspend fun execute(
        inputFile: File,
        workspace: File,
        stripNativeLibs: Boolean,
        skipUnneededSplits: Boolean,
        includedModules: Set<String>? = null,
        onProgress: (String) -> Unit,
        onSubSteps: (List<String>) -> Unit
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
        val usePropOverride = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        if (usePropOverride) {
            val propOverride = findLibrary(context, "prop_override")
            if (propOverride != null) {
                val limit = "${MemoryLimitConfig.maxLimitMb(context)}M"
                env["LD_PRELOAD"] = propOverride.absolutePath
                env["PROP_dalvik.vm.heapgrowthlimit"] = limit
                env["PROP_dalvik.vm.heapsize"] = limit
            } else {
                Log.w(tag, "Split merge process: prop override library not found")
            }
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

        val command = listOf(
            resolveAppProcessBin(),
            "-Djava.io.tmpdir=${context.cacheDir.absolutePath}",
            "/",
            "--nice-name=${context.packageName}:SplitMerge",
            SplitMergeProcess::class.java.name,
            inputFile.absolutePath,
            workspace.absolutePath,
            output.absolutePath,
            stripNativeLibs.toString(),
            skipUnneededSplits.toString(),
            selectedModulesFile.absolutePath
        )
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
        }
        val stdoutJob = launch(Dispatchers.IO) {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    when {
                        line.startsWith(PROGRESS_PREFIX) -> onProgress(line.removePrefix(PROGRESS_PREFIX))
                        line.startsWith(SUBSTEP_PREFIX) -> {
                            subSteps += line.removePrefix(SUBSTEP_PREFIX)
                            onSubSteps(subSteps.toList())
                        }

                        line.isNotBlank() -> Log.d(tag, "[split-merge process] $line")
                    }
                }
            }
        }
        val stderrJob = launch(Dispatchers.IO) {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        Log.w(tag, "[split-merge process] $line")
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
                runCatching { stderrJob.join() }
            }

            if (exitCode != 0) {
                throw ProcessExitException(exitCode)
            }
            if (!output.exists() || output.length() <= 0L) {
                throw IOException("Split merge process completed without output APK.")
            }
            output
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
                stderrJob.cancel()
                runCatching { stdoutJob.join() }
                runCatching { stderrJob.join() }
            }
            runCatching { selectedModulesFile.delete() }
        }
    }

    class ProcessExitException(val exitCode: Int) :
        Exception("Split merge process exited with nonzero exit code $exitCode")

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
        const val SUBSTEP_PREFIX = "URV_SPLIT_SUBSTEP:"
        private const val APP_PROCESS_BIN_PATH = "/system/bin/app_process"
        private const val APP_PROCESS_BIN_PATH_64 = "/system/bin/app_process64"
        private const val APP_PROCESS_BIN_PATH_32 = "/system/bin/app_process32"
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

        runBlocking {
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
                }
            )

            try {
                preparation.file.copyTo(output, overwrite = true)
            } finally {
                preparation.cleanup()
            }
        }
    }
}
