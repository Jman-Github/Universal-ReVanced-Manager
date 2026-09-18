package app.urv.manager.util

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import app.universal.revanced.manager.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * PR builds keep the same package/signature, but never open the release's private storage.
 * Installed before providers and Application.onCreate, including WorkManager initialization.
 * Existing release data is deliberately neither copied nor migrated.
 */
class BuildProfileContext(base: Context) : ContextWrapper(base) {
    private val root: File get() = File(baseContext.getDir(PR_PROFILE_DIRECTORY, Context.MODE_PRIVATE), PR_SCHEMA_DIRECTORY).apply { mkdirs() }
    private fun directory(name: String): File = File(root, name).apply {
        check(mkdirs() || isDirectory) { "Cannot create PR storage: $this" }
    }

    override fun getFilesDir(): File = directory("files")
    // Keep caches beneath the platform roots so Android can reclaim them.
    override fun getCacheDir(): File = profileDirectory(baseContext.cacheDir, null)
    override fun getCodeCacheDir(): File = profileDirectory(baseContext.codeCacheDir, null)
    override fun getNoBackupFilesDir(): File =
        File(File(baseContext.noBackupFilesDir, PR_PROFILE_DIRECTORY), PR_SCHEMA_DIRECTORY).apply { mkdirs() }
    override fun getDataDir(): File = root
    override fun getDir(name: String, mode: Int): File = directory("app_$name")
    override fun getFileStreamPath(name: String): File = File(filesDir, name)
    override fun openFileInput(name: String): FileInputStream = FileInputStream(getFileStreamPath(name))
    override fun openFileOutput(name: String, mode: Int): FileOutputStream =
        FileOutputStream(getFileStreamPath(name), mode and Context.MODE_APPEND != 0)
    override fun deleteFile(name: String): Boolean = getFileStreamPath(name).delete()
    override fun fileList(): Array<String> = filesDir.list() ?: emptyArray()

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        baseContext.getSharedPreferences(prPreferenceName(name), mode)
    override fun deleteSharedPreferences(name: String): Boolean =
        baseContext.deleteSharedPreferences(prPreferenceName(name))

    override fun getDatabasePath(name: String): File {
        val requested = File(name)
        // Room/WorkManager can pass an absolute path inside the selected no-backup directory.
        if (requested.isAbsolute) {
            val path = requested.canonicalFile
            require(path.toPath().startsWith(root.canonicalFile.toPath()) ||
                path.toPath().startsWith(noBackupFilesDir.canonicalFile.toPath())) {
                "Database is outside PR storage: $name"
            }
            return path
        }
        return File(directory("databases"), name)
    }

    override fun openOrCreateDatabase(
        name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?
    ): SQLiteDatabase = baseContext.openOrCreateDatabase(getDatabasePath(name).path, mode, factory)

    override fun openOrCreateDatabase(
        name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
        errorHandler: DatabaseErrorHandler?
    ): SQLiteDatabase =
        baseContext.openOrCreateDatabase(getDatabasePath(name).path, mode, factory, errorHandler)

    override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    override fun databaseList(): Array<String> = directory("databases").list() ?: emptyArray()

    override fun getExternalFilesDir(type: String?): File? =
        baseContext.getExternalFilesDir(null)?.let { profileDirectory(it, type) }
    override fun getExternalFilesDirs(type: String?): Array<File?> =
        baseContext.getExternalFilesDirs(null).map { it?.let { profileDirectory(it, type) } }.toTypedArray()
    override fun getExternalCacheDir(): File? =
        baseContext.externalCacheDir?.let { profileDirectory(it, null) }
    override fun getExternalCacheDirs(): Array<File?> =
        baseContext.externalCacheDirs.map { it?.let { profileDirectory(it, null) } }.toTypedArray()

    private fun profileDirectory(base: File, type: String?): File =
        File(File(base, PR_PROFILE_DIRECTORY), PR_SCHEMA_DIRECTORY).let { if (type == null) it else File(it, type) }
            .apply { mkdirs() }

    companion object {
        fun wrap(context: Context): Context =
            if (BuildConfig.IS_PR_TEST_BUILD && context !is BuildProfileContext) BuildProfileContext(context) else context
    }
}

internal const val PR_PROFILE_DIRECTORY = "pr_profile"
internal val PR_SCHEMA_DIRECTORY = "schema-${BuildConfig.DATABASE_VERSION}"
internal fun prPreferenceName(name: String): String = "pr_profile_${BuildConfig.DATABASE_VERSION}_$name"

/**
 * Activities/providers use the Application's selected storage profile. Standalone app_process
 * package contexts have no Application, so select the profile directly for those contexts.
 */
val Context.managerStorageContext: Context
    get() = applicationContext ?: BuildProfileContext.wrap(this)

/** Used by the storage screen instead of applicationInfo.dataDir, which is always package-wide. */
val Context.managerStorageRoot: File get() =
    if (BuildConfig.IS_PR_TEST_BUILD) File(File(applicationInfo.dataDir, "app_$PR_PROFILE_DIRECTORY"), PR_SCHEMA_DIRECTORY)
    else File(applicationInfo.dataDir)

/** Read only: never create, upgrade or replace the release database from a PR build. */
internal fun releaseDatabaseVersion(context: Context): Int = runCatching {
    val file = File(context.applicationInfo.dataDir, "databases/manager")
    if (!file.exists()) 0 else SQLiteDatabase.openDatabase(
        file.path, null, SQLiteDatabase.OPEN_READONLY
    ).use { it.version }
}.getOrDefault(Int.MAX_VALUE)
