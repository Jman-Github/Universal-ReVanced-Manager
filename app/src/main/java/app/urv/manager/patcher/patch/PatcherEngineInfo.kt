package app.urv.manager.patcher.patch

import app.universal.revanced.manager.BuildConfig

fun patcherEngineDisplayName(
    bundleType: PatchBundleType?,
    usesRevancedPatcher22: Boolean = false
): String? = when (bundleType) {
    PatchBundleType.MORPHE -> "Morphe ${BuildConfig.MORPHE_PATCHER_VERSION}"
    PatchBundleType.REVANCED -> if (usesRevancedPatcher22) {
        "ReVanced ${BuildConfig.REVANCED_PATCHER_V22_VERSION}"
    } else {
        "ReVanced ${BuildConfig.REVANCED_PATCHER_V21_VERSION}"
    }
    PatchBundleType.AMPLE -> "Ample"
    null -> null
}
