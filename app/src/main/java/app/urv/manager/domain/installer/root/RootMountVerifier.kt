package app.urv.manager.domain.installer.root

class RootMountVerifier(
    private val shell: RootShellGateway,
    private val mountTableReader: MountTableReader,
    private val packageStateReader: PackageStateReader,
    private val namespaces: RootMountNamespaces
) : RootMountVerification {
    override suspend fun mountEverywhere(expected: RootCommittedState) {
        namespaces.mount(expected)
    }
    override suspend fun verifyTargetsClear(targets: Set<String>) {
        require(targets.all(::isSafeApkPath)) { "Unsafe APK mount target" }
        val occupied = mountTableReader.mountsAt(targets)
        check(occupied.isEmpty()) {
            "APK mount target is occupied by another mount: ${occupied.joinToString { it.mountPoint }}"
        }
    }

    override suspend fun findUrvMounts(
        packageName: String,
        extraTargets: Set<String>
    ): List<MountInfoEntry> {
        require(extraTargets.all(::isSafeApkPath)) { "Unsafe mount target in transaction state" }
        return findUrvMounts(packageName, mountTableReader.read())
    }

    private suspend fun findUrvMounts(
        packageName: String,
        mountTable: List<MountInfoEntry>
    ): List<MountInfoEntry> {
        val sources = urvSources(packageName)
        val sourceInodes = sources.mapNotNull { path -> inode(path) }.toSet()
        val owned = linkedSetOf<MountInfoEntry>()
        mountTable.filter { isSafeApkPath(it.mountPoint) }
            .groupBy(MountInfoEntry::mountPoint)
            .forEach { (target, layers) ->
                owned += layers.filter { entry ->
                    normalizedMountPath(entry.root) in sources ||
                        normalizedMountPath(entry.source) in sources
                }
                val visibleLayer = layers.maxByOrNull(MountInfoEntry::mountId)
                if (visibleLayer != null && inode(target) in sourceInodes) {
                    owned += visibleLayer
                }
            }
        return mountTable.filter { it in owned }
    }

    private fun urvSources(packageName: String): Set<String> = buildSet {
        listOf(
            RootPaths.moduleApk(packageName),
            RootPaths.moduleStockApk(packageName),
            "${RootPaths.LEGACY}/$packageName/$packageName.apk",
            "${RootPaths.rollbackModule(packageName)}/$packageName.apk",
            "${RootPaths.rollbackModule(packageName)}/$packageName-stock.apk",
            "${RootPaths.backup(packageName)}/module/$packageName.apk",
            "${RootPaths.backup(packageName)}/module/$packageName-stock.apk"
        ).forEach { source ->
            add(source)
            // mountinfo records the root inside the /data filesystem, not the
            // absolute Android path, for bind mounts backed by /data.
            if (source.startsWith("/data/")) add(source.removePrefix("/data"))
        }
    }

    private suspend fun visibleTargetIsUrv(target: String, sourceInodes: Set<String>): Boolean {
        val targetInode = inode(target) ?: return false
        return targetInode in sourceInodes
    }

    private suspend fun knownSourceInodes(packageName: String): Set<String> =
        urvSources(packageName).mapNotNull { path -> inode(path) }.toSet()

    override suspend fun removeAllUrvMounts(
        packageName: String,
        extraTargets: Set<String>,
        allowLazyRecovery: Boolean
    ): List<String> {
        val lazyUnmounts = mutableListOf<String>()
        val sourceInodes = knownSourceInodes(packageName)
        val targets = findUrvMounts(packageName, extraTargets)
            .map { it.mountPoint }
            .distinct()
            .sortedByDescending(String::length)
        lazyUnmounts += namespaces.removeOwned(
            packageName,
            extraTargets + targets,
            allowLazyRecovery
        )
        for (target in targets) {
            var normalAttempts = 0
            while (true) {
                val mountTable = mountTableReader.read()
                val allAtTarget = mountTable.filter { it.mountPoint == target }
                val urvAtTarget = findUrvMounts(packageName, mountTable)
                    .filter { it.mountPoint == target }
                if (urvAtTarget.isEmpty()) break
                val visibleLayerIsUrv = visibleTargetIsUrv(target, sourceInodes)
                check(allAtTarget.size == urvAtTarget.size || visibleLayerIsUrv) {
                    "Foreign mount layer shares URV target $target; refusing to unmount another owner's layer"
                }
                if (normalAttempts >= MAX_NORMAL_UNMOUNTS) {
                    check(allowLazyRecovery) { "Too many mount layers remained at $target" }
                    shell.runIsolatedBounded(
                        "umount -l ${shellQuote(target)}",
                        UNMOUNT_TIMEOUT_SECONDS,
                        "root mount lazy unmount"
                    ).requireSuccess("Recovery lazy unmount $target")
                    if (target !in lazyUnmounts) lazyUnmounts += target
                    break
                }
                val normal = shell.runIsolatedBounded(
                    "umount ${shellQuote(target)}",
                    UNMOUNT_TIMEOUT_SECONDS,
                    "root mount unmount"
                )
                if (!normal.isSuccess) {
                    if (!allowLazyRecovery) normal.requireSuccess("Unmount $target")
                    shell.runIsolatedBounded(
                        "umount -l ${shellQuote(target)}",
                        UNMOUNT_TIMEOUT_SECONDS,
                        "root mount lazy unmount"
                    ).requireSuccess("Recovery lazy unmount $target")
                    if (target !in lazyUnmounts) lazyUnmounts += target
                }
                normalAttempts++
            }
        }
        val remaining = findUrvMounts(packageName, extraTargets)
        check(remaining.isEmpty()) { "URV mount remained after unmount: ${remaining.joinToString { it.mountPoint }}" }
        return lazyUnmounts.distinct()
    }

    override suspend fun verifyMounted(expected: RootCommittedState): RootPackageState {
        val allAtTarget = mountTableReader.mountsAt(setOf(expected.stockPath))
        val mounts = findUrvMounts(expected.packageName, allAtTarget)
        val requiredLayers = if (expected.preserveStockAcrossBoot) 2 else 1
        check(allAtTarget.size == mounts.size) {
            "A foreign mount layer shares the rooted APK target"
        }
        check(allAtTarget.size in requiredLayers..MAX_URV_MOUNT_LAYERS) {
            "Expected at least $requiredLayers bounded URV mount layers at ${expected.stockPath}, " +
                "found ${allAtTarget.size}"
        }
        check(mounts.any { it.referencesMountSource(expected.patchedPath) }) {
            "Patched payload mount layer is missing"
        }
        if (expected.preserveStockAcrossBoot) {
            val stockShadowPath = requireNotNull(expected.stockShadowPath)
            check(mounts.any { it.referencesMountSource(stockShadowPath) }) {
                "Stock shadow mount layer is missing"
            }
            check(sha256(stockShadowPath) == expected.stockShadowSha256) {
                "Stock shadow hash mismatch"
            }
        }
        val sourceInode = inode(expected.patchedPath)
        val targetInode = inode(expected.stockPath)
        check(sourceInode != null && sourceInode == targetInode) { "Bind mount source does not match active payload" }
        val packageState = packageStateReader.read(expected.packageName, expected.userId)
        check(packageState.baseSha256 == expected.patchedSha256) { "Mounted APK hash mismatch" }
        check(packageState.userId == expected.userId) { "PackageManager Android user changed during mount" }
        check(packageState.installed && packageState.versionCode == expected.versionCode) {
            "PackageManager version changed during mount"
        }
        check(packageState.versionName == expected.versionName) { "PackageManager version name changed during mount" }
        check(packageState.signerSha256 == expected.signerSha256) { "PackageManager signer changed during mount" }
        check(packageState.basePath == expected.stockPath) { "PackageManager base path changed during mount" }
        check(packageState.topology == expected.topology) { "Package topology changed during mount" }
        check(packageState.enabled == expected.enabled) { "Package enabled state changed during mount" }
        check(packageState.launcherResolvable == expected.launcherResolvable) {
            "Package launcher resolution changed during mount"
        }
        namespaces.verify(expected)
        return packageState
    }

    suspend fun verifyUnmounted(packageName: String, targets: Set<String>) {
        check(findUrvMounts(packageName, targets).isEmpty()) { "URV mount is still active" }
    }

    private suspend fun inode(path: String): String? {
        val result = shell.runIsolatedBounded(
            "stat -c '%d:%i' ${shellQuote(path)} 2>/dev/null",
            METADATA_TIMEOUT_SECONDS,
            "root mount inode check"
        )
        return result.stdout.firstOrNull()?.trim()?.takeIf(String::isNotEmpty)
    }

    private suspend fun sha256(path: String): String? =
        shell.runIsolatedBounded(
            "sha256sum ${shellQuote(path)} 2>/dev/null | awk '{print ${'$'}1}'",
            HASH_TIMEOUT_SECONDS,
            "root mount hash verification"
        ).stdout.firstOrNull()?.trim()?.takeIf { it.length == 64 }

    private fun MountInfoEntry.referencesMountSource(path: String): Boolean {
        val aliases = buildSet {
            add(path)
            if (path.startsWith("/data/")) add(path.removePrefix("/data"))
        }
        return normalizedMountPath(root) in aliases || normalizedMountPath(source) in aliases
    }

    private companion object {
        const val MAX_NORMAL_UNMOUNTS = 8
        const val MAX_URV_MOUNT_LAYERS = 8
        const val UNMOUNT_TIMEOUT_SECONDS = 15L
        const val METADATA_TIMEOUT_SECONDS = 15L
        const val HASH_TIMEOUT_SECONDS = 60L

        fun isSafeApkPath(path: String): Boolean =
            path.startsWith('/') &&
                path.endsWith(".apk") &&
                path != "/.apk" &&
                !path.contains("/../") &&
                !path.contains("/./")

        fun normalizedMountPath(path: String): String = path.removeSuffix(" (deleted)")
    }
}
