package app.universal.patch.manager.util

import java.net.URI

internal const val UPM_PACKAGE_NAME = "app.universal.patch.manager"
private const val URV_PACKAGE_NAME = "app.universal.revanced.manager"
private const val URV_ASSET_PREFIX = "universal-revanced-manager-"
private const val UPM_ASSET_PREFIX = "universal-patch-manager-"
private val LEGACY_PROFILES = listOf("LITE", "FULL")
private val VERSION_PATTERN = Regex(
    "^v?\\d+\\.\\d+\\.\\d+(?:[-.][a-z0-9]+)*$", RegexOption.IGNORE_CASE
)
private val ABI_SUFFIXES = listOf(
    "armeabi_v7a", "armeabi-v7a", "arm64_v8", "arm64-v8a",
    "universal", "x86_64", "all", "x86"
).sortedByDescending(String::length)

internal data class ManagerReleaseAssetName(
    val legacyBuildProfile: String?,
    val abiSuffix: String?,
    val isUpm: Boolean
)

internal fun parseManagerReleaseAssetName(fileName: String): ManagerReleaseAssetName? {
    if (!fileName.endsWith(".apk", ignoreCase = true)) return null
    var stem = fileName.dropLast(4)
    val profile = LEGACY_PROFILES.firstOrNull { stem.startsWith("$it-", ignoreCase = true) }
    if (profile != null) stem = stem.drop(profile.length + 1)
    val prefix = listOf(URV_ASSET_PREFIX, UPM_ASSET_PREFIX)
        .firstOrNull { stem.startsWith(it, ignoreCase = true) } ?: return null
    val versionStem = stem.drop(prefix.length)
    val abi = ABI_SUFFIXES.firstOrNull { versionStem.endsWith("-$it", ignoreCase = true) }
    val version = if (abi == null) versionStem else versionStem.dropLast(abi.length + 1)
    if (!VERSION_PATTERN.matches(version)) return null
    return ManagerReleaseAssetName(profile, abi, prefix == UPM_ASSET_PREFIX)
}

/** Prefer the renamed manager in mixed releases, then retain profile and device ABI priority. */
internal fun pickManagerReleaseAssetName(fileNames: List<String>, supportedAbis: List<String>): String? {
    val parsed = fileNames.mapNotNull { fileName ->
        parseManagerReleaseAssetName(fileName)?.let { fileName to it }
    }
    val currentBrand = parsed.filter { it.second.isUpm }.ifEmpty { parsed }
    val candidates = currentBrand.filter { it.second.legacyBuildProfile == null }
        .ifEmpty { currentBrand.filter { it.second.legacyBuildProfile == "FULL" } }
        .ifEmpty { currentBrand }

    fun normalizeAbi(abi: String): String = when (abi.lowercase()) {
        "arm64_v8" -> "arm64-v8a"
        "armeabi_v7a" -> "armeabi-v7a"
        "all" -> "universal"
        else -> abi.lowercase()
    }
    supportedAbis.forEach { abi ->
        candidates.firstOrNull { it.second.abiSuffix?.let(::normalizeAbi) == normalizeAbi(abi) }
            ?.let { return it.first }
    }
    for (fallback in listOf("all", "universal", null)) {
        candidates.firstOrNull { it.second.abiSuffix == fallback }?.let { return it.first }
    }
    return null
}

internal fun managerReleaseRequiresMigration(currentPackage: String, downloadUrl: String): Boolean {
    if (currentPackage == UPM_PACKAGE_NAME) return false
    val name = runCatching { URI(downloadUrl).path?.substringAfterLast('/') }.getOrNull()
        ?: return false
    return parseManagerReleaseAssetName(name)?.isUpm == true
}

internal fun isSupportedManagerUpdatePackage(currentPackage: String, candidatePackage: String): Boolean =
    candidatePackage == currentPackage ||
        (candidatePackage == UPM_PACKAGE_NAME &&
            (currentPackage == URV_PACKAGE_NAME || currentPackage.startsWith("$URV_PACKAGE_NAME.")))

internal fun managerUpdateDownloadIdentity(version: String, downloadUrl: String): String =
    "$version\n$downloadUrl"

/** A notice acknowledgement belongs to one exact download, and survives screen rotation in its VM. */
internal class ManagerRebrandConfirmation(private val nowMillis: () -> Long) {
    private var acknowledgedIdentity: String? = null
    private var pendingIdentity: String? = null
    private var deadlineMillis = 0L

    val secondsRemaining: Int
        get() = ((deadlineMillis - nowMillis()).coerceAtLeast(0L) + 999L)
            .div(1000L).coerceAtMost(10L).toInt()

    fun request(identity: String): Boolean {
        if (acknowledgedIdentity == identity) return false
        if (pendingIdentity != identity) {
            pendingIdentity = identity
            deadlineMillis = nowMillis() + 10_000L
        }
        return true
    }

    fun acknowledge(): Boolean {
        if (pendingIdentity == null || secondsRemaining > 0) return false
        acknowledgedIdentity = pendingIdentity
        pendingIdentity = null
        return true
    }

    fun cancel(): Boolean {
        if (pendingIdentity == null || secondsRemaining > 0) return false
        pendingIdentity = null
        return true
    }
}
