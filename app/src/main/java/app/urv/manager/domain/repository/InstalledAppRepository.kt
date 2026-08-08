package app.urv.manager.domain.repository

import app.urv.manager.data.platform.Filesystem
import app.urv.manager.data.platform.RetainedOriginalReference
import app.urv.manager.data.room.AppDatabase
import app.urv.manager.data.room.apps.installed.AppliedPatch
import app.urv.manager.data.room.apps.installed.InstallType
import app.urv.manager.data.room.apps.installed.InstalledApp
import app.urv.manager.data.room.profile.PatchProfilePayload
import app.urv.manager.domain.manager.PreferencesManager
import app.urv.manager.util.PM
import app.urv.manager.util.PatchSelection
import app.urv.manager.util.savedAppBasePackage
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File
import java.util.UUID

private const val INSTALLED_RECORD_TIMESTAMP_TOLERANCE_MS = 60_000L

internal fun installedRecordMatchesCurrentPackage(
    record: InstalledApp,
    installedVersion: String?,
    installedLastUpdateTime: Long,
    managedPatchedFileAvailable: Boolean,
    managedPatchedFileMatchesRecord: Boolean
): Boolean {
    if (record.installType == InstallType.SAVED) return false
    if (
        installedVersion != null &&
        !record.version.equals(installedVersion, ignoreCase = true)
    ) return false
    if (managedPatchedFileMatchesRecord) return true
    if (managedPatchedFileAvailable) return false
    if (record.createdAt <= 0L || installedLastUpdateTime <= 0L) return true
    return installedLastUpdateTime <=
        record.createdAt + INSTALLED_RECORD_TIMESTAMP_TOLERANCE_MS
}

internal class PendingHistoricalSavedEntry(
    private val repository: InstalledAppRepository,
    private val sourceApp: InstalledApp,
    private val sourceSelection: PatchSelection,
    private val targetPackageName: String,
    private val stagingApk: File,
    private val targetApk: File
) {
    private var finished = false

    suspend fun <T> commitWith(
        replacementTargetPackageName: String,
        replacement: suspend () -> T
    ): T {
        check(!finished) { "Historical saved entry transaction is already finished" }
        val previousTarget = repository.get(targetPackageName)
        val previousTargetSelection = previousTarget
            ?.let { repository.getAppliedPatches(targetPackageName) }
            .orEmpty()
        val previousReplacementTarget = repository.get(replacementTargetPackageName)
        val previousReplacementSelection = previousReplacementTarget
            ?.let { repository.getAppliedPatches(replacementTargetPackageName) }
            .orEmpty()
        val targetDirectory = requireNotNull(targetApk.parentFile)
        val backupApk = targetDirectory.resolve(
            ".${targetApk.name}.${UUID.randomUUID()}.bak"
        )
        val targetExisted = targetApk.isFile
        val previousLastModified = targetApk.takeIf(File::isFile)?.lastModified()
        var replacementStarted = false
        var historicalEntryPersisted = false
        var keepBackup = false
        try {
            if (targetExisted) {
                targetApk.copyTo(backupApk, overwrite = true)
                check(backupApk.isFile && backupApk.length() == targetApk.length()) {
                    "Failed to verify the historical saved APK backup"
                }
            }
            replacementStarted = true
            stagingApk.copyTo(targetApk, overwrite = true)
            check(targetApk.isFile && targetApk.length() == stagingApk.length()) {
                "Failed to verify the historical saved APK"
            }
            repository.addOrUpdate(
                currentPackageName = targetPackageName,
                originalPackageName = sourceApp.originalPackageName,
                version = sourceApp.version,
                installType = InstallType.SAVED,
                patchSelection = sourceSelection,
                selectionPayload = sourceApp.selectionPayload,
                createdAtOverride = sourceApp.createdAt
            )
            historicalEntryPersisted = true
            return replacement()
        } catch (error: Throwable) {
            if (replacementStarted) {
                val fileRestoreError = runCatching {
                    if (targetExisted) {
                        check(backupApk.isFile) {
                            "The historical saved APK backup is unavailable"
                        }
                        backupApk.copyTo(targetApk, overwrite = true)
                        check(targetApk.isFile && targetApk.length() == backupApk.length()) {
                            "Failed to verify the restored historical saved APK"
                        }
                        previousLastModified?.let(targetApk::setLastModified)
                    } else {
                        check(targetApk.delete() || !targetApk.exists()) {
                            "Failed to remove the incomplete historical saved APK"
                        }
                    }
                }.exceptionOrNull()
                if (fileRestoreError != null) {
                    keepBackup = backupApk.isFile
                    error.addSuppressed(fileRestoreError)
                }
            }
            runCatching {
                repository.restoreHistoricalTarget(
                    targetPackageName = replacementTargetPackageName,
                    previousApp = previousReplacementTarget,
                    previousSelection = previousReplacementSelection
                )
            }.exceptionOrNull()?.let(error::addSuppressed)
            if (historicalEntryPersisted) {
                runCatching {
                    repository.restoreHistoricalTarget(
                        targetPackageName = targetPackageName,
                        previousApp = previousTarget,
                        previousSelection = previousTargetSelection
                    )
                }.exceptionOrNull()?.let(error::addSuppressed)
            }
            throw error
        } finally {
            stagingApk.delete()
            if (!keepBackup) backupApk.delete()
            finished = true
        }
    }

    fun discard() {
        if (finished) return
        stagingApk.delete()
        finished = true
    }
}

internal fun migratedAutoPatchTargets(
    records: List<InstalledApp>,
    enabledTargets: Set<String>,
    oldKey: String,
    newKey: String
): Set<String> {
    if (oldKey == newKey) return enabledTargets
    val oldRecord = records.firstOrNull { it.currentPackageName == oldKey }
    val originalPackageName = oldRecord?.originalPackageName
        ?.takeIf(String::isNotBlank)
        ?: savedAppBasePackage(oldKey)
    if (oldKey !in enabledTargets && originalPackageName !in enabledTargets) {
        return enabledTargets
    }
    val relatedKeys = records
        .filter { app ->
            val appOriginalPackage = app.originalPackageName.takeIf(String::isNotBlank)
                ?: savedAppBasePackage(app.currentPackageName)
            appOriginalPackage == originalPackageName
        }
        .mapTo(mutableSetOf(), InstalledApp::currentPackageName)
    return enabledTargets.toMutableSet().apply {
        remove(originalPackageName)
        remove(oldKey)
        removeAll(relatedKeys)
        add(newKey)
    }
}

class InstalledAppRepository(
    db: AppDatabase,
    private val patchOptionInputManager: PatchOptionInputManager,
    private val fs: Filesystem,
    private val prefs: PreferencesManager,
    private val pm: PM
) {
    private val dao = db.installedAppDao()

    fun getAll() = dao.getAll().distinctUntilChanged()

    suspend fun get(packageName: String) = dao.get(packageName)

    suspend fun getByInstallType(installType: InstallType) =
        dao.getByInstallType(installType)

    suspend fun getCurrentInstalledRecord(
        packageName: String,
        installedVersion: String?,
        installedLastUpdateTime: Long,
        installedApk: File?
    ): InstalledApp? {
        val record = dao.get(packageName) ?: return null
        val managedPatchedFileAvailable = fs.getPatchedAppFile(
            record.currentPackageName,
            record.version
        ).isFile
        val managedPatchedFileMatchesRecord = installedApk
            ?.takeIf(File::isFile)
            ?.let { apk ->
                fs.isManagedPatchedAppFile(
                    file = apk,
                    packageName = record.currentPackageName,
                    version = record.version
                )
            } == true
        return record.takeIf {
            installedRecordMatchesCurrentPackage(
                record = it,
                installedVersion = installedVersion,
                installedLastUpdateTime = installedLastUpdateTime,
                managedPatchedFileAvailable = managedPatchedFileAvailable,
                managedPatchedFileMatchesRecord = managedPatchedFileMatchesRecord
            )
        }
    }

    suspend fun getAppliedPatches(packageName: String): PatchSelection =
        dao.getPatchesSelection(packageName).mapValues { (_, patches) -> patches.toSet() }

    suspend fun addOrUpdate(
        currentPackageName: String,
        originalPackageName: String,
        version: String,
        installType: InstallType,
        patchSelection: PatchSelection,
        selectionPayload: PatchProfilePayload? = null,
        resetCreatedAt: Boolean = false,
        createdAtOverride: Long? = null,
        sortOrderOverride: Int? = null
    ) {
        patchOptionInputManager.updateReferences {
            val existingApp = dao.get(currentPackageName)
            val existingSortOrder = dao.getSortOrder(currentPackageName)
            val sortOrder = sortOrderOverride
                ?: existingSortOrder
                ?: ((dao.getMaxSortOrder() ?: -1) + 1)
            val createdAt = createdAtOverride ?: when {
                existingApp == null -> System.currentTimeMillis()
                resetCreatedAt -> System.currentTimeMillis()
                else -> existingApp.createdAt
            }
            dao.upsertApp(
                InstalledApp(
                    currentPackageName = currentPackageName,
                    originalPackageName = originalPackageName,
                    version = version,
                    installType = installType,
                    sortOrder = sortOrder,
                    selectionPayload = selectionPayload,
                    createdAt = createdAt
                ),
                patchSelection.flatMap { (uid, patches) ->
                    patches.map { patch ->
                        AppliedPatch(
                            packageName = currentPackageName,
                            bundle = uid,
                            patchName = patch
                        )
                    }
                }
            )
        }
    }

    suspend fun reorderApps(orderedPackageNames: List<String>) {
        orderedPackageNames.forEachIndexed { index, packageName ->
            dao.updateSortOrder(packageName, index)
        }
    }

    internal suspend fun restoreHistoricalTarget(
        targetPackageName: String,
        previousApp: InstalledApp?,
        previousSelection: PatchSelection
    ) {
        if (previousApp == null) {
            patchOptionInputManager.updateReferences {
                dao.get(targetPackageName)?.let { dao.delete(it) }
            }
            return
        }
        addOrUpdate(
            currentPackageName = previousApp.currentPackageName,
            originalPackageName = previousApp.originalPackageName,
            version = previousApp.version,
            installType = previousApp.installType,
            patchSelection = previousSelection,
            selectionPayload = previousApp.selectionPayload,
            createdAtOverride = previousApp.createdAt,
            sortOrderOverride = previousApp.sortOrder
        )
    }

    internal suspend fun prepareHistoricalSavedEntry(
        sourceApp: InstalledApp,
        targetPackageName: String
    ): PendingHistoricalSavedEntry? {
        val sourceApk = fs.getPatchedAppFile(
            sourceApp.currentPackageName,
            sourceApp.version
        ).takeIf(File::isFile) ?: return null
        val targetApk = fs.getPatchedAppFile(targetPackageName, sourceApp.version)
        val targetDirectory = requireNotNull(targetApk.parentFile)
        check(targetDirectory.mkdirs() || targetDirectory.isDirectory) {
            "Unable to create the historical saved APK directory"
        }
        val stagingApk = targetDirectory.resolve(
            ".${targetApk.name}.${UUID.randomUUID()}.history.tmp"
        )
        return try {
            sourceApk.copyTo(stagingApk, overwrite = true)
            check(stagingApk.isFile && stagingApk.length() == sourceApk.length()) {
                "Failed to verify the historical saved APK staging copy"
            }
            PendingHistoricalSavedEntry(
                repository = this,
                sourceApp = sourceApp,
                sourceSelection = getAppliedPatches(sourceApp.currentPackageName),
                targetPackageName = targetPackageName,
                stagingApk = stagingApk,
                targetApk = targetApk
            )
        } catch (error: Throwable) {
            stagingApk.delete()
            throw error
        }
    }

    suspend fun setAutoPatchTarget(installedApp: InstalledApp, enabled: Boolean) {
        val originalPackageName = installedApp.originalPackageName.takeIf(String::isNotBlank)
            ?: savedAppBasePackage(installedApp.currentPackageName)
        val relatedKeys = dao.getAllSnapshot()
            .filter { app ->
                app.originalPackageName == originalPackageName ||
                    savedAppBasePackage(app.currentPackageName) == originalPackageName
            }
            .mapTo(mutableSetOf(), InstalledApp::currentPackageName)
        val enabledTargets = prefs.autoPatchEnabledPackages.get().toMutableSet()
        enabledTargets.remove(originalPackageName)
        enabledTargets.removeAll(relatedKeys)
        if (enabled) enabledTargets.add(installedApp.currentPackageName)
        prefs.autoPatchEnabledPackages.update(enabledTargets)
    }

    suspend fun migrateAutoPatchTarget(oldKey: String, newKey: String) {
        val enabledTargets = prefs.autoPatchEnabledPackages.get()
        val migratedTargets = migratedAutoPatchTargets(
            records = dao.getAllSnapshot(),
            enabledTargets = enabledTargets,
            oldKey = oldKey,
            newKey = newKey
        )
        if (migratedTargets != enabledTargets) {
            prefs.autoPatchEnabledPackages.update(migratedTargets)
        }
    }

    suspend fun delete(installedApp: InstalledApp) {
        patchOptionInputManager.updateReferences {
            dao.delete(installedApp)
        }
        cleanupDeletedAutoPatchTargets(listOf(installedApp))
        pruneRetainedOriginals()
    }

    suspend fun deleteByInstallType(installType: InstallType) {
        val deletedApps = dao.getByInstallType(installType)
        patchOptionInputManager.updateReferences {
            dao.deleteByInstallType(installType)
        }
        cleanupDeletedAutoPatchTargets(deletedApps)
        pruneRetainedOriginals()
    }

    private suspend fun cleanupDeletedAutoPatchTargets(deletedApps: List<InstalledApp>) {
        if (deletedApps.isEmpty()) return
        val remainingApps = dao.getAllSnapshot()
        val remainingOriginalPackages = remainingApps.mapTo(mutableSetOf()) {
            it.originalPackageName.takeIf(String::isNotBlank)
                ?: savedAppBasePackage(it.currentPackageName)
        }
        val enabledTargets = prefs.autoPatchEnabledPackages.get().toMutableSet()
        val changedByEntry = deletedApps.fold(false) { changed, app ->
            enabledTargets.remove(app.currentPackageName) || changed
        }
        val changedByLegacyKey = deletedApps.fold(changedByEntry) { changed, app ->
            val originalPackageName = app.originalPackageName.takeIf(String::isNotBlank)
                ?: savedAppBasePackage(app.currentPackageName)
            if (originalPackageName !in remainingOriginalPackages) {
                enabledTargets.remove(originalPackageName) || changed
            } else {
                changed
            }
        }
        if (changedByLegacyKey) {
            prefs.autoPatchEnabledPackages.update(enabledTargets)
        }
    }

    suspend fun pruneRetainedOriginals() {
        fs.pruneOriginalAppFiles(
            dao.getAllSnapshot().map(::retainedOriginalReference)
        )
    }

    private fun retainedOriginalReference(app: InstalledApp): RetainedOriginalReference {
        val originalPackageName = app.originalPackageName.takeIf(String::isNotBlank)
            ?: savedAppBasePackage(app.currentPackageName)
        val managedVersionCode = fs.getPatchedAppFile(
            app.currentPackageName,
            app.version
        ).takeIf(File::isFile)
            ?.let(pm::getPackageInfo)
            ?.takeIf { info ->
                info.versionName.equals(app.version, ignoreCase = true)
            }
            ?.let(pm::getVersionCode)
        val installedVersionCode = if (
            managedVersionCode == null &&
            app.installType != InstallType.SAVED
        ) {
            pm.getPackageInfo(app.currentPackageName)
                ?.takeIf { info ->
                    info.versionName.equals(app.version, ignoreCase = true)
                }
                ?.let(pm::getVersionCode)
        } else {
            null
        }
        return RetainedOriginalReference(
            packageName = originalPackageName,
            version = app.version,
            versionCode = managedVersionCode ?: installedVersionCode
        )
    }
}
