package app.urv.manager.patcher.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import app.universal.revanced.manager.BuildConfig
import app.urv.manager.patcher.LibraryResolver
import app.urv.manager.patcher.ProgressEvent
import app.urv.manager.patcher.ProgressEventParcel
import app.urv.manager.patcher.logger.Logger
import app.urv.manager.patcher.logger.filtered
import app.urv.manager.patcher.runtime.process.IPatcherEvents
import app.urv.manager.patcher.runtime.process.IPatcherProcess
import app.urv.manager.patcher.runtime.process.Parameters
import app.urv.manager.patcher.runtime.process.PatchConfiguration
import app.urv.manager.patcher.runtime.process.Revanced21PatcherProcess
import app.urv.manager.patcher.runtime.revanced.Revanced21RuntimeAssets
import app.urv.manager.patcher.toEvent
import app.urv.manager.util.Options
import app.urv.manager.util.PM
import app.urv.manager.util.PatchSelection
import app.urv.manager.util.tag
import com.github.pgreze.process.Redirect
import com.github.pgreze.process.process
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.inject

class Revanced21ProcessRuntime(
    private val context: Context,
    private val useMemoryOverride: Boolean = true
) : Runtime(context) {
    private val pm: PM by inject()
    private val binderRef = AtomicReference<IPatcherProcess?>()
    private val eventHandlerRef = AtomicReference<IPatcherEvents?>()
    private val cancellationRequested = AtomicBoolean(false)

    override fun cancel() {
        cancellationRequested.set(true)
        runCatching { binderRef.getAndSet(null)?.exit() }
        eventHandlerRef.set(null)
    }

    private suspend fun awaitBinderConnection(): IPatcherProcess {
        val binderFuture = CompletableDeferred<IPatcherProcess>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val binder =
                    intent.getBundleExtra(INTENT_BUNDLE_KEY)?.getBinder(BUNDLE_BINDER_KEY)!!

                binderFuture.complete(IPatcherProcess.Stub.asInterface(binder))
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter().apply { addAction(CONNECT_TO_APP_ACTION) },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        return try {
            withTimeout(10000L) {
                binderFuture.await()
            }
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    override suspend fun execute(
        inputFile: String,
        outputFile: String,
        packageName: String,
        selectedPatches: PatchSelection,
        options: Options,
        logger: Logger,
        onEvent: (ProgressEvent) -> Unit,
        stripNativeLibs: Boolean,
        skipUnneededSplits: Boolean,
    ) = coroutineScope {
        val logMode = prefs.patcherLogMode.get()
        val runtimeLogger = logger.filtered(logMode)
        currentCoroutineContext()[Job]?.invokeOnCompletion {
            cancellationRequested.set(true)
            runCatching { binderRef.get()?.exit() }
            eventHandlerRef.set(null)
        }
        cancellationRequested.set(false)
        val logQueue = Channel<Pair<String, String>>(Channel.UNLIMITED)
        val eventQueue = Channel<ProgressEvent>(Channel.UNLIMITED)
        val logDrainJob = launch(Dispatchers.Default) {
            for ((level, msg) in logQueue) {
                runCatching { runtimeLogger.log(enumValueOf(level), msg) }
            }
        }
        val eventDrainJob = launch(Dispatchers.Default) {
            for (event in eventQueue) {
                runCatching { onEvent(event) }
            }
        }

        val managerBaseApk = pm.getPackageInfo(context.packageName)!!.applicationInfo!!.sourceDir
        val appProcessBin = resolveAppProcessBin(context)
        val env = System.getenv().toMutableMap().apply {
            put("CLASSPATH", managerBaseApk)
            if (useMemoryOverride) {
                val requestedLimit = prefs.patcherProcessMemoryLimit.get()
                val aggressiveLimit = prefs.patcherProcessMemoryAggressive.get()
                val runtimeLimit = MemoryLimitConfig.clampLimitMb(
                    context,
                    if (aggressiveLimit) MemoryLimitConfig.maxLimitMb(context) else requestedLimit
                )
                val limit = "${runtimeLimit}M"
                val propOverride = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    resolvePropOverride(context)?.absolutePath
                        ?: throw Exception("Couldn't find prop override library")
                } else {
                    null
                }
                if (propOverride != null) {
                    put("LD_PRELOAD", propOverride)
                    put("PROP_dalvik.vm.heapgrowthlimit", limit)
                    put("PROP_dalvik.vm.heapsize", limit)
                } else {
                    Log.w(tag, "Skipping prop override on Android ${Build.VERSION.SDK_INT}")
                }
            }
        }

        val patching = CompletableDeferred<Unit>()
        val finishedReported = AtomicBoolean(false)
        val stdioWarnings = StdIoWarningAccumulator { message ->
            runtimeLogger.warn("[STDIO]: $message")
        }

        fun completeSuccess() {
            if (!patching.isCompleted) {
                patching.complete(Unit)
            }
        }

        fun completeCancelled(cause: Throwable? = null) {
            if (patching.isCompleted) return
            val error = CancellationException("Patching cancelled")
            cause?.let(error::initCause)
            patching.completeExceptionally(error)
        }

        fun completeFailure(throwable: Throwable) {
            if (!patching.isCompleted) {
                if (cancellationRequested.get()) {
                    completeCancelled(throwable)
                    return
                }
                patching.completeExceptionally(throwable)
            }
        }

        launch(Dispatchers.IO) {
            try {
                val result = try {
                    process(
                        appProcessBin,
                        "-Djava.io.tmpdir=$cacheDir",
                        "/",
                        "--nice-name=${context.packageName}:Revanced21Patcher",
                        Revanced21PatcherProcess::class.java.name,
                        context.packageName,
                        env = env,
                        stdout = Redirect.CAPTURE,
                        stderr = Redirect.CAPTURE,
                    ) { line ->
                        stdioWarnings.onLine(line)
                    }
                } finally {
                    stdioWarnings.flush()
                }

                Log.d(tag, "ReVanced v21 process finished with exit code ${result.resultCode}")

                if (result.resultCode == 0) {
                    if (cancellationRequested.get()) {
                        completeCancelled()
                        return@launch
                    }
                    if (finishedReported.get()) {
                        completeSuccess()
                    } else {
                        withTimeoutOrNull(FINISHED_CALLBACK_GRACE_PERIOD_MS) {
                            while (!finishedReported.get() && !patching.isCompleted) {
                                delay(25)
                            }
                        }
                        if (!patching.isCompleted) {
                            if (cancellationRequested.get()) {
                                completeCancelled()
                                return@launch
                            }
                            runtimeLogger.warn(
                                "ReVanced v21 process exited without finished callback; using process exit fallback."
                            )
                            completeSuccess()
                        }
                    }
                } else {
                    completeFailure(ProcessExitException(result.resultCode))
                }
            } catch (throwable: Throwable) {
                completeFailure(throwable)
            }
        }

        launch(Dispatchers.IO) {
            val binder = awaitBinderConnection()
            binderRef.set(binder)

            if (binder.buildId() != BuildConfig.BUILD_ID) {
                throw Exception(
                    "app_process is running outdated code. Clear the app cache or disable Android 11 deployment optimizations in your IDE"
                )
            }

            val eventHandler = object : IPatcherEvents.Stub() {
                override fun log(level: String, msg: String) {
                    logQueue.trySend(level to msg)
                }

                override fun event(event: ProgressEventParcel?) {
                    event?.let { eventQueue.trySend(it.toEvent()) }
                }

                override fun finished(exceptionStackTrace: String?) {
                    finishedReported.set(true)
                    runCatching { binder.exit() }

                    exceptionStackTrace?.let {
                        completeFailure(RemoteFailureException(it))
                        return
                    }
                    completeSuccess()
                }
            }
            eventHandlerRef.set(eventHandler)

            val activeSelectedPatches = selectedPatches.filterValues { it.isNotEmpty() }
            val selectedBundleIds = activeSelectedPatches.keys
            val bundlesByUid = bundles()
            val selectedBundlesByUid = bundlesByUid.filterKeys { it in selectedBundleIds }
            val staleBundleIds = selectedBundleIds - selectedBundlesByUid.keys
            if (staleBundleIds.isNotEmpty()) {
                runtimeLogger.warn("Ignoring missing patch bundle IDs in selection: ${staleBundleIds.joinToString(",")}")
            }
            if (activeSelectedPatches.isNotEmpty() && selectedBundlesByUid.isEmpty()) {
                throw IllegalArgumentException(
                    "Selected patches are unavailable. Re-open patch selection and select patches again."
                )
            }
            val runtimeClassPath = Revanced21RuntimeAssets.ensureRuntimeClassPath(context).absolutePath

            val parameters = Parameters(
                aaptPath = aaptModernPath,
                aaptFallbackPath = aaptLegacyPath,
                frameworkDir = frameworkPath,
                cacheDir = cacheDir,
                packageName = packageName,
                inputFile = inputFile,
                outputFile = outputFile,
                configurations = selectedBundlesByUid.map { (uid, bundle) ->
                    PatchConfiguration(
                        bundle,
                        activeSelectedPatches[uid].orEmpty(),
                        options[uid].orEmpty()
                    )
                },
                stripNativeLibs = stripNativeLibs,
                skipUnneededSplits = skipUnneededSplits,
                continueOnPatchError = prefs.continueOnPatchError.get(),
                patcherLogMode = logMode,
                runtimeClassPath = runtimeClassPath
            )

            binder.start(parameters, eventHandler)
        }

        try {
            patching.await()
            if (cancellationRequested.get()) {
                throw CancellationException("Patching cancelled")
            }
        } finally {
            eventHandlerRef.set(null)
            logQueue.close()
            eventQueue.close()
            withTimeoutOrNull(2_000L) {
                logDrainJob.join()
                eventDrainJob.join()
            } ?: run {
                logDrainJob.cancel()
                eventDrainJob.cancel()
            }
        }
    }

    companion object : LibraryResolver() {
        private const val APP_PROCESS_BIN_PATH = "/system/bin/app_process"
        private const val APP_PROCESS_BIN_PATH_64 = "/system/bin/app_process64"
        private const val APP_PROCESS_BIN_PATH_32 = "/system/bin/app_process32"
        const val OOM_EXIT_CODE = 134

        const val CONNECT_TO_APP_ACTION = "CONNECT_TO_REVANCED21_APP_ACTION"
        const val INTENT_BUNDLE_KEY = "BUNDLE"
        const val BUNDLE_BINDER_KEY = "BINDER"
        private const val FINISHED_CALLBACK_GRACE_PERIOD_MS = 1_500L

        private fun resolvePropOverride(context: Context) = findLibrary(context, "prop_override")

        private fun resolveAppProcessBin(context: Context): String {
            val is64Bit = context.applicationInfo.nativeLibraryDir.contains("64")
            val preferred = if (is64Bit) APP_PROCESS_BIN_PATH_64 else APP_PROCESS_BIN_PATH_32
            return if (File(preferred).exists()) preferred else APP_PROCESS_BIN_PATH
        }
    }

    class RemoteFailureException(val originalStackTrace: String) : Exception()

    class ProcessExitException(val exitCode: Int) :
        Exception("Process exited with nonzero exit code $exitCode")
}
