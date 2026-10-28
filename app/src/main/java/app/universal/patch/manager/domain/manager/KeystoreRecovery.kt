package app.universal.patch.manager.domain.manager

import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Validate before replacing the key or letting backup persistence remove legacy copies.
 * Existing but unreadable keys require explicit import; they must never trigger key generation.
 */
internal suspend fun recoverManagerKeystore(
    candidates: List<File>,
    destination: File,
    validateCredentials: suspend (ByteArray) -> Boolean,
    persistBackup: suspend (ByteArray) -> Unit
): Boolean {
    val existing = candidates.distinct().filter { it.isFile && it.length() > 0L }
    if (existing.isEmpty()) return false

    for (source in existing) {
        val bytes = try {
            source.readBytes()
        } catch (_: IOException) {
            continue
        }
        if (!validateCredentials(bytes)) continue
        destination.parentFile?.mkdirs()
        Files.write(destination.toPath(), bytes)
        persistBackup(bytes)
        return true
    }

    throw IllegalStateException(
        "An existing signing keystore could not be recovered with the saved credentials. " +
            "Import the keystore with its correct credentials before signing apps."
    )
}
