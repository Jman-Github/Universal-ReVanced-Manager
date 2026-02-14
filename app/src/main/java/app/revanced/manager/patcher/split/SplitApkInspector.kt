package app.revanced.manager.patcher.split

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SplitApkInspector {
    suspend fun extractRepresentativeApk(source: File, workspace: File): ExtractedApk? {
        if (!SplitApkPreparer.isSplitArchive(source)) return null

        val temp = File(
            workspace,
            "inspect-${UUID.randomUUID()}.apk"
        )

        return try {
            withContext(Dispatchers.IO) {
                try {
                    ZipFile(source).use { zip ->
                        val entry = selectBestEntry(zip)
                            ?: throw IOException("Split archive does not contain any APK entries.")
                        zip.getInputStream(entry).use { input ->
                            Files.newOutputStream(temp.toPath()).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                } catch (error: IOException) {
                    val message = error.message?.lowercase(Locale.ROOT).orEmpty()
                    if (!message.contains("no such device") && !message.contains("enodev")) {
                        throw error
                    }
                    extractWithStream(source, temp)
                }
            }
            ExtractedApk(temp) { temp.delete() }
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    private fun selectBestEntry(zip: ZipFile): ZipEntry? {
        val entries = zip.entries().asSequence()
            .filterNot { it.isDirectory }
            .filter { it.name.lowercase(Locale.ROOT).endsWith(".apk") }
            .toList()
        if (entries.isEmpty()) return null

        val lowered = entries.associateWith { it.name.lowercase(Locale.ROOT) }
        val baseEntry = lowered.entries.firstOrNull { (_, name) ->
            name.endsWith("/base.apk") || name.endsWith("base.apk") || "base-master" in name || "base-main" in name
        }?.key
        if (baseEntry != null) return baseEntry

        val primaryEntry = lowered.entries.firstOrNull { (_, name) ->
            "main" in name || "master" in name
        }?.key
        if (primaryEntry != null) return primaryEntry

        val nonConfig = lowered.entries.filter { (_, name) ->
            !name.startsWith("config") && !name.contains("split_config") && !name.contains("config.")
        }.map { it.key }
        val largestNonConfig = nonConfig
            .filter { it.size >= 0 }
            .maxByOrNull { it.size }
        if (largestNonConfig != null) return largestNonConfig

        return entries.minWithOrNull(
            compareBy<ZipEntry> { entry ->
                val lower = entry.name.lowercase(Locale.ROOT)
                when {
                    "base" in lower -> 0
                    "main" in lower || "master" in lower -> 1
                    lower.startsWith("config") -> 99
                    else -> 2
                }
            }.thenBy { it.name.length }
        )
    }

    private fun extractWithStream(source: File, temp: File) {
        val entryName = selectBestEntryName(source)
            ?: throw IOException("Split archive does not contain any APK entries.")
        ZipInputStream(FileInputStream(source)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name == entryName) {
                    Files.newOutputStream(temp.toPath()).use { output ->
                        zip.copyTo(output)
                    }
                    return
                }
                entry = zip.nextEntry
            }
        }
        throw IOException("Split archive entry not found: $entryName")
    }

    private fun selectBestEntryName(source: File): String? {
        var baseEntry: ZipEntry? = null
        var primaryEntry: ZipEntry? = null
        var largestNonConfig: ZipEntry? = null
        var bestFallback: ZipEntry? = null

        fun updateFallback(entry: ZipEntry) {
            if (bestFallback == null) {
                bestFallback = entry
                return
            }
            val current = bestFallback ?: return
            val nextName = entry.name.lowercase(Locale.ROOT)
            val currentName = current.name.lowercase(Locale.ROOT)
            val nextScore = when {
                "base" in nextName -> 0
                "main" in nextName || "master" in nextName -> 1
                nextName.startsWith("config") -> 99
                else -> 2
            }
            val currentScore = when {
                "base" in currentName -> 0
                "main" in currentName || "master" in currentName -> 1
                currentName.startsWith("config") -> 99
                else -> 2
            }
            if (nextScore < currentScore || (nextScore == currentScore && entry.name.length < current.name.length)) {
                bestFallback = entry
            }
        }

        ZipInputStream(FileInputStream(source)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val lower = entry.name.lowercase(Locale.ROOT)
                    if (lower.endsWith(".apk")) {
                        if (baseEntry == null && (lower.endsWith("/base.apk") || lower.endsWith("base.apk") || "base-master" in lower || "base-main" in lower)) {
                            baseEntry = entry
                        }
                        if (primaryEntry == null && ("main" in lower || "master" in lower)) {
                            primaryEntry = entry
                        }
                        if (!lower.startsWith("config") && !lower.contains("split_config") && !lower.contains("config.")) {
                            if (entry.size >= 0 && (largestNonConfig == null || entry.size > (largestNonConfig?.size ?: -1))) {
                                largestNonConfig = entry
                            }
                        }
                        updateFallback(entry)
                    }
                }
                entry = zip.nextEntry
            }
        }

        return baseEntry?.name
            ?: primaryEntry?.name
            ?: largestNonConfig?.name
            ?: bestFallback?.name
    }

    data class ExtractedApk(
        val file: File,
        val cleanup: () -> Unit = {}
    )
}
