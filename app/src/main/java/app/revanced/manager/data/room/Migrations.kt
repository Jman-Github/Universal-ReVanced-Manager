package app.revanced.manager.data.room

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.System

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE patch_bundles ADD COLUMN display_name TEXT")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS patch_profiles (
                uid INTEGER NOT NULL,
                package_name TEXT NOT NULL,
                app_version TEXT,
                name TEXT NOT NULL,
                payload TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                PRIMARY KEY(uid)
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE patch_bundles ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0")

        db.query("SELECT uid FROM patch_bundles ORDER BY CASE WHEN uid = 0 THEN 0 ELSE rowid END").use { cursor ->
            var index = 0
            while (cursor.moveToNext()) {
                val uid = cursor.getInt(0)
                db.execSQL("UPDATE patch_bundles SET sort_order = ? WHERE uid = ?", arrayOf(index, uid))
                index += 1
            }
        }
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE installed_app ADD COLUMN selection_payload TEXT")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE patch_bundles ADD COLUMN created_at INTEGER")
        db.execSQL("ALTER TABLE patch_bundles ADD COLUMN updated_at INTEGER")

        val now = System.currentTimeMillis()
        db.execSQL("UPDATE patch_bundles SET created_at = ? WHERE created_at IS NULL", arrayOf(now))
        db.execSQL("UPDATE patch_bundles SET updated_at = ? WHERE updated_at IS NULL", arrayOf(now))
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE patch_bundles ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE patch_bundles ADD COLUMN search_update INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE patch_bundles ADD COLUMN last_notified_version TEXT")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE installed_app ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE patch_profiles ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0")

        db.query("SELECT current_package_name FROM installed_app ORDER BY rowid ASC").use { cursor ->
            var index = 0
            while (cursor.moveToNext()) {
                val packageName = cursor.getString(0)
                db.execSQL(
                    "UPDATE installed_app SET sort_order = ? WHERE current_package_name = ?",
                    arrayOf(index, packageName)
                )
                index += 1
            }
        }

        db.query("SELECT uid FROM patch_profiles ORDER BY created_at DESC, uid DESC").use { cursor ->
            var index = 0
            while (cursor.moveToNext()) {
                val uid = cursor.getInt(0)
                db.execSQL("UPDATE patch_profiles SET sort_order = ? WHERE uid = ?", arrayOf(index, uid))
                index += 1
            }
        }
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE patch_profiles ADD COLUMN apk_path TEXT")
        db.execSQL("ALTER TABLE patch_profiles ADD COLUMN apk_version TEXT")
    }
}

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE patch_profiles ADD COLUMN auto_patch INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE patch_profiles ADD COLUMN apk_source_path TEXT")
    }
}

val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS installed_app_new (
                current_package_name TEXT NOT NULL,
                original_package_name TEXT NOT NULL,
                version TEXT NOT NULL,
                install_type TEXT NOT NULL,
                sort_order INTEGER NOT NULL,
                selection_payload TEXT,
                created_at INTEGER NOT NULL,
                PRIMARY KEY(current_package_name)
            )
            """.trimIndent()
        )

        val now = System.currentTimeMillis()
        val insertSql = """
            INSERT INTO installed_app_new (
                current_package_name,
                original_package_name,
                version,
                install_type,
                sort_order,
                selection_payload,
                created_at
            )
            SELECT
                current_package_name,
                original_package_name,
                version,
                install_type,
                sort_order,
                selection_payload,
                ?
            FROM installed_app
        """.trimIndent()
        db.execSQL(insertSql, arrayOf(now))

        db.execSQL("DROP TABLE installed_app")
        db.execSQL("ALTER TABLE installed_app_new RENAME TO installed_app")
    }
}
