package app.revanced.manager.patcher.revanced

import app.revanced.patcher.patch.Patch
import app.revanced.patcher.patch.loadPatches
import java.io.File
import java.util.jar.JarFile

object RevancedPatchBundleLoader {
    fun loadBundle(bundlePath: String): Collection<Patch> {
        validateDexEntries(bundlePath)
        val bundleFile = File(bundlePath)
        val loadFailures = mutableListOf<Throwable>()
        val loadedPatches = runCatching {
            loadPatches(
                patchesFiles = arrayOf(bundleFile),
                onFailedToLoad = { _, throwable ->
                    loadFailures += throwable
                }
            )
        }.getOrElse { error ->
            throw IllegalStateException("Patch bundle is corrupted or incomplete", error)
        }

        if (loadFailures.isNotEmpty()) {
            val primary = loadFailures.first()
            val wrapped = IllegalStateException("Patch bundle is corrupted or incomplete", primary)
            loadFailures.drop(1).forEach(wrapped::addSuppressed)
            throw wrapped
        }

        val patchFiles = loadedPatches.patchesByFile
        val patches = patchFiles[bundleFile]
            ?: patchFiles.entries.firstOrNull { it.key.absolutePath == bundleFile.absolutePath }?.value
            ?: loadedPatches

        if (patches.isEmpty()) {
            throw IllegalStateException("Unexpected patch bundle load result for $bundlePath")
        }

        return patches
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
