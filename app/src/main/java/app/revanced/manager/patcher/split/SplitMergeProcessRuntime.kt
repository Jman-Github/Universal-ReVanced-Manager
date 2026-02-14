package app.revanced.manager.patcher.split

import android.content.Context
import android.os.Build
import android.util.Log
import app.revanced.manager.patcher.LibraryResolver
import app.revanced.manager.patcher.runtime.MemoryLimitConfig
import app.revanced.manager.util.tag
import com.github.pgreze.process.Redirect
import com.github.pgreze.process.process
import java.io.File
import java.io.IOException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

class SplitMergeProcessRuntime(private val context: Context) : LibraryResolver() {
    suspend fun execute(
        inputFile: File,
        workspace: File,
        stripNativeLibs: Boolean,
        skipUnneededSplits: Boolean,
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

        val result = process(
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
            stdout = Redirect.CAPTURE,
            stderr = Redirect.CAPTURE,
            env = env
        ) { line ->
            when {
                line.startsWith(PROGRESS_PREFIX) -> {
                    onProgress(line.removePrefix(PROGRESS_PREFIX))
                }

                line.startsWith(SUBSTEP_PREFIX) -> {
                    subSteps += line.removePrefix(SUBSTEP_PREFIX)
                    onSubSteps(subSteps.toList())
                }

                line.isNotBlank() -> Log.d(tag, "[split-merge process] $line")
            }
        }

        if (result.resultCode != 0) {
            throw ProcessExitException(result.resultCode)
        }
        if (!output.exists() || output.length() <= 0L) {
            throw IOException("Split merge process completed without output APK.")
        }
        output
    }

    class ProcessExitException(val exitCode: Int) :
        Exception("Split merge process exited with nonzero exit code $exitCode")

    private fun resolveAppProcessBin(): String {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val is64Bit = nativeDir.contains("64")
        val preferred = if (is64Bit) APP_PROCESS_BIN_PATH_64 else APP_PROCESS_BIN_PATH_32
        return if (File(preferred).exists()) preferred else APP_PROCESS_BIN_PATH
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
            "Expected args: <input> <workspace> <output> <stripNativeLibs> <skipUnneededSplits>"
        }

        val input = File(args[0])
        val workspace = File(args[1])
        val output = File(args[2])
        val stripNativeLibs = args[3].toBooleanStrictOrNull() ?: false
        val skipUnneededSplits = args[4].toBooleanStrictOrNull() ?: false

        runBlocking {
            val preparation = SplitApkPreparer.prepareIfNeeded(
                source = input,
                workspace = workspace,
                stripNativeLibs = stripNativeLibs,
                skipUnneededSplits = skipUnneededSplits,
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
