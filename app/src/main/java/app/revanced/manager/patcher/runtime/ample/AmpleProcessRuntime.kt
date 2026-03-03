package app.revanced.manager.patcher.runtime.ample

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
import app.revanced.manager.patcher.runtime.process.IAmplePatcherProcess
import app.revanced.manager.patcher.runtime.process.IPatcherEvents
import app.revanced.manager.patcher.runtime.process.AmpleParameters
import app.revanced.manager.patcher.runtime.process.AmplePatchConfiguration
import app.revanced.manager.patcher.runtime.ample.AmpleRuntimeAssets
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
import java.io.File

class AmpleProcessRuntime(
    private val context: Context,
    private val useMemoryOverride: Boolean = true
) : AmpleRuntime(context) {
    private val binderRef = AtomicReference<IAmplePatcherProcess?>()
    private val eventHandlerRef = AtomicReference<IPatcherEvents?>()

    override fun cancel() {
        runCatching { binderRef.getAndSet(null)?.exit() }
        eventHandlerRef.set(null)
    }

    private suspend fun awaitBinderConnection(): IAmplePatcherProcess {
        val binderFuture = CompletableDeferred<IAmplePatcherProcess>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val binder =
                    intent.getBundleExtra(INTENT_BUNDLE_KEY)?.getBinder(BUNDLE_BINDER_KEY)!!

                binderFuture.complete(IAmplePatcherProcess.Stub.asInterface(binder))
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
        onEvent(ProgressEvent.Started(app.revanced.manager.patcher.StepId.LoadPatches))
        val runtimeClassPath = AmpleRuntimeAssets.ensureRuntimeClassPath(context).absolutePath
        val apkEditorJarPath = AmpleRuntimeAssets.ensureApkEditorJar(context).absolutePath
        val apkEditorMergeJarPath = AmpleRuntimeAssets.ensureApkEditorMergeJar(context).absolutePath

        val env = System.getenv().toMutableMap().apply {
            put("CLASSPATH", runtimeClassPath)
        }

        var propOverridePath: String? = null
        var mergeMemoryLimitMb: Int? = null
        if (useMemoryOverride) {
            val requestedLimit = prefs.patcherProcessMemoryLimit.get()
            val aggressiveLimit = prefs.patcherProcessMemoryAggressive.get()
            val runtimeLimit = MemoryLimitConfig.clampLimitMb(
                context,
                if (aggressiveLimit) MemoryLimitConfig.maxLimitMb(context) else requestedLimit
            )
            val limit = "${runtimeLimit}M"
            propOverridePath = resolvePropOverride(context)?.absolutePath
                ?: throw Exception("Couldn't find prop override library")
            env["LD_PRELOAD"] = propOverridePath
            env["PROP_dalvik.vm.heapgrowthlimit"] = limit
            env["PROP_dalvik.vm.heapsize"] = limit
            mergeMemoryLimitMb = runtimeLimit
        } else {
            Log.d(tag, "Ample process runtime started without memory override")
        }

        val appProcessBin = resolveAppProcessBin(context)

        launch(Dispatchers.IO) {
            val result = process(
                appProcessBin,
                "-Djava.io.tmpdir=$cacheDir",
                "/",
                "--nice-name=${context.packageName}:AmplePatcher",
                AMPLE_PROCESS_CLASS_NAME,
                context.packageName,
                env = env,
                stdout = Redirect.CAPTURE,
                stderr = Redirect.CAPTURE,
            ) { line ->
                logger.warn("[STDIO]: $line")
            }

            Log.d(tag, "Ample process finished with exit code ${result.resultCode}")

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

            val parameters = AmpleParameters(
                aaptPath = aaptPrimaryPath,
                aaptFallbackPath = aaptFallbackPath,
                frameworkDir = frameworkPath,
                cacheDir = cacheDir,
                packageName = packageName,
                inputFile = inputFile,
                outputFile = outputFile,
                configurations = selectedBundlesByUid.map { (uid, bundle) ->
                    AmplePatchConfiguration(
                        bundle.patchesJar,
                        activeSelectedPatches[uid].orEmpty(),
                        options[uid].orEmpty()
                    )
                },
                stripNativeLibs = stripNativeLibs,
                skipUnneededSplits = skipUnneededSplits,
                apkEditorJarPath = apkEditorJarPath,
                apkEditorMergeJarPath = apkEditorMergeJarPath,
                propOverridePath = propOverridePath,
                mergeMemoryLimitMb = mergeMemoryLimitMb,
                appProcessPath = appProcessBin,
            )

            binder.start(parameters, eventHandler)
        }

        try {
            patching.await()
        } finally {
            eventHandlerRef.set(null)
        }
    }

    companion object : LibraryResolver() {
        private const val APP_PROCESS_BIN_PATH = "/system/bin/app_process"
        private const val APP_PROCESS_BIN_PATH_64 = "/system/bin/app_process64"
        private const val APP_PROCESS_BIN_PATH_32 = "/system/bin/app_process32"
        const val OOM_EXIT_CODE = 134
        private const val AMPLE_PROCESS_CLASS_NAME =
            "app.revanced.manager.patcher.runtime.process.AmplePatcherProcess"

        const val CONNECT_TO_APP_ACTION = "CONNECT_TO_AMPLE_APP_ACTION"
        const val INTENT_BUNDLE_KEY = "BUNDLE"
        const val BUNDLE_BINDER_KEY = "BINDER"

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
