package app.urv.manager.network.runtime

import app.urv.manager.patcher.patch.PatchBundleType

enum class PatcherRuntimeKind(
    val id: String,
    val displayName: String
) {
    REVANCED_V21("revanced-v21", "ReVanced v21");

    companion object {
        fun fromId(value: String?): PatcherRuntimeKind? =
            entries.firstOrNull { it.id.equals(value?.trim(), ignoreCase = true) }
    }
}

fun PatchBundleType.requiredRuntimeKinds(useRevancedPatcher22: Boolean): List<PatcherRuntimeKind> =
    when (this) {
        PatchBundleType.REVANCED -> if (!useRevancedPatcher22) {
            listOf(PatcherRuntimeKind.REVANCED_V21)
        } else {
            emptyList()
        }
        PatchBundleType.MORPHE -> emptyList()
        PatchBundleType.AMPLE -> emptyList()
    }
