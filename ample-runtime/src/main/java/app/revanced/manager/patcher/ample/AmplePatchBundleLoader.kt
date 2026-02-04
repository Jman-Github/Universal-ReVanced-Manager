package app.revanced.manager.patcher.ample

import app.revanced.patcher.patch.Patch
import app.revanced.patcher.patch.loadPatchesFromDex
import java.io.File
import java.util.jar.JarFile

object AmplePatchBundleLoader {
    fun loadBundle(bundlePath: String): Collection<Patch<*>> {
        validateDexEntries(bundlePath)
        val patchFiles = runCatching {
            loadPatchesFromDex(setOf(File(bundlePath))).byPatchesFile
        }.getOrElse { error ->
            throw IllegalStateException("Patch bundle is corrupted or incomplete", error)
        }
        val entry = patchFiles.entries.singleOrNull()
            ?: throw IllegalStateException("Unexpected patch bundle load result for $bundlePath")

        return entry.value
    }

    fun patches(bundles: Iterable<String>, packageName: String) =
        bundles.associateWith { bundlePath ->
            loadBundle(bundlePath).filter { patch ->
                val compatiblePackages = patch.compatiblePackages
                    ?: return@filter true

                compatiblePackages.any { (name, _) -> name == packageName }
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
}
