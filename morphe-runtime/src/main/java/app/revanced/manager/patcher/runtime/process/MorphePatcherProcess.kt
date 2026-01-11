package app.revanced.manager.patcher.runtime.process

import android.annotation.SuppressLint
import android.app.ActivityThread
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Looper
import app.universal.revanced.manager.morphe.runtime.BuildConfig
import app.revanced.manager.patcher.ProgressEvent
import app.revanced.manager.patcher.StepId
import app.revanced.manager.patcher.logger.LogLevel
import app.revanced.manager.patcher.logger.Logger
import app.revanced.manager.patcher.morphe.MorphePatchBundleLoader
import app.revanced.manager.patcher.morphe.MorpheSession
import app.revanced.manager.patcher.runStep
import app.revanced.manager.patcher.split.SplitApkPreparer
import app.revanced.manager.patcher.toParcel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger as JavaLogger
import kotlin.system.exitProcess

/**
 * The main class that runs inside the runner process launched by [MorpheProcessRuntime].
 */
class MorphePatcherProcess : IMorphePatcherProcess.Stub() {
    private var eventBinder: IPatcherEvents? = null

    private val scope =
        CoroutineScope(Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            eventBinder?.let {
                try {
                    it.finished(throwable.stackTraceToString())
                    return@CoroutineExceptionHandler
                } catch (_: Exception) {
                }
            }

            throwable.printStackTrace()
            exitProcess(1)
        })

    override fun buildId() = BuildConfig.BUILD_ID
    override fun exit() = exitProcess(0)

    override fun start(parameters: MorpheParameters, events: IPatcherEvents) {
        fun onEvent(event: ProgressEvent) = events.event(event.toParcel())

        eventBinder = events

        scope.launch {
            val dexCompilePattern =
                Regex("(Compiling|Compiled)\\s+(classes\\d*\\.dex)", RegexOption.IGNORE_CASE)
            val dexWritePattern =
                Regex("Write\\s+\\[[^\\]]+\\]\\s+(classes\\d*\\.dex)", RegexOption.IGNORE_CASE)
            val seenDexCompiles = mutableSetOf<String>()
            fun handleDexCompileLine(rawLine: String) {
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
                    events.log(level.name, message)
                }
            }

            logger.info("Memory limit: ${Runtime.getRuntime().maxMemory() / (1024 * 1024)}MB")
            val aaptLogs = AaptLogCapture(onLine = ::handleDexCompileLine).apply { start() }
            val stdioCapture = StdIoCapture(onLine = ::handleDexCompileLine).apply { start() }

            try {
                val patchList = runStep(StepId.LoadPatches, ::onEvent) {
                    val allPatches = MorphePatchBundleLoader.patches(
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
                }

                val input = File(parameters.inputFile)
                val preparation = if (SplitApkPreparer.isSplitArchive(input)) {
                    runStep(StepId.PrepareSplitApk, ::onEvent) {
                        SplitApkPreparer.prepareIfNeeded(
                            input,
                            File(parameters.cacheDir),
                            logger,
                            parameters.stripNativeLibs,
                            parameters.skipUnneededSplits,
                            onProgress = { message ->
                                onEvent(ProgressEvent.Progress(stepId = StepId.PrepareSplitApk, message = message))
                            },
                            onSubSteps = { subSteps ->
                                onEvent(ProgressEvent.Progress(stepId = StepId.PrepareSplitApk, subSteps = subSteps))
                            }
                        )
                    }
                } else {
                    SplitApkPreparer.prepareIfNeeded(
                        input,
                        File(parameters.cacheDir),
                        logger,
                        parameters.stripNativeLibs,
                        parameters.skipUnneededSplits,
                        onProgress = { message ->
                            onEvent(ProgressEvent.Progress(stepId = StepId.PrepareSplitApk, message = message))
                        },
                        onSubSteps = { subSteps ->
                            onEvent(ProgressEvent.Progress(stepId = StepId.PrepareSplitApk, subSteps = subSteps))
                        }
                    )
                }

                try {
                    val session = runStep(StepId.ReadAPK, ::onEvent) {
                        MorpheSession(
                            cacheDir = parameters.cacheDir,
                            frameworkDir = parameters.frameworkDir,
                            aaptPath = parameters.aaptPath,
                            logger = logger,
                            input = preparation.file,
                            onEvent = ::onEvent,
                        )
                    }

                    session.use {
                        it.run(
                            File(parameters.outputFile),
                            patchList,
                            parameters.stripNativeLibs,
                            preparation.merged
                        )
                    }
                } finally {
                    preparation.cleanup()
                }

                events.finished(null)
            } catch (throwable: Throwable) {
                val extra = aaptLogs.dump()
                val stack = throwable.stackTraceToString()
                val report = if (extra.isNotBlank()) {
                    "$stack\n\nAAPT2 output:\n$extra"
                } else {
                    stack
                }
                events.finished(report)
            } finally {
                stdioCapture.close()
                aaptLogs.stop()
            }
        }
    }

    companion object {
        private val longArrayClass = LongArray::class.java
        private val emptyLongArray = LongArray(0)
        private const val CONNECT_TO_APP_ACTION = "CONNECT_TO_MORPHE_APP_ACTION"
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
            Class.forName("android.app.AppCompatCallbacks").apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    getDeclaredMethod("install", longArrayClass, longArrayClass).also { it.isAccessible = true }(null, emptyLongArray, emptyLongArray)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    getDeclaredMethod("install", longArrayClass).also { it.isAccessible = true }(null, emptyLongArray)
                }
            }

            val ipcInterface = MorphePatcherProcess()

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
