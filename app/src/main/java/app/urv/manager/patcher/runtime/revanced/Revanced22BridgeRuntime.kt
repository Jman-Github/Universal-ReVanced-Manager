package app.urv.manager.patcher.runtime

import android.content.Context
import android.os.Build
import app.urv.manager.patcher.LibraryResolver
import app.urv.manager.patcher.ProgressEvent
import app.urv.manager.patcher.StepId
import app.urv.manager.patcher.logger.Logger
import app.urv.manager.patcher.logger.filtered
import app.urv.manager.patcher.runStep
import app.urv.manager.patcher.revanced.Revanced22BridgeFailureException
import app.urv.manager.patcher.revanced.Revanced22RuntimeBridge
import app.urv.manager.patcher.runtime.revanced.Revanced22RuntimeAssets
import app.urv.manager.patcher.split.SplitApkPreparer
import app.urv.manager.util.Options
import app.urv.manager.util.PatchSelection
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

class Revanced22BridgeRuntime(context: Context) : Runtime(context) {
    private val appContext = context.applicationContext
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
        onMemoryUsage: (usedMb: Long, maxMb: Long) -> Unit,
        stripNativeLibs: Boolean,
        skipUnneededSplits: Boolean,
    ) {
        val logMode = prefs.patcherLogMode.get()
        val runtimeLogger = logger.filtered(logMode)
        val memoryMonitor = PatcherMemoryMonitor.start(onMemoryUsage)
        try {
            ensureNotCancelled()
            val sourceInput = File(inputFile)
            val hostPreparation = if (SplitApkPreparer.isSplitArchive(sourceInput)) {
                runStep(
                    stepId = StepId.PrepareSplitApk,
                    onEvent = onEvent,
                    checkCancelled = ::ensureNotCancelled
                ) {
                    SplitApkPreparer.prepareIfNeeded(
                        source = sourceInput,
                        workspace = File(cacheDir),
                        logger = runtimeLogger,
                        stripNativeLibs = stripNativeLibs,
                        skipUnneededSplits = skipUnneededSplits,
                        onProgress = { message ->
                            onEvent(ProgressEvent.Progress(stepId = StepId.PrepareSplitApk, message = message))
                        },
                        onSubSteps = { subSteps ->
                            onEvent(ProgressEvent.Progress(stepId = StepId.PrepareSplitApk, subSteps = subSteps))
                        }
                    )
                }
            } else {
                null
            }
            val runtimeInputFile = hostPreparation?.file?.absolutePath ?: inputFile
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
            val apkEditorJarPath = Revanced22RuntimeAssets.ensureApkEditorJar(appContext).absolutePath
            val apkEditorMergeJarPath = Revanced22RuntimeAssets.ensureApkEditorMergeJar(appContext).absolutePath
            val runtimeClassPath = Revanced22RuntimeAssets.ensureRuntimeClassPath(appContext).absolutePath
            val appProcessPath = resolveAppProcessBin(appContext)

            val mergeMemoryLimitMb = MemoryLimitConfig.resolveMemoryLimitMb(
                appContext,
                prefs.processMemoryLimit.get()
            )

            val propOverridePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                resolvePropOverride(appContext)?.absolutePath
            } else {
                null
            }

            try {
                val params = mapOf(
                    "aaptPath" to aaptModernPath,
                    "aaptFallbackPath" to aaptLegacyPath,
                    "frameworkDir" to frameworkPath,
                    "cacheDir" to cacheDir,
                    "apkEditorJarPath" to apkEditorJarPath,
                    "apkEditorMergeJarPath" to apkEditorMergeJarPath,
                    "runtimeClassPath" to runtimeClassPath,
                    "propOverridePath" to propOverridePath,
                    "mergeMemoryLimitMb" to mergeMemoryLimitMb,
                    "appProcessPath" to appProcessPath,
                    "packageName" to packageName,
                    "inputFile" to runtimeInputFile,
                    "outputFile" to outputFile,
                    "patcherLogMode" to logMode.name,
                    "stripNativeLibs" to stripNativeLibs,
                    "skipUnneededSplits" to skipUnneededSplits,
                    "continueOnPatchError" to prefs.continueOnPatchError.get(),
                    "configurations" to configs
                )

                ensureNotCancelled()
                val error = Revanced22RuntimeBridge.runPatcher(params, runtimeLogger, onEvent, cancelRequested::get)
                if (!error.isNullOrBlank()) {
                    throw Revanced22BridgeFailureException(error)
                }
            } finally {
                hostPreparation?.cleanup()
            }
        } finally {
            memoryMonitor.stop()
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
