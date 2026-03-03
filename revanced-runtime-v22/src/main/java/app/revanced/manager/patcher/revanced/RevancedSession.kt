package app.revanced.manager.patcher.revanced

import app.revanced.manager.patcher.ProgressEvent
import app.revanced.manager.patcher.StepId
import app.revanced.manager.patcher.logger.Logger
import app.revanced.manager.patcher.runStep
import app.revanced.manager.patcher.toRemoteError
import app.revanced.manager.patcher.split.SplitApkPreparer
import app.revanced.manager.patcher.util.NativeLibStripper
import app.revanced.patcher.PatchesResult
import app.revanced.patcher.patcher
import app.revanced.patcher.patch.Patch
import app.revanced.patcher.patch.PatchResult
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.LinkedHashSet
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal typealias RevancedPatchList = List<Patch>

class RevancedSession(
    cacheDir: String,
    frameworkDir: String,
    aaptPath: String,
    private val logger: Logger,
    private val input: File,
    private val onEvent: (ProgressEvent) -> Unit,
) : Closeable {
    private val tempDir = File(cacheDir).resolve("patcher").also { it.mkdirs() }
    private val frameworkDirFile = File(frameworkDir).also { it.mkdirs() }
    private var selectedPatches = emptySet<Patch>()
    private val patcher =
        patcher(
            apkFile = input,
            temporaryFilesPath = tempDir,
            aaptBinaryPath = File(aaptPath),
            frameworkFileDirectory = frameworkDirFile.absolutePath,
        ) { _, _ ->
            selectedPatches
        }

    private suspend fun applyPatchesVerbose(
        patches: RevancedPatchList,
        preStarted: Set<Int> = emptySet()
    ): PatchesResult {
        selectedPatches = LinkedHashSet(patches)
        val indexByPatch = patches.withIndex().associate { it.value to it.index }
        val started = mutableSetOf<Int>()
        started.addAll(preStarted)
        var nextIndex = 0

        fun patchNameAt(index: Int): String =
            patches.getOrNull(index)?.name ?: "Patch #${index + 1}"

        fun startPatch(index: Int) {
            if (index !in patches.indices) return
            if (!started.add(index)) return
            onEvent(ProgressEvent.Started(StepId.ExecutePatch(index)))
        }

        if (patches.isNotEmpty()) {
            startPatch(0)
        }

        val patchResult = patcher { result ->
            val patch = result.patch
            val exception = result.exception
            val index = indexByPatch[patch] ?: return@patcher

            if (exception != null) {
                if (index < nextIndex) {
                    onEvent(ProgressEvent.Failed(StepId.ExecutePatch(index), exception.toRemoteError()))
                    logger.error("${patch.name ?: patchNameAt(index)} failed:")
                    logger.error(exception.stackTraceToString())
                    throw exception
                }
                while (nextIndex < index) {
                    startPatch(nextIndex)
                    onEvent(ProgressEvent.Completed(StepId.ExecutePatch(nextIndex)))
                    logger.info("${patchNameAt(nextIndex)} succeeded")
                    nextIndex += 1
                }
                startPatch(index)
                onEvent(ProgressEvent.Failed(StepId.ExecutePatch(index), exception.toRemoteError()))
                logger.error("${patch.name ?: patchNameAt(index)} failed:")
                logger.error(exception.stackTraceToString())
                throw exception
            }

            if (index < nextIndex) return@patcher
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

        return patchResult
    }

    private suspend fun executePatchesOnce(orderedPatches: RevancedPatchList): PatchesResult {
        if (orderedPatches.isNotEmpty()) {
            onEvent(ProgressEvent.Started(StepId.ExecutePatch(0)))
        }

        logger.info("Applying patches...")
        return applyPatchesVerbose(
            orderedPatches,
            preStarted = if (orderedPatches.isNotEmpty()) setOf(0) else emptySet()
        )
    }

    private suspend fun executePatchesWithFrameworkRecovery(orderedPatches: RevancedPatchList): PatchesResult {
        ensureFrameworkCacheIsValid()
        return executePatchesOnce(orderedPatches)
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
        selectedPatches: RevancedPatchList,
        stripNativeLibs: Boolean,
        inputWasSplit: Boolean
    ) {
        val shouldStripNativeLibs = stripNativeLibs && !inputWasSplit
        val orderedPatches = selectedPatches.sortedBy { it.name.orEmpty() }
        val patchResult = runStep(StepId.ExecutePatches, onEvent) {
            java.util.logging.Logger.getLogger("").apply {
                handlers.forEach {
                    it.close()
                    removeHandler(it)
                }
                addHandler(logger.handler)
            }
            executePatchesWithFrameworkRecovery(orderedPatches)
        }

        // Ensure patch rows are finalized before write/sign steps begin.
        orderedPatches.indices.forEach { index ->
            onEvent(ProgressEvent.Completed(StepId.ExecutePatch(index)))
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
                val updatedDexNames = mergeDexNames(initialDexNames, patchResult)
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
                runInterruptible(Dispatchers.IO) {
                    applyResultToApk(patched, patchResult)
                }

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

    private fun applyResultToApk(apkFile: File, result: PatchesResult) {
        val applyDir = tempDir.resolve("apply").also {
            it.deleteRecursively()
            it.mkdirs()
        }
        val replacementFiles = linkedMapOf<String, File>()
        val deleteEntries = linkedSetOf<String>()
        val doNotCompress = linkedSetOf<String>()
        var replaceResourceDirectory = false

        result.dexFiles.forEach { dex ->
            val entryName = sanitizeZipEntryName(dex.name) ?: return@forEach
            val dexFile = applyDir.resolve("dex").resolve(entryName.toFileSystemPath())
            dexFile.parentFile?.mkdirs()
            dex.stream.use { input ->
                FileOutputStream(dexFile).use { output ->
                    input.copyTo(output)
                }
            }
            replacementFiles[entryName] = dexFile
        }

        result.resources?.let { resources ->
            resources.resourcesApk?.let { resourcesApk ->
                replaceResourceDirectory = true
                ZipFile(resourcesApk).use { zip ->
                    val entries = zip.entries().asSequence().toList()
                    entries.forEach { entry ->
                        if (entry.isDirectory) return@forEach
                        val entryName = sanitizeZipEntryName(entry.name) ?: return@forEach
                        val target = applyDir.resolve("resourcesApk").resolve(entryName.toFileSystemPath())
                        target.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(target).use { output ->
                                input.copyTo(output)
                            }
                        }
                        replacementFiles[entryName] = target
                    }
                }
            }

            resources.otherResources?.let { resourcesDir ->
                if (resourcesDir.exists()) {
                    resourcesDir.walkTopDown()
                        .filter { it.isFile }
                        .forEach { file ->
                            val relative = file.relativeTo(resourcesDir).path.replace(File.separatorChar, '/')
                            val entryName = sanitizeZipEntryName(relative) ?: return@forEach
                            replacementFiles[entryName] = file
                        }
                }
            }

            resources.deleteResources
                .mapNotNull(::sanitizeZipEntryName)
                .forEach(deleteEntries::add)

            resources.doNotCompress
                .mapNotNull(::sanitizeZipEntryName)
                .forEach(doNotCompress::add)
        }

        val tempOutput = File(apkFile.parentFile, "${apkFile.name}.tmp")
        ZipFile(apkFile).use { original ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tempOutput))).use { output ->
                val entries = original.entries().asSequence().toList()
                entries.forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    val entryName = sanitizeZipEntryName(entry.name) ?: return@forEach
                    if (entryName in deleteEntries) return@forEach
                    if (replaceResourceDirectory && entryName.startsWith("res/")) return@forEach

                    val replacement = replacementFiles.remove(entryName)
                    if (replacement != null) {
                        output.writeFileEntry(entryName, replacement, entryName in doNotCompress)
                    } else {
                        output.writeInputEntry(entryName, original, entry)
                    }
                }

                replacementFiles.forEach { (entryName, file) ->
                    output.writeFileEntry(entryName, file, entryName in doNotCompress)
                }
            }
        }

        try {
            Files.move(
                tempOutput.toPath(),
                apkFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            Files.move(
                tempOutput.toPath(),
                apkFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun ZipOutputStream.writeInputEntry(
        entryName: String,
        sourceZip: ZipFile,
        sourceEntry: ZipEntry,
    ) {
        putNextEntry(ZipEntry(entryName))
        sourceZip.getInputStream(sourceEntry).use { input ->
            input.copyTo(this)
        }
        closeEntry()
    }

    private fun ZipOutputStream.writeFileEntry(
        entryName: String,
        sourceFile: File,
        storeUncompressed: Boolean,
    ) {
        val outputEntry = ZipEntry(entryName)
        if (storeUncompressed) {
            outputEntry.method = ZipEntry.STORED
            outputEntry.size = sourceFile.length()
            outputEntry.crc = crc32(sourceFile)
        }
        putNextEntry(outputEntry)
        FileInputStream(sourceFile).use { input ->
            input.copyTo(this)
        }
        closeEntry()
    }

    private fun crc32(file: File): Long {
        val crc = CRC32()
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }

    private fun sanitizeZipEntryName(name: String): String? {
        val normalized = name.replace('\\', '/').trimStart('/')
        if (normalized.isBlank()) return null
        if (normalized.startsWith("../")) return null
        if (normalized.contains("/../")) return null
        return normalized
    }

    private fun String.toFileSystemPath(): String = replace('/', File.separatorChar)

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
    }

    companion object {
        private const val FRAMEWORK_APK_NAME = "1.apk"
        private const val FRAMEWORK_RESOURCES_TABLE = "resources.arsc"
        operator fun PatchResult.component1() = patch
        operator fun PatchResult.component2() = exception
    }
}
