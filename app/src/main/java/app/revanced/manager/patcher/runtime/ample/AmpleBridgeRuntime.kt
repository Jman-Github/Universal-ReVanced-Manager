package app.revanced.manager.patcher.runtime.ample

import android.content.Context
import app.revanced.manager.patcher.ProgressEvent
import app.revanced.manager.patcher.logger.Logger
import app.revanced.manager.patcher.ample.AmpleBridgeFailureException
import app.revanced.manager.patcher.ample.AmpleRuntimeBridge
import app.revanced.manager.util.Options
import app.revanced.manager.util.PatchSelection

class AmpleBridgeRuntime(context: Context) : AmpleRuntime(context) {
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

        val apkEditorJarPath = AmpleRuntimeAssets.ensureApkEditorJar(appContext).absolutePath
        val apkEditorMergeJarPath = AmpleRuntimeAssets.ensureApkEditorMergeJar(appContext).absolutePath

        val params = mapOf(
            "aaptPath" to aaptPath,
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

        val error = AmpleRuntimeBridge.runPatcher(params, logger, onEvent)
        if (!error.isNullOrBlank()) {
            throw AmpleBridgeFailureException(error)
        }
    }
}
