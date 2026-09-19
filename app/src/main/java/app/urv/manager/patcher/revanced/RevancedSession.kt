package app.urv.manager.patcher.revanced

import android.os.Build
import app.urv.manager.patcher.ProgressEvent
import app.urv.manager.patcher.StepId
import app.urv.manager.patcher.logger.Logger
import app.urv.manager.patcher.logger.PatcherLogMode
import app.urv.manager.patcher.logger.withJavaLogging
import app.urv.manager.patcher.runCancellableBlockingIo
import app.urv.manager.patcher.runStep
import app.urv.manager.patcher.toSafeRemoteError
import app.urv.manager.patcher.toSafeStackTraceString
import app.urv.manager.patcher.toRemoteError
import app.urv.manager.patcher.split.SplitApkPreparer
import app.urv.manager.patcher.util.ManifestDecimalResourceReferenceSanitizer
import app.urv.manager.patcher.util.MislabeledImageResourceSanitizer
import app.urv.manager.patcher.util.NativeLibStripper
import app.revanced.patcher.PatchesResult
import app.revanced.patcher.patcher
import app.revanced.patcher.patch.Patch
import app.revanced.patcher.patch.PatchResult
import com.android.tools.build.apkzlib.zip.AlignmentRules
import com.android.tools.build.apkzlib.zip.ZFile
import com.android.tools.build.apkzlib.zip.ZFileOptions
import com.google.common.base.Predicate
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.LinkedHashSet
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

internal typealias RevancedPatchList = List<Patch>

class RevancedSession(
    cacheDir: String,
    frameworkDir: String,
    aaptPath: String,
    private val logger: Logger,
    private val input: File,
    private val initialPatcherInput: File = input,
    private val onEvent: (ProgressEvent) -> Unit,
    private val checkCancelled: () -> Unit = {},
    private val logMode: PatcherLogMode = PatcherLogMode.DEFAULT,
    private val continueOnPatchError: Boolean = false,
) : Closeable {
    private val tempDir = File(cacheDir).resolve("patcher").also { it.mkdirs() }
    private val frameworkDirFile = File(frameworkDir).also { it.mkdirs() }
    private val aaptBinaryPath = File(aaptPath)

    private data class PatchExecutionResult(
        val result: PatchesResult,
        val failedPatchIndexes: Set<Int>,
    )

    data class ExecutedPatches(
        val result: PatchesResult,
        val shouldStripNativeLibs: Boolean,
    )

    private suspend fun applyPatchesVerbose(
        patches: RevancedPatchList,
        preStarted: Set<Int> = emptySet()
    ): PatchExecutionResult = coroutineScope {
        val selectedPatches = LinkedHashSet(patches)
        val runPatcher =
            patcher(
                apkFile = initialPatcherInput,
                temporaryFilesPath = tempDir,
                aaptBinaryPath = aaptBinaryPath,
                frameworkFileDirectory = frameworkDirFile.absolutePath,
            ) { _, _ ->
                selectedPatches
            }
        val indexByPatch = patches.withIndex().associate { it.value to it.index }
        val started = mutableSetOf<Int>()
        started.addAll(preStarted)
        val failedPatchIndexes = mutableSetOf<Int>()
        var firstPatchFailure: Throwable? = null
        var nextIndex = 0
        val decodedResourcesJob =
            launch(Dispatchers.IO) {
                sanitizeDecodedResourcesWhenReady()
            }

        fun patchNameAt(index: Int): String =
            patches.getOrNull(index)?.name ?: "Patch #${index + 1}"

        fun startPatch(index: Int) {
            checkCancelled()
            if (index !in patches.indices) return
            if (!started.add(index)) return
            onEvent(ProgressEvent.Started(StepId.ExecutePatch(index)))
        }

        if (patches.isNotEmpty()) {
            startPatch(0)
        }

        val patchResult = try {
            val patcherResult = runPatcher { result ->
                checkCancelled()
                val patch = result.patch
                val exception = result.exception
                val index = indexByPatch[patch] ?: return@runPatcher

                if (exception != null) {
                    fun recordFailure() {
                        if (firstPatchFailure == null) {
                            firstPatchFailure = exception
                        }
                        failedPatchIndexes += index
                        onEvent(ProgressEvent.Failed(StepId.ExecutePatch(index), exception.toSafeRemoteError()))
                        logger.error(
                            "${patch.name ?: patchNameAt(index)} failed:\n" +
                                exception.toSafeStackTraceString()
                        )
                    }

                    if (index < nextIndex) {
                        recordFailure()
                        if (continueOnPatchError && !isLikelyFrameworkDecodeFailure(exception)) return@runPatcher
                        throw exception
                    }
                    while (nextIndex < index) {
                        startPatch(nextIndex)
                        onEvent(ProgressEvent.Completed(StepId.ExecutePatch(nextIndex)))
                        logger.info("${patchNameAt(nextIndex)} succeeded")
                        nextIndex += 1
                    }
                    startPatch(index)
                    recordFailure()
                    if (continueOnPatchError && !isLikelyFrameworkDecodeFailure(exception)) {
                        nextIndex = index + 1
                        if (nextIndex < patches.size) {
                            startPatch(nextIndex)
                        }
                        return@runPatcher
                    }
                    throw exception
                }

                if (index < nextIndex) return@runPatcher
                while (nextIndex < index) {
                    startPatch(nextIndex)
                    onEvent(ProgressEvent.Completed(StepId.ExecutePatch(nextIndex)))
                    logger.info("${patchNameAt(nextIndex)} succeeded")
                    nextIndex += 1
                }
                startPatch(index)
                onEvent(ProgressEvent.Completed(StepId.ExecutePatch(index)))
                logger.info("${patch.name ?: patchNameAt(index)} succeeded")
                nextIndex = index + 1
                if (nextIndex < patches.size) {
                    startPatch(nextIndex)
                }
            }

            while (nextIndex < patches.size) {
                startPatch(nextIndex)
                onEvent(ProgressEvent.Completed(StepId.ExecutePatch(nextIndex)))
                logger.info("${patchNameAt(nextIndex)} succeeded")
                nextIndex += 1
            }
            if (continueOnPatchError && patches.isNotEmpty() && failedPatchIndexes.size == patches.size) {
                throw firstPatchFailure ?: IllegalStateException("All selected patches failed")
            }
            patcherResult
        } finally {
            decodedResourcesJob.cancelAndJoin()
        }
        sanitizeDecodedResourcesPass()
        PatchExecutionResult(patchResult, failedPatchIndexes.toSet())
    }

    private suspend fun sanitizeDecodedResourcesWhenReady() {
        var loggedDetection = false
        while (currentCoroutineContext().isActive) {
            if (!sanitizeDecodedResourcesPass(logDetection = !loggedDetection)) {
                delay(DECODED_SANITIZER_INTERVAL_MS)
                continue
            }
            loggedDetection = true
            delay(DECODED_SANITIZER_INTERVAL_MS)
        }
    }

    private fun sanitizeDecodedResourcesPass(logDetection: Boolean = false): Boolean {
        val apkDir = tempDir.resolve("apk")
        val resourcesDir = apkDir.resolve("res")
        val manifestFile = apkDir.resolve("AndroidManifest.xml")
        if (!resourcesDir.isDirectory && !manifestFile.isFile) {
            return false
        }

        if (logDetection) {
            logger.info("Detected decoded resources, running manifest and image resource sanitizers")
        }

        ManifestDecimalResourceReferenceSanitizer.sanitize(apkDir, logger)
        MislabeledImageResourceSanitizer.sanitizeDecodedResources(resourcesDir, logger)
        return true
    }

    private suspend fun executePatchesOnce(orderedPatches: RevancedPatchList): PatchExecutionResult {
        checkCancelled()
        if (orderedPatches.isNotEmpty()) {
            onEvent(ProgressEvent.Started(StepId.ExecutePatch(0)))
        }

        logger.info("Applying patches...")
        return applyPatchesVerbose(
            orderedPatches,
            preStarted = if (orderedPatches.isNotEmpty()) setOf(0) else emptySet()
        )
    }

    private suspend fun executePatchesWithFrameworkRecovery(orderedPatches: RevancedPatchList): PatchExecutionResult {
        ensureFrameworkCacheIsValid()
        return try {
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
                aaptBinaryPath.absolutePath,
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
        loadSelectedPatches: suspend () -> RevancedPatchList,
        stripNativeLibs: Boolean,
        inputWasSplit: Boolean
    ) {
        val executedPatches = executePatches(loadSelectedPatches, stripNativeLibs, inputWasSplit)
        writeOutput(output, executedPatches)
    }

    suspend fun executePatches(
        loadSelectedPatches: suspend () -> RevancedPatchList,
        stripNativeLibs: Boolean,
        inputWasSplit: Boolean
    ): ExecutedPatches {
        checkCancelled()
        val (patchResult, patchCount, failedPatchIndexes) = runStep(StepId.ExecutePatches, onEvent, checkCancelled) {
            val orderedPatches = loadSelectedPatches().sortedBy { it.name.orEmpty() }
            logger.withJavaLogging(logMode) {
                val execution = executePatchesWithFrameworkRecovery(orderedPatches)
                Triple(execution.result, orderedPatches.size, execution.failedPatchIndexes)
            }
        }

        // Ensure patch rows are finalized before write/sign steps begin.
        repeat(patchCount) { index ->
            checkCancelled()
            if (index in failedPatchIndexes) return@repeat
            onEvent(ProgressEvent.Completed(StepId.ExecutePatch(index)))
        }

        return ExecutedPatches(
            result = patchResult,
            shouldStripNativeLibs = stripNativeLibs && !inputWasSplit
        )
    }

    suspend fun writeOutput(
        output: File,
        executedPatches: ExecutedPatches,
    ) {
        runStep(
            StepId.WriteAPK,
            onEvent,
            checkCancelled,
            startedSubSteps = buildWriteApkSubSteps(
                includeStripNativeLibs = executedPatches.shouldStripNativeLibs
            )
        ) {
            checkCancelled()
            logger.info("Writing patched files...")
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
                applyResultToApk(patched, executedPatches.result)
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
            if (executedPatches.shouldStripNativeLibs) {
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

    private fun applyResultToApk(apkFile: File, result: PatchesResult) {
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
                            .filter {
                                it.centralDirectoryHeader.name.startsWith(
                                    "res/",
                                    ignoreCase = false
                                )
                            }
                            .toList()
                            .forEach { it.delete() }
                        apk.mergeFrom(resourcesApk, Predicate { false })
                    }
                }

                resources.otherResources?.let { resourcesDir ->
                    if (resourcesDir.exists()) {
                        val noCompress = resources.doNotCompress
                        apk.addAllRecursively(resourcesDir, Predicate { file ->
                            val relative =
                                file.relativeTo(resourcesDir).path.replace(File.separatorChar, '/')
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

    private fun sanitizeZipEntryName(name: String): String? {
        val normalized = name.replace('\\', '/').trimStart('/')
        if (normalized.isBlank()) return null
        if (normalized.startsWith("../")) return null
        if (normalized.contains("/../")) return null
        return normalized
    }

    private fun isDexEntryName(name: String): Boolean =
        name.startsWith("classes") && name.endsWith(".dex", ignoreCase = true)

    private fun String.toFileSystemPath(): String = replace('/', File.separatorChar)

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
                    entry.isFile && isDexEntryName(entry.name) -> dexNames.add(entry.name)
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
        result: PatchesResult
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
        tempDir.deleteRecursively()
    }

    companion object {
        private const val FRAMEWORK_APK_NAME = "1.apk"
        private const val FRAMEWORK_RESOURCES_TABLE = "resources.arsc"
        private const val MIN_BUNDLED_FRAMEWORK_SDK = 23
        private const val DECODED_SANITIZER_INTERVAL_MS = 200L
        private val zFileOptions = ZFileOptions().apply {
            setAlignmentRule(
                AlignmentRules.compose(
                    AlignmentRules.constantForSuffix(".so", 4096),
                    AlignmentRules.constant(4)
                )
            )
        }
        operator fun PatchResult.component1() = patch
        operator fun PatchResult.component2() = exception
    }
}
