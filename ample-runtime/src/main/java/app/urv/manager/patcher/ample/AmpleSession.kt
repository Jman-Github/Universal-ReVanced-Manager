package app.urv.manager.patcher.ample

import android.os.Build
import app.urv.manager.patcher.ProgressEvent
import app.urv.manager.patcher.StepId
import app.urv.manager.patcher.ample.AmpleSession.Companion.component1
import app.urv.manager.patcher.ample.AmpleSession.Companion.component2
import app.urv.manager.patcher.logger.Logger
import app.urv.manager.patcher.runStep
import app.urv.manager.patcher.runCancellableBlockingIo
import app.urv.manager.patcher.toRemoteError
import app.urv.manager.patcher.toSafeRemoteError
import app.urv.manager.patcher.toSafeStackTraceString
import app.urv.manager.patcher.util.ManifestDecimalResourceReferenceSanitizer
import app.urv.manager.patcher.util.MislabeledImageResourceSanitizer
import app.urv.manager.patcher.util.NativeLibStripper
import app.urv.manager.patcher.util.XmlSurrogateSanitizer
import app.revanced.patcher.Patcher
import app.revanced.patcher.PatcherConfig
import app.revanced.patcher.PatcherResult
import app.revanced.patcher.patch.Patch
import app.revanced.patcher.patch.PatchResult
import app.urv.manager.patcher.split.SplitApkPreparer
import com.android.tools.build.apkzlib.zip.AlignmentRules
import com.android.tools.build.apkzlib.zip.ZFile
import com.android.tools.build.apkzlib.zip.ZFileOptions
import com.google.common.base.Predicate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Enumeration
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.LinkedHashSet
import java.util.Locale

internal typealias AmplePatchList = List<Patch<*>>

class AmpleSession private constructor(
    cacheDir: String,
    frameworkDir: String,
    aaptPath: String,
    private val logger: Logger,
    private val input: File,
    private val initialPatcherInput: File? = null,
    private val sanitizeAllEmbeddedApksOnInit: Boolean = false,
    private val onEvent: (ProgressEvent) -> Unit,
    private val checkCancelled: () -> Unit = {},
    private val continueOnPatchError: Boolean = false,
) : Closeable {
    private val tempDir = File(cacheDir).resolve("patcher").also { it.mkdirs() }
    private val patcherInputDir = File(cacheDir).resolve("patcher-inputs").also { it.mkdirs() }
    private val frameworkDirFile = File(frameworkDir).also { it.mkdirs() }
    private val resolvedAaptPath = aaptPath
    private var patcherInput = initializePatcherInput()
    private lateinit var patcher: Patcher

    private fun initializePatcherInput(): PreparedPatcherInput {
        val baseInput = initialPatcherInput ?: input
        return prepareLegacyPatcherFallbackInput(
            sourceApk = baseInput,
            logReason = false,
            hideAllEmbeddedApks = sanitizeAllEmbeddedApksOnInit
        ) ?: PreparedPatcherInput(baseInput)
    }

    private suspend fun initializePatcher() {
        logger.info("Initializing legacy patcher")
        patcher = try {
            createPatcherWithTimeout(patcherInput.file)
        } catch (originalError: Throwable) {
            rethrowIfActuallyCancelled(originalError)
            val fallbackInput = prepareLegacyPatcherFallbackInput(originalError) ?: throw originalError
            patcherInput.cleanup()
            patcherInput = fallbackInput
            try {
                createPatcherWithTimeout(fallbackInput.file)
            } catch (fallbackError: Throwable) {
                fallbackInput.cleanup()
                rethrowIfActuallyCancelled(fallbackError)
                originalError.addSuppressed(fallbackError)
                throw originalError
            }
        }
        logger.info("Legacy patcher initialized")
    }

    private fun rethrowIfActuallyCancelled(error: Throwable) {
        if (error is CancellationException) {
            checkCancelled()
        }
    }

    private suspend fun createPatcherWithTimeout(apkFile: File): Patcher =
        withTimeout(PATCHER_INIT_TIMEOUT_MS) {
            runCancellableBlockingIo(checkCancelled) { createPatcher(apkFile) }
        }

    private fun createPatcher(apkFile: File) = Patcher(
        PatcherConfig(
            apkFile = apkFile,
            temporaryFilesPath = tempDir,
            frameworkFileDirectory = frameworkDirFile.absolutePath,
            aaptBinaryPath = resolvedAaptPath
        )
    )

    private fun requirePatcher(): Patcher {
        check(::patcher.isInitialized) { "Patcher has not been initialized." }
        return patcher
    }

    private suspend fun Patcher.applyPatchesVerbose(
        selectedPatches: AmplePatchList,
        preStarted: Set<Int> = emptySet()
    ) {
        if (selectedPatches.isEmpty()) return
        val indexByPatch = selectedPatches.withIndex().associate { it.value to it.index }
        val started = mutableSetOf<Int>()
        started.addAll(preStarted)
        val failedPatchIndexes = mutableSetOf<Int>()
        var firstPatchFailure: Throwable? = null
        var nextIndex = 0

        fun startPatch(index: Int) {
            checkCancelled()
            if (!started.add(index)) return
            onEvent(ProgressEvent.Started(StepId.ExecutePatch(index)))
        }

        startPatch(0)
        this().collect { (patch, exception) ->
            checkCancelled()
            val index = indexByPatch[patch] ?: return@collect

            if (exception != null) {
                fun recordFailure() {
                    if (firstPatchFailure == null) {
                        firstPatchFailure = exception
                    }
                    failedPatchIndexes += index
                    onEvent(ProgressEvent.Failed(StepId.ExecutePatch(index), exception.toSafeRemoteError()))
                    logger.error("${patch.name} failed:")
                    logger.error(exception.toSafeStackTraceString())
                }

                if (index < nextIndex) {
                    recordFailure()
                    if (continueOnPatchError && !isLikelyFrameworkDecodeFailure(exception)) return@collect
                    throw exception
                }
                while (nextIndex < index) {
                    startPatch(nextIndex)
                    onEvent(ProgressEvent.Completed(StepId.ExecutePatch(nextIndex)))
                    logger.info("${selectedPatches[nextIndex].name} succeeded")
                    nextIndex += 1
                }
                startPatch(index)
                recordFailure()
                if (continueOnPatchError && !isLikelyFrameworkDecodeFailure(exception)) {
                    nextIndex = index + 1
                    if (nextIndex < selectedPatches.size) {
                        startPatch(nextIndex)
                    }
                    return@collect
                }
                throw exception
            }

            if (index < nextIndex) return@collect
            while (nextIndex < index) {
                startPatch(nextIndex)
                onEvent(ProgressEvent.Completed(StepId.ExecutePatch(nextIndex)))
                logger.info("${selectedPatches[nextIndex].name} succeeded")
                nextIndex += 1
            }
            startPatch(index)
            onEvent(ProgressEvent.Completed(StepId.ExecutePatch(index)))
            logger.info("${patch.name} succeeded")
            nextIndex = index + 1
            if (nextIndex < selectedPatches.size) {
                startPatch(nextIndex)
            }
        }
        if (continueOnPatchError && failedPatchIndexes.size == selectedPatches.size) {
            throw firstPatchFailure ?: IllegalStateException("All selected patches failed")
        }
    }

    private suspend fun executePatchesOnce(orderedPatches: AmplePatchList) {
        checkCancelled()
        with(requirePatcher()) {
            if (orderedPatches.isNotEmpty()) {
                onEvent(ProgressEvent.Started(StepId.ExecutePatch(0)))
            }
            logger.info("Merging integrations")
            this += LinkedHashSet(orderedPatches)

            logger.info("Applying patches...")
            applyPatchesVerbose(
                orderedPatches,
                preStarted = if (orderedPatches.isNotEmpty()) setOf(0) else emptySet()
            )
        }
    }

    private suspend fun executePatchesWithFrameworkRecovery(orderedPatches: AmplePatchList) {
        ensureFrameworkCacheIsValid()
        try {
            executePatchesOnce(orderedPatches)
        } catch (error: Throwable) {
            if (error is CancellationException || !isLikelyFrameworkDecodeFailure(error)) {
                throw error
            }

            logger.warn(
                "Framework decode failed, clearing framework cache and retrying once: " +
                    "${error::class.java.simpleName}: ${error.message ?: "unknown error"}"
            )
            clearFrameworkCache("framework decode failure retry")
            ensureFrameworkCacheIsValid()
            executePatchesOnce(orderedPatches)
        }
    }

    private fun ensureFrameworkCacheIsValid() {
        val frameworkApk = frameworkDirFile.resolve(FRAMEWORK_APK_NAME)
        if (!frameworkApk.exists()) {
            seedBundledFrameworkCache(frameworkApk)
            return
        }

        val issue = frameworkApkValidationIssue(frameworkApk) ?: return
        logger.warn("Invalid framework cache at ${frameworkApk.absolutePath}: $issue")
        clearFrameworkCache("preflight validation failed")
        seedBundledFrameworkCache(frameworkApk)
    }

    private fun frameworkApkValidationIssue(file: File): String? {
        if (!file.isFile) return "not a regular file"
        if (file.length() <= 0L) return "file is empty"

        val zipIssue = runCatching {
            ZipFile(file).use { zip ->
                if (zip.getEntry(FRAMEWORK_RESOURCES_TABLE) == null) {
                    "missing $FRAMEWORK_RESOURCES_TABLE"
                } else {
                    null
                }
            }
        }.getOrElse { error ->
            "${error::class.java.simpleName}: ${error.message ?: "failed to parse zip"}"
        }
        if (zipIssue != null) return zipIssue

        return frameworkIncludePathValidationIssue(file)
    }

    private fun frameworkIncludePathValidationIssue(file: File): String? = runCatching {
        tempDir.mkdirs()
        val outputFile = File.createTempFile("framework-include-check-", ".log", tempDir)
        try {
            val process = ProcessBuilder(
                resolvedAaptPath,
                "dump",
                "resources",
                file.absolutePath
            )
                .redirectErrorStream(true)
                .redirectOutput(outputFile)
                .start()

            val completed = process.waitFor(20, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return@runCatching "aapt2 validation timed out"
            }

            if (process.exitValue() == 0) {
                null
            } else {
                val detail = outputFile.useLines { lines ->
                    lines.map(String::trim).firstOrNull { it.isNotEmpty() }
                }
                detail?.let { "aapt2 rejected framework: $it" }
                    ?: "aapt2 rejected framework (exit code ${process.exitValue()})"
            }
        } finally {
            outputFile.delete()
        }
    }.getOrElse { error ->
        "aapt2 validation failed: ${error::class.java.simpleName}: ${error.message ?: "unknown error"}"
    }

    private fun clearFrameworkCache(reason: String) {
        frameworkDirFile.mkdirs()
        val entries = frameworkDirFile.listFiles().orEmpty()
        if (entries.isEmpty()) return

        var failedDeletes = 0
        entries.forEach { entry ->
            if (!entry.deleteRecursively()) {
                failedDeletes += 1
            }
        }

        if (failedDeletes == 0) {
            logger.warn("Cleared framework cache ($reason)")
        } else {
            logger.warn("Cleared framework cache ($reason) with $failedDeletes undeleted entr${if (failedDeletes == 1) "y" else "ies"}")
        }
    }

    private fun seedBundledFrameworkCache(frameworkApk: File): Boolean {
        if (hasEmbeddedPrebuiltFramework()) {
            return false
        }

        val loader = javaClass.classLoader
        if (loader == null) {
            logger.warn("Could not seed framework cache because the runtime class loader is unavailable")
            return false
        }

        for (sdk in bundledFrameworkSdkCandidates()) {
            val resourcePath = "frameworks/android/android-$sdk.apk"
            val input = loader.getResourceAsStream(resourcePath)
                ?: javaClass.getResourceAsStream("/$resourcePath")
                ?: continue

            val seeded = runCatching {
                frameworkApk.parentFile?.mkdirs()
                input.use { bundled ->
                    FileOutputStream(frameworkApk).use { output ->
                        bundled.copyTo(output)
                    }
                }

                val issue = frameworkApkValidationIssue(frameworkApk)
                if (issue != null) {
                    frameworkApk.delete()
                    logger.warn("Bundled framework resource $resourcePath is invalid: $issue")
                    false
                } else {
                    logger.info("Seeded framework cache from bundled resource: $resourcePath")
                    true
                }
            }.getOrElse { error ->
                frameworkApk.delete()
                logger.warn(
                    "Failed to seed framework cache from bundled resource $resourcePath: " +
                        "${error::class.java.simpleName}: ${error.message ?: "unknown error"}"
                )
                false
            }

            if (seeded) {
                return true
            }
        }

        logger.warn("No bundled Android framework resource was found for SDK ${Build.VERSION.SDK_INT} or lower")
        return false
    }

    private fun hasEmbeddedPrebuiltFramework(): Boolean =
        javaClass.getResource("/prebuilt/android-framework.jar") != null ||
            javaClass.classLoader?.getResource("prebuilt/android-framework.jar") != null

    private fun bundledFrameworkSdkCandidates(): IntProgression {
        val maxSdk = Build.VERSION.SDK_INT.coerceAtLeast(MIN_BUNDLED_FRAMEWORK_SDK)
        return maxSdk downTo MIN_BUNDLED_FRAMEWORK_SDK
    }

    private fun isLikelyFrameworkDecodeFailure(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { cause ->
            cause.stackTrace.any { frame ->
                frame.className == "brut.androlib.res.Framework" ||
                    frame.className.startsWith("brut.androlib.res.Framework$") ||
                    frame.className.startsWith("brut.androlib.res.data.ResTable") ||
                    frame.className.startsWith("brut.androlib.res.decoder.AXmlResourceParser")
            }
        }

    suspend fun run(
        output: File,
        loadSelectedPatches: suspend () -> AmplePatchList,
        stripNativeLibs: Boolean,
        inputWasSplit: Boolean
    ) {
        checkCancelled()
        val shouldStripNativeLibs = stripNativeLibs && !inputWasSplit
        runStep(StepId.ExecutePatches, onEvent, checkCancelled) {
            val orderedPatches = loadSelectedPatches().sortedBy { it.name }
            java.util.logging.Logger.getLogger("").apply {
                handlers.forEach {
                    it.close()
                    removeHandler(it)
                }

                addHandler(logger.handler)
            }
            executePatchesWithFrameworkRecovery(orderedPatches)
        }

        suspend fun writePatchedApkStep() {
            runStep(
                StepId.WriteAPK,
                onEvent,
                checkCancelled,
                startedSubSteps = buildWriteApkSubSteps(
                    includeStripNativeLibs = shouldStripNativeLibs
                )
            ) {
                checkCancelled()
                logger.info("Writing patched files...")
                XmlSurrogateSanitizer.sanitize(tempDir.resolve("apk"), logger)
                ManifestDecimalResourceReferenceSanitizer.sanitize(tempDir.resolve("apk"), logger)
                MislabeledImageResourceSanitizer.sanitizeDecodedResources(
                    tempDir.resolve("apk").resolve("res"),
                    logger
                )
                checkCancelled()
                val result = runCancellableBlockingIo(checkCancelled) { requirePatcher().get() }
                onEvent(
                    ProgressEvent.Progress(
                        stepId = StepId.WriteAPK,
                        message = "Copying base APK"
                    )
                )

                val patched = tempDir.resolve("result.apk")
                runCancellableBlockingIo(checkCancelled) {
                    fastCopy(input, patched)
                }
                checkCancelled()
                onEvent(
                    ProgressEvent.Progress(
                        stepId = StepId.WriteAPK,
                        message = "Applying patched changes"
                    )
                )
                runCancellableBlockingIo(checkCancelled) {
                    applyResultToApk(patched, result)
                }
                checkCancelled()
                runCancellableBlockingIo(checkCancelled) {
                    restoreHiddenEntriesIfNeeded(patched)
                }
                checkCancelled()

                logger.info("Patched apk saved to $patched")

                runCancellableBlockingIo(checkCancelled) {
                    checkCancelled()
                    onEvent(
                        ProgressEvent.Progress(
                            stepId = StepId.WriteAPK,
                            message = "Writing output APK"
                        )
                    )
                    try {
                        Files.move(
                            patched.toPath(),
                            output.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE
                        )
                    } catch (_: Exception) {
                        Files.move(
                            patched.toPath(),
                            output.toPath(),
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    }
                }
                onEvent(
                    ProgressEvent.Progress(
                        stepId = StepId.WriteAPK,
                        message = "Finalizing output"
                    )
                )
                if (shouldStripNativeLibs) {
                    checkCancelled()
                    onEvent(
                        ProgressEvent.Progress(
                            stepId = StepId.WriteAPK,
                            message = "Stripping native libraries"
                        )
                    )
                    NativeLibStripper.strip(output, checkCancelled = checkCancelled)
                    checkCancelled()
                }
            }
        }

        writePatchedApkStep()
    }

    private fun buildWriteApkSubSteps(
        includeStripNativeLibs: Boolean = false
    ): List<String> = buildList {
        add("Copying base APK")
        add("Applying patched changes")
        add("Compiling DEX files")
        add("Compiling modified resources")
        add("Writing output APK")
        add("Finalizing output")
        if (includeStripNativeLibs) {
            add("Stripping native libraries")
        }
    }

    private fun applyResultToApk(apkFile: File, result: PatcherResult) {
        ZFile.openReadWrite(apkFile, zFileOptions).use { apk ->
            result.dexFiles.forEach { dex ->
                checkCancelled()
                val entryName = dex.name
                if (isDexEntryName(entryName)) {
                    onEvent(
                        ProgressEvent.Progress(
                            stepId = StepId.WriteAPK,
                            message = "Compiling $entryName"
                        )
                    )
                }
                dex.stream.use { stream ->
                    apk.add(entryName, stream)
                }
            }

            result.resources?.let { resources ->
                onEvent(
                    ProgressEvent.Progress(
                        stepId = StepId.WriteAPK,
                        message = "Compiling modified resources"
                    )
                )
                resources.resourcesApk?.let { resourcesApkFile ->
                    ZFile.openReadOnly(resourcesApkFile).use { resourcesApk ->
                        apk.entries()
                            .filter { it.centralDirectoryHeader.name.startsWith("res/") }
                            .toList()
                            .forEach { it.delete() }
                        apk.mergeFrom(resourcesApk, Predicate { false })
                    }
                }

                resources.otherResources?.let { resourcesDir ->
                    if (resourcesDir.exists()) {
                        val noCompress = resources.doNotCompress
                        apk.addAllRecursively(resourcesDir, Predicate { file ->
                            val relative = file.relativeTo(resourcesDir).path.replace(File.separatorChar, '/')
                            relative !in noCompress
                        })
                    }
                }

                if (resources.deleteResources.isNotEmpty()) {
                    val deleteResources = resources.deleteResources
                    apk.entries()
                        .filter { it.centralDirectoryHeader.name in deleteResources }
                        .toList()
                        .forEach { it.delete() }
                }
            }

            logger.info("Aligning APK")
            apk.realign()
        }
    }

    private fun isDexEntryName(name: String): Boolean =
        name.startsWith("classes") && name.endsWith(".dex", ignoreCase = true)

    private fun dexSortKey(name: String): Int {
        val base = name.removeSuffix(".dex")
        if (base == "classes") return 1
        val suffix = base.removePrefix("classes")
        return suffix.toIntOrNull() ?: Int.MAX_VALUE
    }

    private suspend fun listWritePreloadDexNames(apkDir: File, inputFile: File): List<String> {
        val decodedDexNames = listDexNamesFromDecodedApkDir(apkDir)
        val inputDexNames = listDexNames(inputFile)
        return (inputDexNames + decodedDexNames)
            .distinct()
            .sortedWith(compareBy(::dexSortKey))
    }

    private suspend fun listDexNamesFromDecodedApkDir(apkDir: File): List<String> =
        runInterruptible(Dispatchers.IO) {
            if (!apkDir.isDirectory) return@runInterruptible emptyList<String>()
            val dexNames = mutableSetOf<String>()
            apkDir.walkTopDown().forEach { entry ->
                when {
                    entry.isDirectory -> decodedDexDirectoryToDexName(entry.name)?.let(dexNames::add)
                    entry.isFile &&
                        entry.name.startsWith("classes") &&
                        entry.name.endsWith(".dex", ignoreCase = true) -> dexNames.add(entry.name)
                }
            }
            dexNames.sortedWith(compareBy { dexSortKey(it) })
        }

    private fun decodedDexDirectoryToDexName(name: String): String? = when {
        name.equals("smali", ignoreCase = true) -> "classes.dex"
        name.startsWith("smali_classes", ignoreCase = true) -> {
            val suffix = name.substring("smali_classes".length)
            suffix.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.let { "classes${it}.dex" }
        }

        else -> null
    }

    private fun fastCopy(source: File, target: File) {
        FileInputStream(source).channel.use { input ->
            FileOutputStream(target).channel.use { output ->
                var position = 0L
                val size = input.size()
                while (position < size) {
                    position += input.transferTo(position, size - position, output)
                }
            }
        }
    }

    private suspend fun listDexNames(file: File): List<String> {
        if (!file.exists()) return emptyList()
        if (!SplitApkPreparer.isSplitArchive(file)) {
            return listDexNamesFromApk(file)
        }
        return listDexNamesFromSplitArchive(file)
    }

    private fun mergeDexNames(
        initialDexNames: List<String>,
        result: PatcherResult
    ): List<String> {
        val patchedDexNames = result.dexFiles
            .mapNotNull { it.name }
            .filter { it.endsWith(".dex", ignoreCase = true) }
        if (patchedDexNames.isEmpty()) return initialDexNames
        return (initialDexNames + patchedDexNames)
            .distinct()
            .sortedWith(compareBy { dexSortKey(it) })
    }

    private suspend fun listDexNamesFromApk(file: File): List<String> =
        runInterruptible(Dispatchers.IO) {
            if (!file.exists()) return@runInterruptible emptyList<String>()
            ZipFile(file).use { zip ->
                zip.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .map { it.name }
                    .filter { it.startsWith("classes") && it.endsWith(".dex") }
                    .sortedWith(compareBy { dexSortKey(it) })
                    .toList()
            }
        }

    private suspend fun listDexNamesFromSplitArchive(file: File): List<String> =
        runInterruptible(Dispatchers.IO) {
            if (!file.exists()) return@runInterruptible emptyList<String>()
            val dexNames = mutableSetOf<String>()
            val splitEntryNames = SplitApkPreparer.splitApkEntryNames(file)
            ZipFile(file).use { outer ->
                val entries = outer.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .filter { it.name in splitEntryNames }
                    .toList()
                if (entries.isEmpty()) return@use
                entries.forEach { entry ->
                    outer.getInputStream(entry).use { raw ->
                        ZipInputStream(BufferedInputStream(raw)).use { inner ->
                            while (true) {
                                val innerEntry = inner.nextEntry ?: break
                                if (!innerEntry.isDirectory &&
                                    innerEntry.name.startsWith("classes") &&
                                    innerEntry.name.endsWith(".dex")
                                ) {
                                    dexNames.add(innerEntry.name)
                                }
                            }
                        }
                    }
                }
            }
            dexNames.sortedWith(compareBy { dexSortKey(it) })
        }

    override fun close() {
        if (::patcher.isInitialized) {
            patcher.close()
        }
        patcherInput.cleanup()
        tempDir.deleteRecursively()
    }

    private fun prepareLegacyPatcherFallbackInput(originalError: Throwable): PreparedPatcherInput? {
        val expectedInitInputPath = initialPatcherInput?.absolutePath ?: input.absolutePath
        if (patcherInput.file.absolutePath != expectedInitInputPath) {
            return null
        }
        val fallbackSourceApk = initialPatcherInput ?: input
        return prepareLegacyPatcherFallbackInput(
            sourceApk = fallbackSourceApk,
            logReason = true,
            originalError = originalError,
            hideAllEmbeddedApks = true
        )
    }

    private fun prepareLegacyPatcherFallbackInput(
        sourceApk: File,
        logReason: Boolean,
        originalError: Throwable? = null,
        hideAllEmbeddedApks: Boolean = false,
    ): PreparedPatcherInput? {
        if (!sourceApk.exists() || !sourceApk.extension.equals("apk", ignoreCase = true)) {
            return null
        }
        if (SplitApkPreparer.isSplitArchive(sourceApk)) {
            return null
        }

        return runCatching {
            ZipFile(sourceApk).use { zip ->
                val embeddedApks = zip.entries()
                    .asSequence()
                    .filterNot { it.isDirectory }
                    .filter { it.name.endsWith(".apk", ignoreCase = true) }
                    .filter { hideAllEmbeddedApks || isProblematicEmbeddedApkEntry(it.name) }
                    .toList()
                if (embeddedApks.isEmpty() && !hideAllEmbeddedApks) {
                    return null
                }

                val sanitized = Files.createTempFile(
                    patcherInputDir.toPath(),
                    "${sourceApk.nameWithoutExtension}-patcher-input-",
                    ".apk"
                ).toFile()
                ZipOutputStream(FileOutputStream(sanitized).buffered()).use { output ->
                    copyWithoutEntries(
                        zip = zip,
                        entries = zip.entries(),
                        entriesToSkip = embeddedApks.mapTo(HashSet()) { it.name },
                        output = output
                    )
                }

                val hiddenEntries = embeddedApks.mapTo(LinkedHashSet()) { it.name }
                if (logReason) {
                    val failureSummary = when (originalError) {
                        is TimeoutCancellationException -> "timed out after ${PATCHER_INIT_TIMEOUT_MS / 1000}s"
                        null -> "init failure"
                        is CancellationException -> "init failure reported as cancellation"
                        else -> "${originalError::class.java.simpleName}: ${originalError.message}"
                    }
                    if (hiddenEntries.isEmpty()) {
                        logger.warn(
                            "Retrying legacy patcher with normalized APK container after init failure " +
                                "($failureSummary)"
                        )
                    } else {
                        logger.warn(
                            "Retrying legacy patcher with embedded APK payloads hidden after init failure " +
                                "($failureSummary): " +
                                hiddenEntries.joinToString()
                        )
                    }
                } else {
                    if (hiddenEntries.isEmpty()) {
                        logger.info("Using normalized patcher input during init")
                    } else {
                        logger.info(
                            "Using sanitized patcher input with embedded APK payloads hidden during init: " +
                                hiddenEntries.joinToString()
                        )
                    }
                }
                PreparedPatcherInput(
                    file = sanitized,
                    hiddenEntries = hiddenEntries,
                    cleanup = { sanitized.delete() }
                )
            }
        }.getOrElse { error ->
            logger.warn("Failed to prepare legacy patcher fallback input, using original APK failure: ${error.message}")
            null
        }
    }

    private fun restoreHiddenEntriesIfNeeded(patched: File) {
        val hiddenEntries = patcherInput.hiddenEntries
        if (hiddenEntries.isEmpty()) return

        val restored = Files.createTempFile(tempDir.toPath(), "restored-result-", ".apk").toFile()
        try {
            ZipFile(patched).use { patchedZip ->
                ZipFile(input).use { originalZip ->
                    ZipOutputStream(FileOutputStream(restored).buffered()).use { output ->
                        copyWithoutEntries(
                            zip = patchedZip,
                            entries = patchedZip.entries(),
                            entriesToSkip = emptySet(),
                            output = output
                        )
                        hiddenEntries.forEach { name ->
                            if (patchedZip.getEntry(name) != null) return@forEach
                            val entry = originalZip.getEntry(name) ?: return@forEach
                            output.putNextEntry(cloneEntry(entry))
                            originalZip.getInputStream(entry).use { input -> input.copyTo(output) }
                            output.closeEntry()
                        }
                    }
                }
            }

            try {
                Files.move(
                    restored.toPath(),
                    patched.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Exception) {
                Files.move(
                    restored.toPath(),
                    patched.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            restored.delete()
        }
    }

    private fun isProblematicEmbeddedApkEntry(entryName: String): Boolean {
        val normalized = entryName.replace('\\', '/')
        val fileName = normalized.substringAfterLast('/')
        if (!fileName.endsWith(".apk", ignoreCase = true)) return false
        if (SplitApkPreparer.isLikelySplitApkEntryName(entryName)) return false

        val stem = fileName.removeSuffix(".apk").lowercase(Locale.ROOT)
        val parts = stem.split('.')
        if (parts.size < 3) return false
        return parts.all { part ->
            part.isNotBlank() && part.all { ch -> ch.isLowerCase() || ch.isDigit() || ch == '_' }
        }
    }

    private fun copyWithoutEntries(
        zip: ZipFile,
        entries: Enumeration<out ZipEntry>,
        entriesToSkip: Set<String>,
        output: ZipOutputStream
    ) {
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory || entry.name in entriesToSkip) continue
            output.putNextEntry(cloneEntry(entry))
            zip.getInputStream(entry).use { input -> input.copyTo(output) }
            output.closeEntry()
        }
    }

    private fun cloneEntry(entry: ZipEntry): ZipEntry {
        val clone = ZipEntry(entry.name)
        clone.time = entry.time
        clone.comment = entry.comment
        entry.extra?.let { clone.extra = it.copyOf() }
        when (entry.method) {
            ZipEntry.STORED -> {
                clone.method = ZipEntry.STORED
                if (entry.size >= 0) clone.size = entry.size
                if (entry.compressedSize >= 0) clone.compressedSize = entry.compressedSize
                clone.crc = entry.crc
            }

            ZipEntry.DEFLATED -> clone.method = ZipEntry.DEFLATED
            else -> if (entry.method != -1) clone.method = entry.method
        }
        return clone
    }

    private data class PreparedPatcherInput(
        val file: File,
        val hiddenEntries: Set<String> = emptySet(),
        val cleanup: () -> Unit = {}
    )

    companion object {
        private const val FRAMEWORK_APK_NAME = "1.apk"
        private const val FRAMEWORK_RESOURCES_TABLE = "resources.arsc"
        private const val MIN_BUNDLED_FRAMEWORK_SDK = 23
        private const val PATCHER_INIT_TIMEOUT_MS = 90_000L
        private val zFileOptions = ZFileOptions().apply {
            setAlignmentRule(
                AlignmentRules.compose(
                    AlignmentRules.constantForSuffix(".so", 4096),
                    AlignmentRules.constant(4)
                )
            )
        }

        suspend fun open(
            cacheDir: String,
            frameworkDir: String,
            aaptPath: String,
            logger: Logger,
            input: File,
            initialPatcherInput: File? = null,
            sanitizeAllEmbeddedApksOnInit: Boolean = false,
            onEvent: (ProgressEvent) -> Unit,
            checkCancelled: () -> Unit = {},
            continueOnPatchError: Boolean = false,
        ): AmpleSession {
            val session = AmpleSession(
                cacheDir = cacheDir,
                frameworkDir = frameworkDir,
                aaptPath = aaptPath,
                logger = logger,
                input = input,
                initialPatcherInput = initialPatcherInput,
                sanitizeAllEmbeddedApksOnInit = sanitizeAllEmbeddedApksOnInit,
                onEvent = onEvent,
                checkCancelled = checkCancelled,
                continueOnPatchError = continueOnPatchError
            )
            return try {
                if (sanitizeAllEmbeddedApksOnInit) {
                    session.clearFrameworkCache("reset before legacy split init")
                } else {
                    session.ensureFrameworkCacheIsValid()
                }
                session.initializePatcher()
                session
            } catch (error: Throwable) {
                session.close()
                throw error
            }
        }

        operator fun PatchResult.component1() = patch
        operator fun PatchResult.component2() = exception
    }
}
