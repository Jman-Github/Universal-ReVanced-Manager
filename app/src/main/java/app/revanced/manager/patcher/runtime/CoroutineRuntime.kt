package app.revanced.manager.patcher.runtime

import android.content.Context
import app.revanced.manager.patcher.ProgressEvent
import app.revanced.manager.patcher.Session
import app.revanced.manager.patcher.StepId
import app.revanced.manager.patcher.logger.Logger
import app.revanced.manager.patcher.patch.PatchBundle
import app.revanced.manager.patcher.runStep
import app.revanced.manager.patcher.split.SplitApkPreparer
import app.revanced.manager.util.Options
import app.revanced.manager.util.PatchSelection
import java.io.File

/**
 * Simple [Runtime] implementation that runs the patcher using coroutines.
 */
class CoroutineRuntime(context: Context) : Runtime(context) {
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
        val (patchList, relatedBundleArchives) = runStep(StepId.LoadPatches, onEvent) {
            val activeSelectedPatches = selectedPatches.filterValues { it.isNotEmpty() }
            val selectedBundles = activeSelectedPatches.keys
            val patchBundlesByUid = bundles()
            val selectedPatchBundlesByUid = patchBundlesByUid
                .filterKeys { it in selectedBundles }
            val staleBundleIds = selectedBundles - selectedPatchBundlesByUid.keys
            if (staleBundleIds.isNotEmpty()) {
                logger.warn(
                    "Ignoring missing patch bundle IDs in selection: ${
                        staleBundleIds.joinToString(",")
                    }"
                )
            }
            val uids = selectedPatchBundlesByUid.entries.associate { (key, value) -> value to key }

            val allPatches =
                PatchBundle.Loader.patches(selectedPatchBundlesByUid.values, packageName)
                    .mapKeys { (b, _) -> uids[b]!! }

            val patchList = activeSelectedPatches.flatMap { (bundle, selected) ->
                allPatches[bundle].orEmpty().filter { it.name in selected }
            }

            if (activeSelectedPatches.isNotEmpty() && patchList.isEmpty()) {
                throw IllegalArgumentException(
                    "Selected patches are unavailable. Re-open patch selection and select patches again."
                )
            }

            // Set all patch options.
            options.forEach { (bundle, bundlePatchOptions) ->
                val patches = allPatches[bundle] ?: return@forEach
                bundlePatchOptions.forEach { (patchName, configuredPatchOptions) ->
                    val patchOptions = patches.single { it.name == patchName }.options
                    configuredPatchOptions.forEach { (key, value) ->
                        patchOptions[key] = value
                    }
                }
            }

            patchList to selectedPatchBundlesByUid.values.map { File(it.patchesJar) }
        }

        val input = File(inputFile)
        val preparation = if (SplitApkPreparer.isSplitArchive(input)) {
            runStep(StepId.PrepareSplitApk, onEvent) {
                SplitApkPreparer.prepareIfNeeded(
                    input,
                    File(cacheDir),
                    logger,
                    stripNativeLibs,
                    skipUnneededSplits,
                    onProgress = { message ->
                        onEvent(ProgressEvent.Progress(stepId = StepId.PrepareSplitApk, message = message))
                    },
                    onSubSteps = { subSteps ->
                        onEvent(ProgressEvent.Progress(stepId = StepId.PrepareSplitApk, subSteps = subSteps))
                    }
                )
            }
        } else {
            SplitApkPreparer.prepareIfNeeded(
                input,
                File(cacheDir),
                logger,
                stripNativeLibs,
                skipUnneededSplits,
                onProgress = { message ->
                    onEvent(ProgressEvent.Progress(stepId = StepId.PrepareSplitApk, message = message))
                },
                onSubSteps = { subSteps ->
                    onEvent(ProgressEvent.Progress(stepId = StepId.PrepareSplitApk, subSteps = subSteps))
                }
            )
        }

        try {
            val selectedAaptPath = resolveAaptPath(preparation.file, logger, relatedBundleArchives)
            val frameworkDir = FrameworkCacheResolver.resolve(
                baseFrameworkDir = frameworkPath,
                runtimeTag = "revanced",
                apkFile = preparation.file,
                aaptPath = selectedAaptPath,
                logger = logger
            )
            val session = runStep(StepId.ReadAPK, onEvent) {
                Session(
                    cacheDir,
                    frameworkDir,
                    selectedAaptPath,
                    logger,
                    preparation.file,
                    onEvent
                )
            }

            session.use { s ->
                s.run(
                    File(outputFile),
                    patchList,
                    stripNativeLibs,
                    preparation.merged
                )
            }
        } finally {
            preparation.cleanup()
        }
    }
}
