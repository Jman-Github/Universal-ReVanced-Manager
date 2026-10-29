package app.universal.patch.manager.patcher.patch

/**
 * Metadata-only counterpart to the library's recommendation inputs.
 * Counting and stable descending ordering match
 * app.revanced.library.mostCommonCompatibleVersions (ReVanced Library).
 */
class MetadataPatch(
    val use: Boolean,
    val compatiblePackages: Set<Pair<String, Set<String>?>>?
)

fun Set<MetadataPatch>.mostCommonCompatibleVersions(
    wantedPackages: Set<String>? = null,
    countUnusedPatches: Boolean = false
): Map<String, LinkedHashMap<String, Int>> {
    val counts = linkedMapOf<String, LinkedHashMap<String, Int>>()
    filter { it.use || countUnusedPatches }.flatMap { it.compatiblePackages.orEmpty() }
        .filter { wantedPackages == null || it.first in wantedPackages }
        .forEach { (name, versions) ->
            if (versions?.isEmpty() == true) return@forEach
            val packageCounts = counts.getOrPut(name) { linkedMapOf() }
            versions?.forEach { version ->
                packageCounts[version] = packageCounts.getOrDefault(version, 0) + 1
            }
        }
    return counts.mapValues { (_, versions) ->
        versions.entries.sortedByDescending { it.value }
            .associateTo(linkedMapOf()) { it.key to it.value }
    }
}
