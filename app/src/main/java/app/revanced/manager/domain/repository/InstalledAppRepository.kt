package app.revanced.manager.domain.repository

import app.revanced.manager.data.room.AppDatabase
import app.revanced.manager.data.room.apps.installed.AppliedPatch
import app.revanced.manager.data.room.apps.installed.InstallType
import app.revanced.manager.data.room.apps.installed.InstalledApp
import app.revanced.manager.data.room.profile.PatchProfilePayload
import app.revanced.manager.util.PatchSelection
import kotlinx.coroutines.flow.distinctUntilChanged

class InstalledAppRepository(
    db: AppDatabase
) {
    private val dao = db.installedAppDao()

    fun getAll() = dao.getAll().distinctUntilChanged()

    suspend fun get(packageName: String) = dao.get(packageName)

    suspend fun getByInstallType(installType: InstallType) =
        dao.getByInstallType(installType)

    suspend fun getAppliedPatches(packageName: String): PatchSelection =
        dao.getPatchesSelection(packageName).mapValues { (_, patches) -> patches.toSet() }

    suspend fun addOrUpdate(
        currentPackageName: String,
        originalPackageName: String,
        version: String,
        installType: InstallType,
        patchSelection: PatchSelection,
        selectionPayload: PatchProfilePayload? = null,
        resetCreatedAt: Boolean = false
    ) {
        val existingApp = dao.get(currentPackageName)
        val existingSortOrder = dao.getSortOrder(currentPackageName)
        val sortOrder = existingSortOrder ?: ((dao.getMaxSortOrder() ?: -1) + 1)
        val createdAt = when {
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

    suspend fun reorderApps(orderedPackageNames: List<String>) {
        orderedPackageNames.forEachIndexed { index, packageName ->
            dao.updateSortOrder(packageName, index)
        }
    }

    suspend fun delete(installedApp: InstalledApp) {
        dao.delete(installedApp)
    }

    suspend fun deleteByInstallType(installType: InstallType) {
        dao.deleteByInstallType(installType)
    }
}
