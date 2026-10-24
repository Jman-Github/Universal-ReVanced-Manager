package app.urv.manager.patcher.patch

// JAR discovery follows ReVanced's PatchBundleLoader with an explicit isolated SDK parent.
// https://github.com/ReVanced/revanced-patcher/blob/v19.3.1/src/main/kotlin/app/revanced/patcher/PatchBundleLoader.kt

import app.revanced.patcher.patch.Patch
import app.revanced.patcher.PatchBundleLoader
import java.net.URLClassLoader
import java.lang.reflect.Modifier
import app.urv.manager.patcher.logger.Logger
import java.io.File
import java.io.IOException
import java.util.jar.JarFile

data class PatchBundle(val patchesJar: String) {
    /**
     * The [java.util.jar.Manifest] of [patchesJar].
     */
    private val manifest by lazy {
        try {
            JarFile(patchesJar).use { it.manifest }
        } catch (_: IOException) {
            null
        }
    }

    val manifestAttributes by lazy {
        if (manifest != null)
            ManifestAttributes(
                name = readManifestAttribute("name"),
                version = readManifestAttribute("version"),
                description = readManifestAttribute("description"),
                source = readManifestAttribute("source"),
                author = readManifestAttribute("author"),
                contact = readManifestAttribute("contact"),
                website = readManifestAttribute("website"),
                license = readManifestAttribute("license")
            ) else
            null
    }

    private fun readManifestAttribute(name: String) = manifest?.mainAttributes?.getValue(name)
        ?.takeIf { it.isNotBlank() } // If empty, set it to null instead.

    data class ManifestAttributes(
        val name: String?,
        val version: String?,
        val description: String?,
        val source: String?,
        val author: String?,
        val contact: String?,
        val website: String?,
        val license: String?
    )

    object Loader {
        // Code adapted from Morphe, see third-party/NOTICE.txt for more information.
        // https://github.com/MorpheApp/morphe-manager/pull/1246
        private fun loadBundle(bundle: PatchBundle, logger: Logger? = null): Collection<Patch<*>> {
            logger?.info("Loading patches from ${File(bundle.patchesJar).name}")
            val startedAt = System.nanoTime()
            val isArt = System.getProperty("java.vm.name") == "Dalvik"
            if (isArt) validateDexEntries(bundle.patchesJar)
            val optimizedDexDirectory = optimizedDexDirectory(bundle.patchesJar)
            val patches = runCatching {
                val file = File(bundle.patchesJar)
                if (isArt) PatchBundleLoader.Dex(file, optimizedDexDirectory = optimizedDexDirectory).toList()
                else {
                    // The SDK's JAR loader uses the system parent, which cannot see this isolated SDK.
                    val parent = Patch::class.java.classLoader
                    val classLoader = URLClassLoader(arrayOf(file.toURI().toURL()), parent)
                    JarFile(file).use { jar ->
                        jar.entries().asSequence().filter { it.name.endsWith(".class") }
                            .map { it.name.removeSuffix(".class").replace('/', '.') }
                            .map { classLoader.loadClass(it) }
                            .filter { Patch::class.java.isAssignableFrom(it) && !Modifier.isAbstract(it.modifiers) }
                            .map { type ->
                                @Suppress("UNCHECKED_CAST")
                                (try { type.getField("INSTANCE").get(null) }
                                 catch (_: NoSuchFieldException) {
                                     try { type.getDeclaredConstructor().newInstance() }
                                     catch (_: NoSuchMethodException) { null }
                                     catch (_: IllegalAccessException) { null }
                                 }) as Patch<*>?
                            }.filterNotNull().filter { it.name != null }.toList()
                    }
                }
            }.getOrElse { error ->
                throw IllegalStateException("Could not load this legacy ReVanced bundle with patcher 19.3.1", error)
            }
            require(patches.isNotEmpty()) { "Patch bundle contains no patches: " + bundle.patchesJar }

            logger?.info("Loaded ${patches.size} patches of ${File(bundle.patchesJar).name} in ${(System.nanoTime() - startedAt) / 1_000_000}ms")
            return patches
        }

        private fun metadataFor(bundle: PatchBundle) = loadBundle(bundle)

        fun metadata(bundles: Iterable<PatchBundle>) =
            bundles.associateWith(::metadataFor)

        fun metadata(bundle: PatchBundle) = metadataFor(bundle)

        @JvmOverloads
        fun patches(bundles: Iterable<PatchBundle>, packageName: String, logger: Logger? = null) =
            bundles.associateWith { bundle ->
                loadBundle(bundle, logger).filter { patch ->
                    val compatiblePackages = patch.compatiblePackages
                        ?: return@filter true // Universal patch

                    compatiblePackages.any { it.name == packageName }
                }.toSet()
            }

        private fun validateDexEntries(jarPath: String) {
            JarFile(jarPath).use { jar ->
                val dexEntries = jar.entries().toList().filter { entry ->
                    val name = entry.name.lowercase()
                    name.endsWith(".dex")
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
    }
}
