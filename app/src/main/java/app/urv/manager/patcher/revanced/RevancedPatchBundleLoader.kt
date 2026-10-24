package app.urv.manager.patcher.revanced

import app.revanced.patcher.patch.Patch
import app.revanced.patcher.patch.loadPatches
import app.urv.manager.patcher.logger.Logger
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.util.jar.JarFile

object RevancedPatchBundleLoader {
    // Code adapted from Morphe, see third-party/NOTICE.txt for more information.
    // https://github.com/MorpheApp/morphe-manager/pull/1246
    @JvmOverloads
    fun loadBundle(bundlePath: String, logger: Logger? = null): Collection<Patch> {
        logger?.info("Loading patches from ${File(bundlePath).name}")
        val startedAt = System.nanoTime()
        val isArt = System.getProperty("java.vm.name") == "Dalvik"
        if (isArt) validateDexEntries(bundlePath)
        val bundleFile = File(bundlePath)
        val loadFailures = mutableListOf<Throwable>()
        val patches = runCatching {
            if (isArt) {
                val loaded = loadPatches(
                    patchesFiles = arrayOf(bundleFile),
                    onFailedToLoad = { _, error -> loadFailures += error }
                )
                loaded.patchesByFile[bundleFile]
                    ?: loaded.patchesByFile.entries.firstOrNull { it.key.absolutePath == bundleFile.absolutePath }?.value
                    ?: loaded
            } else loadJvmPatches(bundleFile)
        }.getOrElse { error ->
            throw IllegalStateException("Patch bundle is corrupted or incomplete", error)
        }

        if (loadFailures.isNotEmpty()) {
            val wrapped = IllegalStateException("Patch bundle is corrupted or incomplete", loadFailures.first())
            loadFailures.drop(1).forEach(wrapped::addSuppressed)
            throw wrapped
        }
        if (patches.isEmpty()) {
            throw IllegalStateException("Unexpected patch bundle load result for $bundlePath")
        }

        logger?.info("Loaded ${patches.size} patches of ${bundleFile.name} in ${(System.nanoTime() - startedAt) / 1_000_000}ms")
        return patches
    }

    // The SDK's JVM factory uses the system parent, outside this run's isolated SDK.
    // Use its discovery function with an explicit parent; it is JVM-public but Kotlin-internal.
    // https://github.com/ReVanced/revanced-patcher/blob/v22.0.2-dev.1/patcher/src/commonMain/kotlin/app/revanced/patcher/patch/Patch.kt
    private fun loadJvmPatches(file: File): Collection<Patch> {
        val loader = URLClassLoader(arrayOf(file.toURI().toURL()), Patch::class.java.classLoader)
        try {
            val names = JarFile(file).use { jar ->
                jar.entries().asSequence()
                    .filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/versions/") }
                    .map { it.name.removeSuffix(".class").replace('/', '.') }.toList()
            }
            val discover = Class.forName("app.revanced.patcher.patch.PatchKt", true, Patch::class.java.classLoader)
                .getDeclaredMethod("getPatches", List::class.java, ClassLoader::class.java)
            @Suppress("UNCHECKED_CAST")
            val patches = try {
                discover.invoke(null, names, loader) as Collection<Patch>
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
            // Patches may read extension resources after discovery, so keep their loader open.
            if (patches.isEmpty()) loader.close()
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
                val compatiblePackages = patch.compatiblePackages
                    ?: return@filter true

                compatiblePackages.any { (name, _) -> name == packageName }
            }.toSet()
        }

    private fun validateDexEntries(jarPath: String) {
        JarFile(jarPath).use { jar ->
            val dexEntries = jar.entries().toList().filter { it.name.lowercase().endsWith(".dex") }
            if (dexEntries.isEmpty()) {
                throw IllegalStateException("Patch bundle is missing dex entries")
            }
            if (dexEntries.any { it.size <= 0L }) {
                throw IllegalStateException("Patch bundle contains empty dex entries")
            }
        }
    }
}
