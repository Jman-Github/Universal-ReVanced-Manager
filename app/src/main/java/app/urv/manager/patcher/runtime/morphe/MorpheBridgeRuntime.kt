package app.urv.manager.patcher.runtime.morphe

import android.content.Context
import app.urv.manager.patcher.ProgressEvent
import app.urv.manager.patcher.logger.Logger
import app.urv.manager.patcher.logger.filtered
import app.urv.manager.patcher.morphe.MorpheBridgeFailureException
import app.urv.manager.patcher.morphe.MorpheRuntimeBridge
import app.urv.manager.util.Options
import app.urv.manager.util.PatchSelection
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

class MorpheBridgeRuntime(context: Context) : MorpheRuntime(context) {
    private val cancelRequested = AtomicBoolean(false)

    override fun cancel() {
        cancelRequested.set(true)
    }

    private fun ensureNotCancelled() {
        if (cancelRequested.get()) {
            throw CancellationException("Patching cancelled")
        }
    }

    override suspend fun execute(
        inputFile: String,
        outputFile: String,
        packageName: String,
        selectedPatches: PatchSelection,
        options: Options,
        logger: Logger,
        onEvent: (ProgressEvent) -> Unit,
        stripNativeLibs: Boolean,
        skipUnneededSplits: Boolean,
    ) {
        val logMode = prefs.patcherLogMode.get()
        val runtimeLogger = logger.filtered(logMode)
        ensureNotCancelled()
        val activeSelectedPatches = selectedPatches.filterValues { it.isNotEmpty() }
        val selectedBundleIds = activeSelectedPatches.keys
        val bundlesByUid = bundles()
        val selectedBundlesByUid = bundlesByUid.filterKeys { it in selectedBundleIds }
        val staleBundleIds = selectedBundleIds - selectedBundlesByUid.keys
        if (staleBundleIds.isNotEmpty()) {
            runtimeLogger.warn("Ignoring missing patch bundle IDs in selection: ${staleBundleIds.joinToString(",")}")
        }
        if (activeSelectedPatches.isNotEmpty() && selectedBundlesByUid.isEmpty()) {
            throw IllegalArgumentException(
                "Selected patches are unavailable. Re-open patch selection and select patches again."
            )
        }

        val configs = selectedBundlesByUid.map { (bundleUid, bundle) ->
            mapOf(
                "bundlePath" to bundle.patchesJar,
                "patches" to activeSelectedPatches[bundleUid].orEmpty().toList(),
                "options" to options[bundleUid].orEmpty()
            )
        }

        val params = mapOf(
            "aaptPath" to aaptPath,
            "frameworkDir" to frameworkPath,
            "cacheDir" to cacheDir,
            "packageName" to packageName,
            "inputFile" to inputFile,
            "outputFile" to outputFile,
            "patcherLogMode" to logMode.name,
            "stripNativeLibs" to stripNativeLibs,
            "skipUnneededSplits" to skipUnneededSplits,
            "continueOnPatchError" to prefs.continueOnPatchError.get(),
            "bytecodeMode" to prefs.morpheBytecodeMode.get().runtimeValue,
            "configurations" to configs
        )

        ensureNotCancelled()
        val error = MorpheRuntimeBridge.runPatcher(params, runtimeLogger, onEvent, cancelRequested::get)
        if (!error.isNullOrBlank()) {
            throw MorpheBridgeFailureException(error)
        }
    }
}
