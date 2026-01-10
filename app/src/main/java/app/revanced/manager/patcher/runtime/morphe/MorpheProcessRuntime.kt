package app.revanced.manager.patcher.runtime.morphe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference

class MorpheProcessRuntime(private val context: Context) : MorpheRuntime(context) {
    private val binderRef = AtomicReference<IMorphePatcherProcess?>()

    override fun cancel() {
        runCatching { binderRef.getAndSet(null)?.exit() }
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
    ) = coroutineScope {
        currentCoroutineContext()[Job]?.invokeOnCompletion {
            runCatching { binderRef.get()?.exit() }
        }
        onEvent(ProgressEvent.Started(app.revanced.manager.patcher.StepId.LoadPatches))
        val runtimeApk = MorpheRuntimeAssets.ensureRuntimeApk(context).absolutePath

        val requestedLimit = prefs.patcherProcessMemoryLimit.get()
        val aggressiveLimit = prefs.patcherProcessMemoryAggressive.get()
        val runtimeLimit = if (aggressiveLimit) {
            MemoryLimitConfig.maxLimitMb(context)
        } else {
            MemoryLimitConfig.autoScaleLimitMb(context, requestedLimit)
        }
        if (runtimeLimit != requestedLimit && !aggressiveLimit) {
            Log.d(tag, "Auto-scaled process memory limit from ${requestedLimit}MB to ${runtimeLimit}MB")
        }
        val sanitizedLimit = MemoryLimitConfig.clampLimitMb(context, runtimeLimit)
        if (sanitizedLimit != runtimeLimit) {
            Log.w(tag, "Requested process memory limit ${runtimeLimit}MB exceeded device capabilities; clamped to ${sanitizedLimit}MB")
        }
        val limit = "${sanitizedLimit}M"
        val propOverride = resolvePropOverride(context)?.absolutePath
            ?: throw Exception("Couldn't find prop override library")

        val env =
            System.getenv().toMutableMap().apply {
                putAll(
                    mapOf(
                        "CLASSPATH" to runtimeApk,
                        "LD_PRELOAD" to propOverride,
                        "PROP_dalvik.vm.heapgrowthlimit" to limit,
                        "PROP_dalvik.vm.heapsize" to limit,
                    )
                )
            }

        launch(Dispatchers.IO) {
            val result = process(
                APP_PROCESS_BIN_PATH,
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

            if (result.resultCode != 0) throw ProcessExitException(result.resultCode)
        }

        val patching = CompletableDeferred<Unit>()

        launch(Dispatchers.IO) {
            val binder = awaitBinderConnection()
            binderRef.set(binder)
            val remoteBuildId = binder.buildId()
            if (remoteBuildId != 0L && remoteBuildId != BuildConfig.BUILD_ID) {
                throw Exception("app_process is running outdated code. Clear the app cache or disable Android 11 deployment optimizations in your IDE")
            }

            val eventHandler = object : IPatcherEvents.Stub() {
                override fun log(level: String, msg: String) = logger.log(enumValueOf(level), msg)

                override fun event(event: ProgressEventParcel?) {
                    event?.let { onEvent(it.toEvent()) }
                }

                override fun finished(exceptionStackTrace: String?) {
                    runCatching { binder.exit() }

                    exceptionStackTrace?.let {
                        patching.completeExceptionally(RemoteFailureException(it))
                        return
                    }
                    patching.complete(Unit)
                }
            }

            val parameters = MorpheParameters(
                aaptPath = aaptPath,
                frameworkDir = frameworkPath,
                cacheDir = cacheDir,
                packageName = packageName,
                inputFile = inputFile,
                outputFile = outputFile,
                configurations = bundles().map { (uid, bundle) ->
                    MorphePatchConfiguration(
                        bundle.patchesJar,
                        selectedPatches[uid].orEmpty(),
                        options[uid].orEmpty()
                    )
                },
                stripNativeLibs = stripNativeLibs
            )

            binder.start(parameters, eventHandler)
        }

        patching.await()
    }

    companion object : LibraryResolver() {
        private const val APP_PROCESS_BIN_PATH = "/system/bin/app_process"
        const val OOM_EXIT_CODE = 134
        private const val MORPHE_PROCESS_CLASS_NAME =
            "app.revanced.manager.patcher.runtime.process.MorphePatcherProcess"

        const val CONNECT_TO_APP_ACTION = "CONNECT_TO_MORPHE_APP_ACTION"
        const val INTENT_BUNDLE_KEY = "BUNDLE"
        const val BUNDLE_BINDER_KEY = "BINDER"

        private fun resolvePropOverride(context: Context) = findLibrary(context, "prop_override")
    }

    class RemoteFailureException(val originalStackTrace: String) : Exception()

    class ProcessExitException(val exitCode: Int) :
        Exception("Process exited with nonzero exit code $exitCode")
}
