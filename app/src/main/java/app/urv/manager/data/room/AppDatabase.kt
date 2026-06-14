package app.urv.manager.data.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.urv.manager.data.room.apps.downloaded.DownloadedAppDao
import app.urv.manager.data.room.apps.downloaded.DownloadedApp
import app.urv.manager.data.room.apps.installed.AppliedPatch
import app.urv.manager.data.room.apps.installed.InstalledApp
import app.urv.manager.data.room.apps.installed.InstalledAppDao
import app.urv.manager.data.room.selection.PatchSelection
import app.urv.manager.data.room.selection.SelectedPatch
import app.urv.manager.data.room.selection.SeenPatch
import app.urv.manager.data.room.selection.SelectionDao
import app.urv.manager.data.room.bundles.PatchBundleDao
import app.urv.manager.data.room.bundles.PatchBundleEntity
import app.urv.manager.data.room.options.Option
import app.urv.manager.data.room.options.OptionDao
import app.urv.manager.data.room.options.OptionGroup
import app.urv.manager.data.room.plugins.TrustedDownloaderPlugin
import app.urv.manager.data.room.plugins.TrustedDownloaderPluginDao
import app.urv.manager.data.room.profile.PatchProfileDao
import app.urv.manager.data.room.profile.PatchProfileEntity
import kotlin.random.Random

@Database(
    entities = [PatchBundleEntity::class, PatchSelection::class, SelectedPatch::class, SeenPatch::class, DownloadedApp::class, InstalledApp::class, AppliedPatch::class, OptionGroup::class, Option::class, TrustedDownloaderPlugin::class, PatchProfileEntity::class],
    version = 15
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

    companion object {
        fun generateUid() = Random.Default.nextInt()
    }
}
