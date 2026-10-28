package app.universal.patch.manager.patcher.patch

import app.revanced.patcher.patch.Patch
import app.revanced.patcher.patch.loadPatchesFromDex
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import app.universal.patch.manager.patcher.logger.Logger
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
                if (isArt) {
                    loadPatchesFromDex(setOf(file), optimizedDexDirectory).byPatchesFile.values.flatten()
                } else loadJvmPatches(file)
            }.getOrElse { error ->
                throw IllegalStateException("Patch bundle is corrupted or incomplete", error)
            }
            if (patches.isEmpty()) {
                throw IllegalStateException("Patch bundle contains no patches: ${bundle.patchesJar}")
            }

            logger?.info("Loaded ${patches.size} patches of ${File(bundle.patchesJar).name} in ${(System.nanoTime() - startedAt) / 1_000_000}ms")
            return patches
        }

        // ReVanced's JAR loader uses the system parent, which cannot see an isolated JVM SDK.
        // Keep its public static field/getter discovery with the patcher's class loader as parent.
        // https://github.com/ReVanced/revanced-patcher/blob/v21.0.0/src/main/kotlin/app/revanced/patcher/patch/Patch.kt
        private fun loadJvmPatches(file: File): List<Patch<*>> {
            val loader = URLClassLoader(arrayOf(file.toURI().toURL()), Patch::class.java.classLoader)
            return JarFile(file).use { jar ->
                jar.entries().asSequence().filter { it.name.endsWith(".class") }
                    .map { loader.loadClass(it.name.removeSuffix(".class").replace('/', '.')) }
                    .flatMap { type ->
                        val fields = type.fields.asSequence().filter {
                            Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) &&
                                Patch::class.java.isAssignableFrom(it.type)
                        }.map { it.get(null) as Patch<*> }
                        val methods = type.methods.asSequence().filter {
                            Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) &&
                                it.parameterCount == 0 && Patch::class.java.isAssignableFrom(it.returnType)
                        }.map { it.invoke(null) as Patch<*> }
                        fields + methods
                    }.filter { it.name != null }.distinct().toList()
            }
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

                    compatiblePackages.any { (name, _) -> name == packageName }
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
