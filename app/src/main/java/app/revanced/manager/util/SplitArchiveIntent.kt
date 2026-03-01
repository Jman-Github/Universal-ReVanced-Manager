package app.revanced.manager.util

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.util.Locale

data class SplitArchiveIntent(
    val uri: Uri,
    val displayName: String?
)

object SplitArchiveIntentParser {
    private val supportedMimeTypes = SPLIT_ARCHIVE_MIME_TYPES.map { it.lowercase(Locale.ROOT) }.toSet()

    fun fromIntent(intent: Intent?, contentResolver: ContentResolver): SplitArchiveIntent? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        val displayName = resolveDisplayName(contentResolver, uri)
        val extension = (displayName
            ?: uri.lastPathSegment
            ?: uri.path)
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        val mimeType = (intent.type ?: contentResolver.getType(uri))
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        val extensionSupported = extension in SPLIT_ARCHIVE_FILE_EXTENSIONS
        val mimeTypeSupported = mimeType in supportedMimeTypes
        return if (extensionSupported || mimeTypeSupported) {
            SplitArchiveIntent(uri = uri, displayName = displayName)
        } else {
            null
        }
    }

    private fun resolveDisplayName(contentResolver: ContentResolver, uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1 && cursor.moveToFirst()) cursor.getString(index) else null
            }
            ?: uri.lastPathSegment
    }
}
