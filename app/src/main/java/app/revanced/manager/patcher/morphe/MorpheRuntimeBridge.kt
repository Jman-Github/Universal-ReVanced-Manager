package app.revanced.manager.patcher.morphe

import android.content.Context
import app.revanced.manager.patcher.patch.PatchInfo
import app.revanced.manager.patcher.runtime.morphe.MorpheRuntimeAssets
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.reflect.Method

object MorpheRuntimeBridge {
    private const val ENTRY_CLASS_NAME = "app.revanced.manager.morphe.runtime.MorpheRuntimeEntry"
    private const val LOAD_METADATA_METHOD = "loadMetadata"
    private const val LOAD_METADATA_FOR_BUNDLE_METHOD = "loadMetadataForBundle"

    @Volatile
    private var appContext: Context? = null
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

    private val lock = Any()

    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
    }

    fun loadMetadata(bundlePath: String): List<PatchInfo> {
        val method = ensureLoadMetadataForBundleMethod()
        val raw = method.invoke(null, bundlePath) as? List<*> ?: emptyList<Any?>()
        return raw.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            PatchInfo.fromMorpheMetadata(map as Map<String, Any?>)
        }
    }

    fun loadMetadata(bundlePaths: List<String>): Map<String, List<PatchInfo>> {
        val method = ensureLoadMetadataMethod()
        val raw = method.invoke(null, bundlePaths) as? Map<*, *> ?: emptyMap<String, List<PatchInfo>>()
        return raw.mapNotNull { (key, value) ->
            val path = key as? String ?: return@mapNotNull null
            val metadata = value as? List<*> ?: return@mapNotNull path to emptyList()
            val patches = metadata.mapNotNull { entry ->
                val map = entry as? Map<*, *> ?: return@mapNotNull null
                PatchInfo.fromMorpheMetadata(map as Map<String, Any?>)
            }
            path to patches
        }.toMap()
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

    private fun ensureEntryClass(): Class<*> = synchronized(lock) {
        entryClass ?: run {
            val loader = ensureClassLoader()
            val loaded = loader.loadClass(ENTRY_CLASS_NAME)
            entryClass = loaded
            loaded
        }
    }

    private fun ensureClassLoader(): DexClassLoader {
        val context = appContext ?: error("MorpheRuntimeBridge is not initialized.")
        val runtimeClassPathFile = MorpheRuntimeAssets.ensureRuntimeClassPath(context)
        val path = runtimeClassPathFile.absolutePath
        val existing = classLoader
        if (existing != null && runtimeClassPath == path) return existing

        synchronized(lock) {
            val current = classLoader
            if (current != null && runtimeClassPath == path) return current

            val optimizedDir = File(context.codeCacheDir, "morphe-runtime-dex").apply { mkdirs() }
            // Use the boot classloader as parent to avoid app classpath conflicts.
            val parent = context.classLoader.parent ?: context.classLoader
            val loader = DexClassLoader(path, optimizedDir.absolutePath, null, parent)
            classLoader = loader
            runtimeClassPath = path
            entryClass = null
            loadMetadataMethod = null
            loadMetadataForBundleMethod = null
            return loader
        }
    }
}
