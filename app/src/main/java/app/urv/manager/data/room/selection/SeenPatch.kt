package app.urv.manager.data.room.selection

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import app.urv.manager.data.room.bundles.PatchBundleEntity

@Entity(
    tableName = "seen_patches",
    primaryKeys = ["patch_bundle", "package_name", "patch_name"],
    foreignKeys = [ForeignKey(
        PatchBundleEntity::class,
        parentColumns = ["uid"],
        childColumns = ["patch_bundle"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class SeenPatch(
    @ColumnInfo(name = "patch_bundle") val patchBundle: Int,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "patch_name") val patchName: String
)
