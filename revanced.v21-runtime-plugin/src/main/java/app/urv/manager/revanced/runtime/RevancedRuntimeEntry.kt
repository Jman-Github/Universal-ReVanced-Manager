package app.urv.manager.revanced.runtime

import android.os.Build
import app.revanced.patcher.patch.Option
import app.revanced.patcher.patch.Patch
import app.urv.manager.patcher.PatchList
import app.urv.manager.patcher.ProgressEvent
import app.urv.manager.patcher.RemoteError
import app.urv.manager.patcher.Session
import app.urv.manager.patcher.StepId
import app.urv.manager.patcher.aapt.AaptSelector
import app.urv.manager.patcher.logger.LogLevel
import app.urv.manager.patcher.logger.Logger
import app.urv.manager.patcher.patch.PatchBundle
import app.urv.manager.patcher.runtime.FrameworkCacheResolver
import app.urv.manager.patcher.runStep
import app.urv.manager.patcher.split.SplitApkPreparer
import app.urv.manager.patcher.toSafeStackTraceString
import app.urv.manager.patcher.util.MislabeledImageResourceSanitizer
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.security.MessageDigest
import java.util.Locale
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

object RevancedRuntimeEntry {
    private const val CANCELLATION_SENTINEL = "__PATCHING_CANCELLED__"

    @JvmStatic
    fun loadMetadata(bundlePaths: List<String>): Map<String, List<Map<String, Any?>>> =
        bundlePaths.associateWith(::loadMetadataForBundle)

    @JvmStatic
    fun loadMetadataForBundle(bundlePath: String): List<Map<String, Any?>> =
        PatchBundle.Loader.metadata(PatchBundle(bundlePath)).map(::patchToMap)

    @JvmStatic
    fun runPatcher(params: Map<String, Any?>, callback: RevancedRuntimeCallback): String? {
        fun throwIfCancelled() {
            if (callback.isCancelled()) {
                throw CancellationException("Patching cancelled")
            }
        }

        fun onEvent(event: ProgressEvent) {
            throwIfCancelled()
            callback.event(event.toMap())
        }

        val logger = object : Logger() {
            override fun log(level: LogLevel, message: String) {
                throwIfCancelled()
                callback.log(level.name, message)
            }
        }

        val aaptPath = params["aaptPath"] as? String
            ?: return "Missing aaptPath parameter."
        val aaptFallbackPath = params["aaptFallbackPath"] as? String
        val frameworkDir = params["frameworkDir"] as? String
            ?: return "Missing frameworkDir parameter."
        val cacheDir = params["cacheDir"] as? String
            ?: return "Missing cacheDir parameter."
        val packageName = params["packageName"] as? String
            ?: return "Missing packageName parameter."
        val inputFile = params["inputFile"] as? String
            ?: return "Missing inputFile parameter."
        val outputFile = params["outputFile"] as? String
            ?: return "Missing outputFile parameter."
        val stripNativeLibs = params["stripNativeLibs"] as? Boolean ?: false
        val skipUnneededSplits = params["skipUnneededSplits"] as? Boolean ?: false
        val configurations = params["configurations"] as? List<*> ?: emptyList<Any>()

        val aaptLogs = AaptLogCapture().apply { start() }
        val stdioCapture = StdIoCapture().apply { start() }

        return try {
            val configs = configurations.mapNotNull { raw ->
                val map = raw as? Map<*, *> ?: return@mapNotNull null
                val bundlePath = map["bundlePath"] as? String ?: return@mapNotNull null
                val patches = (map["patches"] as? Iterable<*>)?.mapNotNull { it as? String }
                    ?: emptyList()
                val options = map["options"] as? Map<*, *> ?: emptyMap<Any, Any?>()
                RuntimeConfiguration(bundlePath, patches, options)
            }

            runBlocking {
                val runtimeJob = coroutineContext[Job]
                val cancellationWatcher = launch {
                    while (isActive) {
                        if (callback.isCancelled()) {
                            runtimeJob?.cancel(CancellationException("Patching cancelled"))
                            return@launch
                        }
                        delay(50)
                    }
                }
                try {
                    var cachedSelectedPatches: PatchList? = null
                    suspend fun loadSelectedPatches(): PatchList {
                        cachedSelectedPatches?.let { return it }

                        return runCatching {
                            val patchBundles = configs.map { PatchBundle(it.bundlePath) }
                            val allPatches = PatchBundle.Loader.patches(patchBundles, packageName)

                            configs.flatMap { config ->
                                val bundle = PatchBundle(config.bundlePath)
                                val patches = (allPatches[bundle] ?: return@flatMap emptyList())
                                    .filter { it.name in config.patches }
                                    .associateBy { it.name.orEmpty() }

                                val filteredOptions = config.options.filterKeys { key ->
                                    key is String && key in patches
                                }
                                filteredOptions.forEach { (patchName, opts) ->
                                    val patchOptions = patches[patchName]?.options
                                        ?: throw Exception("Patch with name $patchName does not exist.")
                                    val patchOptionsMap = opts as? Map<*, *> ?: emptyMap<Any, Any?>()
                                    patchOptionsMap.forEach { (key, value) ->
                                        val keyString = key as? String ?: return@forEach
                                        patchOptions[keyString] = value
                                    }
                                }

                                patches.values
                            }
                        }.getOrThrow().also { cachedSelectedPatches = it }
                    }

                    runStep(StepId.LoadPatches, ::onEvent, ::throwIfCancelled) {
                        loadSelectedPatches().size
                    }

                    var sanitizedInput: MislabeledImageResourceSanitizer.Result? = null
                    var preparation: SplitApkPreparer.PreparationResult? = null
                    try {
                        val input = File(inputFile)
                        suspend fun prepareInput() = SplitApkPreparer.prepareIfNeeded(
                            input,
                            File(cacheDir),
                            logger,
                            stripNativeLibs,
                            skipUnneededSplits,
                            onProgress = { message ->
                                throwIfCancelled()
                                onEvent(
                                    ProgressEvent.Progress(
                                        stepId = StepId.PrepareSplitApk,
                                        message = message
                                    )
                                )
                            },
                            onSubSteps = { subSteps ->
                                throwIfCancelled()
                                onEvent(
                                    ProgressEvent.Progress(
                                        stepId = StepId.PrepareSplitApk,
                                        subSteps = subSteps
                                    )
                                )
                            }
                        )
                        if (SplitApkPreparer.isSplitArchive(input)) {
                            preparation = runStep(StepId.PrepareSplitApk, ::onEvent, ::throwIfCancelled) {
                                prepareInput()
                            }
                        }

                        lateinit var preparedInput: SplitApkPreparer.PreparationResult
                        lateinit var patcherInput: File
                        suspend fun openSessionWithAapt(
                            selectedAaptPath: String,
                            eventSink: (ProgressEvent) -> Unit
                        ): Session {
                            val resolvedFrameworkDir = FrameworkCacheResolver.resolve(
                                baseFrameworkDir = frameworkDir,
                                runtimeTag = revanced21FrameworkRuntimeTag(),
                                apkFile = patcherInput,
                                aaptPath = selectedAaptPath,
                                logger = logger
                            )
                            return Session.open(
                                cacheDir = cacheDir,
                                aaptPath = selectedAaptPath,
                                frameworkDir = resolvedFrameworkDir,
                                logger = logger,
                                input = preparedInput.file,
                                initialPatcherInput = patcherInput,
                                sanitizeAllEmbeddedApksOnInit = preparedInput.merged,
                                onEvent = eventSink,
                                checkCancelled = ::throwIfCancelled
                            )
                        }

                        var deferredFailure: ProgressEvent.Failed? = null
                        fun retryAwareOnEvent(event: ProgressEvent) {
                            if (event is ProgressEvent.Failed && event.stepId == StepId.WriteAPK) {
                                deferredFailure = event
                            } else {
                                onEvent(event)
                            }
                        }

                        fun hiddenFallbackOnEvent(event: ProgressEvent) {
                            if (event is ProgressEvent.Failed) {
                                onEvent(ProgressEvent.Failed(StepId.WriteAPK, event.error))
                            }
                        }

                        fun flushDeferredFailure() {
                            deferredFailure?.let(::onEvent)
                            deferredFailure = null
                        }

                        lateinit var selectedAaptPath: String
                        val session = runStep(StepId.ReadAPK, ::onEvent, ::throwIfCancelled) {
                            preparedInput = preparation ?: prepareInput().also { preparation = it }
                            val sanitized = MislabeledImageResourceSanitizer.sanitizeApkFile(
                                apkFile = preparedInput.file,
                                workingDir = File(cacheDir).resolve("patcher-inputs"),
                                logger = logger
                            )
                            sanitizedInput = sanitized
                            patcherInput = sanitized.file
                            val relatedBundleArchives = configs
                                .asSequence()
                                .filter { it.patches.isNotEmpty() }
                                .map { File(it.bundlePath) }
                                .toList()
                            selectedAaptPath = AaptSelector.select(
                                modern = aaptPath,
                                legacy = aaptFallbackPath,
                                apk = patcherInput,
                                logger = logger,
                                additionalArchives = relatedBundleArchives
                            )
                            openSessionWithAapt(selectedAaptPath, ::retryAwareOnEvent)
                        }

                        val output = File(outputFile)
                        session.use {
                            val executedPatches = it.executePatches(
                                { loadSelectedPatches() },
                                stripNativeLibs,
                                preparedInput.merged
                            )
                            try {
                                it.writeOutput(output, executedPatches)
                            } catch (error: Throwable) {
                                val alternateAaptPath = AaptSelector.alternate(
                                    selected = selectedAaptPath,
                                    modern = aaptPath,
                                    legacy = aaptFallbackPath
                                )
                                if (
                                    deferredFailure == null ||
                                    alternateAaptPath == null ||
                                    !error.isRetryableAaptFailure()
                                ) {
                                    flushDeferredFailure()
                                    throw error
                                }

                                deferredFailure = null
                                logger.info("AAPT2 fallback: true (${alternateAaptPath.aaptDisplayName()})")
                                val fallbackSession = openSessionWithAapt(alternateAaptPath, ::hiddenFallbackOnEvent)
                                fallbackSession.use { fallback ->
                                    // Force a clean retry with fresh patch instances.
                                    cachedSelectedPatches = null
                                    val fallbackExecutedPatches = fallback.executePatches(
                                        { loadSelectedPatches() },
                                        stripNativeLibs,
                                        preparedInput.merged
                                    )
                                    fallback.writeOutput(output, fallbackExecutedPatches)
                                }
                                onEvent(ProgressEvent.Completed(StepId.WriteAPK))
                            }
                        }
                    } finally {
                        sanitizedInput?.cleanup()
                        preparation?.cleanup()
                    }
                } finally {
                    cancellationWatcher.cancel()
                }
            }

            null
        } catch (cancelled: CancellationException) {
            CANCELLATION_SENTINEL
        } catch (throwable: Throwable) {
            val extra = aaptLogs.dump()
            val stack = throwable.toSafeStackTraceString()
            if (throwable !is OutOfMemoryError && extra.isNotBlank()) {
                "$stack\n\nAAPT2 output:\n$extra"
            } else {
                stack
            }
        } finally {
            stdioCapture.close()
            aaptLogs.stop()
        }
    }

    private fun String.aaptDisplayName(): String {
        val name = File(this).name
        val lowerName = name.lowercase(Locale.ROOT)
        return when {
            "legacy" in lowerName -> "Legacy"
            "modern" in lowerName -> "Modern"
            else -> name
        }
    }

    private fun Throwable.isRetryableAaptFailure(): Boolean {
        if (this is CancellationException || this is OutOfMemoryError) return false
        return generateSequence(this) { it.cause }.any { error ->
            val message = error.message.orEmpty()
            val className = error::class.java.name
            val stack = error.stackTrace.joinToString("\n") { frame -> frame.className }
            sequenceOf(className, message, stack).any { text ->
                text.contains("AAPT", ignoreCase = true) ||
                    text.contains("aapt2", ignoreCase = true) ||
                    text.contains("AaptInvoker", ignoreCase = true) ||
                    text.contains("Androlib", ignoreCase = true) ||
                    text.contains("BrutException", ignoreCase = true) ||
                    text.contains("APKTOOL_MISSING", ignoreCase = true)
            }
        }
    }

    private fun patchToMap(patch: Patch<*>): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        result["name"] = patch.name.orEmpty()
        result["description"] = patch.description
        result["use"] = patch.use
        result["compatiblePackages"] = patch.compatiblePackages?.map { (pkg, versions) ->
            linkedMapOf(
                "packageName" to pkg,
                "versions" to versions?.toList()
            )
        }
        val options = patch.options.values.map(::optionToMap)
        result["options"] = options.ifEmpty { null }
        return result
    }

    private fun optionToMap(option: Option<*>): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        result["key"] = option.key
        result["title"] = option.title ?: option.key
        result["description"] = option.description.orEmpty()
        result["required"] = option.required
        result["type"] = option.type.toString()
        result["default"] = normalizeValue(option.default)
        result["presets"] = option.values
            ?.mapValues { (_, value) -> normalizeValue(value) }
            ?.ifEmpty { null }
        return result
    }

    private fun normalizeValue(value: Any?): Any? = when (value) {
        null -> null
        is String -> value
        is Boolean -> value
        is Int -> value
        is Long -> value
        is Float -> value
        is Double -> value.toFloat()
        is Iterable<*> -> value.map(::normalizeValue)
        else -> value.toString()
    }

    private data class RuntimeConfiguration(
        val bundlePath: String,
        val patches: List<String>,
        val options: Map<*, *>,
    )

    private fun revanced21FrameworkRuntimeTag(): String {
        val fingerprint = Build.FINGERPRINT.takeUnless { it.isNullOrBlank() } ?: "unknown"
        val fingerprintHash = MessageDigest.getInstance("SHA-256")
            .digest(fingerprint.toByteArray())
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
            .take(12)
        return "revanced21_device${Build.VERSION.SDK_INT}_$fingerprintHash"
    }

    private fun ProgressEvent.toMap(): Map<String, Any?> = when (this) {
        is ProgressEvent.Started -> mapOf(
            "type" to "Started",
            "stepId" to stepId.toMap(),
            "subSteps" to subSteps
        )
        is ProgressEvent.Progress -> mapOf(
            "type" to "Progress",
            "stepId" to stepId.toMap(),
            "current" to current,
            "total" to total,
            "message" to message,
            "subSteps" to subSteps
        )
        is ProgressEvent.Completed -> mapOf(
            "type" to "Completed",
            "stepId" to stepId.toMap()
        )
        is ProgressEvent.Failed -> mapOf(
            "type" to "Failed",
            "stepId" to stepId?.toMap(),
            "error" to error.toMap()
        )
    }

    private fun StepId.toMap(): Map<String, Any?> = when (this) {
        StepId.DownloadAPK -> mapOf("kind" to "DownloadAPK")
        StepId.LoadPatches -> mapOf("kind" to "LoadPatches")
        StepId.PrepareSplitApk -> mapOf("kind" to "PrepareSplitApk")
        StepId.ReadAPK -> mapOf("kind" to "ReadAPK")
        StepId.ExecutePatches -> mapOf("kind" to "ExecutePatches")
        StepId.WriteAPK -> mapOf("kind" to "WriteAPK")
        StepId.SignAPK -> mapOf("kind" to "SignAPK")
        is StepId.ExecutePatch -> mapOf("kind" to "ExecutePatch", "index" to index)
    }

    private fun RemoteError.toMap(): Map<String, Any?> = mapOf(
        "type" to type,
        "message" to message,
        "stackTrace" to stackTrace
    )

    private class AaptLogCapture {
        private val logger = java.util.logging.Logger.getLogger("")
        private val lines = ArrayDeque<String>()
        private var originalLevel: Level? = null
        private val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                val message = record.message?.trim().orEmpty()
                if (message.isEmpty()) return
                synchronized(lines) {
                    if (lines.size >= MAX_LINES) {
                        lines.removeFirst()
                    }
                    lines.addLast(message)
                }
            }

            override fun flush() {}
            override fun close() {}
        }

        fun start() {
            originalLevel = logger.level
            logger.level = Level.ALL
            handler.level = Level.ALL
            logger.addHandler(handler)
        }

        fun stop() {
            logger.removeHandler(handler)
            logger.level = originalLevel
        }

        fun dump(): String = synchronized(lines) { lines.joinToString("\n") }

        companion object {
            private const val MAX_LINES = 200
        }
    }

    private class StdIoCapture(
        private val onLine: (String) -> Unit = {}
    ) {
        private val originalOut = System.out
        private val originalErr = System.err
        private val outBuffer = LineBufferOutputStream(onLine)
        private val errBuffer = LineBufferOutputStream(onLine)
        private val outStream = PrintStream(TeeOutputStream(originalOut, outBuffer), true)
        private val errStream = PrintStream(TeeOutputStream(originalErr, errBuffer), true)

        fun start() {
            System.setOut(outStream)
            System.setErr(errStream)
        }

        fun close() {
            outBuffer.flushPending()
            errBuffer.flushPending()
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }

    private class TeeOutputStream(
        private val first: OutputStream,
        private val second: OutputStream
    ) : OutputStream() {
        override fun write(b: Int) {
            first.write(b)
            second.write(b)
        }

        override fun write(bytes: ByteArray, off: Int, len: Int) {
            first.write(bytes, off, len)
            second.write(bytes, off, len)
        }

        override fun flush() {
            first.flush()
            second.flush()
        }
    }

    private class LineBufferOutputStream(
        private val onLine: (String) -> Unit
    ) : OutputStream() {
        private val buffer = StringBuilder()

        override fun write(b: Int) {
            appendChar(b.toChar())
        }

        override fun write(bytes: ByteArray, off: Int, len: Int) {
            for (index in off until off + len) {
                appendChar(bytes[index].toInt().toChar())
            }
        }

        override fun flush() {
            flushPending()
        }

        fun flushPending() {
            if (buffer.isEmpty()) return
            val line = buffer.toString()
            buffer.setLength(0)
            onLine(line)
        }

        private fun appendChar(ch: Char) {
            when (ch) {
                '\n' -> flushPending()
                '\r' -> Unit
                else -> buffer.append(ch)
            }
        }
    }
}
