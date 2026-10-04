package app.urv.manager.data.platform

import java.io.File
import java.util.UUID

/**
 * Rename disposable files under the cleanup guard, then delete them after releasing it.
 * A new operation can recreate the original paths without waiting for recursive deletion.
 */
internal object DisposableFileCleanup {
    const val PREFIX = ".urv-cleanup-"

    fun detach(files: Collection<File>): List<File> = files.mapNotNull { file ->
        val parent = file.absoluteFile.parentFile ?: return@mapNotNull null
        if (!file.exists() || file.canonicalFile.parentFile != parent.canonicalFile) {
            return@mapNotNull null
        }
        if (file.name.startsWith(PREFIX)) return@mapNotNull file
        val detached = parent.resolve("$PREFIX${UUID.randomUUID()}")
        detached.takeIf { file.renameTo(it) }
    }

    fun deleteDetached(files: Collection<File>): Long = files.sumOf { file ->
        require(file.name.startsWith(PREFIX))
        val bytes = file.directoryBytes()
        if (runCatching { file.deleteRecursively() }.getOrDefault(false)) bytes else 0L
    }

    private fun File.directoryBytes(): Long = when {
        isFile -> length()
        isDirectory -> listFiles().orEmpty().sumOf { it.directoryBytes() }
        else -> 0L
    }
}
