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
import app.revanced.manager.patcher.Session
import app.revanced.manager.patcher.StepId
import app.revanced.manager.patcher.logger.LogLevel
import app.revanced.manager.patcher.logger.Logger
import app.revanced.manager.patcher.patch.PatchBundle
import app.revanced.manager.patcher.runStep
import app.revanced.manager.patcher.runtime.ProcessRuntime
import app.revanced.manager.patcher.split.SplitApkPreparer
import app.revanced.manager.patcher.toParcel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger as JavaLogger
import kotlin.system.exitProcess

/**
 * The main class that runs inside the runner process launched by [ProcessRuntime].
 */
class PatcherProcess : IPatcherProcess.Stub() {
    private var eventBinder: IPatcherEvents? = null

    private val scope =
        CoroutineScope(Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            // Try to send the exception information to the main app.
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

    override fun start(parameters: Parameters, events: IPatcherEvents) {
        fun onEvent(event: ProgressEvent) = events.event(event.toParcel())

        eventBinder = events

        scope.launch {
            val logger = object : Logger() {
                override fun log(level: LogLevel, message: String) =
                    events.log(level.name, message)
            }

            logger.info("Memory limit: ${Runtime.getRuntime().maxMemory() / (1024 * 1024)}MB")

            val aaptLogs = AaptLogCapture().apply { start() }

            try {
                val patchList = runStep(StepId.LoadPatches, ::onEvent) {
                    val allPatches = PatchBundle.Loader.patches(
                        parameters.configurations.map { it.bundle },
                        parameters.packageName
                    )

                    parameters.configurations.flatMap { config ->
                        val patches = (allPatches[config.bundle] ?: return@flatMap emptyList())
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
                            parameters.stripNativeLibs
                        )
                    }
                } else {
                    SplitApkPreparer.prepareIfNeeded(
                        input,
                        File(parameters.cacheDir),
                        logger,
                        parameters.stripNativeLibs
                    )
                }

                try {
                    val session = runStep(StepId.ReadAPK, ::onEvent) {
                        Session(
                            cacheDir = parameters.cacheDir,
                            aaptPath = parameters.aaptPath,
                            frameworkDir = parameters.frameworkDir,
                            logger = logger,
                            input = preparation.file,
                            onEvent = ::onEvent,
                        )
                    }

                    session.use {
                        it.run(File(parameters.outputFile), patchList)
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
                aaptLogs.stop()
            }
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

            val ipcInterface = PatcherProcess()

            appContext.sendBroadcast(Intent().apply {
                action = ProcessRuntime.CONNECT_TO_APP_ACTION
                `package` = managerPackageName

                putExtra(ProcessRuntime.INTENT_BUNDLE_KEY, Bundle().apply {
                    putBinder(ProcessRuntime.BUNDLE_BINDER_KEY, ipcInterface.asBinder())
                })
            })

            Looper.loop()
            exitProcess(1) // Shouldn't happen
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
}
