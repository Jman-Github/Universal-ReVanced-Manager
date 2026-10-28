package app.universal.patch.manager.util

import java.net.URI

private val bundleExportExtensions = setOf("rvp", "mpp", "reseam")

internal fun discoveryBundleExportExtension(bundleType: String, downloadUrl: String?): String {
    // Prefer the actual asset suffix when registry metadata is missing or stale.
    val assetExtension = runCatching {
        URI(downloadUrl.orEmpty().trim()).path?.substringAfterLast('/')
            ?.substringAfterLast('.', "")?.lowercase()
    }.getOrNull()
    if (assetExtension in bundleExportExtensions) return requireNotNull(assetExtension)
    return when {
        bundleType.trim().startsWith("Morphe", ignoreCase = true) -> "mpp"
        bundleType.trim().startsWith("Reseam", ignoreCase = true) -> "reseam"
        else -> "rvp"
    }
}

internal fun withBundleExportExtension(name: String, extension: String): String {
    val trimmed = name.trim()
    val existingExtension = trimmed.substringAfterLast('.', "").lowercase()
    val base = if (existingExtension in bundleExportExtensions) trimmed.substringBeforeLast('.') else trimmed
    return "$base.$extension"
}
