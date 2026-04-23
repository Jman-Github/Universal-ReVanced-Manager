package app.urv.manager.patcher.runtime.process

import android.annotation.SuppressLint
import android.app.ActivityThread
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Looper
import app.universal.revanced.manager.BuildConfig
import app.urv.manager.patcher.ProgressEvent
import app.urv.manager.patcher.logger.LogLevel
import app.urv.manager.patcher.logger.Logger
import app.urv.manager.patcher.logger.PatcherLogMode
import app.urv.manager.patcher.logger.allows
import app.urv.manager.patcher.revanced.Revanced21RuntimeBridge
import app.urv.manager.patcher.runtime.Revanced21ProcessRuntime
import app.urv.manager.patcher.toParcel
import java.io.OutputStream
import java.io.PrintStream
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger as JavaLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess

class Revanced21PatcherProcess(
    private val appContext: Context
) : IPatcherProcess.Stub() {
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

    override fun start(parameters: Parameters, events: IPatcherEvents) {
        val logMode = parameters.patcherLogMode

        fun safeEvent(event: ProgressEvent) {
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
            val logger = object : Logger() {
                override fun log(level: LogLevel, message: String) {
                    if (!logMode.allows(level)) return
                    safeLog(level.name, message)
                }
            }
            val aaptLogs = AaptLogCapture().apply { start(logMode) }
            val stdioCapture = StdIoCapture().apply {
                start(mirrorToOriginal = logMode == PatcherLogMode.VERBOSE)
            }
            var exitCode = 0

            try {
                Revanced21RuntimeBridge.initialize(appContext)
                val params = buildRuntimeParams(parameters)
                val error = Revanced21RuntimeBridge.runPatcher(params, logger, ::safeEvent)
                if (error.isNullOrBlank()) {
                    safeFinished(null)
                    exitCode = 0
                } else {
                    safeFinished(error)
                    exitCode = 1
                }
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

    private fun buildRuntimeParams(parameters: Parameters): Map<String, Any?> {
        val configs = parameters.configurations.map { config ->
            linkedMapOf(
                "bundlePath" to config.bundle.patchesJar,
                "patches" to config.patches.toList(),
                "options" to config.options
            )
        }

        return LinkedHashMap<String, Any?>().apply {
            put("aaptPath", parameters.aaptPath)
            put("frameworkDir", parameters.frameworkDir)
            put("cacheDir", parameters.cacheDir)
            put("packageName", parameters.packageName)
            put("inputFile", parameters.inputFile)
            put("outputFile", parameters.outputFile)
            put("stripNativeLibs", parameters.stripNativeLibs)
            put("skipUnneededSplits", parameters.skipUnneededSplits)
            put("patcherLogMode", parameters.patcherLogMode.name)
            put("configurations", configs)
        }
    }

    companion object {
        private val longArrayClass = LongArray::class.java
        private val emptyLongArray = LongArray(0)

        @SuppressLint("PrivateApi")
        @JvmStatic
        fun main(args: Array<String>) {
            Looper.prepareMainLooper()

            val managerPackageName = args[0]
            val systemContext = ActivityThread.systemMain().systemContext as Context
            val appContext = systemContext.createPackageContext(managerPackageName, 0)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    Class.forName("android.app.AppCompatCallbacks").apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                            getDeclaredMethod("install", longArrayClass, longArrayClass)
                                .also { it.isAccessible = true }(
                                    null,
                                    emptyLongArray,
                                    emptyLongArray
                                )
                        } else {
                            getDeclaredMethod("install", longArrayClass)
                                .also { it.isAccessible = true }(null, emptyLongArray)
                        }
                    }
                }
            }

            val ipcInterface = Revanced21PatcherProcess(appContext)

            appContext.sendBroadcast(Intent().apply {
                action = Revanced21ProcessRuntime.CONNECT_TO_APP_ACTION
                `package` = managerPackageName

                putExtra(Revanced21ProcessRuntime.INTENT_BUNDLE_KEY, Bundle().apply {
                    putBinder(Revanced21ProcessRuntime.BUNDLE_BINDER_KEY, ipcInterface.asBinder())
                })
            })

            Looper.loop()
            exitProcess(1)
        }
    }

    private class AaptLogCapture {
        private val logger = JavaLogger.getLogger("")
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

        fun start(logMode: PatcherLogMode) {
            originalLevel = logger.level
            logger.level = logMode.javaLogLevel
            handler.level = logMode.javaLogLevel
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
        private lateinit var outStream: PrintStream
        private lateinit var errStream: PrintStream

        fun start(mirrorToOriginal: Boolean = true) {
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
            if (ch == '\r') return
            if (ch == '\n') {
                flushPending()
                return
            }
            buffer.append(ch)
        }
    }
}
