package app.revanced.manager.patcher

import app.revanced.library.ApkUtils.applyTo
import app.revanced.manager.patcher.Session.Companion.component1
import app.revanced.manager.patcher.Session.Companion.component2
import app.revanced.manager.patcher.logger.Logger
import app.revanced.manager.patcher.split.SplitApkPreparer
import app.revanced.manager.patcher.util.NativeLibStripper
import app.revanced.patcher.Patcher
import app.revanced.patcher.PatcherConfig
import app.revanced.patcher.PatcherResult
import app.revanced.patcher.patch.Patch
import app.revanced.patcher.patch.PatchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.BufferedInputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

internal typealias PatchList = List<Patch<*>>

class Session(
    cacheDir: String,
    frameworkDir: String,
    aaptPath: String,
    private val logger: Logger,
    private val input: File,
    private val onEvent: (ProgressEvent) -> Unit,
) : Closeable {
    private val tempDir = File(cacheDir).resolve("patcher").also { it.mkdirs() }
    private val patcher = Patcher(
        PatcherConfig(
            apkFile = input,
            temporaryFilesPath = tempDir,
            frameworkFileDirectory = frameworkDir,
            aaptBinaryPath = aaptPath
        )
    )

    private suspend fun Patcher.applyPatchesVerbose(selectedPatches: PatchList) {
        if (selectedPatches.isEmpty()) return
        val indexByPatch = selectedPatches.withIndex().associate { it.value to it.index }
        val started = mutableSetOf<Int>()
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

    suspend fun run(
        output: File,
        selectedPatches: PatchList,
        stripNativeLibs: Boolean,
        inputWasSplit: Boolean
    ) {
        val shouldStripNativeLibs = stripNativeLibs && !inputWasSplit
        runStep(StepId.ExecutePatches, onEvent) {
            java.util.logging.Logger.getLogger("").apply {
                handlers.forEach {
                    it.close()
                    removeHandler(it)
                }

                addHandler(logger.handler)
            }

            with(patcher) {
                val orderedPatches = selectedPatches.sortedBy { it.name }
                logger.info("Merging integrations")
                this += LinkedHashSet(orderedPatches)

                logger.info("Applying patches...")
                applyPatchesVerbose(orderedPatches)
            }
        }

        onEvent(
            ProgressEvent.Progress(
                stepId = StepId.WriteAPK,
                message = "Preparing output APK"
            )
        )

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
            runInterruptible(Dispatchers.IO) {
                dedupeResourceValues()
            }
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
            runInterruptible(Dispatchers.IO) {
                result.applyTo(patched)
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

    private fun dedupeResourceValues() {
        val resDir = tempDir.resolve("apk").resolve("res")
        if (!resDir.exists()) return
        val valuesDirs = resDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("values") }
            .orEmpty()
        valuesDirs.forEach { dir ->
            val files = dir.listFiles()
                ?.filter { it.isFile && it.extension.equals("xml", ignoreCase = true) }
                ?.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
                .orEmpty()
            val seen = HashSet<String>()
            files.asReversed().forEach { file ->
                val removed = runCatching { dedupeResourceFile(file, seen) }.getOrDefault(0)
                if (removed > 0) {
                    logger.warn("Removed $removed duplicate resources from ${file.name}")
                }
            }
        }
    }

    private fun dedupeResourceFile(file: File, seen: MutableSet<String>): Int {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
        }
        val builder = factory.newDocumentBuilder()
        val document = builder.parse(file)
        val root = document.documentElement ?: return 0
        if (!root.tagName.equals("resources", ignoreCase = true)) return 0
        var removed = 0
        val nodes = root.childNodes
        var index = nodes.length - 1
        while (index >= 0) {
            val node = nodes.item(index)
            if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                val element = node as org.w3c.dom.Element
                val name = element.getAttribute("name")
                if (name.isNotBlank()) {
                    val type = if (element.tagName == "item") element.getAttribute("type") else ""
                    val key = "${element.tagName}|$type|$name"
                    if (!seen.add(key)) {
                        root.removeChild(element)
                        removed += 1
                    }
                }
            }
            index -= 1
        }
        if (removed == 0) return 0
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
            setOutputProperty(OutputKeys.ENCODING, "utf-8")
        }
        transformer.transform(DOMSource(document), StreamResult(file))
        return removed
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
        operator fun PatchResult.component1() = patch
        operator fun PatchResult.component2() = exception
    }
}
