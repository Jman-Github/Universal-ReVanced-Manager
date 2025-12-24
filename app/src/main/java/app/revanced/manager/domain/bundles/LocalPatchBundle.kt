package app.revanced.manager.domain.bundles

import app.revanced.manager.data.redux.ActionContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

class LocalPatchBundle(
    name: String,
    uid: Int,
    displayName: String?,
    createdAt: Long?,
    updatedAt: Long?,
    error: Throwable?,
    directory: File,
    enabled: Boolean
) : PatchBundleSource(name, uid, displayName, createdAt, updatedAt, error, directory, enabled) {
    suspend fun ActionContext.replace(
        patches: InputStream,
        totalBytes: Long? = null,
        onProgress: ((bytesRead: Long, totalBytes: Long?) -> Unit)? = null
    ) {
        withContext(Dispatchers.IO) {
            patchBundleOutputStream().use { outputStream ->
                val buffer = ByteArray(256 * 1024)
                var readTotal = 0L
                while (true) {
                    val read = patches.read(buffer)
                    if (read == -1) break
                    outputStream.write(buffer, 0, read)
                    readTotal += read
                    onProgress?.invoke(readTotal, totalBytes)
                }
            }
            requireNonEmptyPatchesFile("Importing patch bundle")
        }
    }

    override fun copy(
        error: Throwable?,
        name: String,
        displayName: String?,
        createdAt: Long?,
        updatedAt: Long?,
        enabled: Boolean
    ) = LocalPatchBundle(
        name,
        uid,
        displayName,
        createdAt,
        updatedAt,
        error,
        directory,
        enabled
    )
}
