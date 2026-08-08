package app.urv.manager.patcher.morphe

import android.os.Build
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.dex.BytecodeMode
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.PatchResult
import app.morphe.patcher.PatcherResult
import app.urv.manager.patcher.ProgressEvent
import app.urv.manager.patcher.StepId
import app.urv.manager.patcher.logger.Logger
import app.urv.manager.patcher.morphe.MorpheSession.Companion.component1
import app.urv.manager.patcher.morphe.MorpheSession.Companion.component2
import app.urv.manager.patcher.runCancellableBlockingIo
import app.urv.manager.patcher.runStep
import app.urv.manager.patcher.split.SplitApkPreparer
import app.urv.manager.patcher.toSafeRemoteError
import app.urv.manager.patcher.toSafeStackTraceString
import app.urv.manager.patcher.util.NativeLibStripper
import app.urv.manager.patcher.util.XmlSurrogateSanitizer
import app.urv.manager.patcher.toRemoteError
import com.android.tools.build.apkzlib.zip.AlignmentRules
import com.android.tools.build.apkzlib.zip.ZFile
import com.android.tools.build.apkzlib.zip.ZFileOptions
import com.google.common.base.Predicate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.yield
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.LinkedHashSet
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Attr
import org.w3c.dom.Element

internal typealias MorphePatchList = List<Patch<*>>

class MorpheSession(
    cacheDir: String,
    frameworkDir: String,
    aaptPath: String,
    private val bytecodeMode: BytecodeMode = BytecodeMode.STRIP_FAST,
    private val logger: Logger,
    private val input: File,
    private val onEvent: (ProgressEvent) -> Unit,
    private val checkCancelled: () -> Unit = {},
    private val continueOnPatchError: Boolean = false,
) : Closeable {
    private val tempDir = File(cacheDir).resolve("patcher").also { it.mkdirs() }
    private val frameworkDirFile = File(frameworkDir).also { it.mkdirs() }
    private val resolvedAaptPath = aaptPath
    private var patcher = createPatcher()

    private fun createPatcher() = Patcher(
        PatcherConfig(
            apkFile = input,
            temporaryFilesPath = tempDir,
            frameworkFileDirectory = frameworkDirFile.absolutePath,
            aaptBinaryPath = resolvedAaptPath,
            useBytecodeMode = bytecodeMode
        )
    )

    private suspend fun Patcher.applyPatchesVerbose(
        selectedPatches: MorphePatchList,
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

    private suspend fun executePatchesOnce(orderedPatches: MorphePatchList) {
        checkCancelled()
        with(patcher) {
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

    private suspend fun executePatchesWithFrameworkRecovery(orderedPatches: MorphePatchList) {
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
        loadSelectedPatches: suspend () -> MorphePatchList,
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
                suspend fun emitWriteApkProgress(message: String) {
                    checkCancelled()
                    onEvent(
                        ProgressEvent.Progress(
                            stepId = StepId.WriteAPK,
                            message = message
                        )
                    )
                    yield()
                    checkCancelled()
                }

                checkCancelled()
                val patched = tempDir.resolve("result.apk")
                emitWriteApkProgress("Copy base APK")
                logger.info("Writing patched files...")
                XmlSurrogateSanitizer.sanitize(tempDir.resolve("apk"), logger)
                ensureMissingDrawables()
                validateMissingResourceReferences()
                checkCancelled()
                val result = runCancellableBlockingIo(checkCancelled) { patcher.get() }
                runCancellableBlockingIo(checkCancelled) {
                    fastCopy(input, patched)
                }
                runCancellableBlockingIo(checkCancelled) {
                    applyResultToApk(patched, result)
                }
                checkCancelled()

                logger.info("Patched apk saved to $patched")

                emitWriteApkProgress("Writing output APK")
                runCancellableBlockingIo(checkCancelled) {
                    checkCancelled()
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
                emitWriteApkProgress("Finalizing output")
                if (shouldStripNativeLibs) {
                    checkCancelled()
                    emitWriteApkProgress("Stripping native libraries")
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
        add("Copy base APK")
        add("Applying patched changes")
        add(writeApkDexGroupTitle())
        add("Compiling modified resources")
        add("Writing output APK")
        add("Finalizing output")
        if (includeStripNativeLibs) {
            add("Stripping native libraries")
        }
    }

    private fun writeApkDexGroupTitle(): String = when (bytecodeMode) {
        BytecodeMode.FULL -> "Compiling DEX files: FULL"
        else -> "Compiling DEX files: FAST"
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

    private fun ensureMissingDrawables() {
        val resDir = tempDir.resolve("apk").resolve("res")
        if (!resDir.exists()) return
        val referenced = collectReferencedDrawables(resDir)
        if (referenced.isEmpty()) return
        val existing = collectExistingDrawables(resDir)
        val missing = referenced.minus(existing)
        if (missing.isEmpty()) return
        val outputDir = resDir.resolve("drawable").also { it.mkdirs() }
        missing.forEach { name ->
            val file = outputDir.resolve("$name.xml")
            if (!file.exists()) {
                file.writeText(
                    """
                        |<shape xmlns:android="http://schemas.android.com/apk/res/android">
                        |    <solid android:color="@android:color/transparent"/>
                        |</shape>
                    """.trimMargin()
                )
            }
        }
        logger.warn("Added placeholder drawables: ${missing.sorted().joinToString()}")
    }

    private fun collectReferencedDrawables(resDir: File): Set<String> {
        val matches = mutableSetOf<String>()
        val pattern = Regex("@drawable/(rvx_morphed_[a-zA-Z0-9_]+)")
        resDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("xml", ignoreCase = true) }
            .forEach { file ->
                val content = runCatching { file.readText() }.getOrNull() ?: return@forEach
                pattern.findAll(content).forEach { match ->
                    match.groupValues.getOrNull(1)?.let(matches::add)
                }
            }
        return matches
    }

    private fun collectExistingDrawables(resDir: File): Set<String> =
        resDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("drawable") }
            ?.flatMap { dir ->
                dir.listFiles()
                    ?.filter { it.isFile }
                    ?.mapNotNull { file -> drawableName(file.name) }
                    .orEmpty()
            }
            ?.toSet()
            .orEmpty()

    private fun drawableName(fileName: String): String? = when {
        fileName.endsWith(".9.png", ignoreCase = true) -> fileName.removeSuffix(".9.png")
        fileName.endsWith(".png", ignoreCase = true) -> fileName.removeSuffix(".png")
        fileName.endsWith(".webp", ignoreCase = true) -> fileName.removeSuffix(".webp")
        fileName.endsWith(".jpg", ignoreCase = true) -> fileName.removeSuffix(".jpg")
        fileName.endsWith(".jpeg", ignoreCase = true) -> fileName.removeSuffix(".jpeg")
        fileName.endsWith(".xml", ignoreCase = true) -> fileName.removeSuffix(".xml")
        else -> null
    }

    private fun validateMissingResourceReferences() {
        val apkDir = tempDir.resolve("apk")
        val resDir = apkDir.resolve("res")
        val manifestFile = apkDir.resolve("AndroidManifest.xml")
        if (!manifestFile.exists() || !resDir.exists()) return

        val resourceIndex = collectResourceIndex(resDir)
        if (resourceIndex.isEmpty()) return

        val missing = mutableListOf<String>()
        collectMissingRefsFromFile(
            file = manifestFile,
            resourceIndex = resourceIndex,
            missing = missing
        )

        resDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("xml", ignoreCase = true) }
            .filterNot { it.parentFile?.name?.startsWith("values") == true }
            .forEach { file ->
                collectMissingRefsFromFile(
                    file = file,
                    resourceIndex = resourceIndex,
                    missing = missing
                )
            }

        if (missing.isNotEmpty()) {
            val uniqueMissing = missing.distinct().sorted()
            logger.error(
                "Missing resource references detected. Aborting patching:\n" +
                    uniqueMissing.joinToString("\n")
            )
            throw IllegalStateException("Missing resource references detected.")
        }
    }

    private fun collectResourceIndex(resDir: File): Map<String, Set<String>> {
        val index = mutableMapOf<String, MutableSet<String>>()

        resDir.listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { dir ->
                val type = dir.name.substringBefore('-')
                if (type == "values") {
                    dir.listFiles { file -> file.isFile && file.extension.equals("xml", true) }
                        ?.forEach { file ->
                            runCatching { collectValuesResources(file, index) }
                        }
                } else {
                    dir.listFiles { file -> file.isFile }
                        ?.forEach { file ->
                            val name = resourceFileName(file.name) ?: return@forEach
                            index.getOrPut(type) { mutableSetOf() }.add(name)
                        }
                }
            }

        return index
    }

    private fun collectValuesResources(file: File, index: MutableMap<String, MutableSet<String>>) {
        val document = parseXml(file) ?: return
        val resources = document.documentElement ?: return
        val nodes = resources.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            val name = node.getAttribute("name").takeIf { it.isNotBlank() } ?: continue
            val type = when {
                node.tagName == "item" -> node.getAttribute("type").takeIf { it.isNotBlank() }
                node.tagName.endsWith("-array") -> "array"
                else -> node.tagName
            } ?: continue
            index.getOrPut(type) { mutableSetOf() }.add(name)
        }
    }

    private fun resourceFileName(fileName: String): String? = when {
        fileName.endsWith(".9.png", ignoreCase = true) -> fileName.removeSuffix(".9.png")
        fileName.endsWith(".png", ignoreCase = true) -> fileName.removeSuffix(".png")
        fileName.endsWith(".webp", ignoreCase = true) -> fileName.removeSuffix(".webp")
        fileName.endsWith(".jpg", ignoreCase = true) -> fileName.removeSuffix(".jpg")
        fileName.endsWith(".jpeg", ignoreCase = true) -> fileName.removeSuffix(".jpeg")
        fileName.contains('.') -> fileName.substringBeforeLast('.')
        else -> null
    }

    private fun collectMissingRefsFromFile(
        file: File,
        resourceIndex: Map<String, Set<String>>,
        missing: MutableList<String>
    ) {
        val document = parseXml(file) ?: return
        val elements = document.getElementsByTagName("*")
        for (i in 0 until elements.length) {
            val element = elements.item(i) as? Element ?: continue
            val attrs = element.attributes
            for (j in 0 until attrs.length) {
                val attr = attrs.item(j) as? Attr ?: continue
                val missingRefs = findMissingRefs(attr.value, resourceIndex)
                if (missingRefs.isEmpty()) continue

                missingRefs.forEach { ref ->
                    missing.add("${file.name}: ${element.tagName}@${attr.name} -> @$ref")
                }
            }
        }
    }

    private fun findMissingRefs(value: String, resourceIndex: Map<String, Set<String>>): List<String> {
        val refs = mutableListOf<String>()
        val pattern = Regex("@(?:(\\*?)([a-zA-Z0-9_.]+):)?([a-zA-Z0-9_]+)/([a-zA-Z0-9_.]+)")
        pattern.findAll(value).forEach { match ->
            val star = match.groupValues[1]
            val pkg = match.groupValues[2]
            val type = match.groupValues[3]
            val name = match.groupValues[4]
            if (value.startsWith("?")) return@forEach
            if (pkg.equals("android", ignoreCase = true) || star.contains("android")) return@forEach
            if (pkg.isNotBlank()) return@forEach
            val known = resourceIndex[type]?.contains(name) == true
            if (!known) {
                refs.add("$type/$name")
            }
        }
        return refs
    }

    private fun parseXml(file: File) = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isXIncludeAware = false
            setExpandEntityReferences(false)
        }
        factory.newDocumentBuilder().parse(file)
    }.getOrNull()

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

    override fun close() {
        tempDir.deleteRecursively()
        patcher.close()
    }

    companion object {
        private const val FRAMEWORK_APK_NAME = "1.apk"
        private const val FRAMEWORK_RESOURCES_TABLE = "resources.arsc"
        private const val MIN_BUNDLED_FRAMEWORK_SDK = 23
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
