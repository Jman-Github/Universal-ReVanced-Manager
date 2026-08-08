package app.urv.manager.domain.installer.root

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RootMountPolicyTest {
    private val installed = RootPackageState(
        packageName = "com.example.app",
        userId = 0,
        installed = true,
        versionName = "2",
        versionCode = 2,
        signerSha256 = "signer",
        basePath = "/data/app/example/base.apk",
        baseSha256 = "stock",
        launcherResolvable = true
    )

    private fun artifact(
        version: Long = 2,
        signer: String = "signer",
        hash: String = "hash"
    ) = RootArtifactState(
        path = "/tmp/app.apk",
        packageName = "com.example.app",
        versionName = version.toString(),
        versionCode = version,
        signerSha256 = signer,
        sha256 = hash
    )

    @Test
    fun `same version bundle switch skips PackageInstaller transition`() {
        assertEquals(
            RootMountPolicy.StockTransition.NONE,
            RootMountPolicy.classifyStockTransition(installed, artifact())
        )
    }

    @Test
    fun `upgrade and downgrade classifications preserve replacement semantics`() {
        assertEquals(
            RootMountPolicy.StockTransition.UPGRADE,
            RootMountPolicy.classifyStockTransition(installed, artifact(version = 3))
        )
        assertEquals(
            RootMountPolicy.StockTransition.DOWNGRADE,
            RootMountPolicy.classifyStockTransition(installed, artifact(version = 1))
        )
        assertEquals(
            RootMountPolicy.StockTransition.UPGRADE,
            RootMountPolicy.classifyStockTransition(installed, artifact().copy(versionName = "2-rebuilt"))
        )
    }

    @Test
    fun `split and signer mismatches fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            RootMountPolicy.validateSafeMount(
                installed.packageName,
                installed.copy(splitPaths = listOf("/data/app/example/split.apk")),
                artifact(),
                listOf(artifact())
            )
        }
        assertFailsWith<IllegalArgumentException> {
            val same = artifact(hash = "same")
            RootMountPolicy.validateSafeMount(
                installed.packageName,
                installed,
                same,
                listOf(same)
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RootMountPolicy.validateSafeMount(
                installed.packageName,
                installed,
                artifact(),
                listOf(artifact(signer = "other"))
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RootMountPolicy.validateSafeMount(
                installed.packageName,
                installed,
                artifact().copy(topology = "SPLIT"),
                emptyList()
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RootMountPolicy.validateSafeMount(
                installed.packageName,
                installed,
                artifact(),
                listOf(artifact().copy(topology = "SPLIT"))
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RootMountPolicy.validateSafeMount(
                installed.packageName,
                installed,
                artifact(),
                listOf(artifact(), artifact())
            )
        }
    }

    @Test
    fun `external exact match remounts while mismatch and removal stay stock`() {
        val committed = RootCommittedState(
            transactionId = "tx",
            packageName = installed.packageName,
            userId = 0,
            versionName = installed.versionName,
            versionCode = installed.versionCode!!,
            signerSha256 = installed.signerSha256,
            stockPath = installed.basePath!!,
            stockSha256 = installed.baseSha256!!,
            patchedPath = "/data/adb/modules/example/example.apk",
            patchedSha256 = "patched",
            topology = "SINGLE",
            enabled = installed.enabled,
            launcherResolvable = installed.launcherResolvable,
            committedAtEpochMs = 1
        )
        assertEquals(RootMountPolicy.ReconcileDecision.REMOUNT, RootMountPolicy.reconcile(committed, installed))
        assertEquals(
            RootMountPolicy.ReconcileDecision.REMOUNT,
            RootMountPolicy.reconcile(committed, installed.copy(basePath = "/data/app/new/base.apk"))
        )
        assertEquals(
            RootMountPolicy.ReconcileDecision.REPATCH_REQUIRED,
            RootMountPolicy.reconcile(committed, installed.copy(versionCode = 3))
        )
        listOf(
            installed.copy(userId = 1),
            installed.copy(versionName = "different"),
            installed.copy(signerSha256 = "different"),
            installed.copy(basePath = null),
            installed.copy(baseSha256 = "different"),
            installed.copy(splitPaths = listOf("/data/app/example/split.apk")),
            installed.copy(sharedUserId = "shared"),
            installed.copy(enabled = false),
            installed.copy(launcherResolvable = false)
        ).forEach { mismatch ->
            assertEquals(
                RootMountPolicy.ReconcileDecision.REPATCH_REQUIRED,
                RootMountPolicy.reconcile(committed, mismatch)
            )
        }
        assertEquals(
            RootMountPolicy.ReconcileDecision.INACTIVE,
            RootMountPolicy.reconcile(committed, installed.copy(installed = false))
        )
        assertEquals(
            RootMountPolicy.ReconcileDecision.INACTIVE,
            RootMountPolicy.reconcile(committed.copy(active = false), installed)
        )
        assertEquals(
            RootMountPolicy.ReconcileDecision.REPATCH_REQUIRED,
            RootMountPolicy.reconcile(committed.copy(active = false, status = "REPATCH_REQUIRED"), installed)
        )
        assertEquals(
            RootMountPolicy.ReconcileDecision.REPAIR_REQUIRED,
            RootMountPolicy.reconcile(committed.copy(active = false, status = "REPAIR_REQUIRED"), installed)
        )
    }

    @Test
    fun `only persisted stock mutation phases trigger stock restoration`() {
        assertEquals(
            false,
            RootMountPolicy.interruptedJournalMayHaveChangedStock(
                RootMountJournal(
                    transactionId = "tx",
                    packageName = installed.packageName,
                    userId = 0,
                    operation = RootMountOperation.UNMOUNT,
                    phase = RootMountPhase.COMMITTING,
                    startedAtEpochMs = 1
                )
            )
        )
        assertEquals(
            true,
            RootMountPolicy.interruptedJournalMayHaveChangedStock(
                RootMountJournal(
                    transactionId = "tx",
                    packageName = installed.packageName,
                    userId = 0,
                    operation = RootMountOperation.REPLACE_STOCK_AND_MOUNT,
                    phase = RootMountPhase.INSTALLING_STOCK,
                    startedAtEpochMs = 1,
                    stockMutationStarted = true
                )
            )
        )
        assertEquals(
            false,
            RootMountPolicy.interruptedJournalMayHaveChangedModule(
                RootMountJournal(
                    transactionId = "tx",
                    packageName = installed.packageName,
                    userId = 0,
                    operation = RootMountOperation.UNMOUNT,
                    phase = RootMountPhase.COMMITTING,
                    startedAtEpochMs = 1,
                    status = "MODULE_DISABLE_PENDING"
                )
            )
        )
        assertEquals(
            true,
            RootMountPolicy.interruptedJournalMayHaveChangedModule(
                RootMountJournal(
                    transactionId = "tx",
                    packageName = installed.packageName,
                    userId = 0,
                    operation = RootMountOperation.UNMOUNT,
                    phase = RootMountPhase.COMMITTING,
                    startedAtEpochMs = 1,
                    status = "MODULE_REMOVAL_PENDING"
                )
            )
        )
        assertEquals(
            false,
            RootMountPolicy.interruptedJournalMayHaveChangedModule(
                RootMountJournal(
                    transactionId = "tx",
                    packageName = installed.packageName,
                    userId = 0,
                    operation = RootMountOperation.RECONCILE,
                    phase = RootMountPhase.MOUNTING,
                    startedAtEpochMs = 1
                )
            )
        )
        assertEquals(
            false,
            RootMountPolicy.interruptedJournalMayHaveChangedModule(
                RootMountJournal(
                    transactionId = "tx",
                    packageName = installed.packageName,
                    userId = 0,
                    operation = RootMountOperation.MOUNT_ONLY,
                    phase = RootMountPhase.SNAPSHOTTING,
                    startedAtEpochMs = 1
                )
            )
        )
        assertEquals(
            true,
            RootMountPolicy.interruptedJournalMayHaveChangedModule(
                RootMountJournal(
                    transactionId = "tx",
                    packageName = installed.packageName,
                    userId = 0,
                    operation = RootMountOperation.MOUNT_ONLY,
                    phase = RootMountPhase.STAGING_PATCHED_PAYLOAD,
                    startedAtEpochMs = 1
                )
            )
        )
    }

    @Test
    fun `a later result cannot mask an earlier shell failure`() {
        val first = RootCommandResult(1, emptyList(), listOf("chcon failed"))
        assertFailsWith<RootCommandException> { first.requireSuccess("Set context") }
    }
}
