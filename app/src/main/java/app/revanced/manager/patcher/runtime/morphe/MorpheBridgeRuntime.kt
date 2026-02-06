package app.revanced.manager.patcher.runtime.morphe

import android.content.Context
import app.revanced.manager.patcher.ProgressEvent
import app.revanced.manager.patcher.logger.Logger
import app.revanced.manager.patcher.morphe.MorpheBridgeFailureException
import app.revanced.manager.patcher.morphe.MorpheRuntimeBridge
import app.revanced.manager.util.Options
import app.revanced.manager.util.PatchSelection

class MorpheBridgeRuntime(context: Context) : MorpheRuntime(context) {
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
        val configs = bundles().map { (bundleUid, bundle) ->
            mapOf(
                "bundlePath" to bundle.patchesJar,
                "patches" to selectedPatches[bundleUid].orEmpty().toList(),
                "options" to options[bundleUid].orEmpty()
            )
        }

        val params = mapOf(
            "aaptPath" to aaptPrimaryPath,
            "aaptFallbackPath" to aaptFallbackPath,
            "frameworkDir" to frameworkPath,
            "cacheDir" to cacheDir,
            "packageName" to packageName,
            "inputFile" to inputFile,
            "outputFile" to outputFile,
            "stripNativeLibs" to stripNativeLibs,
            "skipUnneededSplits" to skipUnneededSplits,
            "configurations" to configs
        )

        val error = MorpheRuntimeBridge.runPatcher(params, logger, onEvent)
        if (!error.isNullOrBlank()) {
            throw MorpheBridgeFailureException(error)
        }
    }
}
