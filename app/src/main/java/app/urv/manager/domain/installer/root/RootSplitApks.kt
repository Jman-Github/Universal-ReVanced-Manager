package app.urv.manager.domain.installer.root

/** Split identity is independent of the install directory, which changes on reinstall. */
internal fun RootPackageState.verifiedSplits(): Map<String, String> {
    require(splitPaths.distinct().size == splitPaths.size) { "Duplicate installed split paths" }
    require(splitSha256.keys == splitPaths.toSet()) { "Installed split APK hashes are incomplete" }
    val directory = basePath?.substringBeforeLast('/')
    val result = linkedMapOf<String, String>()
    splitPaths.forEach { path ->
        require(path.substringBeforeLast('/') == directory && path != basePath) {
            "Split APK is outside the installed package directory"
        }
        val name = path.substringAfterLast('/')
        require(result.put(name, splitSha256.getValue(path)) == null) { "Duplicate split APK name" }
    }
    require(validSplitIdentity(topology, result)) { "Invalid installed split APK identity" }
    return result
}

internal fun validSplitIdentity(topology: String, splits: Map<String, String>): Boolean =
    when (topology) {
        "SINGLE" -> splits.isEmpty()
        "SPLIT" -> splits.isNotEmpty() && splits.all { (name, hash) ->
            name != "base.apk" && SPLIT_NAME.matches(name) && SPLIT_HASH.matches(hash)
        }
        else -> false
    }

internal fun RootPackageState.matchesSplits(splits: Map<String, String>): Boolean =
    runCatching { verifiedSplits() == splits }.getOrDefault(false)

internal fun encodeSplitIdentity(splits: Map<String, String>): String {
    require(validSplitIdentity(if (splits.isEmpty()) "SINGLE" else "SPLIT", splits))
    return splits.toSortedMap().entries.joinToString(" ") { (name, hash) -> "$name:$hash" }
}

internal fun decodeSplitIdentity(value: String): Map<String, String> {
    if (value.isEmpty()) return emptyMap()
    val result = linkedMapOf<String, String>()
    value.split(' ').forEach { entry ->
        val name = entry.substringBefore(':')
        val hash = entry.substringAfter(':', "")
        require(result.put(name, hash) == null) { "Duplicate stored split APK" }
    }
    require(validSplitIdentity("SPLIT", result)) { "Invalid stored split APK identity" }
    return result
}

private val SPLIT_NAME = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*\\.apk")
private val SPLIT_HASH = Regex("[0-9a-f]{64}")

/**
 * Return the base APK with hashes of splits that share its package, version, and signer.
 * PackageInstaller checks split dependencies when installing the set.
 */
internal fun verifiedStockSet(artifacts: List<RootArtifactState>): List<RootArtifactState> {
    if (artifacts.isEmpty()) return emptyList()
    val base = artifacts.singleOrNull { it.splitName == null }
        ?: throw IllegalArgumentException("Stock APK set must contain exactly one base APK")
    val splits = artifacts.filter { it.splitName != null }
    require(splits.map { it.splitName }.distinct().size == splits.size) { "Duplicate stock split name" }
    require(artifacts.map { it.path }.distinct().size == artifacts.size) { "Duplicate stock APK path" }
    for (split in splits) {
        require(split.packageName == base.packageName && split.versionCode == base.versionCode &&
            (split.versionName == null || split.versionName == base.versionName)) { "Mixed stock APK versions or packages" }
        require(!base.signerSha256.isNullOrBlank() && split.signerSha256 == base.signerSha256) {
            "Stock split signing certificate mismatch"
        }
        require(SPLIT_HASH.matches(split.sha256)) { "Stock split hash is invalid" }
    }
    return listOf(if (splits.isEmpty()) base else base.copy(topology = "SPLIT", splitHashes = splits.map { it.sha256 }.sorted()))
}

internal fun RootPackageState.matchesRequestedSplits(artifact: RootArtifactState): Boolean =
    runCatching { verifiedSplits().values.sorted() == artifact.splitHashes.sorted() }.getOrDefault(false)
