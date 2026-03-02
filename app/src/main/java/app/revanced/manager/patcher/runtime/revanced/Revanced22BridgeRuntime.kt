package app.revanced.manager.patcher.runtime

import android.content.Context
import app.revanced.manager.patcher.ProgressEvent
import app.revanced.manager.patcher.logger.Logger
import app.revanced.manager.patcher.revanced.Revanced22BridgeFailureException
import app.revanced.manager.patcher.revanced.Revanced22RuntimeBridge
import app.revanced.manager.patcher.runtime.revanced.Revanced22RuntimeAssets
import app.revanced.manager.util.Options
import app.revanced.manager.util.PatchSelection

class Revanced22BridgeRuntime(context: Context) : Runtime(context) {
    private val appContext = context.applicationContext

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

        val apkEditorJarPath = Revanced22RuntimeAssets.ensureApkEditorJar(appContext).absolutePath
        val apkEditorMergeJarPath = Revanced22RuntimeAssets.ensureApkEditorMergeJar(appContext).absolutePath

        val params = mapOf(
            "aaptPath" to aaptPrimaryPath,
            "aaptFallbackPath" to aaptFallbackPath,
            "frameworkDir" to frameworkPath,
            "cacheDir" to cacheDir,
            "apkEditorJarPath" to apkEditorJarPath,
            "apkEditorMergeJarPath" to apkEditorMergeJarPath,
            "packageName" to packageName,
            "inputFile" to inputFile,
            "outputFile" to outputFile,
            "stripNativeLibs" to stripNativeLibs,
            "skipUnneededSplits" to skipUnneededSplits,
            "configurations" to configs
        )

        val error = Revanced22RuntimeBridge.runPatcher(params, logger, onEvent)
        if (!error.isNullOrBlank()) {
            throw Revanced22BridgeFailureException(error)
        }
    }
}
