package app.urv.manager.data.platform

import java.io.File

/**
 * Patcher-owned scratch files shared by the runtime engines. Selected inputs and UI results
 * have separate owners and must survive cleanup while the user can still return to them.
 */
internal class PatchWorkspaceCleanup(
    private val directory: File,
    private val batchOutputDirectory: File
) {
    // Current-process outputs can still be awaiting publication, just like Repatch staging.
    private val restoredBatchPaths = runCatching {
        batchOutputs().mapTo(mutableSetOf()) { it.canonicalPath }
    }.getOrDefault(mutableSetOf())

    fun pruneScratchFiles(): Int = detachScratchFiles().count { it.deleteRecursively() }

    fun detachScratchFiles(): List<File> {
        val root = directory.canonicalFile
        val files = directory.listFiles().orEmpty().filter { file ->
            val disposable = file.name in RUNTIME_ARTIFACTS ||
                file.name.startsWith(DisposableFileCleanup.PREFIX) ||
                file.name.startsWith("split-patcher-selection-") ||
                file.name.startsWith("split-revanced22-") ||
                file.name.startsWith("installed-splits-")
            disposable && file.canonicalFile.parentFile == root
        }
        return DisposableFileCleanup.detach(files)
    }

    @Synchronized
    fun pruneRestoredBatchOutputs(retainedPaths: Collection<String>): Int {
        val retained = retainedPaths.filter(String::isNotBlank)
            .mapTo(mutableSetOf()) { File(it).canonicalPath }
        // A restored result may remain in navigation after its saved snapshot is replaced.
        restoredBatchPaths.removeAll(retained)
        val root = batchOutputDirectory.canonicalFile
        return batchOutputs().count { file ->
            val path = file.canonicalPath
            path in restoredBatchPaths && file.canonicalFile.parentFile == root && file.delete()
        }
    }

    private fun batchOutputs(): List<File> = batchOutputDirectory.listFiles { file ->
        file.isFile && file.name.startsWith("batch_") &&
            file.name.endsWith(".apk", ignoreCase = true)
    }.orEmpty().toList()

    companion object {
        private val RUNTIME_ARTIFACTS = setOf(
            "patched.apk",
            "patcher",
            "patch-workspace",
            "patcher-inputs",
            "patch-input-metadata",
            "apkeditor-android-data"
        )
    }
}
