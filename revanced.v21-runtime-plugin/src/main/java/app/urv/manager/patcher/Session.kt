package app.urv.manager.patcher

import android.os.Build
import app.urv.manager.patcher.Session.Companion.component1
import app.urv.manager.patcher.Session.Companion.component2
import app.urv.manager.patcher.logger.Logger
import app.urv.manager.patcher.split.SplitApkPreparer
import app.urv.manager.patcher.util.ManifestDecimalResourceReferenceSanitizer
import app.urv.manager.patcher.util.MislabeledImageResourceSanitizer
import app.urv.manager.patcher.util.NativeLibStripper
import app.urv.manager.patcher.util.XmlSurrogateSanitizer
import app.revanced.patcher.Patcher
import app.revanced.patcher.PatcherConfig
import app.revanced.patcher.PatcherResult
import app.revanced.patcher.patch.Patch
import app.revanced.patcher.patch.PatchResult
import com.android.tools.build.apkzlib.zip.AlignmentRules
import com.android.tools.build.apkzlib.zip.ZFile
import com.android.tools.build.apkzlib.zip.ZFileOptions
import com.google.common.base.Predicate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.BufferedInputStream
import java.util.Enumeration
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Attr
import org.w3c.dom.Element

internal typealias PatchList = List<Patch<*>>

class Session private constructor(
    cacheDir: String,
    frameworkDir: String,
    aaptPath: String,
    private val logger: Logger,
    private val input: File,
    private val initialPatcherInput: File? = null,
    private val sanitizeAllEmbeddedApksOnInit: Boolean = false,
    private val onEvent: (ProgressEvent) -> Unit,
    private val checkCancelled: () -> Unit = {},
) : Closeable {
    private val tempDir = File(cacheDir).resolve("patcher").also { it.mkdirs() }
    private val patcherInputDir = File(cacheDir).resolve("patcher-inputs").also { it.mkdirs() }
    private val frameworkDirFile = File(frameworkDir).also { it.mkdirs() }
    private val resolvedAaptPath = aaptPath
    private var patcherInput = initializePatcherInput()
    private lateinit var patcher: Patcher

    data class ExecutedPatches(
        val shouldStripNativeLibs: Boolean,
    )

    private fun initializePatcherInput(): PreparedPatcherInput {
        val baseInput = initialPatcherInput ?: input
        return prepareLegacyPatcherInputIfNeeded(
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

    private suspend fun Patcher.applyPatchesVerbose(selectedPatches: PatchList) {
        if (selectedPatches.isEmpty()) return
        val indexByPatch = selectedPatches.withIndex().associate { it.value to it.index }
        val started = mutableSetOf<Int>()
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
                if (index < nextIndex) {
                    onEvent(ProgressEvent.Failed(StepId.ExecutePatch(index), exception.toSafeRemoteError()))
                    logger.error("${patch.name} failed:")
                    logger.error(exception.toSafeStackTraceString())
                    throw exception
                }
                while (nextIndex < index) {
                    startPatch(nextIndex)
                    onEvent(ProgressEvent.Completed(StepId.ExecutePatch(nextIndex)))
                    logger.info("${selectedPatches[nextIndex].name} succeeded")
                    nextIndex += 1
                }
                startPatch(index)
                onEvent(ProgressEvent.Failed(StepId.ExecutePatch(index), exception.toSafeRemoteError()))
                logger.error("${patch.name} failed:")
                logger.error(exception.toSafeStackTraceString())
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

    private suspend fun executePatchesOnce(orderedPatches: PatchList) {
        checkCancelled()
        with(requirePatcher()) {
            logger.info("Merging integrations")
            this += LinkedHashSet(orderedPatches)

            logger.info("Applying patches...")
            applyPatchesVerbose(orderedPatches)
        }
    }

    private suspend fun executePatchesWithFrameworkRecovery(orderedPatches: PatchList) {
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
        loadSelectedPatches: suspend () -> PatchList,
        stripNativeLibs: Boolean,
        inputWasSplit: Boolean
    ) {
        val executedPatches = executePatches(loadSelectedPatches, stripNativeLibs, inputWasSplit)
        writeOutput(output, executedPatches)
    }

    suspend fun executePatches(
        loadSelectedPatches: suspend () -> PatchList,
        stripNativeLibs: Boolean,
        inputWasSplit: Boolean
    ): ExecutedPatches {
        checkCancelled()
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

        return ExecutedPatches(
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
            XmlSurrogateSanitizer.sanitize(tempDir.resolve("apk"), logger)
            ManifestDecimalResourceReferenceSanitizer.sanitize(tempDir.resolve("apk"), logger)
            MislabeledImageResourceSanitizer.sanitizeDecodedResources(
                tempDir.resolve("apk").resolve("res"),
                logger
            )
            validateMissingResourceReferences()
            validateInvalidNumericCharacterReferences()
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

    private fun validateInvalidNumericCharacterReferences() {
        val apkDir = tempDir.resolve("apk")
        val resDir = apkDir.resolve("res")
        if (!resDir.exists()) return

        val invalidRefs = mutableListOf<String>()
        resDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("xml", ignoreCase = true) }
            .filter { it.parentFile?.name?.startsWith("values") == true }
            .forEach { file ->
                val text = readXmlText(file) ?: return@forEach
                invalidRefs += findInvalidNumericCharRefs(text, file)
            }

        if (invalidRefs.isNotEmpty()) {
            val message = buildString {
                appendLine("Invalid numeric character reference(s) detected:")
                invalidRefs.distinct().sorted().forEach { appendLine(it) }
            }
            logger.error(message)
            throw IllegalStateException("Invalid numeric character reference(s) detected.")
        }
    }

    private fun findInvalidNumericCharRefs(text: String, file: File): List<String> {
        val invalid = mutableListOf<String>()
        val lines = text.split('\n')
        val lineStarts = buildLineStarts(text)
        var index = 0
        while (true) {
            val start = text.indexOf("&#", index)
            if (start == -1) break
            var cursor = start + 2
            val isHex = cursor < text.length && (text[cursor] == 'x' || text[cursor] == 'X')
            if (isHex) cursor++
            val digitsStart = cursor
            while (cursor < text.length && (if (isHex) text[cursor].isHexDigit() else text[cursor].isDigit())) {
                cursor++
            }
            val digits = text.substring(digitsStart, cursor)
            val hasSemicolon = cursor < text.length && text[cursor] == ';'
            val numeric = if (digits.isNotEmpty()) digits.toLongOrNull(if (isHex) 16 else 10) else null
            val valid = digits.isNotEmpty() && hasSemicolon && numeric != null && isValidXmlChar(numeric)
            if (!valid) {
                val lineIndex = lineIndexForOffset(lineStarts, start)
                val lineNumber = lineIndex + 1
                val lineStart = lineStarts[lineIndex]
                val lineEnd = if (lineIndex + 1 < lineStarts.size) lineStarts[lineIndex + 1] - 1 else text.length
                val line = text.substring(lineStart, lineEnd).trim()
                val refEnd = if (hasSemicolon) cursor + 1 else cursor.coerceAtMost(text.length)
                val refSnippet = text.substring(start, refEnd.coerceAtMost(text.length))
                val nameHint = findNearestStringName(lines, lineIndex)?.let { " (name=$it)" } ?: ""
                invalid.add("${file.path}:$lineNumber$nameHint: $refSnippet :: $line")
            }
            index = start + 2
        }
        return invalid
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun readXmlText(file: File): String? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.isEmpty()) return ""
        val charset = detectXmlCharset(bytes) ?: Charsets.UTF_8
        return runCatching {
            if (charset == Charsets.UTF_8 && bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte()
            ) {
                bytes.copyOfRange(3, bytes.size).toString(charset)
            } else {
                bytes.toString(charset)
            }
        }.getOrNull()
    }

    private fun detectXmlCharset(bytes: ByteArray): java.nio.charset.Charset? {
        if (bytes.size >= 2) {
            val b0 = bytes[0]
            val b1 = bytes[1]
            if (b0 == 0xFE.toByte() && b1 == 0xFF.toByte()) return Charsets.UTF_16BE
            if (b0 == 0xFF.toByte() && b1 == 0xFE.toByte()) return Charsets.UTF_16LE
            if (b0 == 0x00.toByte() && b1 == 0x3C.toByte()) return Charsets.UTF_16BE
            if (b0 == 0x3C.toByte() && b1 == 0x00.toByte()) return Charsets.UTF_16LE
        }
        return null
    }

    private fun buildLineStarts(text: String): IntArray {
        val starts = ArrayList<Int>()
        starts.add(0)
        text.forEachIndexed { index, char ->
            if (char == '\n') {
                starts.add(index + 1)
            }
        }
        return starts.toIntArray()
    }

    private fun lineIndexForOffset(lineStarts: IntArray, offset: Int): Int {
        val idx = java.util.Arrays.binarySearch(lineStarts, offset)
        return if (idx >= 0) idx else -idx - 2
    }

    private fun findNearestStringName(lines: List<String>, lineIndex: Int): String? {
        val regex = Regex("<string[^>]*\\sname\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        for (i in lineIndex downTo (lineIndex - 5).coerceAtLeast(0)) {
            val match = regex.find(lines[i]) ?: continue
            return match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun isValidXmlChar(value: Long): Boolean {
        if (value == 0x9L || value == 0xAL || value == 0xDL) return true
        if (value in 0x20..0xD7FF) return true
        if (value in 0xE000..0xFFFD) return true
        if (value in 0x10000..0x10FFFF) return true
        return false
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
        return prepareLegacyPatcherInputIfNeeded(
            sourceApk = fallbackSourceApk,
            logReason = true,
            originalError = originalError,
            hideAllEmbeddedApks = true
        )
    }

    private fun prepareLegacyPatcherInputIfNeeded(
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
        ): Session {
            val session = Session(
                cacheDir = cacheDir,
                frameworkDir = frameworkDir,
                aaptPath = aaptPath,
                logger = logger,
                input = input,
                initialPatcherInput = initialPatcherInput,
                sanitizeAllEmbeddedApksOnInit = sanitizeAllEmbeddedApksOnInit,
                onEvent = onEvent,
                checkCancelled = checkCancelled
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
