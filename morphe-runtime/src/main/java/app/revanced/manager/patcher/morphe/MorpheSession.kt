package app.revanced.manager.patcher.morphe

import app.morphe.library.ApkUtils.applyTo
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.PatchResult
import app.revanced.manager.patcher.ProgressEvent
import app.revanced.manager.patcher.StepId
import app.revanced.manager.patcher.logger.Logger
import app.revanced.manager.patcher.morphe.MorpheSession.Companion.component1
import app.revanced.manager.patcher.morphe.MorpheSession.Companion.component2
import app.revanced.manager.patcher.runStep
import app.revanced.manager.patcher.split.SplitApkPreparer
import app.revanced.manager.patcher.util.NativeLibStripper
import app.revanced.manager.patcher.util.XmlSurrogateSanitizer
import app.revanced.manager.patcher.toRemoteError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.LinkedHashSet
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
    private val logger: Logger,
    private val input: File,
    private val onEvent: (ProgressEvent) -> Unit,
) : Closeable {
    private val tempDir = File(cacheDir).resolve("patcher").also { it.mkdirs() }
    private val frameworkDirFile = File(frameworkDir).also { it.mkdirs() }
    private val resolvedAaptPath = aaptPath
    private var patcher = createPatcher()
    private var resourcesDecoded = false

    private fun createPatcher() = Patcher(
        PatcherConfig(
            apkFile = input,
            temporaryFilesPath = tempDir,
            frameworkFileDirectory = frameworkDirFile.absolutePath,
            aaptBinaryPath = resolvedAaptPath
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

    private suspend fun executePatchesOnce(orderedPatches: MorphePatchList) {
        with(patcher) {
            if (orderedPatches.isNotEmpty()) {
                onEvent(ProgressEvent.Started(StepId.ExecutePatch(0)))
            }
            logger.info("Merging integrations")
            this += LinkedHashSet(orderedPatches)
            decodeResourcesIfNeeded()

            logger.info("Applying patches...")
            applyPatchesVerbose(
                orderedPatches,
                preStarted = if (orderedPatches.isNotEmpty()) setOf(0) else emptySet()
            )
        }
    }

    private suspend fun executePatchesWithFrameworkRecovery(orderedPatches: MorphePatchList) {
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
        selectedPatches: MorphePatchList,
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
                ensureMissingDrawables()
                validateMissingResourceReferences()
                val result = patcher.get()

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

    private suspend fun listDexNames(file: File): List<String> {
        if (!file.exists()) return emptyList()
        if (!SplitApkPreparer.isSplitArchive(file)) {
            return listDexNamesFromApk(file)
        }
        return listDexNamesFromSplitArchive(file)
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

    private fun decodeResourcesIfNeeded() {
        if (resourcesDecoded) return
        runCatching {
            val configField = patcher.javaClass.getDeclaredField("config").apply {
                isAccessible = true
            }
            val config = configField.get(patcher) as? PatcherConfig ?: return
            val resourceMode = config.javaClass
                .getMethod("getResourceMode\$morphe_patcher")
                .invoke(config) ?: return
            if (resourceMode.toString() == "NONE") return

            val resourceContext = patcher.context.javaClass
                .getMethod("getResourceContext\$morphe_patcher")
                .invoke(patcher.context)
            val decodeMethod = resourceContext.javaClass.getMethod(
                "decodeResources\$morphe_patcher",
                resourceMode.javaClass
            )
            decodeMethod.invoke(resourceContext, resourceMode)
            resourcesDecoded = true
        }.onFailure { error ->
            logger.warn("Failed to decode Morphe resources: ${error.message}")
        }
    }

    companion object {
        private const val FRAMEWORK_APK_NAME = "1.apk"
        private const val FRAMEWORK_RESOURCES_TABLE = "resources.arsc"
        operator fun PatchResult.component1() = patch
        operator fun PatchResult.component2() = exception
    }
}
