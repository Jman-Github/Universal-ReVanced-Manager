package app.revanced.manager.morphe.runtime

import app.morphe.patcher.patch.Option
import app.morphe.patcher.patch.Patch
import app.revanced.manager.patcher.ProgressEvent
import app.revanced.manager.patcher.RemoteError
import app.revanced.manager.patcher.StepId
import app.revanced.manager.patcher.aapt.AaptSelector
import app.revanced.manager.patcher.logger.LogLevel
import app.revanced.manager.patcher.logger.Logger
import app.revanced.manager.patcher.morphe.MorphePatchBundleLoader
import app.revanced.manager.patcher.morphe.MorpheSession
import app.revanced.manager.patcher.runtime.FrameworkCacheResolver
import app.revanced.manager.patcher.runStep
import app.revanced.manager.patcher.split.SplitApkPreparer
import app.revanced.manager.patcher.toRemoteError
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.io.PrintStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import kotlinx.coroutines.runBlocking

object MorpheRuntimeEntry {
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
        val dexCompilePattern =
            Regex("(Compiling|Compiled)\\s+(classes\\d*\\.dex)", RegexOption.IGNORE_CASE)
        val dexWritePattern =
            Regex("Write\\s+\\[[^\\]]+\\]\\s+(classes\\d*\\.dex)", RegexOption.IGNORE_CASE)
        val seenDexCompiles = mutableSetOf<String>()
        val writeApkActive = AtomicBoolean(false)
        fun onEvent(event: ProgressEvent) {
            when (event) {
                is ProgressEvent.Started -> {
                    if (event.stepId == StepId.WriteAPK) {
                        writeApkActive.set(true)
                    }
                }

                is ProgressEvent.Completed -> {
                    if (event.stepId == StepId.WriteAPK) {
                        writeApkActive.set(false)
                    }
                }

                is ProgressEvent.Failed -> {
                    if (event.stepId == StepId.WriteAPK) {
                        writeApkActive.set(false)
                    }
                }

                is ProgressEvent.Progress -> Unit
            }
            callback.event(event.toMap())
        }

        fun handleDexCompileLine(rawLine: String) {
            if (!writeApkActive.get()) return
            val line = rawLine.trim()
            if (line.isEmpty()) return
            val match = dexCompilePattern.find(line)
                ?: dexWritePattern.find(line)
                ?: return
            val dexName = match.groupValues.lastOrNull()?.takeIf { it.endsWith(".dex") } ?: return
            if (!seenDexCompiles.add(dexName)) return
            onEvent(
                ProgressEvent.Progress(
                    stepId = StepId.WriteAPK,
                    message = "Compiling $dexName"
                )
            )
        }

        val logger = object : Logger() {
            override fun log(level: LogLevel, message: String) {
                handleDexCompileLine(message)
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

        val aaptLogs = AaptLogCapture(onLine = ::handleDexCompileLine).apply { start() }
        val stdioCapture = StdIoCapture(::handleDexCompileLine).apply { start() }

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
                val patchList = runStep(StepId.LoadPatches, ::onEvent) {
                    val allPatches = MorphePatchBundleLoader.patches(
                        configs.map { it.bundlePath },
                        packageName
                    )

                    configs.flatMap { config ->
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
                }

                val input = File(inputFile)
                val preparation = if (SplitApkPreparer.isSplitArchive(input)) {
                    runStep(StepId.PrepareSplitApk, ::onEvent) {
                        SplitApkPreparer.prepareIfNeeded(
                            input,
                            File(cacheDir),
                            logger,
                            stripNativeLibs,
                            skipUnneededSplits,
                            onProgress = { message ->
                                onEvent(
                                    ProgressEvent.Progress(
                                        stepId = StepId.PrepareSplitApk,
                                        message = message
                                    )
                                )
                            },
                            onSubSteps = { subSteps ->
                                onEvent(
                                    ProgressEvent.Progress(
                                        stepId = StepId.PrepareSplitApk,
                                        subSteps = subSteps
                                    )
                                )
                            }
                        )
                    }
                } else {
                    SplitApkPreparer.prepareIfNeeded(
                        input,
                        File(cacheDir),
                        logger,
                        stripNativeLibs,
                        skipUnneededSplits,
                        onProgress = { message ->
                            onEvent(
                                ProgressEvent.Progress(
                                    stepId = StepId.PrepareSplitApk,
                                    message = message
                                )
                            )
                        },
                        onSubSteps = { subSteps ->
                            onEvent(
                                ProgressEvent.Progress(
                                    stepId = StepId.PrepareSplitApk,
                                    subSteps = subSteps
                                )
                            )
                        }
                    )
                }

                try {
                    val relatedBundleArchives = configs
                        .asSequence()
                        .filter { it.patches.isNotEmpty() }
                        .map { File(it.bundlePath) }
                        .toList()
                    val selectedAaptPath = AaptSelector.select(
                        aaptPath,
                        aaptFallbackPath,
                        preparation.file,
                        logger,
                        additionalArchives = relatedBundleArchives
                    )
                    logAapt2Info(selectedAaptPath, logger)
                    val frameworkCacheDir = FrameworkCacheResolver.resolve(
                        baseFrameworkDir = frameworkDir,
                        runtimeTag = "morphe",
                        apkFile = preparation.file,
                        aaptPath = selectedAaptPath,
                        logger = logger
                    )
                    val session = runStep(StepId.ReadAPK, ::onEvent) {
                        MorpheSession(
                            cacheDir = cacheDir,
                            frameworkDir = frameworkCacheDir,
                            aaptPath = selectedAaptPath,
                            logger = logger,
                            input = preparation.file,
                            onEvent = ::onEvent,
                        )
                    }

                    session.use {
                        it.run(
                            File(outputFile),
                            patchList,
                            stripNativeLibs,
                            preparation.merged
                        )
                    }
                } finally {
                    preparation.cleanup()
                }
            }

            null
        } catch (throwable: Throwable) {
            val extra = aaptLogs.dump()
            val stack = throwable.stackTraceToString()
            if (extra.isNotBlank()) {
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

    private fun ProgressEvent.toMap(): Map<String, Any?> = when (this) {
        is ProgressEvent.Started -> mapOf(
            "type" to "Started",
            "stepId" to stepId.toMap()
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

    private fun logAapt2Info(aaptPath: String, logger: Logger) {
        val aaptFile = File(aaptPath)
        if (!aaptFile.exists()) {
            logger.warn("AAPT2 binary missing at $aaptPath")
            return
        }
        val digest = sha256(aaptFile)
        if (digest != null) {
            logger.info("AAPT2 sha256: $digest")
        }
        val version = runCatching {
            val process = ProcessBuilder(aaptPath, "version")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output.takeIf { it.isNotBlank() }
        }.getOrNull()
        if (!version.isNullOrBlank()) {
            logger.info("AAPT2 version: $version")
        }
    }

    private fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val hex = StringBuilder()
        digest.digest().forEach { byte ->
            hex.append(String.format("%02x", byte))
        }
        hex.toString()
    }.getOrNull()
}
