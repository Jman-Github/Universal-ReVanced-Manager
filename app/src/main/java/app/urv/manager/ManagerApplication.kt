package app.urv.manager

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ShortcutManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import app.urv.manager.data.platform.Filesystem
import app.urv.manager.di.*
import app.urv.manager.domain.batch.batchOriginalPackageName
import app.urv.manager.domain.batch.retainedBatchOutputPaths
import app.urv.manager.domain.manager.PreferencesManager
import app.urv.manager.domain.installer.RootInstaller
import app.urv.manager.domain.installer.root.RootMountResult
import app.urv.manager.domain.installer.root.RootMountTransactionCoordinator
import app.urv.manager.domain.manager.SearchForUpdatesBackgroundInterval
import app.urv.manager.domain.repository.DownloaderPluginRepository
import app.urv.manager.domain.repository.InstalledAppRepository
import app.urv.manager.domain.repository.PatchBundleRepository
import app.urv.manager.domain.repository.PatchProfileRepository
import app.urv.manager.domain.repository.PatcherRuntimePluginRepository
import app.urv.manager.domain.worker.BundleUpdateWebSocketCoordinator
import app.urv.manager.domain.worker.WorkerRepository
import app.urv.manager.patcher.worker.PatcherWorker
import app.urv.manager.patcher.worker.AutoPatchWorker
import app.urv.manager.patcher.worker.reconcileAutoPatchNotificationPermission
import app.urv.manager.patcher.morphe.MorpheRuntimeBridge
import app.urv.manager.patcher.revanced.Revanced21RuntimeBridge
import app.urv.manager.patcher.revanced.Revanced22RuntimeBridge
import app.urv.manager.patcher.runtime.PatcherRuntimePluginRegistry
import app.urv.manager.worker.RootMountReconcileWorker
import app.urv.manager.worker.RootMountReconciliationScheduler
import app.urv.manager.network.service.HttpService
import app.urv.manager.util.AppForeground
import app.urv.manager.util.DownloadProgressNotifier
import app.urv.manager.util.tag
import app.urv.manager.util.PatchListCatalog
import app.urv.manager.util.SplitMergeNotification
import app.urv.manager.util.BatchPatchIntents
import app.urv.manager.util.PM
import app.urv.manager.util.applyAppLanguage
import app.urv.manager.util.savedAppLauncherShortcutCapacity
import app.universal.revanced.manager.BuildConfig
import app.universal.revanced.manager.R
import kotlinx.coroutines.Dispatchers
import coil3.SingletonImageLoader
import coil3.ImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.svg.SvgDecoder
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.internal.BuilderImpl
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File

class ManagerApplication : Application() {
    private val scope = MainScope()
    private val prefs: PreferencesManager by inject()
    private val patchBundleRepository: PatchBundleRepository by inject()
    private val patchProfileRepository: PatchProfileRepository by inject()
    private val downloaderPluginRepository: DownloaderPluginRepository by inject()
    private val patcherRuntimePluginRepository: PatcherRuntimePluginRepository by inject()
    private val workerRepository: WorkerRepository by inject()
    private val bundleUpdateWebSocketCoordinator: BundleUpdateWebSocketCoordinator by inject()
    private val fs: Filesystem by inject()
    private val httpService: HttpService by inject()
    private val downloadProgressNotifier: DownloadProgressNotifier by inject()
    private val rootInstaller: RootInstaller by inject()
    private val rootMountCoordinator: RootMountTransactionCoordinator by inject()
    private val installedAppRepository: InstalledAppRepository by inject()
    private val pm: PM by inject()
    private val json: Json by inject()

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@ManagerApplication)
            androidLogger()
            workManagerFactory()
            modules(
                httpModule,
                preferencesModule,
                repositoryModule,
                serviceModule,
                managerModule,
                workerModule,
                viewModelModule,
                databaseModule,
                rootModule,
                ackpineModule
            )
        }

        downloadProgressNotifier.clearStaleNotifications()
        PatchListCatalog.initialize(this)
        MorpheRuntimeBridge.initialize(this)
        Revanced21RuntimeBridge.initialize(this)
        Revanced22RuntimeBridge.initialize(this)
        PatcherRuntimePluginRegistry.install {
            patcherRuntimePluginRepository.loadedRuntimeSnapshot
        }
        runCatching {
            runBlocking(Dispatchers.IO) {
                patcherRuntimePluginRepository.reload()
            }
        }.onFailure {
            Log.e(tag, "Failed to load patcher runtime plugins", it)
        }

        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components {
                    add(SvgDecoder.Factory())
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        add(AnimatedImageDecoder.Factory())
                    } else {
                        add(GifDecoder.Factory())
                    }
                }
                .build()
        }

        val shellBuilder = BuilderImpl.create().setFlags(Shell.FLAG_MOUNT_MASTER)
        Shell.setDefaultBuilder(shellBuilder)
        RootMountReconciliationScheduler.syncReceiverEnabledState(this)

        bundleUpdateWebSocketCoordinator.start()
        observeLauncherShortcuts()

        scope.launch {
            prefs.preload()
            runCatching {
                fs.pruneBatchPatchOutputFiles(
                    retainedPaths = retainedBatchOutputPaths(
                        json,
                        listOf(
                            prefs.lastBatchPatchResult.get(),
                            prefs.lastAutoPatchResult.get()
                        )
                    ),
                    olderThanTimestampMillis =
                        System.currentTimeMillis() - BATCH_OUTPUT_STALE_AGE_MILLIS
                )
            }.onFailure { error ->
                Log.w(tag, "Failed to prune stale batch patch outputs", error)
            }
            prefs.enableManagerPrereleasesForVersion(BuildConfig.VERSION_NAME)
            prefs.migrateAnnouncementPushNotificationInterval()
            prefs.migrateDashboardBundleBannerState()
            prefs.migrateSplitModuleSortModeSeparation()
            prefs.migrateLegacyShizukuPlayStoreMode()
            runCatching {
                patchProfileRepository.migrateLegacyShizukuInstallerTokens()
            }.onFailure { error ->
                Log.w(tag, "Failed to migrate legacy patch-profile installer settings", error)
            }
            workerRepository.ensureBundleUpdateNotificationWork(
                prefs.searchForUpdatesBackgroundInterval.get()
            )
            workerRepository.ensureManagerUpdateNotificationWork(
                prefs.searchForManagerUpdatesBackgroundInterval.get()
            )
            workerRepository.ensureAnnouncementNotificationWork(
                if (prefs.announcementSystemEnabled.get()) {
                    prefs.announcementPushNotificationInterval.get()
                } else {
                    SearchForUpdatesBackgroundInterval.NEVER
                }
            )
            workerRepository.ensureAutoClearCacheWork(prefs.autoClearCacheInterval.get())
            if (prefs.autoPatchEnabled.get()) {
                AutoPatchWorker.schedule(
                    this@ManagerApplication,
                    prefs,
                    prefs.autoPatchInterval.get(),
                    prefs.autoPatchRequiresCharging.get()
                )
            } else {
                if (
                    prefs.autoPatchInstallWithShizuku.get() ||
                    prefs.autoPatchUninstallOnConflictWithShizuku.get()
                ) {
                    prefs.updateAutoPatchEnabled(false)
                }
                AutoPatchWorker.cancel(this@ManagerApplication)
            }
            val currentApi = prefs.api.get()
            if (currentApi == LEGACY_MANAGER_REPO_URL || currentApi == LEGACY_MANAGER_REPO_API_URL) {
                prefs.api.update(DEFAULT_API_URL)
            }
            val storedLanguage = prefs.appLanguage.get().ifBlank { "system" }
            if (storedLanguage != prefs.appLanguage.get()) {
                prefs.appLanguage.update(storedLanguage)
            }
            applyAppLanguage(storedLanguage)
            scope.launch(Dispatchers.IO) {
                val reconciliationExpected = RootMountReconciliationScheduler.isEnabled(this@ManagerApplication)
                val rootAlreadyAvailable = rootInstaller.peekRootAccess() == true
                if ((reconciliationExpected || rootAlreadyAvailable) &&
                    runCatching { rootInstaller.hasRootAccess() }.getOrDefault(false)
                ) {
                    val userId = android.os.Process.myUid() / 100_000
                    runCatching { rootMountCoordinator.recoverIncompleteTransactions(userId) }
                        .onFailure { Log.e(tag, "Failed to scan incomplete root mount transactions", it) }
                        .onSuccess(::notifyRootMountResults)
                    runCatching {
                        rootMountCoordinator.reconcileCommittedTransactions(userId)
                    }.onFailure { Log.e(tag, "Failed to reconcile committed root mounts", it) }
                        .onSuccess(::notifyRootMountResults)
                }
            }
            scope.launch(Dispatchers.Default) {
                runCatching {
                    with(downloaderPluginRepository) {
                        reload()
                        updateCheck()
                    }
                }.onFailure {
                    Log.e(tag, "Failed to initialize downloader plugins", it)
                }
            }
            scope.launch(Dispatchers.Default) {
                runCatching {
                    with(patcherRuntimePluginRepository) {
                        reload()
                        updateCheck()
                    }
                }.onFailure {
                    Log.e(tag, "Failed to initialize patcher runtime plugins", it)
                }
            }
            scope.launch(Dispatchers.Default) {
                PatchListCatalog.refreshIfNeeded(httpService)
            }
            scope.launch(Dispatchers.Default) {
                with(patchBundleRepository) {
                    reload()
                    updateCheck()
                }
            }
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var firstActivityCreated = false

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is MainActivity) {
                    AppForeground.onMainTaskOpened()
                }
                if (firstActivityCreated) return
                firstActivityCreated = true

                // We do not want to call onFreshProcessStart() if there is state to restore.
                // This can happen on system-initiated process death.
                if (savedInstanceState == null) {
                    Log.d(tag, "Fresh process created")
                    onFreshProcessStart()
                } else Log.d(tag, "System-initiated process death detected")
                SplitMergeNotification.clear(this@ManagerApplication)
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {
                AppForeground.onResumed()
                bundleUpdateWebSocketCoordinator.onAppForegroundChanged(true)
                scope.launch {
                    if (prefs.autoPatchEnabled.get()) {
                        reconcileAutoPatchNotificationPermission(
                            this@ManagerApplication,
                            prefs
                        )
                    }
                }
            }
            override fun onActivityPaused(activity: Activity) {
                AppForeground.onPaused()
                bundleUpdateWebSocketCoordinator.onAppForegroundChanged(false)
            }
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                if (
                    activity is MainActivity &&
                    activity.isFinishing &&
                    !activity.isChangingConfigurations
                ) {
                    AppForeground.onMainTaskClosed()
                    cancelActivePatchingOnAppClose()
                }
            }
        })
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)

        // Apply stored app language as early as possible using DataStore, but never crash startup.
        val storedLang = runCatching {
            base?.let {
                runBlocking { PreferencesManager(it).appLanguage.get() }.ifBlank { "en" }
            }
        }.getOrNull() ?: "en"
        applyAppLanguage(storedLang)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("L")
        }
    }

    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/pull/795
    private fun observeLauncherShortcuts() {
        scope.launch(Dispatchers.IO) {
            combine(
                installedAppRepository.getAll(),
                prefs.savedAppLauncherShortcutPackages.flow
            ) { installedApps, requestedPackages ->
                installedApps to requestedPackages
            }.collect { (installedApps, requestedPackages) ->
                val capacity = savedAppLauncherShortcutCapacity(this@ManagerApplication)
                val normalizedPackages = installedApps
                    .asSequence()
                    .filter(::hasSavedAppCopy)
                    .sortedByDescending { it.createdAt }
                    .map(::batchOriginalPackageName)
                    .distinct()
                    .filter { it in requestedPackages }
                    .take(capacity)
                    .toSet()
                if (normalizedPackages != requestedPackages) {
                    prefs.savedAppLauncherShortcutPackages.update(normalizedPackages)
                }
                publishLauncherShortcuts(installedApps, normalizedPackages)
            }
        }
    }

    private fun publishLauncherShortcuts(
        installedApps: List<app.urv.manager.data.room.apps.installed.InstalledApp>,
        enabledPackages: Set<String>
    ) {
        val maxShortcuts = ShortcutManagerCompat.getMaxShortcutCountPerActivity(this)
        if (maxShortcuts <= 0) return

        val shortcutManager = getSystemService(ShortcutManager::class.java)
        val fallbackIconSize = (96 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val shortcutIconSize = shortcutManager
            ?.let { minOf(it.iconMaxWidth, it.iconMaxHeight) }
            ?.takeIf { it > 0 }
            ?: fallbackIconSize
        val shortcuts = mutableListOf(
            batchShortcut(
                id = "check_patch_updates",
                shortLabel = getString(R.string.shortcut_check_patch_updates_short),
                longLabel = getString(R.string.shortcut_check_patch_updates),
                rank = 0,
                action = BatchPatchIntents.ACTION_CHECK_UPDATES,
                icon = IconCompat.createWithResource(
                    this,
                    R.drawable.ic_shortcut_update_repatch
                )
            )
        )

        installedApps.asSequence()
            .filter(::hasSavedAppCopy)
            .sortedByDescending { it.createdAt }
            .filter { batchOriginalPackageName(it) in enabledPackages }
            .distinctBy(::batchOriginalPackageName)
            .take((maxShortcuts - shortcuts.size).coerceAtLeast(0))
            .forEach { installed ->
                val packageName = batchOriginalPackageName(installed)
                val originalLabel = pm.getPackageInfo(packageName)
                    ?.let { with(pm) { it.label() } }
                    ?.takeIf(String::isNotBlank)
                val currentLabel = pm.getPackageInfo(installed.currentPackageName)
                    ?.let { with(pm) { it.label() } }
                    ?.takeIf(String::isNotBlank)
                val savedApkPackageInfo = fs.getPatchedAppFile(
                    installed.currentPackageName,
                    installed.version
                ).takeIf(File::isFile)
                    ?.let(pm::getPackageInfo)
                val savedApkLabel = savedApkPackageInfo
                    ?.let { with(pm) { it.label() } }
                    ?.takeIf(String::isNotBlank)
                val label = batchShortcutLabel(
                    originalLabel = originalLabel,
                    currentLabel = currentLabel,
                    savedApkLabel = savedApkLabel,
                    originalPackageName = packageName
                )
                shortcuts += batchShortcut(
                    id = "patch_$packageName",
                    shortLabel = label,
                    longLabel = getString(R.string.shortcut_patch_app, label),
                    rank = shortcuts.size,
                    action = BatchPatchIntents.ACTION_PATCH_APP,
                    packageName = packageName,
                    icon = savedAppShortcutIcon(
                        installed.currentPackageName,
                        packageName,
                        savedApkPackageInfo?.applicationInfo,
                        shortcutIconSize
                    )
                )
            }

        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(this, shortcuts.take(maxShortcuts))
        }.onFailure { Log.w(tag, "Failed to publish patch shortcuts", it) }
    }

    private fun hasSavedAppCopy(
        installedApp: app.urv.manager.data.room.apps.installed.InstalledApp
    ): Boolean = fs.getPatchedAppFile(
        installedApp.currentPackageName,
        installedApp.version
    ).isFile

    private fun batchShortcut(
        id: String,
        shortLabel: String,
        longLabel: String,
        rank: Int,
        action: String,
        packageName: String? = null,
        icon: IconCompat
    ) = ShortcutInfoCompat.Builder(this, id)
        .setShortLabel(shortLabel)
        .setLongLabel(longLabel)
        .setIcon(icon)
        .setRank(rank)
        .setIntent(
            BatchPatchIntents.markInternal(
                this,
                Intent(this, MainActivity::class.java).apply {
                    this.action = action
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    packageName?.let { putExtra(BatchPatchIntents.EXTRA_PACKAGE, it) }
                }
            )
        )
        .build()

    private fun savedAppShortcutIcon(
        currentPackageName: String,
        originalPackageName: String,
        savedApkApplicationInfo: ApplicationInfo?,
        size: Int
    ): IconCompat {
        val applicationInfo = pm.getApplicationInfo(currentPackageName)
            ?: pm.getApplicationInfo(originalPackageName)
            ?: savedApkApplicationInfo
        val bitmap = applicationInfo?.let { info ->
            runCatching {
                info.loadIcon(packageManager).toBitmap(size, size)
            }.getOrNull()
        }
        return bitmap?.let(IconCompat::createWithBitmap)
            ?: IconCompat.createWithResource(this, R.drawable.ic_shortcut_patch_app)
    }

    private fun onFreshProcessStart() {
        fs.uiTempDir.apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun cancelActivePatchingOnAppClose() {
        if (PatcherWorker.backgroundExecutionAllowed) return
        workerRepository.cancelUniqueWork(PatcherWorker.UNIQUE_WORK_NAME)
        PatcherWorker.clearNotification(this)
    }

    private fun notifyRootMountResults(results: Map<String, RootMountResult>) {
        results.forEach { (packageName, result) ->
            when (result) {
                is RootMountResult.Success -> RootMountReconcileWorker.clearAttentionNotification(
                    this,
                    packageName
                )
                is RootMountResult.RecoveredToPreviousMount -> RootMountReconcileWorker.notifyAttentionRequired(
                    this,
                    packageName,
                    getString(R.string.root_mount_recovered_title),
                    getString(
                        R.string.root_mount_recovered_previous_message,
                        result.diagnosticId
                    )
                )
                is RootMountResult.RecoveredToStock -> RootMountReconcileWorker.notifyAttentionRequired(
                    this,
                    packageName,
                    getString(R.string.root_mount_recovered_title),
                    getString(
                        R.string.root_mount_recovered_stock_message,
                        result.diagnosticId
                    )
                )
                is RootMountResult.RequiresRepatch -> RootMountReconcileWorker.notifyAttentionRequired(
                    this,
                    packageName,
                    getString(R.string.root_mount_repatch_title),
                    result.reason
                )
                is RootMountResult.Failure -> RootMountReconcileWorker.notifyAttentionRequired(
                    this,
                    packageName,
                    getString(R.string.root_mount_repair_notification_title),
                    getString(
                        R.string.root_mount_repair_notification_message,
                        result.message,
                        result.diagnosticId
                    )
                )
                else -> Unit
            }
        }
    }

    private companion object {
        private const val DEFAULT_API_URL = "https://api.revanced.app"
        private const val LEGACY_MANAGER_REPO_URL = "https://github.com/Jman-Github/universal-revanced-manager"
        private const val LEGACY_MANAGER_REPO_API_URL = "https://api.github.com/repos/Jman-Github/universal-revanced-manager"
        private const val BATCH_OUTPUT_STALE_AGE_MILLIS = 24L * 60 * 60 * 1_000
    }
}


internal fun batchShortcutLabel(
    originalLabel: String?,
    currentLabel: String?,
    savedApkLabel: String?,
    originalPackageName: String
): String = originalLabel?.takeIf(String::isNotBlank)
    ?: currentLabel?.takeIf(String::isNotBlank)
    ?: savedApkLabel?.takeIf(String::isNotBlank)
    ?: originalPackageName
