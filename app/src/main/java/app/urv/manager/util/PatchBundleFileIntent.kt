package app.urv.manager.util

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.util.Locale
import java.util.jar.Manifest
import java.util.zip.ZipInputStream

data class PatchBundleFileIntent(
    val uri: Uri,
    val displayName: String?
)

data class PatchBundleFileManifest(
    val name: String?,
    val version: String?,
    val author: String?,
    val description: String?,
    val source: String?,
    val timestamp: Long?
)

// Code adapted from Morphe, see third-party/NOTICE for more information
// https://github.com/MorpheApp/morphe-manager/blob/6688aa17ea35b5ab398a3c1922be13626290cbf1/app/src/main/java/app/morphe/manager/util/FilePickerUtils.kt#L23-L120
object PatchBundleFileIntentParser {
    fun fromIntent(intent: Intent?, contentResolver: ContentResolver): PatchBundleFileIntent? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        if (uri.scheme != ContentResolver.SCHEME_CONTENT && uri.scheme != ContentResolver.SCHEME_FILE) {
            return null
        }

        val displayName = resolveDisplayName(contentResolver, uri)
        val extension = (displayName ?: uri.lastPathSegment ?: uri.path)
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        if (extension !in setOf("mpp", "rvp")) return null

        return PatchBundleFileIntent(uri = uri, displayName = displayName)
    }

    fun readManifest(contentResolver: ContentResolver, uri: Uri): PatchBundleFileManifest? = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use zipUse@ { zip ->
                generateSequence { zip.nextEntry }
                    .firstOrNull { it.name.equals("META-INF/MANIFEST.MF", ignoreCase = true) }
                    ?: return@zipUse null

                val attributes = Manifest(zip).mainAttributes
                fun attribute(name: String): String? = attributes.getValue(name)
                    ?.takeUnless { it.isBlank() || it.equals("na", ignoreCase = true) }

                PatchBundleFileManifest(
                    name = attribute("Name"),
                    version = attribute("Version"),
                    author = attribute("Author"),
                    description = attribute("Description"),
                    source = attribute("Source") ?: attribute("Website"),
                    timestamp = attribute("Timestamp")?.toLongOrNull()
                )
            }
        }
    }.getOrNull()

    private fun resolveDisplayName(contentResolver: ContentResolver, uri: Uri): String? =
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1 && cursor.moveToFirst()) cursor.getString(index) else null
                }
        }.getOrNull() ?: uri.lastPathSegment
}
