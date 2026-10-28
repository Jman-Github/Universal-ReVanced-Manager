package app.universal.patch.manager.data.room

import app.universal.patch.manager.BuildConfig
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.universal.patch.manager.data.room.apps.downloaded.DownloadedAppDao
import app.universal.patch.manager.data.room.apps.downloaded.DownloadedApp
import app.universal.patch.manager.data.room.apps.installed.AppliedPatch
import app.universal.patch.manager.data.room.apps.installed.InstalledApp
import app.universal.patch.manager.data.room.apps.installed.InstalledAppDao
import app.universal.patch.manager.data.room.selection.PatchSelection
import app.universal.patch.manager.data.room.selection.SelectedPatch
import app.universal.patch.manager.data.room.selection.SeenPatch
import app.universal.patch.manager.data.room.selection.SelectionDao
import app.universal.patch.manager.data.room.bundles.PatchBundleDao
import app.universal.patch.manager.data.room.bundles.PatchBundleEntity
import app.universal.patch.manager.data.room.options.Option
import app.universal.patch.manager.data.room.options.OptionDao
import app.universal.patch.manager.data.room.options.OptionGroup
import app.universal.patch.manager.data.room.plugins.TrustedDownloaderPlugin
import app.universal.patch.manager.data.room.plugins.TrustedDownloaderPluginDao
import app.universal.patch.manager.data.room.profile.PatchProfileDao
import app.universal.patch.manager.data.room.profile.PatchProfileEntity
import app.universal.patch.manager.data.room.lsposed.LsposedModule
import app.universal.patch.manager.data.room.lsposed.LsposedModuleDao
import kotlin.random.Random

@Database(
    entities = [PatchBundleEntity::class, PatchSelection::class, SelectedPatch::class, SeenPatch::class, DownloadedApp::class, InstalledApp::class, AppliedPatch::class, OptionGroup::class, Option::class, TrustedDownloaderPlugin::class, PatchProfileEntity::class, LsposedModule::class],
    version = BuildConfig.DATABASE_VERSION
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun patchBundleDao(): PatchBundleDao
    abstract fun selectionDao(): SelectionDao
    abstract fun downloadedAppDao(): DownloadedAppDao
    abstract fun installedAppDao(): InstalledAppDao
    abstract fun optionDao(): OptionDao
    abstract fun trustedDownloaderPluginDao(): TrustedDownloaderPluginDao
    abstract fun patchProfileDao(): PatchProfileDao
    abstract fun lsposedModuleDao(): LsposedModuleDao

    companion object {
        fun generateUid() = Random.Default.nextInt()
    }
}
