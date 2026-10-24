package app.urv.manager.patcher.patch

import java.io.File
import java.util.zip.ZipFile

/** Detect by the archive marker: imported bundles are stored without their original extension. */
object ReseamBundleFormat {
    const val MIME_TYPE = "application/vnd.reseam.bundle"

    /** Stored selection keys append the patch ID; user-facing rows show its original name. */
    fun patchDisplayName(selectionKey: String): String =
        if (selectionKey.endsWith(']')) selectionKey.substringBeforeLast(" [", selectionKey)
        else selectionKey

    /** Routing hints only: the runtime must still verify the MIME marker and signed catalog. */
    fun isCandidate(file: File, fileNameHint: String? = null): Boolean {
        if (sequenceOf(fileNameHint, file.name).filterNotNull().any {
            it.substringBefore('?').substringBefore('#').endsWith(".reseam", ignoreCase = true)
        }) return true
        if (isBundle(file)) return true
        return runCatching {
            ZipFile(file).use { zip ->
                listOf("manifest.toml", "manifest.pubkey", "manifest.sig").all { name ->
                    zip.getEntry(name)?.let { !it.isDirectory } == true
                }
            }
        }.getOrDefault(false)
    }

    fun isBundle(file: File): Boolean = runCatching {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("mimetype") ?: return@use false
            zip.getInputStream(entry).use { input ->
                val bytes = ByteArray(MIME_TYPE.length + 1)
                var size = 0
                while (size < bytes.size) {
                    val count = input.read(bytes, size, bytes.size - size)
                    if (count < 0) break
                    if (count == 0) continue
                    size += count
                }
                size == MIME_TYPE.length && String(bytes, 0, size, Charsets.UTF_8) == MIME_TYPE
            }
        }
    }.getOrDefault(false)
}
