/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */
package app.urv.manager.patcher.runtime.morphe

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/pull/1202
// https://github.com/MorpheApp/morphe-manager/pull/1212

import android.content.Context
import app.urv.manager.patcher.ProgressEvent
import app.urv.manager.patcher.logger.*
import app.urv.manager.patcher.morphe.MorpheRuntimeBridge
import app.urv.manager.patcher.runtime.*
import app.urv.manager.util.Options
import app.urv.manager.util.PatchSelection

class MorpheJvmRuntime(private val context: Context) : MorpheRuntime(context) {
    private val client = BoundRuntimeClient(context)
    override fun cancel() = client.cancel()
    override suspend fun execute(inputFile: String, outputFile: String, packageName: String,
        selectedPatches: PatchSelection, options: Options, logger: Logger,
        onEvent: (ProgressEvent) -> Unit, onMemoryUsage: (Long, Long) -> Unit,
        stripNativeLibs: Boolean, skipUnneededSplits: Boolean) {
        val mode = prefs.patcherLogMode.get()
        val runtimeLogger = logger.filtered(mode)
        val selected = selectedPatches.filterValues { it.isNotEmpty() }
        val available = bundles().filterKeys { it in selected }
        require((selected.keys - available.keys).isEmpty()) {
            "Selected patches are unavailable. Re-open patch selection and select patches again."
        }
        val params = mapOf(
            "aaptPath" to aaptPath, "frameworkDir" to frameworkPath, "cacheDir" to cacheDir,
            "packageName" to packageName, "inputFile" to inputFile, "outputFile" to outputFile,
            "patcherLogMode" to mode.name, "stripNativeLibs" to stripNativeLibs,
            "skipUnneededSplits" to skipUnneededSplits, "continueOnPatchError" to prefs.continueOnPatchError.get(),
            "bytecodeMode" to prefs.morpheBytecodeMode.get().runtimeValue,
            "configurations" to available.map { (uid, bundle) ->
                mapOf("bundlePath" to bundle.patchesJar, "patches" to selected[uid].orEmpty().toList(),
                    "options" to options[uid].orEmpty())
            }
        )

        client.execute(RuntimeEngine.MORPHE, params, available.values.map { it.patchesJar },
            runtimeClassPath = MorpheRuntimeAssets.ensureRuntimeClassPath(context).path) { type, event ->
            when (type) {
                "log" -> runtimeLogger.log(LogLevel.valueOf(event["level"] as String), event["message"] as String)
                "event" -> onEvent(MorpheRuntimeBridge.mapToProgressEvent(event["event"] as Map<*, *>))
                "memory" -> onMemoryUsage((event["used"] as Number).toLong(), (event["max"] as Number).toLong())
            }
        }
    }
}
