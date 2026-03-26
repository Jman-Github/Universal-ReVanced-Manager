package app.revanced.manager.util

import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.name

private val normalizedApkMimeTypes = APK_FILE_MIME_TYPES.map { it.lowercase(Locale.ROOT) }.toSet()
private val normalizedSplitArchiveMimeTypes = SPLIT_ARCHIVE_MIME_TYPES.map { it.lowercase(Locale.ROOT) }.toSet()
private val normalizedGenericArchiveMimeTypes = setOf(
    "application/zip",
    "application/x-zip-compressed",
    BIN_MIMETYPE.lowercase(Locale.ROOT)
)

fun isAllowedApkFile(path: Path): Boolean {
    val extension = path.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return extension in APK_FILE_EXTENSIONS
}

fun resolveSupportedApkExtension(displayName: String?, mimeType: String?): String? {
    val normalizedName = displayName?.trim().orEmpty()
    val extension = normalizedName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (extension in APK_FILE_EXTENSIONS) return extension

    val normalizedMimeType = mimeType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
        .orEmpty()
    if (normalizedMimeType !in normalizedApkMimeTypes) return null
    if (normalizedMimeType in normalizedGenericArchiveMimeTypes) return null

    return if (normalizedMimeType in normalizedSplitArchiveMimeTypes) "apks" else "apk"
}

fun isAllowedPatchBundleFile(path: Path): Boolean {
    val extension = path.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return extension == "rvp" || extension == "mpp" || extension == "arp"
}

fun isAllowedSplitArchiveFile(path: Path): Boolean {
    val extension = path.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return extension in SPLIT_ARCHIVE_FILE_EXTENSIONS
}
