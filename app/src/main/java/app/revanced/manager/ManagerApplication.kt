package app.revanced.manager

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import app.revanced.manager.data.platform.Filesystem
import app.revanced.manager.di.*
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.domain.repository.DownloaderPluginRepository
import app.revanced.manager.domain.repository.PatchBundleRepository
import app.revanced.manager.domain.worker.BundleUpdateWebSocketCoordinator
import app.revanced.manager.domain.worker.WorkerRepository
import app.revanced.manager.patcher.ample.AmpleRuntimeBridge
import app.revanced.manager.patcher.morphe.MorpheRuntimeBridge
import app.revanced.manager.network.service.HttpService
import app.revanced.manager.util.AppForeground
import app.revanced.manager.util.tag
import app.revanced.manager.util.PatchListCatalog
import app.revanced.manager.util.applyAppLanguage
import kotlinx.coroutines.Dispatchers
import coil.Coil
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.internal.BuilderImpl
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.zhanghai.android.appiconloader.coil.AppIconFetcher
import me.zhanghai.android.appiconloader.coil.AppIconKeyer
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
    private val workerRepository: WorkerRepository by inject()
    private val bundleUpdateWebSocketCoordinator: BundleUpdateWebSocketCoordinator by inject()
    private val fs: Filesystem by inject()
    private val httpService: HttpService by inject()

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

        PatchListCatalog.initialize(this)
        MorpheRuntimeBridge.initialize(this)
        AmpleRuntimeBridge.initialize(this)

        val pixels = 512
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .components {
                    add(AppIconKeyer())
                    add(AppIconFetcher.Factory(pixels, true, this@ManagerApplication))
                    add(SvgDecoder.Factory())
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        add(ImageDecoderDecoder.Factory())
                    } else {
                        add(GifDecoder.Factory())
                    }
                }
                .build()
        )

        val shellBuilder = BuilderImpl.create().setFlags(Shell.FLAG_MOUNT_MASTER)
        Shell.setDefaultBuilder(shellBuilder)

        bundleUpdateWebSocketCoordinator.start()

        scope.launch {
            prefs.preload()
            workerRepository.scheduleBundleUpdateNotificationWork(
                prefs.searchForUpdatesBackgroundInterval.get()
            )
            workerRepository.scheduleManagerUpdateNotificationWork(
                prefs.searchForManagerUpdatesBackgroundInterval.get()
            )
            val currentApi = prefs.api.get()
            if (currentApi == LEGACY_MANAGER_REPO_URL || currentApi == LEGACY_MANAGER_REPO_API_URL) {
                prefs.api.update(DEFAULT_API_URL)
            }
            val storedLanguage = prefs.appLanguage.get().ifBlank { "system" }
            if (storedLanguage != prefs.appLanguage.get()) {
                prefs.appLanguage.update(storedLanguage)
            }
            applyAppLanguage(storedLanguage)
        }
        scope.launch(Dispatchers.Default) {
            downloaderPluginRepository.reload()
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
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var firstActivityCreated = false

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (firstActivityCreated) return
                firstActivityCreated = true

                // We do not want to call onFreshProcessStart() if there is state to restore.
                // This can happen on system-initiated process death.
                if (savedInstanceState == null) {
                    Log.d(tag, "Fresh process created")
                    onFreshProcessStart()
                } else Log.d(tag, "System-initiated process death detected")
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
            override fun onActivityDestroyed(activity: Activity) {}
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

    private companion object {
        private const val DEFAULT_API_URL = "https://api.revanced.app"
        private const val LEGACY_MANAGER_REPO_URL = "https://github.com/Jman-Github/universal-revanced-manager"
        private const val LEGACY_MANAGER_REPO_API_URL = "https://api.github.com/repos/Jman-Github/universal-revanced-manager"
    }
}
