package app.revanced.manager.data.room.profile

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "patch_profiles")
data class PatchProfileEntity(
    @PrimaryKey @ColumnInfo(name = "uid") val uid: Int,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "app_version") val appVersion: String?,
    @ColumnInfo(name = "apk_path") val apkPath: String?,
    @ColumnInfo(name = "apk_source_path") val apkSourcePath: String?,
    @ColumnInfo(name = "apk_version") val apkVersion: String?,
    @ColumnInfo(name = "auto_patch") val autoPatch: Boolean,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "payload") val payload: PatchProfilePayload,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "sort_order") val sortOrder: Int
)
