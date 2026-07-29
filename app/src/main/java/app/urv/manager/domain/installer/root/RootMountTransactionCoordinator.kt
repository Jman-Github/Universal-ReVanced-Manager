package app.urv.manager.domain.installer.root

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RootMountTransactionCoordinator(
    private val shell: RootShellGateway,
    private val packageStateReader: PackageStateReader,
    private val mountVerifier: RootMountVerification,
    private val transactionStore: RootTransactionStorage,
    private val moduleStore: RootModuleStorage,
    private val packageInstaller: RootPackageInstallation,
    private val packageLock: RootPackageLocking,
    private val reconciliationScheduler: RootReconciliationScheduling
) {
    suspend fun execute(
        request: RootMountRequest,
        onPhase: (RootMountPhase) -> Unit = {}
    ): RootMountResult = withContext(Dispatchers.IO) {
        mutexFor(request.packageName).withLock {
            executeLocked(request, onPhase)
        }
    }

    suspend fun recoverIncompleteTransactions(userId: Int): Map<String, RootMountResult> {
        require(userId >= 0) { "Invalid Android user" }
        transactionStore.initialize()
        val results = linkedMapOf<String, RootMountResult>()
        for (packageName in transactionStore.listIncompletePackages()) {
            try {
                val interrupted = transactionStore.readActive(packageName)
                val committed = transactionStore.readCommitted(packageName)
                val interruptedUserId = interrupted
                    ?.takeIf { it.packageName == packageName && it.userId >= 0 }
                    ?.userId
                val committedUserId = committed
                    ?.takeIf { isValidCommittedState(packageName, it) }
                    ?.userId
                val recoveryUserId = interruptedUserId ?: committedUserId
                if (recoveryUserId != null && recoveryUserId != userId) {
                    continue
                }
                if (recoveryUserId == null) {
                    val diagnosticId = "unknown-user-${UUID.randomUUID().toString().take(8)}"
                    transactionStore.appendDiagnostic(
                        packageName,
                        diagnosticId,
                        "Incomplete transaction has no trustworthy Android user; automatic recovery was refused"
                    )
                    results[packageName] = RootMountResult.Failure(
                        RootMountPhase.PREPARING,
                        RootRecoveryState.NONE,
                        diagnosticId,
                        "Android user identity is unavailable; use Repair root mount from the affected user"
                    )
                    continue
                }
                val request = RootMountRequest(
                    packageName = packageName,
                    userId = userId,
                    operation = RootMountOperation.RECOVER
                )
                results[packageName] = execute(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val diagnosticId = "recovery-${UUID.randomUUID().toString().take(8)}"
                runCatching {
                    transactionStore.appendDiagnostic(
                        packageName,
                        diagnosticId,
                        "Incomplete transaction scan failed: ${failure.stackTraceToString()}"
                    )
                }
                results[packageName] = RootMountResult.Failure(
                    RootMountPhase.PREPARING,
                    RootRecoveryState.NONE,
                    diagnosticId,
                    failure.message ?: failure.javaClass.simpleName
                )
            }
        }
        return results
    }
    suspend fun reconcileCommittedTransactions(
        userId: Int,
        requestedPackageName: String? = null
    ): Map<String, RootMountResult> {
        require(userId >= 0) { "Invalid Android user" }
        transactionStore.initialize()
        val results = linkedMapOf<String, RootMountResult>()
        val packages = requestedPackageName?.let { setOf(it) } ?: buildSet {
            addAll(transactionStore.listCommittedPackages())
            addAll(reconciliationScheduler.trackedPackages(userId))
        }
        for (packageName in packages) {
            try {
                val (handled, quickResult) = mutexFor(packageName).withLock {
                    val scanId = "startup-${UUID.randomUUID().toString().take(8)}"
                    val activePhase = runCatching {
                        transactionStore.readActive(packageName)?.phase
                    }.getOrNull()
                    val rootLock = try {
                        packageLock.acquire(packageName, scanId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        return@withLock true to RootMountResult.Busy(
                            activePhase,
                            "Root package lock is temporarily unavailable: " +
                                (failure.message ?: failure.javaClass.simpleName)
                        )
                    } ?: return@withLock true to RootMountResult.Busy(activePhase)
                    try {
                        if (transactionStore.activeExists(packageName)) {
                            val active = transactionStore.readActive(packageName)
                            val activeUserId = active
                                ?.takeIf { it.packageName == packageName && it.userId >= 0 }
                                ?.userId
                            val committedUserId = if (activeUserId == null) {
                                transactionStore.readCommitted(packageName)
                                    ?.takeIf { isValidCommittedState(packageName, it) }
                                    ?.userId
                            } else {
                                null
                            }
                            val recoveryUserId = activeUserId ?: committedUserId
                            if (recoveryUserId == null) {
                                val diagnosticId = "unknown-user-${scanId.take(8)}"
                                transactionStore.appendDiagnostic(
                                    packageName,
                                    diagnosticId,
                                    "Unreadable active transaction has no trustworthy Android user; " +
                                        "committed reconciliation was refused"
                                )
                                return@withLock true to RootMountResult.Failure(
                                    RootMountPhase.PREPARING,
                                    RootRecoveryState.NONE,
                                    diagnosticId,
                                    "Android user identity is unavailable; use Repair root mount from the affected user"
                                )
                            }
                            if (recoveryUserId != userId) {
                                return@withLock true to null
                            }
                            return@withLock false to null
                        }
                        val committedExists = transactionStore.committedExists(packageName)
                        val committed = transactionStore.readCommitted(packageName)
                        if (committed == null) {
                            return@withLock true to if (committedExists) {
                                recoverCorruptCommittedState(
                                    packageName,
                                    userId,
                                    scanId,
                                    "Committed root mount state is unreadable"
                                )
                            } else {
                                stopReconciliation(packageName, userId, scanId)
                                RootMountResult.Success(scanId)
                            }
                        }
                        if (!isValidCommittedState(packageName, committed)) {
                            return@withLock true to recoverCorruptCommittedState(
                                packageName,
                                userId,
                                scanId,
                                "Committed root mount identity is invalid"
                            )
                        }
                        if (!committed.active || committed.userId != userId) {
                            if (!committed.active && committed.userId == userId) {
                                stopReconciliation(packageName, userId, scanId)
                            }
                            return@withLock true to null
                        }
                        runCatching { reconciliationScheduler.ensureScheduled(userId, packageName) }
                        val installedOnlyForRequestedUser = runCatching {
                            packageStateReader.installedUserIds(packageName)
                        }.getOrNull() == setOf(userId)
                        if (installedOnlyForRequestedUser &&
                            runCatching { mountVerifier.verifyMounted(committed) }.isSuccess
                        ) {
                            moduleStore.enable(packageName)
                            true to RootMountResult.Success(committed.transactionId)
                        } else {
                            false to null
                        }
                    } finally {
                        withContext(NonCancellable) {
                            runCatching { packageLock.release(rootLock) }.onFailure { releaseFailure ->
                                runCatching {
                                    transactionStore.appendDiagnostic(
                                        packageName,
                                        "lock-${scanId.take(8)}",
                                        "Failed to release package lock cleanly: ${releaseFailure.message}"
                                    )
                                }
                            }
                        }
                    }
                }
                when {
                    quickResult != null -> results[packageName] = quickResult
                    !handled -> results[packageName] = execute(
                        RootMountRequest(packageName, userId, RootMountOperation.RECONCILE)
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val diagnosticId = "startup-${UUID.randomUUID().toString().take(8)}"
                runCatching {
                    transactionStore.appendDiagnostic(
                        packageName,
                        diagnosticId,
                        "Startup reconciliation failed: ${failure.stackTraceToString()}"
                    )
                }
                results[packageName] = RootMountResult.Failure(
                    RootMountPhase.PREPARING,
                    RootRecoveryState.NONE,
                    diagnosticId,
                    failure.message ?: failure.javaClass.simpleName
                )
            }
        }
        return results
    }
    suspend fun exportDiagnostics(packageName: String): String =
        withContext(Dispatchers.IO) {
            transactionStore.exportDiagnostics(packageName)
        }

    private suspend fun executeLocked(
        request: RootMountRequest,
        onPhase: (RootMountPhase) -> Unit
    ): RootMountResult {
        reportProgress(onPhase, RootMountPhase.PREPARING)
        transactionStore.initialize()
        val transactionId = UUID.randomUUID().toString()
        val activePhase = runCatching {
            transactionStore.readActive(request.packageName)?.phase
        }.getOrNull()
        val rootLock = try {
            packageLock.acquire(request.packageName, transactionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return RootMountResult.Busy(
                activePhase,
                "Root package lock is temporarily unavailable: " +
                    (failure.message ?: failure.javaClass.simpleName)
            )
        } ?: return RootMountResult.Busy(activePhase)
        var journal: RootMountJournal? = null
        var currentPhase = RootMountPhase.PREPARING
        var stockChanged = false
        var moduleChanged = false
        var stockBackup: RootBackupArtifact? = null
        fun progress(phase: RootMountPhase) {
            currentPhase = phase
            reportProgress(onPhase, phase)
        }
        suspend fun persist(transform: (RootMountJournal) -> RootMountJournal = { it }) {
            val updated = transform(requireNotNull(journal)).copy(phase = currentPhase)
            journal = updated
            transactionStore.writeActive(updated)
        }

        try {
            val storedCommitted = transactionStore.readCommitted(request.packageName)
            val committedFileExists = transactionStore.committedExists(request.packageName)
            val validStoredCommitted = storedCommitted?.takeIf {
                isValidCommittedState(request.packageName, it)
            }
            val recoveredModuleState = if (
                validStoredCommitted == null &&
                !committedFileExists &&
                request.operation in MANUAL_STATE_RECOVERY_OPERATIONS
            ) {
                moduleStore.readCommittedState(request.packageName)?.takeIf {
                    isValidCommittedState(request.packageName, it)
                }
            } else {
                null
            }
            if (recoveredModuleState != null) {
                transactionStore.writeCommitted(recoveredModuleState)
                transactionStore.appendDiagnostic(
                    request.packageName,
                    "module-state-${transactionId.take(8)}",
                    "Recovered missing transaction state from the verified root module state"
                )
            }
            val previousCommitted = validStoredCommitted ?: recoveredModuleState
            val corruptCommitted = committedFileExists && previousCommitted == null
            if (previousCommitted != null && previousCommitted.userId != request.userId) {
                val diagnosticId = "user-${transactionId.take(8)}"
                transactionStore.appendDiagnostic(
                    request.packageName,
                    diagnosticId,
                    "Committed root mount belongs to Android user ${previousCommitted.userId}; " +
                        "request for user ${request.userId} was refused"
                )
                return RootMountResult.Failure(
                    RootMountPhase.PREPARING,
                    RootRecoveryState.NONE,
                    diagnosticId,
                    "This root mount is committed for a different Android user"
                )
            }
            val interrupted = transactionStore.readActive(request.packageName)
            val activeExists = transactionStore.activeExists(request.packageName)
            if (activeExists && interrupted == null) {
                return recoverCorruptJournal(request, previousCommitted, transactionId)
            }
            if (interrupted != null) {
                if (interrupted.packageName != request.packageName || interrupted.userId < 0) {
                    return recoverCorruptJournal(request, previousCommitted, transactionId)
                }
                if (interrupted.userId != request.userId) {
                    val diagnosticId = "user-${transactionId.take(8)}"
                    transactionStore.appendDiagnostic(
                        request.packageName,
                        diagnosticId,
                        "Interrupted root mount belongs to Android user ${interrupted.userId}; " +
                            "request for user ${request.userId} cannot recover it"
                    )
                    return RootMountResult.Failure(
                        interrupted.phase,
                        RootRecoveryState.NONE,
                        diagnosticId,
                        "The unfinished root mount belongs to Android user ${interrupted.userId}, " +
                            "not the current user ${request.userId}"
                    )
                }
                journal = interrupted.copy(transactionId = transactionId, phase = RootMountPhase.ROLLING_BACK)
                progress(RootMountPhase.ROLLING_BACK)
                persist()
                return rollback(
                    journal = requireNotNull(journal),
                    stockChanged = RootMountPolicy.interruptedJournalMayHaveChangedStock(interrupted),
                    moduleChanged = RootMountPolicy.interruptedJournalMayHaveChangedModule(interrupted),
                    stockBackup = interrupted.initialPackageState?.baseSha256?.let { hash ->
                        RootBackupArtifact(
                            "${RootPaths.backup(request.packageName)}/package/0.apk",
                            hash
                        )
                    },
                    diagnosticId = "recovery-${transactionId.take(8)}",
                    reason = "Recovered interrupted transaction"
                )
            }
            if (corruptCommitted) {
                val recovery = recoverCorruptCommittedState(
                    request.packageName,
                    request.userId,
                    transactionId,
                    if (storedCommitted == null) {
                        "Committed root mount state is unreadable"
                    } else {
                        "Committed root mount identity is invalid"
                    }
                )
                val canRebuildSavedPayload =
                    request.operation == RootMountOperation.MOUNT_ONLY &&
                        request.patchedApk != null &&
                        request.stockApks.size == 1
                if (!canRebuildSavedPayload ||
                    recovery !is RootMountResult.RecoveredToStock &&
                    recovery !is RootMountResult.Success
                ) {
                    return recovery
                }
            }
            if (request.operation == RootMountOperation.RECOVER) {
                if (previousCommitted?.active != true) {
                    stopReconciliation(request.packageName, request.userId, transactionId)
                }
                return RootMountResult.Success(transactionId)
            }
            if (request.operation == RootMountOperation.RECONCILE && previousCommitted == null) {
                stopReconciliation(request.packageName, request.userId, transactionId)
                return RootMountResult.Success(transactionId)
            }

            val initialMountedState = packageStateReader.read(request.packageName, request.userId)
            if (request.operation in MOUNTING_OPERATIONS) {
                requireExclusivePackageUser(request.packageName, request.userId)
            }
            val patchedArtifact = request.patchedApk?.let(packageStateReader::inspect)
            val stockArtifacts = request.stockApks.map(packageStateReader::inspect)
            val preflightMounts = mountVerifier.findUrvMounts(
                request.packageName,
                setOfNotNull(initialMountedState.basePath, previousCommitted?.stockPath)
            )
            val shouldInspectLegacyPayload = previousCommitted == null && when (request.operation) {
                RootMountOperation.UNMOUNT -> preflightMounts.isNotEmpty()
                RootMountOperation.SWITCH_PATCHED_BUILD ->
                    stockArtifacts.isEmpty() && patchedArtifact != null
                else -> false
            }
            val legacyPayload = if (shouldInspectLegacyPayload) {
                moduleStore.readLegacyPayload(request.packageName)
                    ?.takeIf { it.sha256 == initialMountedState.baseSha256 }
            } else {
                null
            }
            val legacyMigration = legacyPayload != null
            val journalPatchedArtifact = patchedArtifact ?: legacyPayload?.let { legacy ->
                RootArtifactState(
                    path = legacy.path,
                    packageName = request.packageName,
                    versionName = initialMountedState.versionName,
                    versionCode = requireNotNull(initialMountedState.versionCode) {
                        "Legacy mounted payload version code is unavailable"
                    },
                    signerSha256 = initialMountedState.signerSha256,
                    sha256 = legacy.sha256,
                    topology = initialMountedState.topology
                )
            }
            preflight(
                request,
                initialMountedState,
                patchedArtifact,
                stockArtifacts,
                previousCommitted,
                legacyMigration
            )
            if (request.operation in MOUNTING_OPERATIONS) {
                val rollbackPaths = if (initialMountedState.installed) {
                    listOfNotNull(initialMountedState.basePath) + initialMountedState.splitPaths
                } else {
                    emptyList()
                }
                val incomingBytes = request.stockApks.sumOf { it.length() } +
                    (request.patchedApk?.length() ?: 0L)
                moduleStore.ensureRollbackSpace(request.packageName, rollbackPaths, incomingBytes)
            }

            reconciliationScheduler.ensureScheduled(request.userId, request.packageName)

            journal = RootMountJournal(
                transactionId = transactionId,
                packageName = request.packageName,
                userId = request.userId,
                operation = request.operation,
                phase = currentPhase,
                startedAtEpochMs = System.currentTimeMillis(),
                initialPackageState = initialMountedState,
                patchedArtifact = journalPatchedArtifact,
                stockArtifact = stockArtifacts.singleOrNull(),
                previousCommitted = previousCommitted,
                candidateMountTargets = preflightMounts.map { it.mountPoint }.distinct(),
                status = if (legacyMigration) "LEGACY_MIGRATION" else null
            )
            persist()

            progress(RootMountPhase.STOPPING_APP)
            persist()
            stopAndWait(request.packageName, request.userId)

            progress(RootMountPhase.REMOVING_OLD_MOUNTS)
            persist()
            val knownTargets = setOfNotNull(
                initialMountedState.basePath,
                previousCommitted?.stockPath,
                interrupted?.initialPackageState?.basePath,
                interrupted?.expectedPackageState?.basePath
            ) + requireNotNull(journal).candidateMountTargets
            val lazyUnmounts = mountVerifier.removeAllUrvMounts(
                request.packageName,
                knownTargets,
                allowLazyRecovery = request.operation == RootMountOperation.UNMOUNT
            )
            if (lazyUnmounts.isNotEmpty()) {
                transactionStore.appendDiagnostic(
                    request.packageName,
                    "unmount-${transactionId.take(8)}",
                    "Unmount used lazy detach after process quiescence: ${lazyUnmounts.joinToString()}"
                )
            }
            mountVerifier.verifyTargetsClear(knownTargets)

            if (request.operation == RootMountOperation.UNMOUNT) {
                val rawStock = packageStateReader.read(request.packageName, request.userId)
                if (rawStock.installed) {
                    previousCommitted?.let {
                        checkCommittedIdentity(it, rawStock)
                        check(rawStock.basePath?.isSafeAbsoluteApkPath() == true) {
                            "Unmounted stock base path is unsafe"
                        }
                    } ?: check(isStructurallyVerifiedStock(rawStock)) {
                        "Raw stock package state could not be verified after unmount"
                    }
                } else {
                    check(request.removeModuleAfterUnmount) {
                        "Package is not installed"
                    }
                    check(rawStock.packageName == request.packageName) { "Package name mismatch" }
                    check(rawStock.userId == request.userId) { "Android user mismatch" }
                }
                check(packageStateReader.waitUntilStopped(request.packageName, 1_000)) {
                    "Target app restarted while verifying the unmounted stock package"
                }
                var migratedLegacyState: RootCommittedState? = null
                if (!request.removeModuleAfterUnmount && legacyPayload != null) {
                    check(rawStock.installed) { "Legacy mount migration requires an installed stock package" }
                    val legacyArtifact = requireNotNull(journalPatchedArtifact)
                    check(rawStock.versionName == legacyArtifact.versionName) {
                        "Legacy mounted payload and stock version names differ"
                    }
                    check(rawStock.versionCode == legacyArtifact.versionCode) {
                        "Legacy mounted payload and stock version codes differ"
                    }
                    check(rawStock.signerSha256 == legacyArtifact.signerSha256) {
                        "Legacy mounted payload and stock signing identities differ"
                    }
                    check(rawStock.baseSha256 != legacyPayload.sha256) {
                        "Legacy mounted payload is byte-identical to raw stock"
                    }

                    progress(RootMountPhase.SNAPSHOTTING)
                    persist { it.copy(expectedPackageState = rawStock, status = "LEGACY_UNMOUNT_SNAPSHOT_PENDING") }
                    val moduleBackupHash = moduleStore.snapshot(request.packageName, legacyPayload)
                    check(moduleBackupHash == legacyPayload.sha256) {
                        "Legacy root module could not be snapshotted and verified"
                    }

                    progress(RootMountPhase.STAGING_PATCHED_PAYLOAD)
                    persist { it.copy(status = "LEGACY_UNMOUNT_STAGE_PENDING") }
                    moduleChanged = true
                    val patchedPath = moduleStore.stageAndActivate(
                        transactionId = transactionId,
                        packageName = request.packageName,
                        label = request.label.ifBlank { request.packageName },
                        patchedApk = File(legacyPayload.path),
                        compatible = rawStock,
                        patchedHash = legacyPayload.sha256
                    )
                    migratedLegacyState = RootCommittedState(
                        transactionId = transactionId,
                        packageName = request.packageName,
                        userId = request.userId,
                        versionName = requireNotNull(rawStock.versionName),
                        versionCode = requireNotNull(rawStock.versionCode),
                        signerSha256 = rawStock.signerSha256,
                        stockPath = requireNotNull(rawStock.basePath),
                        stockSha256 = requireNotNull(rawStock.baseSha256),
                        patchedPath = patchedPath,
                        patchedSha256 = legacyPayload.sha256,
                        stockShadowPath = RootPaths.moduleStockApk(request.packageName),
                        stockShadowSha256 = requireNotNull(rawStock.baseSha256),
                        preserveStockAcrossBoot = true,
                        topology = rawStock.topology,
                        enabled = rawStock.enabled,
                        launcherResolvable = rawStock.launcherResolvable,
                        active = false,
                        status = "STOCK",
                        committedAtEpochMs = System.currentTimeMillis()
                    )
                } else if (request.removeModuleAfterUnmount) {
                    progress(RootMountPhase.SNAPSHOTTING)
                    persist()
                    val moduleBackupHash = moduleStore.snapshot(request.packageName)
                    previousCommitted?.let { committed ->
                        check(moduleBackupHash == committed.patchedSha256) {
                            "Root module removal backup could not be verified"
                        }
                    }
                    moduleChanged = true
                }
                progress(RootMountPhase.COMMITTING)
                persist {
                    it.copy(
                        status = when {
                            request.removeModuleAfterUnmount -> "MODULE_REMOVAL_PENDING"
                            migratedLegacyState != null -> "LEGACY_UNMOUNT_COMMIT_PENDING"
                            else -> "MODULE_DISABLE_PENDING"
                        }
                    )
                }
                moduleStore.disable(request.packageName)
                if (request.removeModuleAfterUnmount) {
                    moduleStore.removeActive(request.packageName)
                    persist { it.copy(status = "MODULE_REMOVED") }
                    moduleStore.commitSnapshot(request.packageName)
                } else if (migratedLegacyState != null) {
                    moduleStore.commitSnapshot(request.packageName)
                }
                val inactive = if (request.removeModuleAfterUnmount) {
                    null
                } else {
                    migratedLegacyState ?: previousCommitted?.copy(
                        active = false,
                        status = "STOCK",
                        committedAtEpochMs = System.currentTimeMillis()
                    )
                }
                withContext(NonCancellable) {
                    if (inactive == null) transactionStore.clearCommitted(request.packageName)
                    transactionStore.complete(requireNotNull(journal), inactive)
                    if (request.removeModuleAfterUnmount) {
                        val cleanupFailure = runCatching {
                            moduleStore.purgeBackups(request.packageName)
                        }.exceptionOrNull()
                        if (cleanupFailure != null) {
                            runCatching {
                                transactionStore.appendDiagnostic(
                                    request.packageName,
                                    "cleanup-${transactionId.take(8)}",
                                    "Permanent module removal committed, but backup cleanup failed: " +
                                        (cleanupFailure.message ?: cleanupFailure::class.java.simpleName)
                                )
                            }
                        }
                    }
                    stopReconciliation(request.packageName, request.userId, transactionId)
                }
                return RootMountResult.Success(transactionId)
            }

            val initial = packageStateReader.read(request.packageName, request.userId)
            if (request.operation == RootMountOperation.RECONCILE) {
                return reconcile(request, requireNotNull(previousCommitted), initial, transactionId, onPhase, requireNotNull(journal))
            }

            progress(RootMountPhase.SNAPSHOTTING)
            persist { it.copy(initialPackageState = initial) }
            val moduleBackupHash = moduleStore.snapshot(request.packageName, legacyPayload)
            val rebuildMountOnlyModule =
                request.operation == RootMountOperation.MOUNT_ONLY &&
                    patchedArtifact != null &&
                    (previousCommitted == null ||
                        moduleBackupHash != previousCommitted.patchedSha256)
            if (rebuildMountOnlyModule && previousCommitted != null) {
                preflight(
                    request,
                    initial,
                    patchedArtifact,
                    stockArtifacts,
                    committed = null,
                    legacyMigration = false
                )
            }
            previousCommitted?.let { committed ->
                check(moduleBackupHash == committed.patchedSha256 || rebuildMountOnlyModule) {
                    "Previous committed module could not be snapshotted and verified"
                }
            }
            legacyPayload?.let { legacy ->
                check(moduleBackupHash == legacy.sha256) {
                    "Legacy root module could not be snapshotted and verified"
                }
            }
            if (initial.installed) {
                val rawPaths = listOfNotNull(initial.basePath) + initial.splitPaths
                stockBackup = moduleStore.snapshotStock(request.packageName, rawPaths).singleOrNull()
                check(stockBackup?.sha256 == initial.baseSha256) {
                    "Raw stock rollback snapshot hash mismatch"
                }
            }

            val useRequestedPayload =
                request.operation != RootMountOperation.MOUNT_ONLY || rebuildMountOnlyModule
            val stock = stockArtifacts.singleOrNull().takeIf { useRequestedPayload }
            val effectivePatchedArtifact = patchedArtifact.takeIf { useRequestedPayload }
            val stockTransition = RootMountPolicy.classifyStockTransition(initial, stock)
            if (stockTransition == RootMountPolicy.StockTransition.NONE && stock != null) {
                check(initial.baseSha256 == stock.sha256) {
                    "Same-version stock input does not match the unmounted installed stock APK"
                }
            }
            if (request.operation == RootMountOperation.SWITCH_PATCHED_BUILD && stock == null) {
                if (legacyMigration) {
                    check(initial.versionName == patchedArtifact?.versionName &&
                        initial.versionCode == patchedArtifact?.versionCode
                    ) { "Legacy mount and raw stock versions differ" }
                } else {
                    checkCommittedCompatibility(
                        requireNotNull(previousCommitted) {
                            "No committed stock identity is available for bundle switching"
                        },
                        initial
                    )
                }
            }
            val stockNeedsChange = stockTransition != RootMountPolicy.StockTransition.NONE

            var stableStock = initial
            if (stockNeedsChange) {
                val requiredStock = requireNotNull(stock) {
                    "A complete stock APK is required for this package transition"
                }
                val stockFile = request.stockApks.singleOrNull()
                    ?: throw IllegalArgumentException("A complete stock APK is required for this package transition")
                progress(RootMountPhase.INSTALLING_STOCK)
                persist { it.copy(stockMutationStarted = true) }
                val downgrade = stockTransition == RootMountPolicy.StockTransition.DOWNGRADE
                val replacement = packageInstaller.replace(
                    listOf(stockFile),
                    userId = request.userId,
                    allowDowngrade = downgrade
                )
                var replacementProven = replacement is RootPackageReplaceResult.Success
                if (!replacementProven) {
                    val afterRejectedReplacement = packageStateReader.read(request.packageName, request.userId)
                    stockChanged = packageRegistrationChanged(initial, afterRejectedReplacement)
                    if (matchesRequestedStock(afterRejectedReplacement, requiredStock)) {
                        val expectedAfterAmbiguousCommit = afterRejectedReplacement.copy(
                            installed = true,
                            versionName = requiredStock.versionName,
                            versionCode = requiredStock.versionCode,
                            signerSha256 = requiredStock.signerSha256,
                            splitPaths = emptyList(),
                            baseSha256 = requiredStock.sha256
                        )
                        val stableAfterAmbiguousCommit = runCatching {
                            packageStateReader.waitForStable(expectedAfterAmbiguousCommit)
                        }.getOrNull()
                        replacementProven = stableAfterAmbiguousCommit?.let {
                            matchesRequestedStock(it, requiredStock)
                        } == true
                    }
                }
                if (!replacementProven && downgrade) {
                    if (replacement !is RootPackageReplaceResult.DowngradeRejected) {
                        replacement.getOrThrow()
                        error("Package replacement failed without an error")
                    }
                    if (!request.downgradeFallbackConfirmed) {
                        val diagnosticId = "downgrade-${transactionId.take(8)}"
                        transactionStore.appendDiagnostic(
                            request.packageName,
                            diagnosticId,
                            replacement.cause.message ?: "In-place downgrade rejected"
                        )
                        val recovery = rollback(
                            requireNotNull(journal),
                            stockChanged = stockChanged,
                            moduleChanged = false,
                            stockBackup = stockBackup,
                            diagnosticId = diagnosticId,
                            reason = "In-place downgrade rejected before registration changed"
                        )
                        if (recovery is RootMountResult.Failure) return recovery
                        return RootMountResult.RequiresDowngradeConfirmation(
                            "Android rejected the in-place downgrade. Confirm keep-data uninstall/reinstall to continue."
                        )
                    }
                    check(!initial.systemApp) {
                        "Automatic per-user removal is disabled for system-app downgrade recovery"
                    }
                    persist { it.copy(registrationGap = true) }
                    stockChanged = true
                    packageInstaller.uninstallKeepData(request.packageName, request.userId)
                    packageInstaller.replace(
                        listOf(stockFile),
                        userId = request.userId,
                        allowDowngrade = false
                    ).getOrThrow()
                } else {
                    if (!replacementProven) replacement.getOrThrow()
                    stockChanged = stockChanged || replacement is RootPackageReplaceResult.Success
                }

                progress(RootMountPhase.WAITING_FOR_PACKAGE_MANAGER)
                persist()
                waitForPackageManagerIdle()
                val observed = packageStateReader.read(request.packageName, request.userId)
                val expected = observed.copy(
                    installed = true,
                    versionName = requiredStock.versionName,
                    versionCode = requiredStock.versionCode,
                    signerSha256 = requiredStock.signerSha256,
                    splitPaths = emptyList(),
                    baseSha256 = requiredStock.sha256,
                    enabled = if (initial.installed) initial.enabled else observed.enabled,
                    launcherResolvable = if (initial.installed) {
                        initial.launcherResolvable
                    } else {
                        observed.launcherResolvable
                    }
                )
                stableStock = packageStateReader.waitForStable(expected)
                check(stableStock.baseSha256 == requiredStock.sha256) {
                    "Installed stock APK hash does not match the verified input"
                }
            }

            check(stableStock.installed) { "Stock package is not installed for Android user ${request.userId}" }
            check(stableStock.topology == "SINGLE") { "Safe root mount requires a complete single APK" }
            check(stableStock.sharedUserId == null) { "Shared-UID process ownership cannot be isolated safely" }
            check(!stableStock.signerSha256.isNullOrBlank()) { "Installed stock signer is unavailable" }
            check(!stableStock.basePath.isNullOrBlank()) { "Installed stock base path is unavailable" }
            check(!stableStock.baseSha256.isNullOrBlank()) { "Installed stock hash is unavailable" }
            effectivePatchedArtifact?.let { artifact ->
                check(stableStock.versionName == artifact.versionName) { "Patched and stock version names differ" }
                check(stableStock.versionCode == artifact.versionCode) { "Patched and stock version codes differ" }
                check(stableStock.baseSha256 != artifact.sha256) {
                    "Patched payload is byte-identical to raw stock"
                }
            }
            val compatible = stableStock.copy(baseSha256 = stableStock.baseSha256)
            val mountOnlyCommitted = if (
                request.operation == RootMountOperation.MOUNT_ONLY &&
                !rebuildMountOnlyModule
            ) {
                val previous = requireNotNull(previousCommitted) { "No committed payload is available to mount" }
                checkCommittedIdentity(previous, compatible)
                val currentStockPath = requireNotNull(compatible.basePath) {
                    "Installed stock base path is unavailable"
                }
                check(currentStockPath.isSafeAbsoluteApkPath()) { "Installed stock base path is unsafe" }
                val pathChanged = currentStockPath != previous.stockPath
                val retargeted = previous.copy(
                    transactionId = if (pathChanged) transactionId else previous.transactionId,
                    stockPath = currentStockPath,
                    active = true,
                    status = "MOUNTED",
                    committedAtEpochMs = System.currentTimeMillis()
                )
                if (pathChanged) {
                    persist {
                        it.copy(
                            expectedPackageState = compatible,
                            status = "MODULE_STATE_RETARGET_PENDING"
                        )
                    }
                    moduleStore.updateState(retargeted)
                }
                moduleStore.enable(request.packageName)
                retargeted
            } else {
                null
            }
            val activePatchedPath = mountOnlyCommitted?.patchedPath ?: run {
                progress(RootMountPhase.STAGING_PATCHED_PAYLOAD)
                persist { it.copy(expectedPackageState = compatible) }
                moduleChanged = true
                moduleStore.stageAndActivate(
                    transactionId = transactionId,
                    packageName = request.packageName,
                    label = request.label,
                    patchedApk = requireNotNull(request.patchedApk),
                    compatible = compatible,
                    patchedHash = requireNotNull(patchedArtifact).sha256
                )
            }

            val patchedHash = effectivePatchedArtifact?.sha256
                ?: requireNotNull(previousCommitted).patchedSha256
            val committed = mountOnlyCommitted ?: run {
                RootCommittedState(
                    transactionId = transactionId,
                    packageName = request.packageName,
                    userId = request.userId,
                    versionName = requireNotNull(compatible.versionName),
                    versionCode = requireNotNull(compatible.versionCode),
                    signerSha256 = compatible.signerSha256,
                    stockPath = requireNotNull(compatible.basePath),
                    stockSha256 = requireNotNull(compatible.baseSha256),
                    patchedPath = activePatchedPath,
                    patchedSha256 = patchedHash,
                    stockShadowPath = RootPaths.moduleStockApk(request.packageName),
                    stockShadowSha256 = requireNotNull(compatible.baseSha256),
                    preserveStockAcrossBoot = true,
                    topology = compatible.topology,
                    enabled = compatible.enabled,
                    launcherResolvable = compatible.launcherResolvable,
                    committedAtEpochMs = System.currentTimeMillis()
                )
            }

            progress(RootMountPhase.MOUNTING)
            persist { it.copy(expectedPackageState = compatible) }
            stopAndWait(request.packageName, request.userId)
            requireExclusivePackageUser(request.packageName, request.userId)
            mountVerifier.verifyTargetsClear(setOf(committed.stockPath))
            mountVerifier.mountEverywhere(committed)

            progress(RootMountPhase.VERIFYING)
            persist()
            mountVerifier.verifyMounted(committed)
            check(packageStateReader.waitUntilStopped(request.packageName, 1_000)) {
                "Target app restarted during verification"
            }

            progress(RootMountPhase.COMMITTING)
            persist()
            moduleStore.commitSnapshot(request.packageName)
            transactionStore.complete(requireNotNull(journal), committed)
            progress(RootMountPhase.COMPLETED)
            return RootMountResult.Success(transactionId)
        } catch (cancelled: CancellationException) {
            val activeJournal = journal
            if (activeJournal != null) {
                currentPhase = RootMountPhase.ROLLING_BACK
                reportProgress(onPhase, RootMountPhase.ROLLING_BACK)
            }
            val cancellationRecovery = withContext(NonCancellable) {
                activeJournal?.let {
                    rollback(
                        it.copy(phase = RootMountPhase.ROLLING_BACK),
                        stockChanged || RootMountPolicy.interruptedJournalMayHaveChangedStock(it),
                        moduleChanged || RootMountPolicy.interruptedJournalMayHaveChangedModule(it),
                        stockBackup,
                        "cancel-${transactionId.take(8)}",
                        "Operation cancelled"
                    )
                }
            }
            if (cancellationRecovery is RootMountResult.Failure) return cancellationRecovery
            throw cancelled
        } catch (failure: Throwable) {
            val diagnosticId = "root-${transactionId.take(8)}"
            val failedPhase = currentPhase
            val failureMessage = failure.message ?: failure.javaClass.simpleName
            runCatching {
                transactionStore.appendDiagnostic(request.packageName, diagnosticId, failure.stackTraceToString())
            }
            val activeJournal = journal
            if (activeJournal == null) {
                return RootMountResult.Failure(
                    failedPhase,
                    RootRecoveryState.NONE,
                    diagnosticId,
                    failureMessage
                )
            }
            currentPhase = RootMountPhase.ROLLING_BACK
            reportProgress(onPhase, RootMountPhase.ROLLING_BACK)
            val recovery = rollback(
                activeJournal.copy(phase = RootMountPhase.ROLLING_BACK),
                stockChanged || RootMountPolicy.interruptedJournalMayHaveChangedStock(activeJournal),
                moduleChanged || RootMountPolicy.interruptedJournalMayHaveChangedModule(activeJournal),
                stockBackup,
                diagnosticId,
                failureMessage
            )
            return when (recovery) {
                is RootMountResult.RecoveredToPreviousMount -> RootMountResult.Failure(
                    failedPhase,
                    RootRecoveryState.PREVIOUS_MOUNT,
                    recovery.diagnosticId,
                    failureMessage
                )
                is RootMountResult.RecoveredToStock -> RootMountResult.Failure(
                    failedPhase,
                    RootRecoveryState.STOCK,
                    recovery.diagnosticId,
                    failureMessage
                )
                else -> recovery
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { packageLock.release(rootLock) }.onFailure { releaseFailure ->
                    runCatching {
                        transactionStore.appendDiagnostic(
                            request.packageName,
                            "lock-${transactionId.take(8)}",
                            "Failed to release package lock cleanly: ${releaseFailure.message}"
                        )
                    }
                }
            }
        }
    }

    private fun preflight(
        request: RootMountRequest,
        initial: RootPackageState,
        patched: RootArtifactState?,
        stock: List<RootArtifactState>,
        committed: RootCommittedState?,
        legacyMigration: Boolean
    ) {
        if (request.operation in MOUNTING_OPERATIONS) {
            val useCommittedMount =
                request.operation == RootMountOperation.MOUNT_ONLY && committed != null
            val requestedPatched = patched.takeUnless { useCommittedMount }
            val requestedStock = stock.takeUnless { useCommittedMount }.orEmpty()
            if (request.operation == RootMountOperation.REPLACE_STOCK_AND_MOUNT) {
                require(requestedStock.size == 1) { "A complete stock APK is required for stock replacement" }
            }
            if (request.operation == RootMountOperation.SWITCH_PATCHED_BUILD) {
                require(initial.installed) { "A separately installed stock package is required for bundle switching" }
                require(requestedStock.size == 1 || committed != null || legacyMigration) {
                    "Exact stock compatibility cannot be proven without a verified stock APK or committed mount"
                }
                val switchPayload = requireNotNull(requestedPatched) {
                    "Patched APK is required for bundle switching"
                }
                require(
                    initial.versionName == switchPayload.versionName &&
                        initial.versionCode == switchPayload.versionCode,
                ) {
                    "Bundle switching cannot change the installed stock version"
                }
                requestedStock.singleOrNull()?.let { artifact ->
                    require(initial.versionName == artifact.versionName && initial.versionCode == artifact.versionCode) {
                        "Bundle switching cannot change the installed stock version"
                    }
                }
            }
            if (request.operation != RootMountOperation.MOUNT_ONLY || requestedPatched != null) {
                requireNotNull(requestedPatched) { "Patched APK is required" }
                require(requestedPatched.packageName == request.packageName) { "Patched APK package mismatch" }
                request.expectedVersionName?.let {
                    require(requestedPatched.versionName == it) { "Patched APK version name mismatch" }
                }
                request.expectedVersionCode?.let {
                    require(requestedPatched.versionCode == it) { "Patched APK version code mismatch" }
                }
            }
            if (request.operation == RootMountOperation.MOUNT_ONLY) {
                require(
                    committed != null || requestedPatched != null && requestedStock.size == 1
                ) {
                    "No committed root mount or complete saved payload is available"
                }
            }
            RootMountPolicy.validateSafeMount(
                request.packageName,
                initial,
                requestedPatched,
                requestedStock
            )
            requestedStock.singleOrNull()?.let { artifact ->
                request.expectedVersionCode?.let { require(artifact.versionCode == it) }
                request.expectedVersionName?.let { require(artifact.versionName == it) }
            }
        }
    }

    private suspend fun requireExclusivePackageUser(packageName: String, userId: Int) {
        val installedUserIds = packageStateReader.installedUserIds(packageName)
        val otherUserIds = installedUserIds - userId
        require(otherUserIds.isEmpty()) {
            "Safe root mounting requires the package to be installed only for Android user " +
                "$userId; it is also installed for ${otherUserIds.sorted().joinToString()}"
        }
    }

    private suspend fun stopAndWait(packageName: String, userId: Int) {
        val installedUserIds = packageStateReader.installedUserIds(packageName)
        if (packageStateReader.read(packageName, userId).installed) {
            check(userId in installedUserIds) {
                "Target package user ownership could not be verified"
            }
        }
        installedUserIds.sorted().forEach { installedUserId ->
            shell.runIsolatedBounded(
                "am force-stop --user $installedUserId ${shellQuote(packageName)}",
                FORCE_STOP_TIMEOUT_SECONDS,
                "root mount force-stop"
            ).requireSuccess("Stop target app for Android user $installedUserId")
        }
        check(packageStateReader.waitUntilStopped(packageName)) {
            "Target package processes did not exit; shared-UID ownership may be unsafe"
        }
    }

    private suspend fun waitForPackageManagerIdle() {
        shell.runIsolatedBounded(
            """
                set -eu
                package_help="${'$'}(cmd package help 2>/dev/null || true)"
                handler_supported=0
                background_supported=0
                if printf '%s\n' "${'$'}package_help" | grep -q 'wait-for-handler --timeout'; then
                  handler_supported=1
                  cmd package wait-for-handler --timeout 20000
                fi
                if printf '%s\n' "${'$'}package_help" | grep -q 'wait-for-background-handler --timeout'; then
                  background_supported=1
                  cmd package wait-for-background-handler --timeout 20000
                fi
                if [ "${'$'}handler_supported" = 1 ]; then
                  cmd package wait-for-handler --timeout 20000
                elif [ "${'$'}background_supported" = 0 ]; then
                  sleep 1
                fi
            """.trimIndent(),
            PACKAGE_MANAGER_IDLE_TIMEOUT_SECONDS,
            "post-install PackageManager idle barrier"
        ).requireSuccess("Wait for PackageManager post-install work")
    }

    private suspend fun reconcile(
        request: RootMountRequest,
        committed: RootCommittedState,
        current: RootPackageState,
        transactionId: String,
        onPhase: (RootMountPhase) -> Unit,
        journal: RootMountJournal
    ): RootMountResult {
        val otherUserIds = packageStateReader.installedUserIds(request.packageName) - request.userId
        if (otherUserIds.isNotEmpty()) {
            val reason = "Root mounting is unsafe because the package is also installed for Android " +
                "users ${otherUserIds.sorted().joinToString()}"
            moduleStore.disable(request.packageName)
            transactionStore.markRepatchRequired(request.packageName, reason)
            transactionStore.complete(
                journal.copy(phase = RootMountPhase.COMPLETED),
                committed.copy(active = false, status = "REPATCH_REQUIRED")
            )
            stopReconciliation(request.packageName, request.userId, transactionId)
            return RootMountResult.RequiresRepatch(reason)
        }
        when (RootMountPolicy.reconcile(committed, current)) {
            RootMountPolicy.ReconcileDecision.INACTIVE -> {
                moduleStore.disable(request.packageName)
                val inactive = committed.copy(active = false, status = "INACTIVE")
                transactionStore.complete(journal.copy(phase = RootMountPhase.COMPLETED), inactive)
                stopReconciliation(request.packageName, request.userId, transactionId)
                return RootMountResult.Success(transactionId)
            }
            RootMountPolicy.ReconcileDecision.REPATCH_REQUIRED -> {
                moduleStore.disable(request.packageName)
                transactionStore.markRepatchRequired(request.packageName, "External package change is incompatible")
                transactionStore.complete(
                    journal.copy(phase = RootMountPhase.COMPLETED),
                    committed.copy(active = false, status = "REPATCH_REQUIRED")
                )
                stopReconciliation(request.packageName, request.userId, transactionId)
                return RootMountResult.RequiresRepatch(
                    "Installed stock no longer exactly matches the committed patched build"
                )
            }
            RootMountPolicy.ReconcileDecision.REPAIR_REQUIRED -> {
                moduleStore.disable(request.packageName)
                val diagnosticId = "repair-${transactionId.take(8)}"
                transactionStore.appendDiagnostic(
                    request.packageName,
                    diagnosticId,
                    "External reconciliation left an existing repair-required transaction unchanged"
                )
                transactionStore.complete(journal.copy(phase = RootMountPhase.COMPLETED), committed)
                stopReconciliation(request.packageName, request.userId, transactionId)
                return RootMountResult.Failure(
                    RootMountPhase.ROLLING_BACK,
                    RootRecoveryState.NONE,
                    diagnosticId,
                    "Root mount repair is required before reconciliation"
                )
            }
            RootMountPolicy.ReconcileDecision.REMOUNT -> Unit
        }
        val currentStockPath = requireNotNull(current.basePath) {
            "Reconciled stock base path is unavailable"
        }
        check(currentStockPath.isSafeAbsoluteApkPath()) { "Reconciled stock base path is unsafe" }
        val stockPathChanged = currentStockPath != committed.stockPath
        val reconciledState = if (stockPathChanged) {
            committed.copy(
                transactionId = transactionId,
                stockPath = currentStockPath,
                active = true,
                status = "MOUNTED",
                committedAtEpochMs = System.currentTimeMillis()
            )
        } else {
            committed
        }
        reportProgress(onPhase, RootMountPhase.MOUNTING)
        transactionStore.writeActive(
            journal.copy(
                phase = RootMountPhase.MOUNTING,
                expectedPackageState = current,
                status = if (stockPathChanged) "MODULE_STATE_RETARGET_PENDING" else journal.status
            )
        )
        if (stockPathChanged) moduleStore.updateState(reconciledState)
        stopAndWait(request.packageName, request.userId)
        requireExclusivePackageUser(request.packageName, request.userId)
        moduleStore.enable(request.packageName)
        mountVerifier.verifyTargetsClear(setOf(reconciledState.stockPath))
        mountVerifier.mountEverywhere(reconciledState)
        reportProgress(onPhase, RootMountPhase.VERIFYING)
        transactionStore.writeActive(journal.copy(phase = RootMountPhase.VERIFYING))
        mountVerifier.verifyMounted(reconciledState)
        check(packageStateReader.waitUntilStopped(request.packageName, 1_000)) {
            "Target app restarted during reconciliation"
        }
        transactionStore.complete(
            journal.copy(phase = RootMountPhase.COMPLETED),
            reconciledState.copy(active = true, status = "MOUNTED")
        )
        return RootMountResult.Success(transactionId)
    }

    private fun checkCommittedIdentity(committed: RootCommittedState, current: RootPackageState) {
        check(current.installed) { "Package is not installed" }
        check(current.packageName == committed.packageName) { "Package name mismatch" }
        check(current.userId == committed.userId) { "Android user mismatch" }
        check(current.versionName == committed.versionName) { "Version name mismatch" }
        check(current.versionCode == committed.versionCode) { "Version code mismatch" }
        check(current.signerSha256 == committed.signerSha256) { "Signing certificate mismatch" }
        check(current.baseSha256 == committed.stockSha256) { "Stock APK hash mismatch" }
        check(current.topology == committed.topology) { "APK topology mismatch" }
        check(current.enabled == committed.enabled) { "Enabled state mismatch" }
        check(current.launcherResolvable == committed.launcherResolvable) { "Launcher resolution mismatch" }
    }

    private fun checkCommittedCompatibility(committed: RootCommittedState, current: RootPackageState) {
        checkCommittedIdentity(committed, current)
        check(current.basePath == committed.stockPath) { "Stock base path mismatch" }
    }

    private suspend fun rollback(
        journal: RootMountJournal,
        stockChanged: Boolean,
        moduleChanged: Boolean,
        stockBackup: RootBackupArtifact?,
        diagnosticId: String,
        reason: String
    ): RootMountResult {
        val packageName = journal.packageName
        return try {
            transactionStore.writeActive(journal.copy(phase = RootMountPhase.ROLLING_BACK, diagnosticId = diagnosticId))
            val stopped = runCatchingPreservingCancellation {
                stopAndWait(packageName, journal.userId)
                true
            }.getOrDefault(false)
            check(stopped) { "Could not quiesce target app during rollback" }
            val targets = setOfNotNull(
                journal.initialPackageState?.basePath,
                journal.expectedPackageState?.basePath,
                journal.previousCommitted?.stockPath
            ) + journal.candidateMountTargets
            val lazyUnmounts = mountVerifier.removeAllUrvMounts(
                packageName,
                targets,
                allowLazyRecovery = true
            )
            if (lazyUnmounts.isNotEmpty()) {
                transactionStore.appendDiagnostic(
                    packageName,
                    diagnosticId,
                    "Recovery used lazy unmount after process quiescence: ${lazyUnmounts.joinToString()}"
                )
            }
            mountVerifier.verifyTargetsClear(targets)

            val initial = journal.initialPackageState
            if (stockChanged && initial?.installed == true) {
                val beforeRestore = packageStateReader.read(packageName, journal.userId)
                if (initial.systemApp && !beforeRestore.installed) {
                    check(packageInstaller.restoreSystemRegistration(packageName, journal.userId)) {
                        "Failed to restore system package registration"
                    }
                }
                val backup = stockBackup ?: RootBackupArtifact(
                    "${RootPaths.backup(packageName)}/package/0.apk",
                    requireNotNull(initial.baseSha256) { "Previous stock hash is unavailable" }
                )
                packageInstaller.replaceRootBackup(
                    backup.path,
                    backup.sha256,
                    journal.userId
                ).getOrThrow()
                packageStateReader.waitForStable(initial.copy(installed = true))
            }

            val restoredState = packageStateReader.read(packageName, journal.userId)
            val previous = journal.previousCommitted
            if (moduleChanged) {
                val restoredModule = moduleStore.restorePrevious(packageName)
                if (previous != null) check(restoredModule) { "Previous root module could not be restored" }
            }
            val previousMountUserSafe = runCatchingPreservingCancellation {
                packageStateReader.installedUserIds(packageName) == setOf(journal.userId)
            }.getOrDefault(false)
            if (previous != null && previous.active && previousMountUserSafe &&
                runCatching { checkCommittedCompatibility(previous, restoredState) }.isSuccess &&
                restoredState.baseSha256 == previous.stockSha256
            ) {
                stopAndWait(packageName, journal.userId)
                requireExclusivePackageUser(packageName, journal.userId)
                moduleStore.enable(packageName)
                mountVerifier.verifyTargetsClear(setOf(previous.stockPath))
                mountVerifier.mountEverywhere(previous)
                mountVerifier.verifyMounted(previous)
                transactionStore.complete(journal, previous)
                RootMountResult.RecoveredToPreviousMount(journal.transactionId, diagnosticId, reason)
            } else {
                moduleStore.disable(packageName)
                if (isVerifiedStockRecovery(journal, restoredState)) {
                    val stockState = inactiveCommittedForVerifiedStock(previous, restoredState)
                    if (stockState == null) transactionStore.clearCommitted(packageName)
                    transactionStore.complete(journal, stockState)
                    stopReconciliation(packageName, journal.userId, diagnosticId)
                    RootMountResult.RecoveredToStock(journal.transactionId, diagnosticId, reason)
                } else {
                    transactionStore.writeActive(
                        journal.copy(
                            phase = RootMountPhase.ROLLING_BACK,
                            diagnosticId = diagnosticId,
                            status = "REPAIR_REQUIRED"
                        )
                    )
                    previous?.let {
                        transactionStore.writeCommitted(it.copy(active = false, status = "REPAIR_REQUIRED"))
                    }
                    RootMountResult.Failure(
                        RootMountPhase.ROLLING_BACK,
                        RootRecoveryState.NONE,
                        diagnosticId,
                        "$reason; stock recovery could not be proven"
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rollbackFailure: Throwable) {
            runCatchingPreservingCancellation {
                transactionStore.appendDiagnostic(
                    packageName,
                    diagnosticId,
                    "Rollback failed: ${rollbackFailure.stackTraceToString()}"
                )
            }
            val stockFallback = runCatchingPreservingCancellation {
                stopAndWait(packageName, journal.userId)
                val targets = setOfNotNull(
                    journal.initialPackageState?.basePath,
                    journal.expectedPackageState?.basePath,
                    journal.previousCommitted?.stockPath
                ) + journal.candidateMountTargets
                val lazyUnmounts = mountVerifier.removeAllUrvMounts(
                    packageName,
                    targets,
                    allowLazyRecovery = true
                )
                if (lazyUnmounts.isNotEmpty()) {
                    transactionStore.appendDiagnostic(
                        packageName,
                        diagnosticId,
                        "Stock fallback used lazy unmount after process quiescence: ${lazyUnmounts.joinToString()}"
                    )
                }
                mountVerifier.verifyTargetsClear(targets)
                moduleStore.disable(packageName)
                val stock = packageStateReader.read(packageName, journal.userId)
                check(isVerifiedStockRecovery(journal, stock)) {
                    "Stock fallback state could not be verified"
                }
                val inactive = inactiveCommittedForVerifiedStock(journal.previousCommitted, stock)
                if (inactive == null) transactionStore.clearCommitted(packageName)
                transactionStore.complete(journal, inactive)
                stopReconciliation(packageName, journal.userId, diagnosticId)
            }.isSuccess
            if (stockFallback) {
                RootMountResult.RecoveredToStock(
                    journal.transactionId,
                    diagnosticId,
                    "$reason; previous mount restoration failed and verified stock was retained"
                )
            } else {
                runCatchingPreservingCancellation { moduleStore.disable(packageName) }
                journal.previousCommitted?.let { previous ->
                    runCatchingPreservingCancellation {
                        transactionStore.writeCommitted(previous.copy(active = false, status = "REPAIR_REQUIRED"))
                    }
                }
                RootMountResult.Failure(
                    RootMountPhase.ROLLING_BACK,
                    RootRecoveryState.NONE,
                    diagnosticId,
                    "$reason; automatic recovery failed: ${rollbackFailure.message}"
                )
            }
        }
    }

    private suspend fun recoverCorruptJournal(
        request: RootMountRequest,
        previousCommitted: RootCommittedState?,
        transactionId: String
    ): RootMountResult {
        val diagnosticId = "corrupt-${transactionId.take(8)}"
        val current = packageStateReader.read(request.packageName, request.userId)
        val stopped = runCatching {
            stopAndWait(request.packageName, request.userId)
            true
        }.getOrDefault(false)
        val mountsRemoved = stopped && runCatching {
            val targets = setOfNotNull(current.basePath, previousCommitted?.stockPath)
            val lazyUnmounts = mountVerifier.removeAllUrvMounts(
                request.packageName,
                targets,
                allowLazyRecovery = true
            )
            if (lazyUnmounts.isNotEmpty()) {
                transactionStore.appendDiagnostic(
                    request.packageName,
                    diagnosticId,
                    "Corrupt-journal recovery used lazy unmount: ${lazyUnmounts.joinToString()}"
                )
            }
            mountVerifier.verifyTargetsClear(targets)
        }.isSuccess
        val moduleDisabled = runCatching { moduleStore.disable(request.packageName) }.isSuccess

        var restored = packageStateReader.read(request.packageName, request.userId)
        if (stopped && mountsRemoved && previousCommitted != null &&
            !matchesCommittedStock(previousCommitted, restored)
        ) {
            val restoredBackup = packageInstaller.replaceRootBackup(
                "${RootPaths.backup(request.packageName)}/package/0.apk",
                previousCommitted.stockSha256,
                request.userId
            )
            if (restoredBackup.isSuccess) {
                val observedAfterRestore = packageStateReader.read(request.packageName, request.userId)
                restored = packageStateReader.waitForStable(
                    observedAfterRestore.copy(
                        installed = true,
                        versionName = previousCommitted.versionName,
                        versionCode = previousCommitted.versionCode,
                        signerSha256 = previousCommitted.signerSha256,
                        splitPaths = emptyList(),
                        baseSha256 = previousCommitted.stockSha256
                    )
                )
            }
        }
        val stockProven = previousCommitted?.let { matchesCommittedStock(it, restored) }
            ?: isStructurallyVerifiedStock(restored)
        val recoveryProven = stopped && mountsRemoved && moduleDisabled && stockProven
        val inactive = previousCommitted?.copy(
            active = false,
            status = if (recoveryProven) "STOCK" else "REPAIR_REQUIRED"
        )
        if (inactive != null) transactionStore.writeCommitted(inactive)
        else transactionStore.clearCommitted(request.packageName)
        transactionStore.appendDiagnostic(
            request.packageName,
            diagnosticId,
            "The active journal was unreadable. Mounts were removed, the module was disabled, and backups were preserved."
        )
        if (recoveryProven) transactionStore.clearActive(request.packageName)
        if (recoveryProven) {
            stopReconciliation(request.packageName, request.userId, diagnosticId)
        }
        return if (recoveryProven) {
            RootMountResult.RecoveredToStock(
                transactionId,
                diagnosticId,
                "Unreadable transaction journal was recovered conservatively"
            )
        } else {
            RootMountResult.Failure(
                RootMountPhase.ROLLING_BACK,
                RootRecoveryState.NONE,
                diagnosticId,
                "Unreadable journal recovery could not prove an installed stock package; use Repair root mount"
            )
        }
    }

    private suspend fun recoverCorruptCommittedState(
        packageName: String,
        userId: Int,
        transactionId: String,
        reason: String
    ): RootMountResult {
        val diagnosticId = "committed-${transactionId.take(8)}"
        val initial = packageStateReader.read(packageName, userId)
        val stopped = runCatching {
            stopAndWait(packageName, userId)
            true
        }.getOrDefault(false)
        val mountsRemoved = stopped && runCatching {
            val discovered = mountVerifier.findUrvMounts(
                packageName,
                setOfNotNull(initial.basePath)
            )
            val targets = setOfNotNull(initial.basePath) + discovered.map(MountInfoEntry::mountPoint)
            val lazyUnmounts = mountVerifier.removeAllUrvMounts(
                packageName,
                targets,
                allowLazyRecovery = true
            )
            if (lazyUnmounts.isNotEmpty()) {
                transactionStore.appendDiagnostic(
                    packageName,
                    diagnosticId,
                    "Corrupt committed-state recovery used lazy unmount: ${lazyUnmounts.joinToString()}"
                )
            }
            mountVerifier.verifyTargetsClear(targets)
        }.isSuccess
        val moduleDisabled = runCatching { moduleStore.disable(packageName) }.isSuccess
        val restored = packageStateReader.read(packageName, userId)
        val stockProven = !restored.installed || isStructurallyVerifiedStock(restored)
        val recoveryProven = stopped && mountsRemoved && moduleDisabled && stockProven
        transactionStore.appendDiagnostic(
            packageName,
            diagnosticId,
            if (recoveryProven) {
                "$reason; URV mounts were removed and the module was disabled"
            } else {
                "$reason; automatic stock recovery could not be proven"
            }
        )
        if (!recoveryProven) {
            return RootMountResult.Failure(
                RootMountPhase.ROLLING_BACK,
                RootRecoveryState.NONE,
                diagnosticId,
                "$reason; use Repair root mount"
            )
        }
        transactionStore.clearCommitted(packageName)
        stopReconciliation(packageName, userId, diagnosticId)
        return if (restored.installed) {
            RootMountResult.RecoveredToStock(
                transactionId,
                diagnosticId,
                "$reason; verified stock was retained"
            )
        } else {
            RootMountResult.Success(transactionId)
        }
    }

    private fun packageRegistrationChanged(before: RootPackageState, after: RootPackageState): Boolean =
        before.installed != after.installed ||
            before.versionName != after.versionName ||
            before.versionCode != after.versionCode ||
            before.signerSha256 != after.signerSha256 ||
            before.basePath != after.basePath ||
            before.splitPaths != after.splitPaths ||
            before.baseSha256 != after.baseSha256 ||
            before.enabled != after.enabled ||
            before.launcherResolvable != after.launcherResolvable

    private fun matchesRequestedStock(state: RootPackageState, artifact: RootArtifactState): Boolean =
        state.installed &&
            state.packageName == artifact.packageName &&
            state.versionName == artifact.versionName &&
            state.versionCode == artifact.versionCode &&
            state.signerSha256 == artifact.signerSha256 &&
            state.topology == "SINGLE" &&
            !state.basePath.isNullOrBlank() &&
            state.baseSha256 == artifact.sha256

    private fun isVerifiedStockRecovery(journal: RootMountJournal, restored: RootPackageState): Boolean {
        if (restored.packageName != journal.packageName || restored.userId != journal.userId) {
            return false
        }
        val initial = journal.initialPackageState
        if (initial?.installed == false && !restored.installed) {
            return initial.packageName == journal.packageName && initial.userId == journal.userId
        }
        if (!restored.installed || restored.topology != "SINGLE") {
            return false
        }
        if (initial?.installed == true) {
            if (journal.status == "LEGACY_MIGRATION") {
                return restored.versionName == initial.versionName &&
                    restored.versionCode == initial.versionCode &&
                    restored.signerSha256 == initial.signerSha256 &&
                    restored.basePath == initial.basePath &&
                    restored.enabled == initial.enabled &&
                    restored.launcherResolvable == initial.launcherResolvable &&
                    journal.patchedArtifact?.let { patched ->
                        restored.versionName == patched.versionName &&
                            restored.versionCode == patched.versionCode
                    } == true
            }
            val matchesInitial = restored.versionName == initial.versionName &&
                restored.versionCode == initial.versionCode &&
                restored.signerSha256 == initial.signerSha256 &&
                restored.baseSha256 == initial.baseSha256 &&
                restored.enabled == initial.enabled &&
                restored.launcherResolvable == initial.launcherResolvable
            if (matchesInitial) return true
            return journal.previousCommitted?.let { matchesCommittedStock(it, restored) } == true
        }
        val requested = journal.stockArtifact ?: return false
        return restored.versionName == requested.versionName &&
            restored.versionCode == requested.versionCode &&
            restored.signerSha256 == requested.signerSha256 &&
            restored.baseSha256 == requested.sha256
    }

    private fun matchesCommittedStock(committed: RootCommittedState, state: RootPackageState): Boolean =
        state.installed &&
            state.packageName == committed.packageName &&
            state.userId == committed.userId &&
            state.versionName == committed.versionName &&
            state.versionCode == committed.versionCode &&
            state.signerSha256 == committed.signerSha256 &&
            state.baseSha256 == committed.stockSha256 &&
            state.topology == committed.topology &&
            state.enabled == committed.enabled &&
            state.launcherResolvable == committed.launcherResolvable

    private suspend fun inactiveCommittedForVerifiedStock(
        previous: RootCommittedState?,
        restored: RootPackageState
    ): RootCommittedState? {
        previous ?: return null
        val inactive = if (matchesCommittedStock(previous, restored)) {
            previous.copy(
                stockPath = requireNotNull(restored.basePath) {
                    "Verified recovered stock base path is unavailable"
                },
                active = false,
                status = "STOCK",
                committedAtEpochMs = System.currentTimeMillis()
            )
        } else {
            previous.copy(
                active = false,
                status = "STOCK",
                committedAtEpochMs = System.currentTimeMillis()
            )
        }
        if (inactive.stockPath != previous.stockPath) {
            moduleStore.updateState(inactive)
        }
        return inactive
    }

    private fun isStructurallyVerifiedStock(state: RootPackageState): Boolean =
        state.installed &&
            state.topology == "SINGLE" &&
            !state.versionName.isNullOrBlank() &&
            state.versionCode != null &&
            !state.signerSha256.isNullOrBlank() &&
            !state.basePath.isNullOrBlank() &&
            !state.baseSha256.isNullOrBlank()

    private fun isValidCommittedState(packageName: String, state: RootCommittedState): Boolean {
        val safeStockPath = state.stockPath.isSafeAbsoluteApkPath()
        val expectedPatchedPath = RootPaths.moduleApk(packageName)
        val validStockShadow = !state.preserveStockAcrossBoot ||
            (state.stockShadowPath == RootPaths.moduleStockApk(packageName) &&
                state.stockShadowSha256 == state.stockSha256)
        val versionName = state.versionName
        val validStatus = if (state.active) {
            state.status == "MOUNTED"
        } else {
            state.status in INACTIVE_COMMITTED_STATUSES
        }
        return state.packageName == packageName &&
            state.userId >= 0 &&
            !state.transactionId.isBlank() &&
            versionName != null &&
            versionName.isNotBlank() &&
            versionName.none { it.isISOControl() } &&
            state.versionCode >= 0 &&
            state.signerSha256?.matches(SHA256) == true &&
            safeStockPath &&
            state.stockPath != expectedPatchedPath &&
            state.stockSha256.matches(SHA256) &&
            state.patchedPath == expectedPatchedPath &&
            state.patchedSha256.matches(SHA256) &&
            validStockShadow &&
            state.topology == "SINGLE" &&
            validStatus
    }

    private fun String.isSafeAbsoluteApkPath(): Boolean =
        startsWith('/') &&
            endsWith(".apk") &&
            this != "/.apk" &&
            none { it.isISOControl() } &&
            !contains("/../") &&
            !contains("/./")

    private suspend fun stopReconciliation(packageName: String, userId: Int, diagnosticId: String) {
        runCatchingPreservingCancellation {
            reconciliationScheduler.stopScheduled(userId, packageName)
        }.onFailure { failure ->
            runCatchingPreservingCancellation {
                transactionStore.appendDiagnostic(
                    packageName,
                    "schedule-${diagnosticId.take(24)}",
                    "Failed to stop root mount reconciliation: ${failure.message}"
                )
            }
        }
    }

    private fun reportProgress(onPhase: (RootMountPhase) -> Unit, phase: RootMountPhase) {
        try {
            onPhase(phase)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Progress observers are not part of the transaction's correctness boundary.
        }
    }

    private suspend fun <T> runCatchingPreservingCancellation(
        block: suspend () -> T
    ): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Result.failure(failure)
    }

    private companion object {
        val mutexes = ConcurrentHashMap<String, Mutex>()
        val MOUNTING_OPERATIONS = setOf(
            RootMountOperation.MOUNT_ONLY,
            RootMountOperation.SWITCH_PATCHED_BUILD,
            RootMountOperation.REPLACE_STOCK_AND_MOUNT
        )
        val MANUAL_STATE_RECOVERY_OPERATIONS = setOf(
            RootMountOperation.MOUNT_ONLY,
            RootMountOperation.UNMOUNT,
            RootMountOperation.RECOVER
        )
        const val FORCE_STOP_TIMEOUT_SECONDS = 15L
        const val PACKAGE_MANAGER_IDLE_TIMEOUT_SECONDS = 70L
        val SHA256 = Regex("[0-9a-f]{64}")
        val INACTIVE_COMMITTED_STATUSES = setOf(
            "STOCK",
            "INACTIVE",
            "REPATCH_REQUIRED",
            "REPAIR_REQUIRED"
        )
        fun mutexFor(packageName: String): Mutex = mutexes.getOrPut(packageName) { Mutex() }
    }
}
