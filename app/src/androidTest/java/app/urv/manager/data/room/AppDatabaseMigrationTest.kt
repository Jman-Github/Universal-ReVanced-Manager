package app.urv.manager.data.room

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate16To18CreatesLsposedModulesTable() {
        migrationHelper.createDatabase(DATABASE_16, 16).close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            DATABASE_16,
            18,
            true,
            MIGRATION_16_17,
            MIGRATION_17_18,
        )
        try {
            assertEquals(EXPECTED_COLUMNS, migrated.lsposedModuleColumns())
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migrate17To18PreservesModuleAndRemovesLegacySettingsColumn() {
        migrationHelper.createDatabase(DATABASE_17, 17).apply {
            execSQL(
                """
                INSERT INTO lsposed_modules (
                    package_name,
                    display_name,
                    installed_version,
                    installed_version_code,
                    source_kind,
                    source_reference,
                    release_tag,
                    asset_name,
                    asset_digest,
                    signing_fingerprint,
                    has_settings_activity,
                    latest_version,
                    latest_asset_digest,
                    last_update_check,
                    update_available
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    PACKAGE_NAME,
                    "Test module",
                    "1.0",
                    10L,
                    "GITHUB_REPOSITORY",
                    "https://github.com/example/module",
                    "v1.0",
                    "module.apk",
                    "a".repeat(64),
                    "AA:BB",
                    1,
                    "v1.1",
                    "b".repeat(64),
                    1234L,
                    1,
                ),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            DATABASE_17,
            18,
            true,
            MIGRATION_17_18,
        )
        try {
            val columns = migrated.lsposedModuleColumns()
            assertEquals(EXPECTED_COLUMNS, columns)
            assertFalse(columns.contains("has_settings_activity"))

            migrated.query(
                """
                SELECT package_name, display_name, installed_version,
                    installed_version_code, source_kind, source_reference,
                    signing_fingerprint, update_available
                FROM lsposed_modules
                WHERE package_name = ?
                """.trimIndent(),
                arrayOf(PACKAGE_NAME),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(PACKAGE_NAME, cursor.getString(0))
                assertEquals("Test module", cursor.getString(1))
                assertEquals("1.0", cursor.getString(2))
                assertEquals(10L, cursor.getLong(3))
                assertEquals("GITHUB_REPOSITORY", cursor.getString(4))
                assertEquals("https://github.com/example/module", cursor.getString(5))
                assertEquals("AA:BB", cursor.getString(6))
                assertEquals(1, cursor.getInt(7))
            }
        } finally {
            migrated.close()
        }
    }

    private fun SupportSQLiteDatabase.lsposedModuleColumns(): List<String> =
        query("PRAGMA table_info(`lsposed_modules`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }

    private companion object {
        const val DATABASE_16 = "lsposed-migration-16"
        const val DATABASE_17 = "lsposed-migration-17"
        const val PACKAGE_NAME = "com.example.module"

        val EXPECTED_COLUMNS = listOf(
            "package_name",
            "display_name",
            "installed_version",
            "installed_version_code",
            "source_kind",
            "source_reference",
            "release_tag",
            "asset_name",
            "asset_digest",
            "signing_fingerprint",
            "latest_version",
            "latest_asset_digest",
            "last_update_check",
            "update_available",
        )
    }
}
