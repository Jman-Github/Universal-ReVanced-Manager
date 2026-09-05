package app.urv.manager.domain.installer.root

object RootMountPolicy {
    enum class StockTransition { NONE, INSTALL, UPGRADE, DOWNGRADE }
    enum class ReconcileDecision { REMOUNT, REPATCH_REQUIRED, REPAIR_REQUIRED, INACTIVE }

    fun classifyStockTransition(
        installed: RootPackageState,
        requestedStock: RootArtifactState?
    ): StockTransition = when {
        requestedStock == null -> StockTransition.NONE
        !installed.installed -> StockTransition.INSTALL
        installed.versionCode == requestedStock.versionCode &&
            installed.versionName == requestedStock.versionName -> StockTransition.NONE
        requireNotNull(installed.versionCode) < requestedStock.versionCode -> StockTransition.UPGRADE
        requireNotNull(installed.versionCode) > requestedStock.versionCode -> StockTransition.DOWNGRADE
        else -> StockTransition.UPGRADE
    }

    fun validateSafeMount(
        packageName: String,
        installed: RootPackageState,
        patched: RootArtifactState?,
        stock: List<RootArtifactState>,
        expectedStockVersionCode: Long? = patched?.versionCode
    ) {
        require(stock.size <= 1) { "Safe root mount rejects split or mixed APK sets" }
        require(!installed.installed || installed.topology == "SINGLE") {
            "Safe root mount rejects split/resource-dependent installations"
        }
        if (installed.installed) {
            val installedVersionName = requireNotNull(installed.versionName?.takeUnless(String::isBlank)) {
                "Installed version name is unavailable"
            }
            require(installedVersionName.none { char -> char.isISOControl() }) {
                "Installed version name contains control text"
            }
            require(installed.versionCode != null) { "Installed version code is unavailable" }
            require(!installed.signerSha256.isNullOrBlank()) { "Installed signing certificate is unavailable" }
            require(!installed.basePath.isNullOrBlank()) { "Installed stock base path is unavailable" }
            require(!installed.baseSha256.isNullOrBlank()) { "Installed APK hash is unavailable" }
        }
        require(installed.sharedUserId == null) {
            "Shared-UID process ownership cannot be isolated safely"
        }
        patched?.let {
            require(it.packageName == packageName) { "Patched APK package mismatch" }
            require(it.topology == "SINGLE") { "Safe root mount requires one complete patched APK" }
            val patchedVersionName = requireNotNull(it.versionName?.takeUnless(String::isBlank)) {
                "Patched APK version name is unavailable"
            }
            require(patchedVersionName.none { char -> char.isISOControl() }) {
                "Patched APK version name contains control text"
            }
            require(!it.signerSha256.isNullOrBlank()) { "Patched APK signing certificate is unavailable" }
        }
        stock.singleOrNull()?.let { artifact ->
            require(artifact.packageName == packageName) { "Stock APK package mismatch" }
            require(artifact.topology == "SINGLE") { "Safe root mount requires one complete stock APK" }
            val stockVersionName = requireNotNull(artifact.versionName?.takeUnless(String::isBlank)) {
                "Stock APK version name is unavailable"
            }
            require(stockVersionName.none { char -> char.isISOControl() }) {
                "Stock APK version name contains control text"
            }
            require(!artifact.signerSha256.isNullOrBlank()) { "Stock APK signing certificate is unavailable" }
            require(expectedStockVersionCode == null || artifact.versionCode == expectedStockVersionCode) {
                "Stock APK version code does not match the patched APK source"
            }
            require(patched == null || artifact.versionName == patched.versionName) {
                "Stock and patched version names differ"
            }
            require(patched == null || artifact.sha256 != patched.sha256) {
                "Raw stock and patched payload must be separate verified APKs"
            }
            if (installed.installed && installed.signerSha256 != null) {
                val exactInstalledStock =
                    installed.baseSha256 != null &&
                        artifact.sha256 == installed.baseSha256 &&
                        artifact.versionCode == installed.versionCode &&
                        artifact.versionName == installed.versionName
                require(exactInstalledStock || artifact.signerSha256 == installed.signerSha256) {
                    "Stock signing certificate mismatch"
                }
            }
        }
    }

    fun reconcile(committed: RootCommittedState, current: RootPackageState): ReconcileDecision {
        if (!current.installed) return ReconcileDecision.INACTIVE
        if (committed.status == "REPAIR_REQUIRED") return ReconcileDecision.REPAIR_REQUIRED
        if (committed.status == "REPATCH_REQUIRED") return ReconcileDecision.REPATCH_REQUIRED
        if (!committed.active) return ReconcileDecision.INACTIVE
        val exact = current.packageName == committed.packageName &&
            current.userId == committed.userId &&
            current.versionName == committed.versionName &&
            current.versionCode == committed.versionCode &&
            current.signerSha256 == committed.signerSha256 &&
            current.sharedUserId == null &&
            !current.basePath.isNullOrBlank() &&
            current.baseSha256 == committed.stockSha256 &&
            current.topology == committed.topology &&
            current.enabled == committed.enabled &&
            current.launcherResolvable == committed.launcherResolvable
        return if (exact) ReconcileDecision.REMOUNT else ReconcileDecision.REPATCH_REQUIRED
    }

    fun interruptedJournalMayHaveChangedStock(journal: RootMountJournal): Boolean =
        journal.stockMutationStarted || journal.registrationGap

    fun interruptedJournalMayHaveChangedMounts(journal: RootMountJournal): Boolean {
        // A persisted rollback journal is itself a mutation boundary: rollback can remove
        // mounts even when the interrupted forward transaction had not reached mount cleanup.
        if (journal.phase == RootMountPhase.ROLLING_BACK) return true
        journal.mountMutationStarted?.let { return it }
        val forwardPhase = journal.rollbackFromPhase ?: journal.phase
        return forwardPhase >= RootMountPhase.REMOVING_OLD_MOUNTS
    }

    fun interruptedJournalMayHaveChangedModule(journal: RootMountJournal): Boolean {
        journal.moduleRestoreRequired?.let { return it }
        if (
            journal.status == "MODULE_STATE_RETARGET_PENDING" &&
            journal.operation in setOf(RootMountOperation.MOUNT_ONLY, RootMountOperation.RECONCILE)
        ) {
            // Backward compatibility for journals written before moduleRestoreRequired existed.
            return false
        }
        journal.moduleMutationStarted?.let { return it }
        val forwardPhase = journal.rollbackFromPhase ?: journal.phase
        return when (journal.operation) {
            RootMountOperation.SWITCH_PATCHED_BUILD,
            RootMountOperation.REPLACE_STOCK_AND_MOUNT ->
                forwardPhase >= RootMountPhase.STAGING_PATCHED_PAYLOAD

            RootMountOperation.UNMOUNT ->
                journal.status in setOf(
                    "LEGACY_UNMOUNT_STAGE_PENDING",
                    "LEGACY_UNMOUNT_COMMIT_PENDING",
                    "MODULE_REMOVAL_PENDING",
                    "MODULE_REMOVED"
                )

            RootMountOperation.MOUNT_ONLY ->
                !journal.reusableCommittedModule &&
                    forwardPhase >= RootMountPhase.STAGING_PATCHED_PAYLOAD

            RootMountOperation.RECOVER,
            RootMountOperation.RECONCILE -> false
        }
    }
}
