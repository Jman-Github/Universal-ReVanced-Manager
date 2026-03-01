package app.revanced.manager.patcher.ample

import app.revanced.library.ApkUtils.applyTo
import app.revanced.manager.patcher.ProgressEvent
import app.revanced.manager.patcher.StepId
import app.revanced.manager.patcher.ample.AmpleSession.Companion.component1
import app.revanced.manager.patcher.ample.AmpleSession.Companion.component2
import app.revanced.manager.patcher.logger.Logger
import app.revanced.manager.patcher.runStep
import app.revanced.manager.patcher.toRemoteError
import app.revanced.manager.patcher.util.NativeLibStripper
import app.revanced.manager.patcher.util.XmlSurrogateSanitizer
import app.revanced.patcher.Patcher
import app.revanced.patcher.PatcherConfig
import app.revanced.patcher.PatcherResult
import app.revanced.patcher.patch.Patch
import app.revanced.patcher.patch.PatchResult
import app.revanced.manager.patcher.split.SplitApkPreparer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.LinkedHashSet

internal typealias AmplePatchList = List<Patch<*>>

class AmpleSession(
    cacheDir: String,
    frameworkDir: String,
    aaptPath: String,
    private val logger: Logger,
    private val input: File,
    private val onEvent: (ProgressEvent) -> Unit,
) : Closeable {
    private val tempDir = File(cacheDir).resolve("patcher").also { it.mkdirs() }
    private val frameworkDirFile = File(frameworkDir).also { it.mkdirs() }
    private var patcher = createPatcher()

    private fun createPatcher() = Patcher(
        PatcherConfig(
            input,
            tempDir,
            frameworkDirFile.absolutePath
        )
    )

    private suspend fun Patcher.applyPatchesVerbose(
        selectedPatches: AmplePatchList,
        preStarted: Set<Int> = emptySet()
    ) {
        if (selectedPatches.isEmpty()) return
        val indexByPatch = selectedPatches.withIndex().associate { it.value to it.index }
        val started = mutableSetOf<Int>()
        started.addAll(preStarted)
        var nextIndex = 0

        fun startPatch(index: Int) {
            if (!started.add(index)) return
            onEvent(ProgressEvent.Started(StepId.ExecutePatch(index)))
        }

        startPatch(0)
        this().collect { (patch, exception) ->
            val index = indexByPatch[patch] ?: return@collect

            if (exception != null) {
                val error = exception as? Exception ?: Exception(exception)
                if (index < nextIndex) {
                    onEvent(ProgressEvent.Failed(StepId.ExecutePatch(index), error.toRemoteError()))
                    logger.error("${patch.name} failed:")
                    logger.error(exception.stackTraceToString())
                    throw exception
                }
                while (nextIndex < index) {
                    startPatch(nextIndex)
                    onEvent(ProgressEvent.Completed(StepId.ExecutePatch(nextIndex)))
                    logger.info("${selectedPatches[nextIndex].name} succeeded")
                    nextIndex += 1
                }
                startPatch(index)
                onEvent(ProgressEvent.Failed(StepId.ExecutePatch(index), error.toRemoteError()))
                logger.error("${patch.name} failed:")
                logger.error(exception.stackTraceToString())
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
    }

    private suspend fun executePatchesOnce(orderedPatches: AmplePatchList) {
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

    private suspend fun executePatchesWithFrameworkRecovery(orderedPatches: AmplePatchList) {
        ensureFrameworkCacheIsValid()
        executePatchesOnce(orderedPatches)
    }

    private fun ensureFrameworkCacheIsValid() {
        val frameworkApk = frameworkDirFile.resolve(FRAMEWORK_APK_NAME)
        if (!frameworkApk.exists()) return

        val issue = frameworkApkValidationIssue(frameworkApk) ?: return
        logger.warn("Invalid framework cache at ${frameworkApk.absolutePath}: $issue")
        clearFrameworkCache("preflight validation failed")
    }

    private fun frameworkApkValidationIssue(file: File): String? {
        if (!file.isFile) return "not a regular file"
        if (file.length() <= 0L) return "file is empty"

        return runCatching {
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

    suspend fun run(
        output: File,
        selectedPatches: AmplePatchList,
        stripNativeLibs: Boolean,
        inputWasSplit: Boolean
    ) {
        val shouldStripNativeLibs = stripNativeLibs && !inputWasSplit
        val orderedPatches = selectedPatches.sortedBy { it.name }
        runStep(StepId.ExecutePatches, onEvent) {
            java.util.logging.Logger.getLogger("").apply {
                handlers.forEach {
                    it.close()
                    removeHandler(it)
                }

                addHandler(logger.handler)
            }
            executePatchesWithFrameworkRecovery(orderedPatches)
        }

        onEvent(
            ProgressEvent.Progress(
                stepId = StepId.WriteAPK,
                message = "Preparing output APK"
            )
        )

        suspend fun writePatchedApkStep() {
            runStep(StepId.WriteAPK, onEvent) {
                onEvent(
                    ProgressEvent.Progress(
                        stepId = StepId.WriteAPK,
                        message = "Copying base APK"
                    )
                )
                val initialDexNames = listDexNames(input)
                onEvent(
                    ProgressEvent.Progress(
                        stepId = StepId.WriteAPK,
                        subSteps = buildWriteApkSubSteps(
                            initialDexNames.map { "Compiling $it" },
                            shouldStripNativeLibs
                        )
                    )
                )
                logger.info("Writing patched files...")
                XmlSurrogateSanitizer.sanitize(tempDir.resolve("apk"), logger)
                val result = patcher.get()
                val updatedDexNames = mergeDexNames(initialDexNames, result)
                if (updatedDexNames != initialDexNames) {
                    onEvent(
                        ProgressEvent.Progress(
                            stepId = StepId.WriteAPK,
                            subSteps = buildWriteApkSubSteps(
                                updatedDexNames.map { "Compiling $it" },
                                shouldStripNativeLibs
                            )
                        )
                    )
                }

                val patched = tempDir.resolve("result.apk")
                runInterruptible(Dispatchers.IO) {
                    fastCopy(input, patched)
                }
                onEvent(
                    ProgressEvent.Progress(
                        stepId = StepId.WriteAPK,
                        message = "Applying patched changes"
                    )
                )
                result.applyTo(patched)

                logger.info("Patched apk saved to $patched")

                withContext(Dispatchers.IO) {
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
                    onEvent(
                        ProgressEvent.Progress(
                            stepId = StepId.WriteAPK,
                            message = "Stripping native libraries"
                        )
                    )
                    NativeLibStripper.strip(output)
                }
            }
        }

        writePatchedApkStep()
    }

    private fun buildWriteApkSubSteps(
        compileSteps: List<String> = emptyList(),
        includeStripNativeLibs: Boolean = false
    ): List<String> = buildList {
        add("Copying base APK")
        add("Applying patched changes")
        addAll(compileSteps)
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
            ZipFile(file).use { outer ->
                val entries = outer.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .filter { it.name.endsWith(".apk", ignoreCase = true) }
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
        patcher.close()
    }

    companion object {
        private const val FRAMEWORK_APK_NAME = "1.apk"
        private const val FRAMEWORK_RESOURCES_TABLE = "resources.arsc"
        operator fun PatchResult.component1() = patch
        operator fun PatchResult.component2() = exception
    }
}
