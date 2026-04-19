package app.urv.manager.morphe.runtime

import android.os.Build
import app.morphe.patcher.dex.BytecodeMode
import app.morphe.patcher.patch.Option
import app.morphe.patcher.patch.Patch
import app.urv.manager.patcher.ProgressEvent
import app.urv.manager.patcher.RemoteError
import app.urv.manager.patcher.StepId
import app.urv.manager.patcher.toSafeStackTraceString
import app.urv.manager.patcher.logger.LogLevel
import app.urv.manager.patcher.logger.Logger
import app.urv.manager.patcher.morphe.MorphePatchBundleLoader
import app.urv.manager.patcher.morphe.MorphePatchList
import app.urv.manager.patcher.morphe.MorpheSession
import app.urv.manager.patcher.runtime.FrameworkCacheResolver
import app.urv.manager.patcher.runStep
import app.urv.manager.patcher.split.SplitApkPreparer
import app.urv.manager.patcher.toRemoteError
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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

object MorpheRuntimeEntry {
    private const val CANCELLATION_SENTINEL = "__PATCHING_CANCELLED__"
    @JvmStatic
    fun loadMetadata(bundlePaths: List<String>): Map<String, List<Map<String, Any?>>> {
        val result = LinkedHashMap<String, List<Map<String, Any?>>>()
        bundlePaths.forEach { path ->
            result[path] = loadMetadataForBundle(path)
        }
        return result
    }

    @JvmStatic
    fun loadMetadataForBundle(bundlePath: String): List<Map<String, Any?>> =
        MorphePatchBundleLoader.loadBundle(bundlePath).map(::patchToMap)

    @JvmStatic
    fun runPatcher(params: Map<String, Any?>, callback: MorpheRuntimeCallback): String? {
        val processingClassesPattern =
            Regex("Processing\\s+(\\d+)\\s+classes\\s+in\\s+parallel", RegexOption.IGNORE_CASE)
        val wroteDexFilesPattern =
            Regex("Wrote\\s+(\\d+)\\s+dex\\s+files\\b", RegexOption.IGNORE_CASE)
        val strippedDexPattern =
            Regex(
                "Stripped\\s+\\d+\\s+class_def\\s+entries\\s+from\\s+(classes\\d*\\.dex)",
                RegexOption.IGNORE_CASE
            )
        val seenWriteApkDetails = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        val writeApkActive = AtomicBoolean(false)
        val writeApkSubStepsReady = AtomicBoolean(false)
        fun throwIfCancelled() {
            if (callback.isCancelled()) {
                throw CancellationException("Patching cancelled")
            }
        }
        fun onEvent(event: ProgressEvent) {
            throwIfCancelled()
            when (event.stepId) {
                StepId.WriteAPK -> when (event) {
                    is ProgressEvent.Started -> {
                        writeApkActive.set(true)
                        writeApkSubStepsReady.set(!event.subSteps.isNullOrEmpty())
                        seenWriteApkDetails.clear()
                    }

                    is ProgressEvent.Progress -> {
                        writeApkActive.set(true)
                        if (!event.subSteps.isNullOrEmpty()) {
                            writeApkSubStepsReady.set(true)
                            seenWriteApkDetails.clear()
                        }
                    }
                    is ProgressEvent.Completed,
                    is ProgressEvent.Failed -> {
                        writeApkActive.set(false)
                        writeApkSubStepsReady.set(false)
                        seenWriteApkDetails.clear()
                    }
                }

                StepId.SignAPK -> if (event is ProgressEvent.Started) {
                    writeApkActive.set(false)
                    writeApkSubStepsReady.set(false)
                    seenWriteApkDetails.clear()
                }
                else -> Unit
            }
            callback.event(event.toMap())
        }

        fun handleWriteProgressLine(rawLine: String) {
            if (!writeApkActive.get() || !writeApkSubStepsReady.get()) return
            val line = rawLine.trim()
            if (line.isEmpty()) return
            val detail = when {
                line.contains("Writing patched files", ignoreCase = true) ->
                    "Writing patched files..."
                line.contains("Compiling modified resources", ignoreCase = true) ||
                    line.contains("Compiling patched resources", ignoreCase = true) ->
                    "Compiling modified resources"
                processingClassesPattern.containsMatchIn(line) ->
                    "Processing ${processingClassesPattern.find(line)?.groupValues?.get(1)} classes"
                wroteDexFilesPattern.containsMatchIn(line) ->
                    "Wrote ${wroteDexFilesPattern.find(line)?.groupValues?.get(1)} dex files"
                strippedDexPattern.containsMatchIn(line) -> {
                    val dexName = strippedDexPattern.find(line)?.groupValues?.get(1) ?: return
                    "Modified $dexName"
                }
                else -> return
            }
            if (!seenWriteApkDetails.add(detail)) return
            onEvent(
                ProgressEvent.Progress(
                    stepId = StepId.WriteAPK,
                    message = detail
                )
            )
        }

        val logger = object : Logger() {
            override fun log(level: LogLevel, message: String) {
                throwIfCancelled()
                handleWriteProgressLine(message)
                callback.log(level.name, message)
            }
        }

        val aaptPath = params["aaptPath"] as? String
            ?: return "Missing aaptPath parameter."
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
        val bytecodeMode = runCatching {
            BytecodeMode.valueOf((params["bytecodeMode"] as? String)?.uppercase(Locale.ROOT) ?: BytecodeMode.STRIP_FAST.name)
        }.getOrDefault(BytecodeMode.STRIP_FAST)
        val configurations = params["configurations"] as? List<*> ?: emptyList<Any>()

        val aaptLogs = AaptLogCapture(onLine = ::handleWriteProgressLine).apply { start() }
        val stdioCapture = StdIoCapture(::handleWriteProgressLine).apply { start() }

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
                var preparation: SplitApkPreparer.PreparationResult? = null
                if (SplitApkPreparer.isSplitArchive(input)) {
                    preparation = runStep(StepId.PrepareSplitApk, ::onEvent, ::throwIfCancelled) {
                        prepareInput()
                    }
                }
                var cachedSelectedPatches: MorphePatchList? = null
                suspend fun loadSelectedPatches(): MorphePatchList {
                    cachedSelectedPatches?.let { return it }

                    return runCatching {
                        val activeConfigs = configs.filter { it.patches.isNotEmpty() }
                        val allPatches = MorphePatchBundleLoader.patches(
                            activeConfigs.map { it.bundlePath },
                            packageName
                        )

                    val selectedPatches = activeConfigs.flatMap { config ->
                        val patches = (allPatches[config.bundlePath] ?: return@flatMap emptyList())
                            .filter { it.name in config.patches }
                            .associateBy { it.name }

                        val filteredOptions = config.options
                            .filterKeys { key -> key is String && key in patches }
                            .mapKeys { (key, _) -> key as String }
                            .mapValues { (_, value) -> value as? Map<*, *> ?: emptyMap<Any, Any?>() }

                        filteredOptions.forEach { (patchName, opts) ->
                            val patchOptions = patches[patchName]?.options
                                ?: throw Exception("Patch with name $patchName does not exist.")

                            opts.forEach { (key, value) ->
                                val keyString = key as? String ?: return@forEach
                                patchOptions[keyString] = value
                            }
                        }

                        patches.values
                    }

                    if (activeConfigs.isNotEmpty() && selectedPatches.isEmpty()) {
                        throw IllegalArgumentException(
                            "Selected patches are unavailable. Re-open patch selection and select patches again."
                        )
                    }

                        selectedPatches
                    }.getOrThrow().also { cachedSelectedPatches = it }
                }

                runStep(StepId.LoadPatches, ::onEvent, ::throwIfCancelled) {
                    loadSelectedPatches().size
                }

                try {
                    val relatedBundleArchives = configs
                        .asSequence()
                        .filter { it.patches.isNotEmpty() }
                        .map { File(it.bundlePath) }
                        .toList()
                    val session = runStep(StepId.ReadAPK, ::onEvent, ::throwIfCancelled) {
                        val preparedInput = preparation ?: prepareInput().also { preparation = it }
                        val selectedAaptPath = aaptPath
                        val frameworkCacheDir = FrameworkCacheResolver.resolve(
                            baseFrameworkDir = frameworkDir,
                            runtimeTag = morpheFrameworkRuntimeTag(),
                            apkFile = preparedInput.file,
                            aaptPath = selectedAaptPath,
                            logger = logger
                        )
                        MorpheSession(
                            cacheDir = cacheDir,
                            frameworkDir = frameworkCacheDir,
                            aaptPath = selectedAaptPath,
                            bytecodeMode = bytecodeMode,
                            logger = logger,
                            input = preparedInput.file,
                            onEvent = ::onEvent,
                            checkCancelled = ::throwIfCancelled,
                        )
                    }
                    val preparedInput = requireNotNull(preparation) {
                        "APK preparation did not produce an input file."
                    }

                    throwIfCancelled()
                    session.use {
                        it.run(
                            File(outputFile),
                            { loadSelectedPatches() },
                            stripNativeLibs,
                            preparedInput.merged
                        )
                    }
                } finally {
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
        result["description"] = option.description
        result["required"] = option.required
        result["type"] = option.type.toString()
        result["default"] = normalizeValue(option.default)
        result["presets"] = option.values?.mapValues { (_, value) -> normalizeValue(value) }
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

    private fun morpheFrameworkRuntimeTag(): String {
        val fingerprint = Build.FINGERPRINT.takeUnless { it.isNullOrBlank() } ?: "unknown"
        val fingerprintHash = MessageDigest.getInstance("SHA-256")
            .digest(fingerprint.toByteArray())
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
            .take(12)
        return "morphe_device${Build.VERSION.SDK_INT}_$fingerprintHash"
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

    private class AaptLogCapture(
        private val onLine: ((String) -> Unit)? = null
    ) {
        private val logger = java.util.logging.Logger.getLogger("")
        private val lines = ArrayDeque<String>()
        private var originalLevel: Level? = null
        private val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                val message = record.message?.trim().orEmpty()
                if (message.isEmpty()) return
                onLine?.invoke(message)
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
        private val onLine: (String) -> Unit
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
