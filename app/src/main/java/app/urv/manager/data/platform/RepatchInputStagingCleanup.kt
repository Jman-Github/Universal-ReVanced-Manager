package app.urv.manager.data.platform

import java.io.File

/**
 * Only reclaim files present when Filesystem was created. New staging copies may still belong
 * to a live result that has not published its WorkManager output or batch snapshot yet.
 */
internal class RepatchInputStagingCleanup(private val directory: File) {
    private val restoredPaths = runCatching {
        stagingFiles().mapTo(mutableSetOf()) { it.canonicalPath }
    }.getOrDefault(mutableSetOf())

    @Synchronized
    fun prune(retainedPaths: Collection<String>): Int {
        // Resolve every reference before deleting anything. A lookup failure must keep the files.
        val retainedCanonicalPaths = retainedPaths
            .filter(String::isNotBlank)
            .mapTo(mutableSetOf()) { File(it).canonicalPath }
        // Once restored by a result, it can remain in live navigation after its snapshot changes.
        // Explicit result cleanup releases it; automatic cleanup can reconsider it next process.
        restoredPaths.removeAll(retainedCanonicalPaths)
        return stagingFiles().count { file ->
            val path = file.canonicalPath
            path in restoredPaths && path !in retainedCanonicalPaths && file.delete()
        }
    }

    private fun stagingFiles(): List<File> = directory.listFiles { file ->
        file.isFile && file.name.startsWith("input_")
    }.orEmpty().toList()
}
