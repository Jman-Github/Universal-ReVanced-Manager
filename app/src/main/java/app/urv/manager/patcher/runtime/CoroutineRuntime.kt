package app.urv.manager.patcher.runtime

import android.content.Context
import app.urv.manager.patcher.PatchList
import app.urv.manager.patcher.ProgressEvent
import app.urv.manager.patcher.Session
import app.urv.manager.patcher.StepId
import app.urv.manager.patcher.logger.Logger
import app.urv.manager.patcher.patch.PatchBundle
import app.urv.manager.patcher.runStep
import app.urv.manager.patcher.split.SplitApkPreparer
import app.urv.manager.patcher.util.MislabeledImageResourceSanitizer
import app.urv.manager.util.Options
import app.urv.manager.util.PatchSelection
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/**
 * Simple [Runtime] implementation that runs the patcher using coroutines.
 */
class CoroutineRuntime(context: Context) : Runtime(context) {
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
        val input = File(inputFile)
        suspend fun prepareInput() = SplitApkPreparer.prepareIfNeeded(
            input,
            File(cacheDir),
            logger,
            stripNativeLibs,
            skipUnneededSplits,
            onProgress = { message ->
                ensureNotCancelled()
                onEvent(ProgressEvent.Progress(stepId = StepId.PrepareSplitApk, message = message))
            },
            onSubSteps = { subSteps ->
                ensureNotCancelled()
                onEvent(ProgressEvent.Progress(stepId = StepId.PrepareSplitApk, subSteps = subSteps))
            }
        )
        var preparation: SplitApkPreparer.PreparationResult? = null
        if (SplitApkPreparer.isSplitArchive(input)) {
            preparation = runStep(StepId.PrepareSplitApk, onEvent, ::ensureNotCancelled) {
                prepareInput()
            }
        }
        val (loadSelectedPatches, relatedBundleArchives) = run {
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

            var cachedSelectedPatches: PatchList? = null
            suspend fun loadSelectedPatches(): PatchList {
                cachedSelectedPatches?.let { return it }

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

                return patchList.also { cachedSelectedPatches = it }
            }

            runStep(StepId.LoadPatches, onEvent, ::ensureNotCancelled) {
                loadSelectedPatches().size
            }

            ::loadSelectedPatches to selectedPatchBundlesByUid.values.map { File(it.patchesJar) }
        }

        var sanitizedInput: MislabeledImageResourceSanitizer.Result? = null
        try {
            val session = runStep(StepId.ReadAPK, onEvent, ::ensureNotCancelled) {
                val preparedInput = preparation ?: prepareInput().also { preparation = it }
                val sanitized = MislabeledImageResourceSanitizer.sanitizeApkFile(
                    apkFile = preparedInput.file,
                    workingDir = File(cacheDir).resolve("patcher-inputs"),
                    logger = logger
                )
                sanitizedInput = sanitized
                val patcherInput = sanitized.file
                val selectedAaptPath = resolveAaptPath(patcherInput, logger, relatedBundleArchives)
                val frameworkDir = FrameworkCacheResolver.resolve(
                    baseFrameworkDir = frameworkPath,
                    runtimeTag = "revanced",
                    apkFile = patcherInput,
                    aaptPath = selectedAaptPath,
                    logger = logger
                )
                Session.open(
                    cacheDir = cacheDir,
                    frameworkDir = frameworkDir,
                    aaptPath = selectedAaptPath,
                    logger = logger,
                    input = preparedInput.file,
                    initialPatcherInput = patcherInput,
                    sanitizeAllEmbeddedApksOnInit = preparedInput.merged,
                    onEvent = onEvent,
                    checkCancelled = ::ensureNotCancelled
                )
            }
            val preparedInput = requireNotNull(preparation) {
                "APK preparation did not produce an input file."
            }

            ensureNotCancelled()
            session.use { s ->
                s.run(
                    File(outputFile),
                    { loadSelectedPatches() },
                    stripNativeLibs,
                    preparedInput.merged
                )
            }
        } finally {
            sanitizedInput?.cleanup()
            preparation?.cleanup()
        }
    }
}
