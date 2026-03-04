package app.revanced.manager.patcher.runtime.process

import android.annotation.SuppressLint
import android.app.ActivityThread
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Looper
import app.universal.revanced.manager.BuildConfig
import app.revanced.manager.patcher.ProgressEvent
import app.revanced.manager.patcher.logger.LogLevel
import app.revanced.manager.patcher.logger.Logger
import app.revanced.manager.patcher.revanced.Revanced22RuntimeBridge
import app.revanced.manager.patcher.runtime.Revanced22ProcessRuntime
import app.revanced.manager.patcher.runtime.revanced.Revanced22RuntimeAssets
import app.revanced.manager.patcher.toParcel
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger as JavaLogger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

/**
 * The main class that runs inside the runner process launched by [Revanced22ProcessRuntime].
 */
class Revanced22PatcherProcess(
    private val appContext: Context
) : IPatcherProcess.Stub() {
    private var eventBinder: IPatcherEvents? = null
    private val eventsEnabled = AtomicBoolean(true)

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
    override fun exit() = exitProcess(0)

    override fun start(parameters: Parameters, events: IPatcherEvents) {
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

        scope.launch {
            fun onEvent(event: ProgressEvent) {
                safeEvent(event)
            }

            val logger = object : Logger() {
                override fun log(level: LogLevel, message: String) {
                    safeLog(level.name, message)
                }
            }
            val aaptLogs = AaptLogCapture().apply { start() }
            val stdioCapture = StdIoCapture().apply { start() }
            var exitCode = 0

            try {
                Revanced22RuntimeBridge.initialize(appContext)
                val params = buildRuntimeParams(parameters)
                val error = Revanced22RuntimeBridge.runPatcher(params, logger, ::onEvent)
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
        val runtimeClassPath = Revanced22RuntimeAssets.ensureRuntimeClassPath(appContext).absolutePath
        val apkEditorJarPath = Revanced22RuntimeAssets.ensureApkEditorJar(appContext).absolutePath
        val apkEditorMergeJarPath =
            Revanced22RuntimeAssets.ensureApkEditorMergeJar(appContext).absolutePath
        val mergeMemoryLimitMb = System.getenv(ENV_MERGE_MEMORY_LIMIT_MB)?.toIntOrNull()
        val propOverridePath = System.getenv(ENV_PROP_OVERRIDE_PATH)?.takeIf { it.isNotBlank() }
        val appProcessPath = System.getenv(ENV_APP_PROCESS_PATH)
            ?.takeIf { it.isNotBlank() }
            ?: resolveAppProcessBin(appContext)

        val configs = parameters.configurations.map { config ->
            linkedMapOf(
                "bundlePath" to config.bundle.patchesJar,
                "patches" to config.patches.toList(),
                "options" to config.options
            )
        }

        return LinkedHashMap<String, Any?>().apply {
            put("aaptPath", parameters.aaptPath)
            put("aaptFallbackPath", parameters.aaptFallbackPath)
            put("frameworkDir", parameters.frameworkDir)
            put("cacheDir", parameters.cacheDir)
            put("apkEditorJarPath", apkEditorJarPath)
            put("apkEditorMergeJarPath", apkEditorMergeJarPath)
            put("runtimeClassPath", runtimeClassPath)
            put("propOverridePath", propOverridePath)
            put("mergeMemoryLimitMb", mergeMemoryLimitMb)
            put("appProcessPath", appProcessPath)
            put("packageName", parameters.packageName)
            put("inputFile", parameters.inputFile)
            put("outputFile", parameters.outputFile)
            put("stripNativeLibs", parameters.stripNativeLibs)
            put("skipUnneededSplits", parameters.skipUnneededSplits)
            put("configurations", configs)
        }
    }

    private fun resolveAppProcessBin(context: Context): String {
        val is64Bit = context.applicationInfo.nativeLibraryDir.contains("64")
        val preferred = if (is64Bit) APP_PROCESS_BIN_PATH_64 else APP_PROCESS_BIN_PATH_32
        return if (File(preferred).exists()) preferred else APP_PROCESS_BIN_PATH
    }

    companion object {
        private val longArrayClass = LongArray::class.java
        private val emptyLongArray = LongArray(0)
        private const val APP_PROCESS_BIN_PATH = "/system/bin/app_process"
        private const val APP_PROCESS_BIN_PATH_64 = "/system/bin/app_process64"
        private const val APP_PROCESS_BIN_PATH_32 = "/system/bin/app_process32"
        private const val ENV_PROP_OVERRIDE_PATH = "REVANCED22_PROP_OVERRIDE_PATH"
        private const val ENV_MERGE_MEMORY_LIMIT_MB = "REVANCED22_MERGE_MEMORY_LIMIT_MB"
        private const val ENV_APP_PROCESS_PATH = "REVANCED22_APP_PROCESS_PATH"

        @SuppressLint("PrivateApi")
        @JvmStatic
        fun main(args: Array<String>) {
            Looper.prepareMainLooper()

            val managerPackageName = args[0]

            // Abuse hidden APIs to get a context.
            val systemContext = ActivityThread.systemMain().systemContext as Context
            val appContext = systemContext.createPackageContext(managerPackageName, 0)

            // Avoid noisy logs while running through app_process.
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

            val ipcInterface = Revanced22PatcherProcess(appContext)

            appContext.sendBroadcast(Intent().apply {
                action = Revanced22ProcessRuntime.CONNECT_TO_APP_ACTION
                `package` = managerPackageName

                putExtra(Revanced22ProcessRuntime.INTENT_BUNDLE_KEY, Bundle().apply {
                    putBinder(Revanced22ProcessRuntime.BUNDLE_BINDER_KEY, ipcInterface.asBinder())
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
            if (ch == '\r') return
            if (ch == '\n') {
                flushPending()
                return
            }
            buffer.append(ch)
        }
    }
}
