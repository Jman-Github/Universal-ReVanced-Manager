/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.urv.manager.patcher.patch

import app.urv.manager.util.PatchSelection

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/pull/747

/** UI-facing state for patches whose availability cannot be changed by the user. */
enum class PatchLockState {
    NONE,
    LOCKED_ON,
    LOCKED_OFF,
}

enum class PatchInstallerType {
    STANDARD,
    MOUNT,
    SHIZUKU,
}

enum class PatchAvailabilityState {
    ENABLED,
    DISABLED,
    REQUIRED,
    UNAVAILABLE,
}

const val GMSCORE_SUPPORT_PATCH_NAME = "GmsCore support"

/** Translate URV's pre-patch mount choice into the Morphe availability taxonomy. */
fun installerTypeFor(useMount: Boolean): PatchInstallerType =
    if (useMount) PatchInstallerType.MOUNT else PatchInstallerType.STANDARD

/**
 * Enforce REQUIRED and UNAVAILABLE declarations after loading a saved or custom selection.
 * ENABLED and DISABLED remain user choices once their initial default has been established.
 */
fun PatchSelection.applyAvailability(
    installerType: PatchInstallerType,
    eligibleBundlePatches: Map<Int, Map<String, PatchInfo>>,
    enabled: Boolean = true,
): PatchSelection {
    if (!enabled) return this

    val result = mapValuesTo(mutableMapOf()) { (_, patches) -> patches.toMutableSet() }

    eligibleBundlePatches.forEach { (bundleUid, patchesInBundle) ->
        val current = result[bundleUid]?.toMutableSet() ?: mutableSetOf()

        patchesInBundle.values.forEach { patch ->
            when (patch.availability?.get(installerType)) {
                PatchAvailabilityState.REQUIRED -> current.add(patch.name)
                PatchAvailabilityState.UNAVAILABLE -> current.remove(patch.name)
                PatchAvailabilityState.ENABLED,
                PatchAvailabilityState.DISABLED,
                null -> Unit
            }
        }

        if (current.isEmpty()) result.remove(bundleUid) else result[bundleUid] = current
    }

    return result
}

/** Remove the legacy GmsCore compatibility patch when Root Mount is the configured path. */
fun PatchSelection.removeGmsCoreSupport(enabled: Boolean): PatchSelection {
    if (!enabled) return this

    return mapNotNull { (bundleUid, patches) ->
        val filtered = patches - GMSCORE_SUPPORT_PATCH_NAME
        bundleUid.takeIf { filtered.isNotEmpty() }?.let { it to filtered }
    }.toMap()
}
