package app.universal.patch.manager.domain.bundles

internal fun compareBundleVersions(first: String, second: String): Int? {
    val firstVersion = BundleVersion.parse(first) ?: return null
    val secondVersion = BundleVersion.parse(second) ?: return null
    return firstVersion.compareTo(secondVersion)
}

internal fun isRemoteBundleVersionUpdate(
    latestVersion: String,
    installedVersion: String?,
    manifestVersion: String?,
    preventDowngrade: Boolean = false,
    pinnedVersion: Boolean = false,
): Boolean {
    fun normalize(raw: String?): String? = raw?.trim()
        ?.removePrefix("v")?.removePrefix("V")?.substringBefore('+')
        ?.takeIf(String::isNotBlank)

    // A pin identifies an exact release tag. The manifest version may be shared
    // by stable/dev releases, and build metadata can distinguish separate tags.
    if (pinnedVersion) {
        val selected = latestVersion.trim().takeIf(String::isNotBlank) ?: return false
        return selected != installedVersion?.trim()
    }
    val latest = normalize(latestVersion) ?: return false
    val installed = listOfNotNull(normalize(installedVersion), normalize(manifestVersion))
    if (latest in installed) return false
    if (!preventDowngrade) return true
    // Release tags and the archive's internal version can use different numbering.
    // Prefer the tag saved at download time; use the manifest only for older imports.
    val current = normalize(installedVersion) ?: normalize(manifestVersion) ?: return true
    // Unknown tag formats retain change detection; only comparable versions can be ordered.
    return compareBundleVersions(latest, current)?.let { it > 0 } ?: true
}

private data class BundleVersion(
    val core: List<Long>,
    val prerelease: List<String>
) : Comparable<BundleVersion> {
    override fun compareTo(other: BundleVersion): Int {
        val max = maxOf(core.size, other.core.size)
        for (index in 0 until max) {
            val first = core.getOrElse(index) { 0 }
            val second = other.core.getOrElse(index) { 0 }
            if (first != second) return first.compareTo(second)
        }

        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0
        if (prerelease.isEmpty()) return 1
        if (other.prerelease.isEmpty()) return -1

        val prereleaseMax = maxOf(prerelease.size, other.prerelease.size)
        for (index in 0 until prereleaseMax) {
            val first = prerelease.getOrNull(index) ?: return -1
            val second = other.prerelease.getOrNull(index) ?: return 1
            val firstNumber = first.toLongOrNull()
            val secondNumber = second.toLongOrNull()
            val comparison = when {
                firstNumber != null && secondNumber != null -> firstNumber.compareTo(secondNumber)
                firstNumber != null -> -1
                secondNumber != null -> 1
                else -> first.compareTo(second, ignoreCase = true)
            }
            if (comparison != 0) return comparison
        }

        return 0
    }

    companion object {
        private val versionPattern = Regex("^[vV]?(\\d+(?:\\.\\d+)*)(?:[-_]([^+\\s]+))?(?:\\+.*)?$")

        fun parse(value: String): BundleVersion? {
            val match = versionPattern.matchEntire(value.trim()) ?: return null
            val core = match.groupValues[1]
                .split('.')
                .map { it.toLongOrNull() ?: return null }
            val prerelease = match.groupValues.getOrNull(2)
                ?.takeIf { it.isNotBlank() }
                ?.split(Regex("[.-]"))
                ?.filter { it.isNotBlank() }
                .orEmpty()

            return BundleVersion(core, prerelease)
        }
    }
}
