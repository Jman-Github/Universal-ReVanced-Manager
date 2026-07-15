package app.urv.manager.ui.viewmodel

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.revanced.manager.R
import app.urv.manager.data.room.lsposed.LsposedModule
import app.urv.manager.domain.installer.InstallerManager
import app.urv.manager.domain.installer.ShizukuInstaller
import app.urv.manager.domain.lsposed.LsposedFrameworkState
import app.urv.manager.domain.lsposed.LsposedInstalledPackageState
import app.urv.manager.domain.lsposed.LsposedReleaseAsset
import app.urv.manager.domain.lsposed.LsposedRepository
import app.urv.manager.domain.lsposed.LsposedSourceKind
import app.urv.manager.domain.lsposed.PendingLsposedModule
import app.urv.manager.domain.manager.PreferencesManager
import app.urv.manager.util.PM
import app.urv.manager.util.simpleMessage
import app.urv.manager.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import ru.solrudev.ackpine.installer.InstallFailure
import ru.solrudev.ackpine.installer.PackageInstaller
import ru.solrudev.ackpine.uninstaller.UninstallFailure
import ru.solrudev.ackpine.installer.createSession
import ru.solrudev.ackpine.session.Session
import ru.solrudev.ackpine.session.await
import ru.solrudev.ackpine.session.parameters.Confirmation

class LsposedViewModel : ViewModel(), KoinComponent {
    private val app: Application by inject()
    private val repository: LsposedRepository by inject()
    private val installerManager: InstallerManager by inject()
    private val shizukuInstaller: ShizukuInstaller by inject()
    private val prefs: PreferencesManager by inject()
    private val pm: PM by inject()
    private val ackpineInstaller: PackageInstaller = get()

    val modules = repository.modules

    var frameworkState by mutableStateOf(LsposedFrameworkState(false, false))
        private set
    var frameworkRefreshing by mutableStateOf(false)
        private set
    var pendingModule by mutableStateOf<PendingLsposedModule?>(null)
        private set
    var assetChoices by mutableStateOf<List<LsposedReleaseAsset>>(emptyList())
        private set
    private var assetChoiceExpectedModule: LsposedModule? = null
    var busyMessage by mutableStateOf<String?>(null)
        private set
    var showInstallComplete by mutableStateOf(false)
        private set
    var externalInstallRequest by mutableStateOf<Intent?>(null)
        private set
    private var pendingExternalPlan: InstallerManager.InstallPlan.External? = null
    private var pendingExternalModule: PendingLsposedModule? = null
    private var pendingExternalInstalledState: LsposedInstalledPackageState? = null
    private var frameworkCheckRunning = false
    private lateinit var packageReceiver: BroadcastReceiver

    init {
        packageReceiver = createPackageReceiver()
        loadFramework(showShimmer = false)
        ContextCompat.registerReceiver(
            app,
            packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun refreshFramework() {
        loadFramework(showShimmer = true)
    }

    fun checkRootAccess() {
        loadFramework(showShimmer = false)
    }

    private fun loadFramework(showShimmer: Boolean) {
        if (frameworkCheckRunning) return
        frameworkCheckRunning = true
        viewModelScope.launch {
            if (showShimmer) frameworkRefreshing = true
            val startedAt = System.currentTimeMillis()
            try {
                val state = repository.frameworkState()
                frameworkState = state
                if (!state.rootAvailable) {
                    prefs.showLsposedTab.update(false)
                }
            } catch (error: Exception) {
                if (showShimmer) {
                    app.toast(error.simpleMessage() ?: app.getString(R.string.lsposed_refresh_failed))
                }
            } finally {
                if (showShimmer) {
                    delay((900L - (System.currentTimeMillis() - startedAt)).coerceAtLeast(0L))
                    frameworkRefreshing = false
                }
                frameworkCheckRunning = false
            }
        }
    }

    fun openManager() = launchTask(app.getString(R.string.lsposed_busy_opening)) {
        if (!repository.openManager()) {
            throw IllegalStateException(app.getString(R.string.lsposed_manager_unavailable))
        }
    }

    fun openModuleSettings(module: LsposedModule) {
        if (!repository.openModuleSettings(module.packageName)) {
            app.toast(app.getString(R.string.lsposed_settings_unavailable))
        }
    }

    fun selectLocal(uri: Uri) = launchTask(app.getString(R.string.lsposed_busy_checking_module)) {
        replacePending(repository.prepareLocal(uri))
    }

    fun resolveUrl(url: String) = launchTask(app.getString(R.string.lsposed_busy_checking_release)) {
        val choices = repository.resolveRemoteSource(url)
        if (choices.size == 1) {
            prepareRemote(choices.single())
        } else {
            assetChoiceExpectedModule = null
            assetChoices = choices
        }
    }

    fun chooseAsset(asset: LsposedReleaseAsset) = launchTask(app.getString(R.string.lsposed_busy_downloading_module)) {
        val expectedModule = assetChoiceExpectedModule
        assetChoiceExpectedModule = null
        assetChoices = emptyList()
        prepareRemote(asset, expectedModule)
    }

    fun dismissAssetChoices() {
        assetChoiceExpectedModule = null
        assetChoices = emptyList()
    }

    fun dismissPending() {
        val candidate = pendingModule
        if (candidate != null && pendingExternalModule === candidate) {
            pendingExternalPlan?.let(::clearExternalInstall)
        }
        candidate?.let(repository::deleteTemporary)
        pendingModule = null
    }

    fun installPending(installerToken: InstallerManager.Token? = null) {
        val candidate = pendingModule ?: return
        pendingModule = null
        if (pendingExternalModule === candidate) {
            pendingExternalPlan?.let(::clearExternalInstall)
        }
        launchTask(app.getString(R.string.lsposed_busy_installing_module)) {
            try {
                install(candidate, installerToken)
            } catch (error: Exception) {
                if (pendingModule == null && pendingExternalPlan == null) {
                    pendingModule = candidate
                }
                throw error
            }
        }
    }

    fun checkForUpdate(module: LsposedModule) = launchTask(app.getString(R.string.lsposed_busy_checking_updates)) {
        val updated = repository.checkForUpdate(module)
        app.toast(
            app.getString(
                if (updated.updateAvailable) R.string.lsposed_update_found
                else R.string.lsposed_up_to_date
            )
        )
    }

    fun update(module: LsposedModule) = launchTask(app.getString(R.string.lsposed_busy_checking_update)) {
        val choices = repository.resolveUpdateAssets(module)
        val chosen = choices.firstOrNull { it.asset.name == module.assetName }
            ?: choices.singleOrNull()
        if (chosen != null) {
            prepareRemote(chosen, module)
        } else {
            assetChoiceExpectedModule = module
            assetChoices = choices
        }
    }

    fun reinstall(module: LsposedModule) = launchTask(app.getString(R.string.lsposed_busy_checking_saved_module)) {
        replacePending(repository.prepareStored(module))
    }

    fun uninstall(module: LsposedModule) = launchTask(app.getString(R.string.lsposed_busy_uninstalling_module)) {
        when (pm.uninstallPackage(module.packageName)) {
            Session.State.Succeeded -> {
                repository.forget(module.packageName)
                app.toast(app.getString(R.string.lsposed_uninstalled))
            }
            is Session.State.Failed<UninstallFailure> ->
                app.toast(app.getString(R.string.lsposed_uninstall_failed))
        }
    }

    fun forget(module: LsposedModule) = launchTask(app.getString(R.string.lsposed_busy_removing_record)) {
        repository.forget(module.packageName)
        app.toast(app.getString(R.string.lsposed_forgotten))
    }

    fun dismissInstallComplete() {
        showInstallComplete = false
    }

    fun consumeExternalInstallRequest() {
        externalInstallRequest = null
    }

    fun onExternalInstallerLaunched() {
        pendingExternalPlan?.let { plan ->
            app.toast(app.getString(R.string.lsposed_complete_external_install, plan.installerLabel))
        }
    }

    fun onExternalInstallerLaunchFailed(error: Exception) {
        val plan = pendingExternalPlan ?: return
        val candidate = pendingExternalModule ?: return
        clearExternalInstall(plan)
        pendingModule = candidate
        val message = if (error is ActivityNotFoundException) {
            app.getString(R.string.lsposed_installer_unavailable)
        } else {
            error.simpleMessage() ?: app.getString(R.string.lsposed_installer_unavailable)
        }
        app.toast(message)
    }

    fun onExternalInstallerResult() {
        val plan = pendingExternalPlan ?: return
        val candidate = pendingExternalModule ?: return
        val previousState = pendingExternalInstalledState
        viewModelScope.launch {
            val installed = waitForExternalInstall(candidate, previousState)
            if (pendingExternalPlan != plan) return@launch
            if (installed) {
                if (pendingModule === candidate) pendingModule = null
                clearExternalInstall(plan)
                try {
                    completeInstall(candidate)
                } catch (error: Exception) {
                    pendingModule = candidate
                    app.toast(error.simpleMessage() ?: app.getString(R.string.lsposed_operation_failed))
                }
            } else {
                pendingModule = candidate
                app.toast(app.getString(R.string.lsposed_install_unconfirmed))
            }
        }
    }

    private suspend fun prepareRemote(
        asset: LsposedReleaseAsset,
        expectedModule: LsposedModule? = null,
    ) {
        val candidate = repository.prepareRemote(asset)
        if (expectedModule != null && candidate.packageName != expectedModule.packageName) {
            repository.deleteTemporary(candidate)
            throw IllegalArgumentException(
                app.getString(
                    R.string.lsposed_update_wrong_package,
                    candidate.packageName,
                    expectedModule.packageName,
                )
            )
        }
        if (expectedModule != null && candidate.versionCode < expectedModule.installedVersionCode) {
            repository.deleteTemporary(candidate)
            throw IllegalArgumentException(app.getString(R.string.lsposed_update_older))
        }
        replacePending(candidate)
    }

    private fun replacePending(candidate: PendingLsposedModule) {
        pendingModule?.let(repository::deleteTemporary)
        pendingModule = candidate
    }

    private suspend fun install(
        candidate: PendingLsposedModule,
        installerToken: InstallerManager.Token?,
    ) {
        val target = InstallerManager.InstallTarget.LSPOSED_MODULE
        val plan = if (installerToken == null) {
            installerManager.resolvePlan(
                target,
                candidate.file,
                candidate.packageName,
                candidate.displayName,
            )
        } else {
            installerManager.resolvePlanForToken(
                installerToken,
                target,
                candidate.file,
                candidate.packageName,
                candidate.displayName,
            ) ?: run {
                pendingModule = candidate
                throw IllegalStateException(app.getString(R.string.lsposed_installer_unavailable))
            }
        }
        when (plan) {
            is InstallerManager.InstallPlan.Internal -> installInternal(candidate)
            is InstallerManager.InstallPlan.Shizuku -> {
                shizukuInstaller.install(
                    candidate.file,
                    candidate.packageName,
                    plan.installerPackageNameOverride,
                )
                completeInstall(candidate)
            }
            is InstallerManager.InstallPlan.External -> launchExternal(plan, candidate)
            is InstallerManager.InstallPlan.Mount ->
                throw IllegalStateException(app.getString(R.string.lsposed_root_mount_unsupported))
        }
    }

    private suspend fun installInternal(candidate: PendingLsposedModule) {
        if (!pm.requestInstallPackagesPermission()) {
            pendingModule = candidate
            app.toast(app.getString(R.string.lsposed_allow_installs))
            return
        }
        val result = withContext(Dispatchers.IO) {
            ackpineInstaller.createSession(Uri.fromFile(candidate.file)) {
                confirmation = Confirmation.IMMEDIATE
            }.await()
        }
        when (result) {
            Session.State.Succeeded -> completeInstall(candidate)
            is Session.State.Failed<InstallFailure> -> {
                pendingModule = candidate
                if (result.failure is InstallFailure.Aborted) return
                val message = result.failure.message ?: app.getString(R.string.lsposed_install_failed)
                throw IllegalStateException(message)
            }
        }
    }

    private fun launchExternal(
        plan: InstallerManager.InstallPlan.External,
        candidate: PendingLsposedModule,
    ) {
        pendingExternalPlan?.let(::cleanupExternal)
        pendingExternalPlan = plan
        pendingExternalModule = candidate
        pendingExternalInstalledState = repository.installedPackageState(candidate.packageName)
        externalInstallRequest = plan.intent
        busyMessage = null
    }

    private suspend fun waitForExternalInstall(
        candidate: PendingLsposedModule,
        previousState: LsposedInstalledPackageState?,
    ): Boolean {
        repeat(EXTERNAL_RESULT_CHECK_ATTEMPTS) {
            if (repository.installedPackageChangedSince(candidate, previousState)) return true
            delay(EXTERNAL_RESULT_CHECK_INTERVAL_MS)
        }
        return repository.installedPackageChangedSince(candidate, previousState)
    }

    private suspend fun completeInstall(candidate: PendingLsposedModule) {
        check(repository.installedPackageMatches(candidate)) {
            app.getString(R.string.lsposed_install_verification_failed)
        }
        val installedCandidate = repository.persistLocalApk(candidate)
        repository.recordInstalled(installedCandidate)
        repository.deleteTemporary(candidate)
        showInstallComplete = true
        app.toast(app.getString(R.string.lsposed_installed))
    }

    private fun createPackageReceiver(): BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val plan = pendingExternalPlan ?: return
                val candidate = pendingExternalModule ?: return
                val packageName = intent?.data?.schemeSpecificPart ?: return
                if (packageName != candidate.packageName) return
                if (intent.action != Intent.ACTION_PACKAGE_ADDED &&
                    intent.action != Intent.ACTION_PACKAGE_REPLACED
                ) return
                val pendingResult = goAsync()
                if (pendingModule === candidate) pendingModule = null
                clearExternalInstall(plan)
                launchTask(app.getString(R.string.lsposed_busy_installing_module)) {
                    try {
                        completeInstall(candidate)
                    } catch (error: Exception) {
                        pendingModule = candidate
                        throw error
                    }
                }.invokeOnCompletion { pendingResult.finish() }
            }
        }

    private fun clearExternalInstall(plan: InstallerManager.InstallPlan.External) {
        if (pendingExternalPlan != plan) return
        cleanupExternal(plan)
        pendingExternalPlan = null
        pendingExternalModule = null
        pendingExternalInstalledState = null
        externalInstallRequest = null
    }

    private fun cleanupExternal(plan: InstallerManager.InstallPlan.External) {
        installerManager.cleanup(plan)
    }

    private fun launchTask(
        message: String,
        reportError: Boolean = true,
        block: suspend () -> Unit,
    ) = viewModelScope.launch {
        busyMessage = message
        try {
            block()
        } catch (error: Exception) {
            if (reportError) app.toast(error.simpleMessage() ?: app.getString(R.string.lsposed_operation_failed))
        } finally {
            if (pendingExternalPlan == null) busyMessage = null
        }
    }

    override fun onCleared() {
        runCatching { app.unregisterReceiver(packageReceiver) }
        pendingExternalPlan?.let(::cleanupExternal)
        pendingExternalModule?.let(repository::deleteTemporary)
        pendingModule?.let(repository::deleteTemporary)
        super.onCleared()
    }

    companion object {
        private const val EXTERNAL_RESULT_CHECK_ATTEMPTS = 10
        private const val EXTERNAL_RESULT_CHECK_INTERVAL_MS = 200L
    }
}
