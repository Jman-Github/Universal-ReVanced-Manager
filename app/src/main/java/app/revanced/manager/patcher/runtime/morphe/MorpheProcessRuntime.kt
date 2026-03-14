package app.revanced.manager.patcher.runtime.morphe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import app.universal.revanced.manager.BuildConfig
import app.revanced.manager.patcher.LibraryResolver
import app.revanced.manager.patcher.ProgressEvent
import app.revanced.manager.patcher.ProgressEventParcel
import app.revanced.manager.patcher.logger.Logger
import app.revanced.manager.patcher.runtime.MemoryLimitConfig
import app.revanced.manager.patcher.runtime.process.IMorphePatcherProcess
import app.revanced.manager.patcher.runtime.process.IPatcherEvents
import app.revanced.manager.patcher.runtime.process.MorpheParameters
import app.revanced.manager.patcher.runtime.process.MorphePatchConfiguration
import app.revanced.manager.patcher.runtime.morphe.MorpheRuntimeAssets
import app.revanced.manager.patcher.toEvent
import app.revanced.manager.util.Options
import app.revanced.manager.util.PatchSelection
import app.revanced.manager.util.tag
import com.github.pgreze.process.Redirect
import com.github.pgreze.process.process
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
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File

class MorpheProcessRuntime(
    private val context: Context,
    private val useMemoryOverride: Boolean = true
) : MorpheRuntime(context) {
    private val binderRef = AtomicReference<IMorphePatcherProcess?>()
    private val eventHandlerRef = AtomicReference<IPatcherEvents?>()

    override fun cancel() {
        runCatching { binderRef.getAndSet(null)?.exit() }
        eventHandlerRef.set(null)
    }

    private suspend fun awaitBinderConnection(): IMorphePatcherProcess {
        val binderFuture = CompletableDeferred<IMorphePatcherProcess>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val binder =
                    intent.getBundleExtra(INTENT_BUNDLE_KEY)?.getBinder(BUNDLE_BINDER_KEY)!!

                binderFuture.complete(IMorphePatcherProcess.Stub.asInterface(binder))
            }
        }

        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(CONNECT_TO_APP_ACTION)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)

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
        currentCoroutineContext()[Job]?.invokeOnCompletion {
            runCatching { binderRef.get()?.exit() }
            eventHandlerRef.set(null)
        }
        val logQueue = Channel<Pair<String, String>>(Channel.UNLIMITED)
        val eventQueue = Channel<ProgressEvent>(Channel.UNLIMITED)
        val logDrainJob = launch(Dispatchers.Default) {
            for ((level, msg) in logQueue) {
                runCatching { logger.log(enumValueOf(level), msg) }
            }
        }
        val eventDrainJob = launch(Dispatchers.Default) {
            for (event in eventQueue) {
                runCatching { onEvent(event) }
            }
        }
        onEvent(ProgressEvent.Started(app.revanced.manager.patcher.StepId.LoadPatches))
        val runtimeClassPath = MorpheRuntimeAssets.ensureRuntimeClassPath(context).absolutePath

        val env = System.getenv().toMutableMap().apply {
            put("CLASSPATH", runtimeClassPath)
        }

        if (useMemoryOverride) {
            val requestedLimit = prefs.patcherProcessMemoryLimit.get()
            val aggressiveLimit = prefs.patcherProcessMemoryAggressive.get()
            val runtimeLimit = MemoryLimitConfig.clampLimitMb(
                context,
                if (aggressiveLimit) MemoryLimitConfig.maxLimitMb(context) else requestedLimit
            )
            val limit = "${runtimeLimit}M"
            val usePropOverride = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            val propOverride = if (usePropOverride) {
                resolvePropOverride(context)?.absolutePath
                    ?: throw Exception("Couldn't find prop override library")
            } else {
                null
            }
            if (propOverride != null) {
                env["LD_PRELOAD"] = propOverride
                env["PROP_dalvik.vm.heapgrowthlimit"] = limit
                env["PROP_dalvik.vm.heapsize"] = limit
            } else {
                Log.w(tag, "Skipping prop override on Android ${Build.VERSION.SDK_INT}")
            }
        } else {
            Log.d(tag, "Morphe process runtime started without memory override")
        }

        val appProcessBin = resolveAppProcessBin(context)

        val patching = CompletableDeferred<Unit>()
        val finishedReported = AtomicBoolean(false)

        fun completeSuccess() {
            if (!patching.isCompleted) {
                patching.complete(Unit)
            }
        }

        fun completeFailure(throwable: Throwable) {
            if (!patching.isCompleted) {
                patching.completeExceptionally(throwable)
            }
        }

        launch(Dispatchers.IO) {
            try {
                val result = process(
                    appProcessBin,
                    "-Djava.io.tmpdir=$cacheDir",
                    "/",
                    "--nice-name=${context.packageName}:MorphePatcher",
                    MORPHE_PROCESS_CLASS_NAME,
                    context.packageName,
                    env = env,
                    stdout = Redirect.CAPTURE,
                    stderr = Redirect.CAPTURE,
                ) { line ->
                    logger.warn("[STDIO]: $line")
                }

                Log.d(tag, "Morphe process finished with exit code ${result.resultCode}")

                if (result.resultCode == 0) {
                    if (finishedReported.get()) {
                        completeSuccess()
                    } else {
                        withTimeoutOrNull(FINISHED_CALLBACK_GRACE_PERIOD_MS) {
                            while (!finishedReported.get() && !patching.isCompleted) {
                                delay(25)
                            }
                        }
                        if (!patching.isCompleted) {
                            logger.warn(
                                "Morphe process exited without finished callback; using process exit fallback."
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
            val remoteBuildId = binder.buildId()
            if (remoteBuildId != 0L && remoteBuildId != BuildConfig.BUILD_ID) {
                throw Exception("app_process is running outdated code. Clear the app cache or disable Android 11 deployment optimizations in your IDE")
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
                logger.warn("Ignoring missing patch bundle IDs in selection: ${staleBundleIds.joinToString(",")}")
            }
            if (activeSelectedPatches.isNotEmpty() && selectedBundlesByUid.isEmpty()) {
                throw IllegalArgumentException(
                    "Selected patches are unavailable. Re-open patch selection and select patches again."
                )
            }

            val parameters = MorpheParameters(
                aaptPath = aaptPrimaryPath,
                aaptFallbackPath = aaptFallbackPath,
                frameworkDir = frameworkPath,
                cacheDir = cacheDir,
                packageName = packageName,
                inputFile = inputFile,
                outputFile = outputFile,
                configurations = selectedBundlesByUid.map { (uid, bundle) ->
                    MorphePatchConfiguration(
                        bundle.patchesJar,
                        activeSelectedPatches[uid].orEmpty(),
                        options[uid].orEmpty()
                    )
                },
                stripNativeLibs = stripNativeLibs,
                skipUnneededSplits = skipUnneededSplits
            )

            binder.start(parameters, eventHandler)
        }

        try {
            patching.await()
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
        private const val MORPHE_PROCESS_CLASS_NAME =
            "app.revanced.manager.patcher.runtime.process.MorphePatcherProcess"

        const val CONNECT_TO_APP_ACTION = "CONNECT_TO_MORPHE_APP_ACTION"
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
