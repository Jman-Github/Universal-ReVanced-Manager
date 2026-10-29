package app.universal.patch.manager.patcher.runtime

import android.content.Context
import app.universal.patch.manager.patcher.ProgressEvent
import app.universal.patch.manager.patcher.logger.*
import app.universal.patch.manager.patcher.morphe.MorpheRuntimeBridge
import app.universal.patch.manager.patcher.runtime.revanced.Revanced21RuntimeAssets
import app.universal.patch.manager.patcher.runtime.revanced.RevancedLegacyFormat
import app.universal.patch.manager.util.Options
import app.universal.patch.manager.util.PatchSelection

class RevancedJvmRuntime(private val context: Context, private val v22: Boolean) : Runtime(context) {
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
            "aaptPath" to aaptModernPath, "aaptFallbackPath" to aaptLegacyPath,
            "frameworkDir" to frameworkPath, "cacheDir" to cacheDir,
            "packageName" to packageName, "inputFile" to inputFile, "outputFile" to outputFile,
            "patcherLogMode" to mode.name, "stripNativeLibs" to stripNativeLibs,
            "skipUnneededSplits" to skipUnneededSplits,
            "continueOnPatchError" to prefs.continueOnPatchError.get(),
            "configurations" to available.map { (uid, bundle) ->
                mapOf("bundlePath" to bundle.patchesJar, "patches" to selected[uid].orEmpty().toList(),
                    "options" to options[uid].orEmpty())
            }
        )
        val legacy = !v22 && RevancedLegacyFormat
            .selectionUsesLegacy(available.values.map { it.patchesJar })
        val engine = when {
            v22 -> RuntimeEngine.REVANCED22
            legacy -> RuntimeEngine.REVANCED_LEGACY
            else -> RuntimeEngine.REVANCED21
        }
        client.execute(engine, params, available.values.map { it.patchesJar },
            runtimeClassPath = when {
                v22 -> app.universal.patch.manager.patcher.runtime.revanced.Revanced22RuntimeAssets.ensureRuntimeClassPath(context).path
                legacy -> Revanced21RuntimeAssets.ensureLegacyRuntimeClassPath(context).path
                else -> Revanced21RuntimeAssets.ensureRuntimeClassPath(context).path
            }
        ) { type, event ->
            when (type) {
                "log" -> runtimeLogger.log(LogLevel.valueOf(event["level"] as String), event["message"] as String)
                "event" -> onEvent(MorpheRuntimeBridge.mapToProgressEvent(event["event"] as Map<*, *>))
                "memory" -> onMemoryUsage((event["used"] as Number).toLong(), (event["max"] as Number).toLong())
            }
        }
    }
}
