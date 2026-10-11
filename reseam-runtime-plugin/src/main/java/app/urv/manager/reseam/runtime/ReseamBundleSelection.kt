package app.urv.manager.reseam.runtime

import app.reseam.sdk.PatchSpec

/** Keep one source per bundle ID: Reseam rejects duplicate patch references across catalogs. */
// https://git.reseam.app/reseam/reseam/src/tag/v0.18.3/crates/patcher/src/engine/index.rs
internal object ReseamBundleSelection {
    fun select(catalogs: List<List<PatchSpec>>, selectedSources: Set<Int>): List<Int> {
        val ids = catalogs.map { patches -> patches.map { it.bundle }.toSet() }
        val selectedIds = mutableSetOf<String>()
        selectedSources.forEach { index ->
            require(ids[index].none { it in selectedIds }) {
                "Select patches from only one source for each Reseam bundle. Disable duplicate sources if needed."
            }
            selectedIds += ids[index]
        }
        val dependencyIds = mutableSetOf<String>()
        return catalogs.indices.filter { index ->
            when {
                index in selectedSources -> true
                ids[index].any { it in selectedIds } -> false
                else -> {
                    require(ids[index].none { it in dependencyIds }) {
                        "Multiple enabled Reseam sources provide the same dependency bundle. Disable duplicate sources."
                    }
                    dependencyIds += ids[index]
                    true
                }
            }
        }
    }
}
