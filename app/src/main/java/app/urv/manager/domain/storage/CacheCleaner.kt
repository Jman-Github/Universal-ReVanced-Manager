package app.urv.manager.domain.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

suspend fun clearManagerCache(context: Context): Long = withContext(Dispatchers.IO) {
    listOf(context.cacheDir, context.codeCacheDir)
        .plus(context.externalCacheDirs.filterNotNull())
        .sumOf { it.deleteContentsAndReturnBytes() }
}

private fun File.deleteContentsAndReturnBytes(): Long {
    if (!exists()) return 0L
    if (isFile) {
        val bytes = length()
        val deleted = runCatching { delete() }.getOrDefault(false)
        return if (deleted) bytes else 0L
    }
    if (!isDirectory) return 0L
    return listFiles().orEmpty().sumOf { child ->
        val bytes = child.directoryBytes()
        val deleted = runCatching { child.deleteRecursively() }.getOrDefault(false)
        if (deleted) bytes else 0L
    }
}

private fun File.directoryBytes(): Long {
    if (!exists()) return 0L
    if (isFile) return length()
    if (!isDirectory) return 0L
    return listFiles().orEmpty().sumOf { it.directoryBytes() }
}
