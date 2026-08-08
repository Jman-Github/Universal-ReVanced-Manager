package app.urv.manager.network.runtime

import java.io.File

data class LoadedPatcherRuntimePlugin(
    val packageName: String,
    val name: String,
    val version: String,
    val kind: PatcherRuntimeKind,
    val sourceId: String? = null,
    val apkFile: File
) {
    val id: String = sourceId ?: packageName
}

fun String.toRuntimeMainName(): String {
    val original = trim()
    val withoutPrefix = original.substringAfterLast(':', original).trim()
    val normalized = withoutPrefix
        .replace(Regex("(?i)revanced[ ._-]*runtime[ ._-]*v?21"), "ReVanced v21")
        .replace(Regex("(?i)revanced[ ._-]*v?21"), "ReVanced v21")
        .replace(Regex("(?i)\\s+runtime\\b"), "")
        .replace(Regex("(?i)\\s+plugin\\b"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    return normalized.ifBlank { original }
}
