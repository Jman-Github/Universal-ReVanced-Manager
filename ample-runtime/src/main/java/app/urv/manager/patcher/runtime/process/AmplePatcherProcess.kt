package app.urv.manager.patcher.runtime.process

import android.annotation.SuppressLint
import android.app.ActivityThread
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Looper
import app.universal.revanced.manager.ample.runtime.BuildConfig
import app.urv.manager.patcher.ProgressEvent
import app.urv.manager.patcher.StepId
import app.urv.manager.patcher.logger.LogLevel
import app.urv.manager.patcher.logger.Logger
import app.urv.manager.patcher.ample.AmplePatchBundleLoader
import app.urv.manager.patcher.ample.AmplePatchList
import app.urv.manager.patcher.ample.AmpleSession
import app.urv.manager.patcher.runtime.FrameworkCacheResolver
import app.urv.manager.patcher.runStep
import app.urv.manager.patcher.split.ApkEditorMergeRuntime
import app.urv.manager.patcher.split.SplitApkPreparer
import app.urv.manager.patcher.toParcel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger as JavaLogger
import kotlin.system.exitProcess
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The main class that runs inside the runner process launched by [AmpleProcessRuntime].
 */
class AmplePatcherProcess : IAmplePatcherProcess.Stub() {
    private var eventBinder: IPatcherEvents? = null
    private val eventsEnabled = AtomicBoolean(true)
    private val exitRequested = AtomicBoolean(false)
    @Volatile
    private var runningJob: Job? = null

    private val scope =
        CoroutineScope(Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            eventBinder?.let { binder ->
                try {
                    if (!eventsEnabled.get()) return@let
                    binder.finished(throwable.stackTraceToString())
                    return@CoroutineExceptionHandler
                } catch (_: Exception) {
                    eventsEnabled.set(false)
                }
            }

            throwable.printStackTrace()
            exitProcess(1)
        })

    override fun buildId() = BuildConfig.BUILD_ID

    override fun exit() {
        if (!exitRequested.compareAndSet(false, true)) return
        eventsEnabled.set(false)
        runningJob?.cancel(CancellationException("Patching cancelled"))
        scope.launch {
            withTimeoutOrNull(2_000L) {
                runningJob?.join()
            }
            exitProcess(0)
        }
    }

    override fun start(parameters: AmpleParameters, events: IPatcherEvents) {
        val isVerboseLogging = parameters.patcherLogMode == "VERBOSE"
        val minLogLevel = if (isVerboseLogging) LogLevel.TRACE else LogLevel.INFO
        val javaLogLevel = if (isVerboseLogging) Level.ALL else Level.INFO
        var writeApkSubStepsReady = false
        val seenDexCompiles = mutableSetOf<String>()
        fun safeEvent(event: ProgressEvent) {
            if (event.stepId == StepId.WriteAPK) {
                when (event) {
                    is ProgressEvent.Started -> {
                        writeApkSubStepsReady = !event.subSteps.isNullOrEmpty()
                        seenDexCompiles.clear()
                    }
                    is ProgressEvent.Progress -> {
                        if (!event.subSteps.isNullOrEmpty()) {
                            writeApkSubStepsReady = true
                            seenDexCompiles.clear()
                        }
                    }
                    is ProgressEvent.Completed,
                    is ProgressEvent.Failed -> {
                        writeApkSubStepsReady = false
                        seenDexCompiles.clear()
                    }
                }
            }
            if (!eventsEnabled.get()) return
            try {
                events.event(event.toParcel())
            } catch (_: Throwable) {
                eventsEnabled.set(false)
            }
        }
        fun safeLog(level: String, message: String) {
            if (!eventsEnabled.get()) return
            try {
                events.log(level, message)
            } catch (_: Throwable) {
                eventsEnabled.set(false)
            }
        }
        fun safeFinished(exceptionStackTrace: String?) {
            if (!eventsEnabled.get()) return
            try {
                events.finished(exceptionStackTrace)
            } catch (_: Throwable) {
                eventsEnabled.set(false)
            }
        }

        eventBinder = events
        exitRequested.set(false)

        runningJob = scope.launch {
            val dexCompilePattern =
                Regex("(Compiling|Compiled)\\s+(classes\\d*\\.dex)", RegexOption.IGNORE_CASE)
            val dexWritePattern =
                Regex("Write\\s+\\[[^\\]]+\\]\\s+(classes\\d*\\.dex)", RegexOption.IGNORE_CASE)
            val dexAnyPattern = Regex("(classes\\d*\\.dex)", RegexOption.IGNORE_CASE)
            fun handleDexCompileLine(rawLine: String) {
                if (!writeApkSubStepsReady) return
                val line = rawLine.trim()
                if (line.isEmpty()) return
                val match = dexCompilePattern.find(line)
                    ?: dexWritePattern.find(line)
                    ?: dexAnyPattern.find(line)
                    ?: return
                val dexName = match.groupValues.lastOrNull()?.takeIf { it.endsWith(".dex") } ?: return
                if (!seenDexCompiles.add(dexName)) return
                safeEvent(
                    ProgressEvent.Progress(
                        stepId = StepId.WriteAPK,
                        message = "Compiling $dexName"
                    )
                )
            }
            val logger = object : Logger() {
                override fun log(level: LogLevel, message: String) {
                    if (level.ordinal < minLogLevel.ordinal) return
                    safeLog(level.name, message)
                }
            }

            val androidDataDir = File(parameters.cacheDir, "apkeditor-android-data").absolutePath
            ApkEditorMergeRuntime.configure(
                parameters.apkEditorJarPath,
                parameters.apkEditorMergeJarPath,
                parameters.propOverridePath,
                parameters.mergeMemoryLimitMb,
                parameters.appProcessPath,
                androidDataDir,
                resolveRuntimeClassPath(parameters.runtimeClassPath)
            )
            logger.info("Memory limit: ${Runtime.getRuntime().maxMemory() / (1024 * 1024)}MB")
            val aaptLogs = AaptLogCapture(onLine = ::handleDexCompileLine).apply { start(javaLogLevel) }
            val stdioCapture = StdIoCapture(onLine = ::handleDexCompileLine).apply {
                start(
                    captureAll = isVerboseLogging,
                    mirrorToOriginal = isVerboseLogging
                ) { line ->
                    line.contains("Compiling modified resources", ignoreCase = true) ||
                        line.contains("Compiling patched resources", ignoreCase = true) ||
                        dexCompilePattern.containsMatchIn(line) ||
                        dexWritePattern.containsMatchIn(line) ||
                        dexAnyPattern.containsMatchIn(line)
                }
            }
            var exitCode = 0

            try {
                var cachedSelectedPatches: AmplePatchList? = null
                suspend fun loadSelectedPatches(): AmplePatchList {
                    cachedSelectedPatches?.let { return it }

                    return runCatching {
                        val allPatches = AmplePatchBundleLoader.patches(
                            parameters.configurations.map { it.bundlePath },
                            parameters.packageName
                        )

                        parameters.configurations.flatMap { config ->
                            val patches = (allPatches[config.bundlePath] ?: return@flatMap emptyList())
                                .filter { it.name in config.patches }
                                .associateBy { it.name }

                            val filteredOptions = config.options.filterKeys { it in patches }
                            filteredOptions.forEach { (patchName, opts) ->
                                val patchOptions = patches[patchName]?.options
                                    ?: throw Exception("Patch with name $patchName does not exist.")

                                opts.forEach { (key, value) ->
                                    patchOptions[key] = value
                                }
                            }

                            patches.values
                        }
                    }.getOrThrow().also { cachedSelectedPatches = it }
                }

                runStep(StepId.LoadPatches, ::safeEvent) {
                    loadSelectedPatches().size
                }

                val input = File(parameters.inputFile)
                suspend fun prepareInput() = SplitApkPreparer.prepareIfNeeded(
                    input,
                    File(parameters.cacheDir),
                    logger,
                    parameters.stripNativeLibs,
                    parameters.skipUnneededSplits,
                    onProgress = { message ->
                        safeEvent(ProgressEvent.Progress(stepId = StepId.PrepareSplitApk, message = message))
                    },
                    onSubSteps = { subSteps ->
                        safeEvent(ProgressEvent.Progress(stepId = StepId.PrepareSplitApk, subSteps = subSteps))
                    }
                )
                var preparation: SplitApkPreparer.PreparationResult? = null
                if (SplitApkPreparer.isSplitArchive(input)) {
                    preparation = runStep(StepId.PrepareSplitApk, ::safeEvent) {
                        prepareInput()
                    }
                }

                try {
                    val relatedBundleArchives = parameters.configurations
                        .asSequence()
                        .filter { it.patches.isNotEmpty() }
                        .map { File(it.bundlePath) }
                        .toList()
                    val session = runStep(StepId.ReadAPK, ::safeEvent) {
                        val preparedInput = preparation ?: prepareInput().also { preparation = it }
                        val selectedAaptPath = parameters.aaptPath
                        val frameworkDir = FrameworkCacheResolver.resolve(
                            baseFrameworkDir = parameters.frameworkDir,
                            runtimeTag = "ample",
                            apkFile = preparedInput.file,
                            aaptPath = selectedAaptPath,
                            logger = logger
                        )
                        AmpleSession.open(
                            cacheDir = parameters.cacheDir,
                            frameworkDir = frameworkDir,
                            aaptPath = selectedAaptPath,
                            logger = logger,
                            input = preparedInput.file,
                            sanitizeAllEmbeddedApksOnInit = preparedInput.merged,
                            onEvent = ::safeEvent,
                        )
                    }
                    val preparedInput = requireNotNull(preparation) {
                        "APK preparation did not produce an input file."
                    }

                    session.use {
                        it.run(
                            File(parameters.outputFile),
                            { loadSelectedPatches() },
                            parameters.stripNativeLibs,
                            preparedInput.merged
                        )
                    }
                } finally {
                    preparation?.cleanup()
                }

                safeFinished(null)
                exitCode = 0
            } catch (throwable: Throwable) {
                val extra = aaptLogs.dump()
                val stack = throwable.stackTraceToString()
                val report = if (extra.isNotBlank()) {
                    "$stack\n\nAAPT2 output:\n$extra"
                } else {
                    stack
                }
                safeFinished(report)
                exitCode = 1
            } finally {
                stdioCapture.close()
                aaptLogs.stop()
            }

            if (!eventsEnabled.get()) {
                exitProcess(exitCode)
            }
        }
    }

    companion object {
        private val longArrayClass = LongArray::class.java
        private val emptyLongArray = LongArray(0)
        private const val CONNECT_TO_APP_ACTION = "CONNECT_TO_AMPLE_APP_ACTION"
        private const val INTENT_BUNDLE_KEY = "BUNDLE"
        private const val BUNDLE_BINDER_KEY = "BINDER"

        @SuppressLint("PrivateApi")
        @JvmStatic
        fun main(args: Array<String>) {
            Looper.prepareMainLooper()

            val managerPackageName = args[0]

            // Abuse hidden APIs to get a context.
            val systemContext = ActivityThread.systemMain().systemContext as Context
            val appContext = systemContext.createPackageContext(managerPackageName, 0)

            // Avoid annoying logs. See https://github.com/robolectric/robolectric/blob/ad0484c6b32c7d11176c711abeb3cb4a900f9258/robolectric/src/main/java/org/robolectric/android/internal/AndroidTestEnvironment.java#L376-L388
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    Class.forName("android.app.AppCompatCallbacks").apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                            getDeclaredMethod("install", longArrayClass, longArrayClass).also { it.isAccessible = true }(null, emptyLongArray, emptyLongArray)
                        } else {
                            getDeclaredMethod("install", longArrayClass).also { it.isAccessible = true }(null, emptyLongArray)
                        }
                    }
                }
            }

            val ipcInterface = AmplePatcherProcess()

            appContext.sendBroadcast(Intent().apply {
                action = CONNECT_TO_APP_ACTION
                `package` = managerPackageName

                putExtra(INTENT_BUNDLE_KEY, Bundle().apply {
                    putBinder(BUNDLE_BINDER_KEY, ipcInterface.asBinder())
                })
            })

            Looper.loop()
            exitProcess(1) // Shouldn't happen
        }
    }

    private class AaptLogCapture(
        private val onLine: ((String) -> Unit)? = null
    ) {
        private val logger = JavaLogger.getLogger("")
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

        fun start(logLevel: Level) {
            originalLevel = logger.level
            logger.level = logLevel
            handler.level = logLevel
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
        private lateinit var outStream: PrintStream
        private lateinit var errStream: PrintStream

        fun start(
            captureAll: Boolean = true,
            mirrorToOriginal: Boolean = true,
            shouldCaptureLine: ((String) -> Boolean)? = null
        ) {
            outBuffer.captureAll = captureAll
            outBuffer.shouldCaptureLine = shouldCaptureLine
            errBuffer.captureAll = captureAll
            errBuffer.shouldCaptureLine = shouldCaptureLine
            val passthroughOut = if (mirrorToOriginal) originalOut else NullOutputStream
            val passthroughErr = if (mirrorToOriginal) originalErr else NullOutputStream
            outStream = PrintStream(TeeOutputStream(passthroughOut, outBuffer), true)
            errStream = PrintStream(TeeOutputStream(passthroughErr, errBuffer), true)
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

    private object NullOutputStream : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(bytes: ByteArray, off: Int, len: Int) = Unit
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
        var captureAll: Boolean = true
        var shouldCaptureLine: ((String) -> Boolean)? = null

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
            if (captureAll || shouldCaptureLine?.invoke(line) == true) {
                onLine(line)
            }
        }

        private fun appendChar(ch: Char) {
            when (ch) {
                '\n' -> flushPending()
                '\r' -> Unit
                else -> buffer.append(ch)
            }
        }
    }

    private fun resolveRuntimeClassPath(explicitPath: String?): String? {
        val explicit = explicitPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf(File::exists)
            ?.absolutePath
        if (explicit != null) return explicit

        return runCatching {
            val location = AmplePatcherProcess::class.java.protectionDomain
                ?.codeSource
                ?.location
                ?: return@runCatching null
            val path = File(location.toURI()).absolutePath
            path.takeIf { File(it).exists() }
        }.getOrNull()
    }
}
