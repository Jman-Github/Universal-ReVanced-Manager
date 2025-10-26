package app.revanced.manager.domain.repository

import app.revanced.manager.data.room.AppDatabase
import app.revanced.manager.data.room.profile.PatchProfileEntity
import app.revanced.manager.data.room.profile.PatchProfilePayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

class PatchProfileRepository(
    db: AppDatabase
) {
    private val dao = db.patchProfileDao()

    fun profilesFlow(): Flow<List<PatchProfile>> =
        dao.observeAll().map(List<PatchProfileEntity>::toDomain)

    fun profilesForPackageFlow(packageName: String): Flow<List<PatchProfile>> =
        dao.observeForPackage(packageName).map(List<PatchProfileEntity>::toDomain)

    suspend fun createProfile(
        packageName: String,
        appVersion: String?,
        name: String,
        payload: PatchProfilePayload
    ): PatchProfile {
        val entity = PatchProfileEntity(
            uid = AppDatabase.generateUid(),
            packageName = packageName,
            appVersion = appVersion,
            name = name,
            payload = payload,
            createdAt = System.currentTimeMillis()
        )
        dao.upsert(entity)
        return entity.toDomain()
    }

    suspend fun deleteProfile(uid: Int) = dao.delete(uid)

    suspend fun deleteProfiles(uids: Collection<Int>) {
        if (uids.isEmpty()) return
        dao.delete(uids.toList())
    }

    suspend fun updateProfile(
        uid: Int,
        packageName: String,
        appVersion: String?,
        name: String,
        payload: PatchProfilePayload
    ): PatchProfile? {
        val existing = dao.get(uid) ?: return null
        val entity = existing.copy(
            packageName = packageName,
            appVersion = appVersion,
            name = name,
            payload = payload,
            createdAt = System.currentTimeMillis()
        )
        dao.upsert(entity)
        return entity.toDomain()
    }

    suspend fun getProfile(uid: Int): PatchProfile? = dao.get(uid)?.toDomain()

    suspend fun exportProfiles(): List<PatchProfileExportEntry> =
        dao.getAll().map(PatchProfileEntity::toExportEntry)

    suspend fun importProfiles(entries: Collection<PatchProfileExportEntry>): Int {
        if (entries.isEmpty()) return 0
        val entities = entries.map(PatchProfileExportEntry::toEntity)
        dao.insertAll(entities)
        return entities.size
    }
}

data class PatchProfile(
    val uid: Int,
    val packageName: String,
    val appVersion: String?,
    val name: String,
    val createdAt: Long,
    val payload: PatchProfilePayload
)

@Serializable
data class PatchProfileExportEntry(
    val name: String,
    val packageName: String,
    val appVersion: String?,
    val createdAt: Long?,
    val payload: PatchProfilePayload
)

private fun PatchProfileEntity.toDomain() = PatchProfile(
    uid = uid,
    packageName = packageName,
    appVersion = appVersion,
    name = name,
    createdAt = createdAt,
    payload = payload
)

private fun List<PatchProfileEntity>.toDomain() = map(PatchProfileEntity::toDomain)

private fun PatchProfileEntity.toExportEntry() = PatchProfileExportEntry(
    name = name,
    packageName = packageName,
    appVersion = appVersion,
    createdAt = createdAt,
    payload = payload
)

private fun PatchProfileExportEntry.toEntity() = PatchProfileEntity(
    uid = AppDatabase.generateUid(),
    packageName = packageName,
    appVersion = appVersion,
    name = name,
    payload = payload,
    createdAt = createdAt ?: System.currentTimeMillis()
)
