/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */
package app.universal.patch.manager.ui.component.patches

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/blob/0b39b6479b07cfdae5b976cd922480fba406d155/app/src/main/java/app/morphe/manager/ui/screen/shared/FilePicker.kt
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Ancestors up to the active storage root, ordered from root to current folder. */
internal fun pathSelectorBreadcrumbs(root: Path, directory: Path): List<Path> =
    generateSequence(directory) { path -> if (path == root) null else path.parent }
        .toList()
        .asReversed()

/** Both preview sizes and APK icons must change identity after a picker refresh. */
internal fun pathPreviewCacheKey(generation: String, path: Path): String =
    "path-selector:$generation:${path.toAbsolutePath()}"

internal data class PathEntryDetails(
    val itemCount: Int? = null,
    val size: Long? = null,
    val modified: Long? = null
)

/** Read only on an I/O dispatcher. Missing or inaccessible metadata stays unknown. */
internal fun readPathEntryDetails(path: Path, isDirectory: Boolean): PathEntryDetails? {
    if (isDirectory) {
        val count = runCatching {
            Files.newDirectoryStream(path).use { entries -> entries.count() }
        }.getOrNull()
        return count?.let { PathEntryDetails(itemCount = it) }
    }
    val attributes = runCatching {
        Files.readAttributes(path, BasicFileAttributes::class.java)
    }.getOrNull() ?: return null
    return PathEntryDetails(size = attributes.size(), modified = attributes.lastModifiedTime().toMillis())
}
