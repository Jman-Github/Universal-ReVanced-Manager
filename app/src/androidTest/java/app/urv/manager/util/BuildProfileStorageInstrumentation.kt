package app.urv.manager.util

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Bundle
import java.io.File

/**
 * Runs in a disposable test package. Never point this runner at a user's manager installation.
 * Exercises the production context on Android, including SQLiteOpenHelper's open path.
 */
class BuildProfileStorageInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val results = Bundle()
        try {
            val release = targetContext
            check(release.packageName == "app.urv.storageisolationtest") { "Disposable package required" }
            val pr = BuildProfileContext(release)
            check(release.applicationContext.filesDir == pr.filesDir) { "Application startup did not select PR storage" }
            val normalDatabase = release.getDatabasePath("manager")
            normalDatabase.parentFile!!.mkdirs()
            open(release, 18).use { db ->
                db.execSQL("INSERT OR REPLACE INTO records VALUES (1, 'release')")
            }
            release.getSharedPreferences("settings", 0).edit().putString("value", "release").commit()
            File(release.getDir("signing", 0), "key").writeText("release-key")
            File(release.filesDir, "background").writeText("release-background")
            File(release.getDir("patch_bundles", 0), "bundle").writeText("release-bundle")

            check(pr.getDatabasePath("manager") != normalDatabase)
            check(pr.getSharedPreferences("settings", 0).getString("value", null) == null)
            check(!File(pr.getDir("signing", 0), "key").exists())
            check(!File(pr.filesDir, "background").exists())
            open(pr, 20).use { db ->
                db.execSQL("INSERT OR REPLACE INTO records VALUES (1, 'pr')")
            }
            pr.getSharedPreferences("settings", 0).edit().putString("value", "pr").commit()
            File(pr.getDir("signing", 0), "key").writeText("pr-key")
            File(pr.filesDir, "background").writeText("pr-background")
            File(pr.getDir("patch_bundles", 0), "bundle").writeText("pr-bundle")
            pr.openFileOutput("file-api", 0).use { it.write("pr".toByteArray()) }
            check(!File(release.filesDir, "file-api").exists())
            check(pr.openFileInput("file-api").bufferedReader().use { it.readText() } == "pr")
            check(pr.cacheDir != release.cacheDir && pr.codeCacheDir != release.codeCacheDir)
            check(pr.cacheDir.canonicalFile.toPath().startsWith(release.cacheDir.canonicalFile.toPath()))
            check(pr.codeCacheDir.canonicalFile.toPath().startsWith(release.codeCacheDir.canonicalFile.toPath()))
            check(pr.noBackupFilesDir != release.noBackupFilesDir)
            val workerDb = File(pr.noBackupFilesDir, "work.db")
            pr.openOrCreateDatabase(workerDb.path, 0, null).close()
            check(workerDb.exists())
            check(runCatching { pr.getDatabasePath(normalDatabase.path) }.isFailure)
            check(pr.getExternalFilesDir("keystore") != release.getExternalFilesDir("keystore"))

            // Switch back: the old reader must still open schema 18 and see its original records.
            open(release, 18).use { db ->
                check(db.version == 18)
                db.rawQuery("SELECT value FROM records", null).use { c ->
                    check(c.moveToFirst() && c.getString(0) == "release")
                }
            }
            check(release.getSharedPreferences("settings", 0).getString("value", null) == "release")
            check(File(release.getDir("signing", 0), "key").readText() == "release-key")
            check(File(release.filesDir, "background").readText() == "release-background")
            check(File(release.getDir("patch_bundles", 0), "bundle").readText() == "release-bundle")
            check(releaseDatabaseVersion(release) == 18)

            // Re-entering a PR must keep the earlier PR data.
            val nextPr = BuildProfileContext(release)
            open(nextPr, 20).use { db ->
                db.rawQuery("SELECT value FROM records", null).use { c ->
                    check(c.moveToFirst() && c.getString(0) == "pr")
                }
            }
            check(nextPr.getSharedPreferences("settings", 0).getString("value", null) == "pr")
            nextPr.deleteDatabase("manager")
            check(normalDatabase.exists())
            check(releaseDatabaseVersion(release) == 18)
            results.putString("stream", "PASS: release 18 -> PR 20 -> release 18 -> PR 20; database, preferences, keys, bundles, files and no-backup paths isolated.")
            finish(Activity.RESULT_OK, results)
        } catch (error: Throwable) {
            results.putString("stream", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, results)
        }
    }

    private fun open(context: Context, version: Int): SQLiteDatabase =
        object : SQLiteOpenHelper(context, "manager", null, version) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE records (id INTEGER PRIMARY KEY, value TEXT)")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                error("Unexpected shared-database migration: $oldVersion -> $newVersion")
            }
        }.writableDatabase
}

class BuildProfileTestApplication : android.app.Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(BuildProfileContext.wrap(base))
    }
}
