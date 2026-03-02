package app.revanced.manager.patcher.split

import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object Merger {
    private val MERGE_PATTERN = Regex("Merging\\s*:?\\s*(.+)", RegexOption.IGNORE_CASE)

    suspend fun merge(
        apkDir: Path,
        outputApk: File,
        skipModules: Set<String> = emptySet(),
        onProgress: ((String) -> Unit)? = null,
        sortApkEntries: Boolean = false
    ) {
        val progressHandler: ((String) -> Unit)? = onProgress?.let { handler ->
            { line: String ->
                val normalized = normalizeMergeProgress(line)
                if (normalized != null) {
                    handler(normalized)
                }
            }
        }
        withContext(Dispatchers.IO) {
            ApkEditorMergeRuntime.merge(
                apkDir = apkDir.toFile(),
                outputApk = outputApk,
                skipModules = skipModules,
                sortApkEntries = sortApkEntries,
                onLine = progressHandler
            )
        }
    }

    fun listMergeOrder(apkDir: Path): List<String> = runCatching {
        ApkEditorMergeRuntime.listMergeOrder(apkDir.toFile())
    }.getOrElse { emptyList() }

    private fun normalizeMergeProgress(line: String): String? {
        val match = MERGE_PATTERN.find(line) ?: return null
        val moduleName = match.groupValues.getOrNull(1)?.trim().orEmpty()
        if (moduleName.isBlank()) return null
        val normalized = if (moduleName.lowercase().endsWith(".apk")) {
            moduleName
        } else {
            "$moduleName.apk"
        }
        return "Merging $normalized"
    }
}
