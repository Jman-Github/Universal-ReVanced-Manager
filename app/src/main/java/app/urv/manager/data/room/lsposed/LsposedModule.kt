package app.urv.manager.data.room.lsposed

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lsposed_modules")
data class LsposedModule(
    @PrimaryKey @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "installed_version") val installedVersion: String,
    @ColumnInfo(name = "installed_version_code") val installedVersionCode: Long,
    @ColumnInfo(name = "source_kind") val sourceKind: String,
    @ColumnInfo(name = "source_reference") val sourceReference: String,
    @ColumnInfo(name = "release_tag") val releaseTag: String? = null,
    @ColumnInfo(name = "asset_name") val assetName: String? = null,
    @ColumnInfo(name = "asset_digest") val assetDigest: String? = null,
    @ColumnInfo(name = "signing_fingerprint") val signingFingerprint: String,
    @ColumnInfo(name = "latest_version") val latestVersion: String? = null,
    @ColumnInfo(name = "latest_asset_digest") val latestAssetDigest: String? = null,
    @ColumnInfo(name = "last_update_check") val lastUpdateCheck: Long? = null,
    @ColumnInfo(name = "update_available") val updateAvailable: Boolean = false,
)
