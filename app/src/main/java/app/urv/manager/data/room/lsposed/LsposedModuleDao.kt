package app.urv.manager.data.room.lsposed

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LsposedModuleDao {
    @Query("SELECT * FROM lsposed_modules ORDER BY display_name COLLATE NOCASE, package_name")
    fun observeAll(): Flow<List<LsposedModule>>

    @Query("SELECT * FROM lsposed_modules WHERE package_name = :packageName LIMIT 1")
    suspend fun get(packageName: String): LsposedModule?

    @Upsert
    suspend fun upsert(module: LsposedModule)

    @Query("DELETE FROM lsposed_modules WHERE package_name = :packageName")
    suspend fun delete(packageName: String)

    @Query("DELETE FROM lsposed_modules WHERE source_kind = :sourceKind")
    suspend fun deleteBySourceKind(sourceKind: String)
}
