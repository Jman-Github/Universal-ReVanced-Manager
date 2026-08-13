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
        var sourceInodes: Set<String>? = null
        val owned = linkedSetOf<MountInfoEntry>()
        mountTable.filter { isSafeApkPath(it.mountPoint) }
            .groupBy(MountInfoEntry::mountPoint)
            .forEach { (target, layers) ->
                val directlyOwned = layers.filter { entry ->
                    normalizedMountPath(entry.root) in sources ||
                        normalizedMountPath(entry.source) in sources
                }
                owned += directlyOwned

                // Current URV mounts expose their module path directly in mountinfo. Only
                // pay for inode probes when the visible layer cannot be identified by path,
                // which keeps ordinary mount/remount cleanup from issuing many redundant
                // stat calls across every APK mount on the device.
                val visibleLayer = layers.maxByOrNull(MountInfoEntry::mountId)
                if (visibleLayer != null && visibleLayer !in directlyOwned) {
                    val inodes = sourceInodes ?: sources.mapNotNull { path -> inode(path) }
                        .toSet()
                        .also { sourceInodes = it }
                    if (inode(target) in inodes) {
                        owned += visibleLayer
                    }
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
        var sourceInodes: Set<String>? = null
        suspend fun revalidateBeforeLazyUnmount(target: String): Boolean {
            val latestMountTable = mountTableReader.read()
            val allAtTarget = latestMountTable.filter { it.mountPoint == target }
            val urvAtTarget = findUrvMounts(packageName, latestMountTable)
                .filter { it.mountPoint == target }
            if (urvAtTarget.isEmpty()) return false
            if (allAtTarget.size != urvAtTarget.size) {
                val inodes = sourceInodes ?: knownSourceInodes(packageName)
                    .also { sourceInodes = it }
                check(visibleTargetIsUrv(target, inodes)) {
                    "Foreign mount layer shares URV target $target; refusing to lazily unmount another owner's layer"
                }
            }
            return true
        }
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
            var unmountAttempts = 0
            var lazyAttempts = 0
            while (true) {
                val mountTable = mountTableReader.read()
                val allAtTarget = mountTable.filter { it.mountPoint == target }
                val urvAtTarget = findUrvMounts(packageName, mountTable)
                    .filter { it.mountPoint == target }
                if (urvAtTarget.isEmpty()) break
                if (allAtTarget.size != urvAtTarget.size) {
                    val inodes = sourceInodes ?: knownSourceInodes(packageName)
                        .also { sourceInodes = it }
                    check(visibleTargetIsUrv(target, inodes)) {
                        "Foreign mount layer shares URV target $target; refusing to unmount another owner's layer"
                    }
                }
                check(unmountAttempts < MAX_UNMOUNT_ATTEMPTS) {
                    "Too many mount layers remained at $target"
                }
                val normal = shell.runIsolatedBounded(
                    "umount ${shellQuote(target)}",
                    UNMOUNT_TIMEOUT_SECONDS,
                    "root mount unmount"
                )
                if (!normal.isSuccess) {
                    if (!allowLazyRecovery) normal.requireSuccess("Unmount $target")

                    // A failed umount can race with namespace teardown. Re-read ownership
                    // before using lazy detach so a newly exposed foreign layer is never
                    // detached just because the previous visible layer belonged to URV.
                    if (!revalidateBeforeLazyUnmount(target)) break
                    check(lazyAttempts < MAX_LAZY_UNMOUNTS) { "Too many mount layers remained at $target" }
                    shell.runIsolatedBounded(
                        "umount -l ${shellQuote(target)}",
                        UNMOUNT_TIMEOUT_SECONDS,
                        "root mount lazy unmount"
                    ).requireSuccess("Lazy unmount $target")
                    if (target !in lazyUnmounts) lazyUnmounts += target
                    lazyAttempts++
                }
                unmountAttempts++
            }
        }
        val remaining = findUrvMounts(packageName, extraTargets)
        check(remaining.isEmpty()) { "URV mount remained after unmount: ${remaining.joinToString { it.mountPoint }}" }
        return lazyUnmounts.distinct()
    }

    override suspend fun verifyRootMounted(expected: RootCommittedState): RootPackageState =
        verifyMountedState(expected, verifyShadowHash = true)

    override suspend fun verifyMounted(expected: RootCommittedState): RootPackageState {
        val packageState = verifyMountedState(expected, verifyShadowHash = false)
        namespaces.verify(expected)
        if (expected.preserveStockAcrossBoot) {
            val stockShadowPath = requireNotNull(expected.stockShadowPath)
            check(sha256(stockShadowPath) == expected.stockShadowSha256) {
                "Stock shadow hash mismatch"
            }
        }
        return packageState
    }

    private suspend fun verifyMountedState(
        expected: RootCommittedState,
        verifyShadowHash: Boolean
    ): RootPackageState {
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
            if (verifyShadowHash) {
                check(sha256(stockShadowPath) == expected.stockShadowSha256) {
                    "Stock shadow hash mismatch"
                }
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
        const val MAX_UNMOUNT_ATTEMPTS = 16
        const val MAX_LAZY_UNMOUNTS = 16
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
