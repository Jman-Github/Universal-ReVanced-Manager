package app.revanced.manager.patcher.runtime

import android.content.Context
import android.os.Build
import app.revanced.manager.patcher.LibraryResolver
import app.revanced.manager.patcher.ProgressEvent
import app.revanced.manager.patcher.logger.Logger
import app.revanced.manager.patcher.revanced.Revanced22BridgeFailureException
import app.revanced.manager.patcher.revanced.Revanced22RuntimeBridge
import app.revanced.manager.patcher.runtime.revanced.Revanced22RuntimeAssets
import app.revanced.manager.util.Options
import app.revanced.manager.util.PatchSelection
import java.io.File

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
        val activeSelectedPatches = selectedPatches.filterValues { it.isNotEmpty() }
        val selectedBundleIds = activeSelectedPatches.keys
        val bundlesByUid = bundles()
        val selectedBundlesByUid = bundlesByUid.filterKeys { it in selectedBundleIds }
        val staleBundleIds = selectedBundleIds - selectedBundlesByUid.keys
        if (staleBundleIds.isNotEmpty()) {
            logger.warn("Ignoring missing patch bundle IDs in selection: ${staleBundleIds.joinToString(",")}")
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

        val apkEditorJarPath = Revanced22RuntimeAssets.ensureApkEditorJar(appContext).absolutePath
        val apkEditorMergeJarPath = Revanced22RuntimeAssets.ensureApkEditorMergeJar(appContext).absolutePath
        val runtimeClassPath = Revanced22RuntimeAssets.ensureRuntimeClassPath(appContext).absolutePath
        val appProcessPath = resolveAppProcessBin(appContext)

        val requestedLimit = prefs.patcherProcessMemoryLimit.get()
        val aggressiveLimit = prefs.patcherProcessMemoryAggressive.get()
        val mergeMemoryLimitMb = MemoryLimitConfig.clampLimitMb(
            appContext,
            if (aggressiveLimit) {
                MemoryLimitConfig.maxLimitMb(appContext)
            } else {
                requestedLimit
            }
        )

        val propOverridePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            resolvePropOverride(appContext)?.absolutePath
        } else {
            null
        }

        val params = mapOf(
            "aaptPath" to aaptPrimaryPath,
            "aaptFallbackPath" to aaptFallbackPath,
            "frameworkDir" to frameworkPath,
            "cacheDir" to cacheDir,
            "apkEditorJarPath" to apkEditorJarPath,
            "apkEditorMergeJarPath" to apkEditorMergeJarPath,
            "runtimeClassPath" to runtimeClassPath,
            "propOverridePath" to propOverridePath,
            "mergeMemoryLimitMb" to mergeMemoryLimitMb,
            "appProcessPath" to appProcessPath,
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

    companion object : LibraryResolver() {
        private const val APP_PROCESS_BIN_PATH = "/system/bin/app_process"
        private const val APP_PROCESS_BIN_PATH_64 = "/system/bin/app_process64"
        private const val APP_PROCESS_BIN_PATH_32 = "/system/bin/app_process32"

        private fun resolvePropOverride(context: Context) = findLibrary(context, "prop_override")

        private fun resolveAppProcessBin(context: Context): String {
            val is64Bit = context.applicationInfo.nativeLibraryDir.contains("64")
            val preferred = if (is64Bit) APP_PROCESS_BIN_PATH_64 else APP_PROCESS_BIN_PATH_32
            return if (File(preferred).exists()) preferred else APP_PROCESS_BIN_PATH
        }
    }
}
