package app.urv.manager.patcher.morphe

import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.loadPatchesFromDex
import app.morphe.patcher.patch.PatchLoader
import app.urv.manager.patcher.logger.Logger
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.util.concurrent.ExecutionException
import java.util.jar.JarFile

object MorphePatchBundleLoader {
    private val isArt = System.getProperty("java.vm.name") == "Dalvik"
    private data class Stamp(val path: String, val length: Long, val modified: Long)
    private val prepared = java.util.concurrent.ConcurrentHashMap<Stamp, Prepared>()

    // Code adapted from Morphe, see third-party/NOTICE.txt for more information.
    // https://github.com/MorpheApp/morphe-manager/pull/1246
    private class Prepared(bundlePath: String) {
        val startedAt = System.nanoTime()
        @Volatile var loadedInMs = 0L
        val task = java.util.concurrent.FutureTask {
            loadFresh(bundlePath).also { loadedInMs = (System.nanoTime() - startedAt) / 1_000_000 }
        }.also { Thread(it, "PreloadMorpheBundle").apply { isDaemon = true }.start() }
    }

    // Code adapted from Morphe, see third-party/NOTICE for more information.
    // https://github.com/MorpheApp/morphe-manager/pull/1212
    /** Only the JVM host preloads. A prepared bundle is consumed once so patch options cannot leak. */
    fun preload(bundlePath: String) {
        if (isArt) return
        val file = File(bundlePath)
        val key = Stamp(file.canonicalPath, file.length(), file.lastModified())
        prepared.computeIfAbsent(key) { Prepared(bundlePath) }
    }

    @JvmOverloads
    fun loadBundle(bundlePath: String, logger: Logger? = null): Collection<Patch<*>> {
        val file = File(bundlePath)
        val key = Stamp(file.canonicalPath, file.length(), file.lastModified())
        val preload = prepared.remove(key)
        if (preload != null) {
            if (!preload.task.isDone) {
                logger?.info("Waiting for ${file.name}, loading ahead for ${(System.nanoTime() - preload.startedAt) / 1_000_000}ms")
            }
            try {
                return preload.task.get().also {
                    logger?.info("Loaded ${it.size} patches of ${file.name} ahead in ${preload.loadedInMs}ms")
                }
            } catch (error: ExecutionException) {
                logger?.warn("Loading ${file.name} ahead of the run failed, loading it again: ${error.cause}")
            }
        } else {
            val stale = prepared.keys.filter { it.path == key.path }
            if (stale.isNotEmpty()) {
                stale.forEach(prepared::remove)
                logger?.info("${file.name} changed since it was loaded ahead, loading it again")
            }
        }
        logger?.info("Loading patches from ${file.name}")
        val startedAt = System.nanoTime()
        return loadFresh(bundlePath).also {
            logger?.info("Loaded ${it.size} patches of ${file.name} in ${(System.nanoTime() - startedAt) / 1_000_000}ms")
        }
    }

    private fun loadFresh(bundlePath: String): Collection<Patch<*>> {
        if (isArt) validateDexEntries(bundlePath)
        val optimizedDexDirectory = optimizedDexDirectory(bundlePath)
        val patchFiles = runCatching {
            withRuntimeContextClassLoader {
                if (isArt) loadPatchesFromDex(setOf(File(bundlePath)), optimizedDexDirectory).byPatchesFile
                else loadJvmPatches(File(bundlePath))
            }
        }.getOrElse { error ->
            throw IllegalStateException("Patch bundle is corrupted or incomplete", error)
        }
        if (patchFiles.isEmpty()) {
            throw IllegalStateException("Unexpected patch bundle load result for $bundlePath")
        }

        val patches = patchFiles.values
            .asSequence()
            .flatten()
            .toList()
        if (patches.isEmpty()) {
            throw IllegalStateException("Patch bundle contains no patches: $bundlePath")
        }

        return patches
    }

    /**
     * Morphe's JAR convenience loader uses the system parent, which cannot see this isolated SDK.
     * Invoke its existing discovery with an explicit parent to retain bundle provenance, dependency
     * metadata and version-dependent behavior checks. The pinned SDK has no public JVM factory
     * accepting a class loader; changing the thread context loader does not change its JAR parent.
     *
     * Code adapted from Morphe; see third-party/NOTICE.txt.
     * https://github.com/MorpheApp/morphe-patcher/blob/v2.0.0/src/main/kotlin/app/morphe/patcher/patch/Patch.kt
     */
    private fun loadJvmPatches(file: File): Map<File, Set<Patch<*>>> {
        val loader = URLClassLoader(arrayOf(file.toURI().toURL()), Patch::class.java.classLoader)
        try {
            val names = JarFile(file).use { jar ->
                jar.entries().asSequence()
                    .filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/versions/") }
                    .map { it.name.removeSuffix(".class").replace('/', '.') }.toSet()
            }
            val companion = PatchLoader::class.java.getDeclaredField("Companion")
                .apply { isAccessible = true }.get(null)
            val discover = companion.javaClass.getDeclaredMethod("loadPatches", ClassLoader::class.java, Map::class.java)
                .apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val patches = try {
                discover.invoke(companion, loader, mapOf(file to names)) as Map<File, Set<Patch<*>>>
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
            // Successful patches retain their loader for lazy class and extension-resource access.
            if (patches.values.all { it.isEmpty() }) loader.close()
            return patches
        } catch (error: Throwable) {
            runCatching { loader.close() }
            throw error
        }
    }

    @JvmOverloads
    fun patches(bundles: Iterable<String>, packageName: String, logger: Logger? = null) =
        bundles.associateWith { bundlePath ->
            loadBundle(bundlePath, logger).filter { patch ->
                val compatibility = patch.compatibility
                    ?: return@filter true

                compatibility.any { it.packageName == null || it.packageName == packageName }
            }.toSet()
        }

    private fun validateDexEntries(jarPath: String) {
        JarFile(jarPath).use { jar ->
            val dexEntries = jar.entries().toList().filter { entry ->
                entry.name.lowercase().endsWith(".dex")
            }
            if (dexEntries.isEmpty()) {
                throw IllegalStateException("Patch bundle is missing dex entries")
            }
            val hasEmptyDex = dexEntries.any { it.size <= 0L }
            if (hasEmptyDex) {
                throw IllegalStateException("Patch bundle contains empty dex entries")
            }
        }
    }

    private fun optimizedDexDirectory(bundlePath: String): File? = runCatching {
        File(bundlePath).absoluteFile.parentFile?.resolve("oat")?.apply { mkdirs() }
    }.getOrNull()

    private inline fun <T> withRuntimeContextClassLoader(block: () -> T): T {
        val thread = Thread.currentThread()
        val runtimeClassLoader = MorphePatchBundleLoader::class.java.classLoader
            ?: return block()
        val previousClassLoader = thread.contextClassLoader
        if (previousClassLoader === runtimeClassLoader) return block()

        thread.contextClassLoader = runtimeClassLoader
        return try {
            block()
        } finally {
            thread.contextClassLoader = previousClassLoader
        }
    }
}
