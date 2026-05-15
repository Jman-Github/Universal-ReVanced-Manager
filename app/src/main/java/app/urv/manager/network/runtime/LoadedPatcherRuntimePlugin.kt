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
