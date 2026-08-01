package app.urv.manager

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import app.urv.manager.data.platform.Filesystem
import app.urv.manager.di.*
import app.urv.manager.domain.manager.PreferencesManager
import app.urv.manager.domain.installer.RootInstaller
import app.urv.manager.domain.installer.root.RootMountResult
import app.urv.manager.domain.installer.root.RootMountTransactionCoordinator
import app.urv.manager.domain.manager.SearchForUpdatesBackgroundInterval
import app.urv.manager.domain.repository.DownloaderPluginRepository
import app.urv.manager.domain.repository.PatchBundleRepository
import app.urv.manager.domain.repository.PatcherRuntimePluginRepository
import app.urv.manager.domain.worker.BundleUpdateWebSocketCoordinator
import app.urv.manager.domain.worker.WorkerRepository
import app.urv.manager.patcher.worker.PatcherWorker
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
import app.urv.manager.util.applyAppLanguage
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin
import org.lsposed.hiddenapibypass.HiddenApiBypass

class ManagerApplication : Application() {
    private val scope = MainScope()
    private val prefs: PreferencesManager by inject()
    private val patchBundleRepository: PatchBundleRepository by inject()
    private val downloaderPluginRepository: DownloaderPluginRepository by inject()
    private val patcherRuntimePluginRepository: PatcherRuntimePluginRepository by inject()
    private val workerRepository: WorkerRepository by inject()
    private val bundleUpdateWebSocketCoordinator: BundleUpdateWebSocketCoordinator by inject()
    private val fs: Filesystem by inject()
    private val httpService: HttpService by inject()
    private val downloadProgressNotifier: DownloadProgressNotifier by inject()
    private val rootInstaller: RootInstaller by inject()
    private val rootMountCoordinator: RootMountTransactionCoordinator by inject()

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

        scope.launch {
            prefs.preload()
            prefs.enableManagerPrereleasesForVersion(BuildConfig.VERSION_NAME)
            prefs.migrateAnnouncementPushNotificationInterval()
            prefs.migrateDashboardBundleBannerState()
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

    private fun onFreshProcessStart() {
        fs.uiTempDir.apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun cancelActivePatchingOnAppClose() {
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
    }
}
