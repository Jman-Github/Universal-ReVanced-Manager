package app.universal.patch.manager.ui.viewmodel

import app.universal.patch.manager.domain.bundles.legacyOfficialBundleRegistryUrl

/** Decode the old explicit bundle entry without reinstating a built-in source. */
internal fun PatchBundleSnapshot.asUserManagedBundle(): PatchBundleSnapshot? {
    if (!endpoint.trim().equals("api", ignoreCase = true)) return this
    if (officialState == OfficialBundleState.ABSENT) return null
    return copy(
        endpoint = legacyOfficialBundleRegistryUrl(officialUsePrereleases ?: usePrereleases ?: false),
        autoUpdate = officialAutoUpdate ?: autoUpdate,
        usePrereleases = officialUsePrereleases ?: usePrereleases,
        officialState = null,
        officialAutoUpdate = null,
        officialUsePrereleases = null
    )
}
