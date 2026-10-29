package app.universal.patch.manager.patcher.revanced

import app.universal.patch.manager.util.managerStorageContext

import android.content.Context
import app.universal.patch.manager.patcher.ProgressEvent
import app.universal.patch.manager.patcher.RemoteError
import app.universal.patch.manager.patcher.StepId
import app.universal.patch.manager.patcher.patch.PatchInfo
import app.universal.patch.manager.patcher.logger.LogLevel
import app.universal.patch.manager.patcher.logger.Logger
import app.universal.patch.manager.patcher.runtime.revanced.Revanced21RuntimeAssets
import app.universal.patch.manager.patcher.runtime.revanced.RevancedLegacyFormat
import dalvik.system.DexClassLoader
import java.io.File
import java.io.IOException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException

object Revanced21RuntimeBridge {
    private const val ENTRY_CLASS_NAME = "app.universal.patch.manager.revanced.runtime.RevancedRuntimeEntry"
    private const val CALLBACK_CLASS_NAME = "app.universal.patch.manager.revanced.runtime.RevancedRuntimeCallback"
    private const val LOAD_METADATA_METHOD = "loadMetadata"
    private const val LOAD_METADATA_FOR_BUNDLE_METHOD = "loadMetadataForBundle"
    private const val RUN_PATCHER_METHOD = "runPatcher"
    private const val CANCELLATION_SENTINEL = "__PATCHING_CANCELLED__"

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var runtimeClassPathOverride: String? = null
    @Volatile
    private var runtimeClassPath: String? = null
    @Volatile
    private var classLoader: DexClassLoader? = null
    @Volatile
    private var entryClass: Class<*>? = null
    @Volatile
    private var loadMetadataMethod: Method? = null
    @Volatile
    private var loadMetadataForBundleMethod: Method? = null
    @Volatile
    private var runPatcherMethod: Method? = null
    @Volatile
    private var callbackClass: Class<*>? = null

    private val lock = Any()

    fun initialize(context: Context, runtimeClassPath: String? = null) {
        appContext = context.applicationContext ?: context
        val overridePath = runtimeClassPath?.takeIf { it.isNotBlank() }
        if (overridePath != null && runtimeClassPathOverride != overridePath) {
            synchronized(lock) {
                if (runtimeClassPathOverride != overridePath) {
                    runtimeClassPathOverride = overridePath
                    this.runtimeClassPath = null
                    classLoader = null
                    entryClass = null
                    loadMetadataMethod = null
                    loadMetadataForBundleMethod = null
                    runPatcherMethod = null
                    callbackClass = null
                }
            }
        }
    }

    fun loadMetadata(bundlePath: String): List<PatchInfo> = synchronized(lock) {
        ensureClassLoader(RevancedLegacyFormat.isLegacyBundle(File(bundlePath)))
        val method = ensureLoadMetadataForBundleMethod()
        val raw = method.invoke(null, bundlePath) as? List<*> ?: emptyList<Any?>()
        raw.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            PatchInfo.fromMorpheMetadata(map as Map<String, Any?>)
        }
    }

    fun loadMetadata(bundlePaths: List<String>): Map<String, List<PatchInfo>> =
        bundlePaths.associateWith(::loadMetadata)

    fun runPatcher(
        params: Map<String, Any?>,
        logger: Logger,
        onEvent: (ProgressEvent) -> Unit,
        isCancelled: () -> Boolean = { false }
    ): String? {
        val (entry, callback, method) = synchronized(lock) {
            val paths = (params["configurations"] as? List<*>).orEmpty().mapNotNull {
                (it as? Map<*, *>)?.get("bundlePath") as? String
            }
            ensureClassLoader(RevancedLegacyFormat.selectionUsesLegacy(paths))
            Triple(ensureEntryClass(), ensureCallbackClass(), ensureRunPatcherMethod())
        }
        val proxy = Proxy.newProxyInstance(
            entry.classLoader,
            arrayOf(callback)
        ) { _, method, args ->
            when (method.name) {
                "log" -> {
                    val level = args?.getOrNull(0) as? String
                    val message = args?.getOrNull(1) as? String
                    if (level != null && message != null) {
                        logger.log(LogLevel.valueOf(level), message)
                    }
                    null
                }
                "event" -> {
                    val raw = args?.getOrNull(0) as? Map<*, *> ?: return@newProxyInstance null
                    onEvent(mapToProgressEvent(raw))
                    null
                }
                "isCancelled" -> isCancelled()
                else -> null
            }
        }
        val result = method.invoke(null, params, proxy) as? String
        if (result == CANCELLATION_SENTINEL) {
            throw CancellationException("Patching cancelled")
        }
        return result
    }

    private fun ensureLoadMetadataMethod(): Method = synchronized(lock) {
        loadMetadataMethod ?: run {
            val method = ensureEntryClass().getMethod(LOAD_METADATA_METHOD, List::class.java)
            loadMetadataMethod = method
            method
        }
    }

    private fun ensureLoadMetadataForBundleMethod(): Method = synchronized(lock) {
        loadMetadataForBundleMethod ?: run {
            val method = ensureEntryClass().getMethod(LOAD_METADATA_FOR_BUNDLE_METHOD, String::class.java)
            loadMetadataForBundleMethod = method
            method
        }
    }

    private fun ensureRunPatcherMethod(): Method = synchronized(lock) {
        runPatcherMethod ?: run {
            val method = ensureEntryClass().getMethod(
                RUN_PATCHER_METHOD,
                Map::class.java,
                ensureCallbackClass()
            )
            runPatcherMethod = method
            method
        }
    }

    private fun ensureCallbackClass(): Class<*> = synchronized(lock) {
        callbackClass ?: run {
            val loaded = checkNotNull(classLoader).loadClass(CALLBACK_CLASS_NAME)
            callbackClass = loaded
            loaded
        }
    }

    private fun ensureEntryClass(): Class<*> = synchronized(lock) {
        entryClass ?: run {
            val loader = checkNotNull(classLoader)
            val loaded = loader.loadClass(ENTRY_CLASS_NAME)
            entryClass = loaded
            loaded
        }
    }

    private fun ensureClassLoader(legacy: Boolean): DexClassLoader {
        val context = appContext ?: error("Revanced21RuntimeBridge is not initialized.")
        val originalClassPathFile = runtimeClassPathOverride
            ?.takeIf { it.isNotBlank() }
            ?.let { path ->
                File(path).takeIf { it.exists() }
                    ?: throw IOException("ReVanced v21 runtime classpath does not exist: $path")
            }
            ?: Revanced21RuntimeAssets.ensureRuntimeClassPath(context)
        val runtimeClassPathFile = if (legacy) {
            Revanced21RuntimeAssets.ensureLegacyRuntimeClassPath(context, originalClassPathFile)
        } else originalClassPathFile
        val path = runtimeClassPathFile.absolutePath
        val cacheKey = "$path:${runtimeClassPathFile.lastModified()}:${runtimeClassPathFile.length()}"
        val existing = classLoader
        if (existing != null && runtimeClassPath == cacheKey) return existing

        synchronized(lock) {
            val current = classLoader
            if (current != null && runtimeClassPath == cacheKey) return current

            val optimizedDir = File(context.managerStorageContext.codeCacheDir, "revanced21-runtime-dex").apply { mkdirs() }
            val parent = context.classLoader.parent ?: context.classLoader
            val loader = DexClassLoader(path, optimizedDir.absolutePath, null, parent)
            classLoader = loader
            runtimeClassPath = cacheKey
            entryClass = null
            loadMetadataMethod = null
            loadMetadataForBundleMethod = null
            runPatcherMethod = null
            callbackClass = null
            return loader
        }
    }

    private fun mapToProgressEvent(map: Map<*, *>): ProgressEvent {
        val type = map["type"] as? String ?: return ProgressEvent.Progress(StepId.LoadPatches)
        val stepId = mapToStepId(map["stepId"] as? Map<*, *>)
        return when (type) {
            "Started" -> ProgressEvent.Started(
                stepId ?: StepId.LoadPatches,
                (map["subSteps"] as? Iterable<*>)?.mapNotNull { it as? String }
            )
            "Completed" -> ProgressEvent.Completed(stepId ?: StepId.LoadPatches)
            "Progress" -> ProgressEvent.Progress(
                stepId = stepId ?: StepId.LoadPatches,
                current = (map["current"] as? Number)?.toLong(),
                total = (map["total"] as? Number)?.toLong(),
                message = map["message"] as? String,
                subSteps = (map["subSteps"] as? Iterable<*>)?.mapNotNull { it as? String }
            )
            "Failed" -> {
                val error = map["error"] as? Map<*, *> ?: emptyMap<Any, Any?>()
                ProgressEvent.Failed(
                    stepId,
                    RemoteError(
                        type = error["type"] as? String ?: "UnknownError",
                        message = error["message"] as? String,
                        stackTrace = error["stackTrace"] as? String ?: ""
                    )
                )
            }
            else -> ProgressEvent.Progress(stepId ?: StepId.LoadPatches)
        }
    }

    private fun mapToStepId(map: Map<*, *>?): StepId? {
        val kind = map?.get("kind") as? String ?: return null
        return when (kind) {
            "DownloadAPK" -> StepId.DownloadAPK
            "LoadPatches" -> StepId.LoadPatches
            "PrepareSplitApk" -> StepId.PrepareSplitApk
            "ReadAPK" -> StepId.ReadAPK
            "ExecutePatches" -> StepId.ExecutePatches
            "WriteAPK" -> StepId.WriteAPK
            "SignAPK" -> StepId.SignAPK
            "ExecutePatch" -> {
                val index = (map["index"] as? Number)?.toInt() ?: 0
                StepId.ExecutePatch(index)
            }
            else -> null
        }
    }
}

class Revanced21BridgeFailureException(val originalStackTrace: String) :
    Exception("ReVanced v21 external runtime failed")
