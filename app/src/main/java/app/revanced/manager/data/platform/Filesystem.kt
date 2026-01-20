package app.revanced.manager.data.platform

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import app.revanced.manager.util.FilenameUtils
import app.revanced.manager.util.RequestManageStorageContract
import java.io.File
import java.nio.file.Path
import java.util.Locale

class Filesystem(private val app: Application) {
    data class StorageRoot(val path: Path, val label: String, val isRemovable: Boolean)

    val contentResolver = app.contentResolver // TODO: move Content Resolver operations to here.

    /**
     * A directory that gets cleared when the app restarts.
     * Do not store paths to this directory in a parcel.
     */
    val tempDir: File = app.getDir("ephemeral", Context.MODE_PRIVATE).apply {
        deleteRecursively()
        mkdirs()
    }

    /**
     * A directory for storing temporary files related to UI.
     * This is the same as [tempDir], but does not get cleared on system-initiated process death.
     * Paths to this directory can be safely stored in parcels.
     */
    val uiTempDir: File = app.getDir("ui_ephemeral", Context.MODE_PRIVATE)
    private val patchedAppsDir: File = app.getDir("patched-apps", Context.MODE_PRIVATE).apply { mkdirs() }
    private val patchProfileInputsDir: File = app.getDir("patch-profile-inputs", Context.MODE_PRIVATE).apply { mkdirs() }

    fun externalFilesDir(): Path = Environment.getExternalStorageDirectory().toPath()

    fun storageRoots(): List<StorageRoot> {
        val roots = LinkedHashMap<String, StorageRoot>()
        val storageManager = app.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        storageManager.storageVolumes.forEach { volume ->
            val directory = resolveStorageVolumeDirectory(volume) ?: return@forEach
            val path = directory.toPath()
            val label = volume.getDescription(app).takeIf { it.isNotBlank() } ?: path.toString()
            roots.putIfAbsent(
                path.toString(),
                StorageRoot(path = path, label = label, isRemovable = volume.isRemovable)
            )
        }

        val primaryPath = Environment.getExternalStorageDirectory().toPath()
        roots.putIfAbsent(
            primaryPath.toString(),
            StorageRoot(path = primaryPath, label = primaryPath.toString(), isRemovable = false)
        )

        return roots.values.toList()
    }

    private fun resolveStorageVolumeDirectory(volume: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return volume.directory
        }
        val pathFile = runCatching {
            val method = StorageVolume::class.java.getDeclaredMethod("getPathFile")
            method.isAccessible = true
            method.invoke(volume) as? File
        }.getOrNull()
        if (pathFile != null) return pathFile

        val path = runCatching {
            val method = StorageVolume::class.java.getDeclaredMethod("getPath")
            method.isAccessible = true
            method.invoke(volume) as? String
        }.getOrNull()
        return path?.let(::File)
    }

    private fun usesManagePermission() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private val storagePermissionName =
        if (usesManagePermission()) Manifest.permission.MANAGE_EXTERNAL_STORAGE else Manifest.permission.WRITE_EXTERNAL_STORAGE

    fun permissionContract(): Pair<ActivityResultContract<String, Boolean>, String> {
        val contract =
            if (usesManagePermission()) RequestManageStorageContract() else ActivityResultContracts.RequestPermission()
        return contract to storagePermissionName
    }

    fun hasStoragePermission() =
        if (usesManagePermission()) Environment.isExternalStorageManager() else app.checkSelfPermission(
            storagePermissionName
        ) == PackageManager.PERMISSION_GRANTED

    fun getPatchedAppFile(packageName: String, version: String): File {
        val safePackage = FilenameUtils.sanitize(packageName)
        val safeVersion = FilenameUtils.sanitize(version.ifBlank { "unspecified" })
        return patchedAppsDir.resolve("${safePackage}_${safeVersion}.apk")
    }

    fun findPatchedAppFile(packageName: String): File? {
        val safePackage = FilenameUtils.sanitize(packageName)
        return patchedAppsDir
            .listFiles { file ->
                file.isFile &&
                    file.name.startsWith("${safePackage}_") &&
                    file.name.endsWith(".apk")
            }
            ?.maxByOrNull { it.lastModified() }
    }

    fun deletePatchedAppFiles(packageName: String): Int {
        val safePackage = FilenameUtils.sanitize(packageName)
        val matches = patchedAppsDir.listFiles { file ->
            file.isFile &&
                file.name.startsWith("${safePackage}_") &&
                file.name.endsWith(".apk")
        } ?: return 0

        var removed = 0
        matches.forEach { file ->
            if (file.delete()) {
                removed++
            }
        }
        return removed
    }

    fun getPatchProfileInputFile(profileId: Int, extension: String): File {
        val sanitized = extension.lowercase(Locale.ROOT).takeIf { it.matches(Regex("^[a-z0-9]{1,10}$")) }
            ?: "apk"
        return patchProfileInputsDir.resolve("profile_${profileId}.$sanitized")
    }
}
