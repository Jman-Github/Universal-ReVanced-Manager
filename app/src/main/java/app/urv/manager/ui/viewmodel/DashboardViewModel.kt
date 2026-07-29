package app.urv.manager.ui.viewmodel

import android.app.Activity
import android.annotation.SuppressLint
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageInstaller as AndroidPackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.os.PowerManager
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.revanced.manager.R
import app.urv.manager.data.platform.NetworkInfo
import app.urv.manager.domain.bundles.PatchBundleSource.Extensions.asRemoteOrNull
import app.urv.manager.domain.installer.InstallCancelledException
import app.urv.manager.domain.installer.InstallResult
import app.urv.manager.domain.installer.InstallerManager
import app.urv.manager.domain.installer.SessionDeadException
import app.urv.manager.domain.installer.SessionInstaller
import app.urv.manager.domain.installer.ShizukuInstaller
import app.urv.manager.domain.manager.KeystoreManager
import app.urv.manager.domain.manager.PreferencesManager
import app.urv.manager.domain.repository.AnnouncementRepository
import app.urv.manager.domain.repository.DownloaderPluginRepository
import app.urv.manager.domain.repository.PatchBundleRepository
import app.urv.manager.domain.repository.PatcherRuntimePluginRepository
import app.urv.manager.domain.storage.CacheCleanupGuard
import app.urv.manager.network.downloader.LoadedDownloaderPlugin
import app.urv.manager.network.api.ReVancedAPI
import app.urv.manager.network.dto.ReVancedAnnouncement
import app.urv.manager.network.dto.ReVancedAsset
import app.urv.manager.patcher.runtime.MemoryLimitConfig
import app.urv.manager.patcher.split.InstalledSplitArchiveBuilder
import app.urv.manager.patcher.split.SplitApkPreparer
import app.urv.manager.patcher.split.SplitMergeProcessRuntime
import app.urv.manager.patcher.worker.PatcherMemoryUsage
import app.urv.manager.util.PM
import app.urv.manager.util.announcementTagKey
import app.urv.manager.util.SplitMergeNotification
import app.urv.manager.util.toast
import app.urv.manager.util.uiSafe
import app.urv.manager.plugin.downloader.GetScope
import app.urv.manager.plugin.downloader.OutputDownloadScope
import app.urv.manager.plugin.downloader.PluginHostApi
import app.urv.manager.plugin.downloader.UserInteractionException
import app.urv.manager.util.ExportNameFormatter
import app.urv.manager.util.PatchBundleFileIntent
import app.urv.manager.util.PatchBundleFileIntentParser
import app.urv.manager.util.PatchBundleFileManifest
import app.urv.manager.util.PatchedAppExportData
import app.urv.manager.util.simpleMessage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Date
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileNotFoundException
import kotlin.coroutines.coroutineContext

private const val SPLIT_MERGE_NOTIFICATION_PROGRESS_MAX = 1000
private const val SPLIT_MERGE_EXTERNAL_INSTALL_TIMEOUT_MS = 120_000L
private const val SPLIT_MERGE_INSTALL_POLL_INTERVAL_MS = 1_000L
internal const val SPLIT_MERGE_PRESET_UNSELECTED = "unselected"

internal enum class NewPluginNotification {
    DOWNLOADER,
    PATCHER_RUNTIME
}

private data class NewPluginNotificationOrder(
    val notification: NewPluginNotification,
    val firstInstallTime: Long
)

data class PatchBundleFileImportState(
    val fileIntent: PatchBundleFileIntent,
    val manifest: PatchBundleFileManifest? = null
)

@OptIn(PluginHostApi::class)
class DashboardViewModel(
    private val app: Application,
    private val patchBundleRepository: PatchBundleRepository,
    private val downloaderPluginRepository: DownloaderPluginRepository,
    private val patcherRuntimePluginRepository: PatcherRuntimePluginRepository,
    private val announcementRepository: AnnouncementRepository,
    private val reVancedAPI: ReVancedAPI,
    private val networkInfo: NetworkInfo,
    val prefs: PreferencesManager,
    private val keystoreManager: KeystoreManager,
    private val installerManager: InstallerManager,
    private val shizukuInstaller: ShizukuInstaller,
    private val sessionInstaller: SessionInstaller,
    private val pm: PM,
) : ViewModel() {
    val availablePatches =
        patchBundleRepository.enabledBundlesInfoFlow.map { it.values.sumOf { bundle -> bundle.patches.size } }
    val patchBundlesLoading = patchBundleRepository.reloadInProgress
    val bundleUpdateProgress = patchBundleRepository.bundleUpdateProgress
    val bundleImportProgress = patchBundleRepository.bundleImportProgress
    private val contentResolver: ContentResolver = app.contentResolver
    private val powerManager = app.getSystemService<PowerManager>()!!

    internal val newPluginNotifications = combine(
        downloaderPluginRepository.newPluginPackageNames,
        patcherRuntimePluginRepository.newPluginPackageNames
    ) { downloaderPackageNames, patcherRuntimePackageNames ->
        listOfNotNull(
            newPluginNotificationOrder(
                notification = NewPluginNotification.DOWNLOADER,
                packageNames = downloaderPackageNames
            ),
            newPluginNotificationOrder(
                notification = NewPluginNotification.PATCHER_RUNTIME,
                packageNames = patcherRuntimePackageNames
            )
        )
            .sortedWith(
                compareBy<NewPluginNotificationOrder> { it.firstInstallTime }
                    .thenBy { it.notification.ordinal }
            )
            .map { it.notification }
    }.flowOn(Dispatchers.IO)
    val loadedDownloaderPlugins = downloaderPluginRepository.loadedPluginsFlow

    /**
     * Android 11 kills the app process after granting the "install apps" permission, which is a problem for the patcher screen.
     * This value is true when the conditions that trigger the bug are met.
     *
     * See: https://github.com/ReVanced/revanced-manager/issues/2138
     */
    val android11BugActive get() = Build.VERSION.SDK_INT == Build.VERSION_CODES.R && !pm.canInstallPackages()

    var updatedManagerRelease: ReVancedAsset? by mutableStateOf(null)
        private set
    val updatedManagerVersion: String?
        get() = updatedManagerRelease?.version
    var unreadAnnouncement: ReVancedAnnouncement? by mutableStateOf(null)
        private set
    var showBatteryOptimizationsWarning by mutableStateOf(false)
        private set
    var pendingPatchBundleFileImport: PatchBundleFileImportState? by mutableStateOf(null)
        private set
    private var patchBundleFileManifestJob: Job? = null

    private val bundleListEventsChannel = Channel<BundleListViewModel.Event>()
    val bundleListEventsFlow = bundleListEventsChannel.receiveAsFlow()
    private val splitMergeStateFlow = MutableStateFlow(SplitMergeState())
    val splitMergeState = splitMergeStateFlow.asStateFlow()
    private val launchActivityChannel = Channel<Intent>()
    val launchActivityFlow = launchActivityChannel.receiveAsFlow()
    private val openSplitMergeScreenChannel = Channel<Unit>()
    val openSplitMergeScreenFlow = openSplitMergeScreenChannel.receiveAsFlow()
    private val splitMergeWorkspace = app.cacheDir.resolve("split-merge-tools").apply { mkdirs() }
    private val splitMergeRuntime = SplitMergeProcessRuntime(app)
    private var cachedMergedApk: File? = null
    private var activeSplitMergeRunWorkspace: File? = null
    private var splitMergeJob: Job? = null
    private var splitMergePluginJob: Job? = null
    private var splitMergeCancellationJob: Job? = null
    private var splitMergeInstallJob: Job? = null
    private var splitMergeExternalInstall: InstallerManager.InstallPlan.External? = null
    private var splitMergePlugin: LoadedDownloaderPlugin? = null
    private var pendingSplitMergeSource: PendingSplitMergeSource? = null
    private var lastSplitMergeNotificationSubStepIndex = -1
    private var lastSplitMergeNotificationProgress = 0
    private var launchedActivity by mutableStateOf<CompletableDeferred<ActivityResult>?>(null)
    val activeSplitMergePluginId: String? get() = splitMergePlugin?.id

    init {
        viewModelScope.launch {
            checkForManagerUpdates()
            updateBatteryOptimizationsWarning()
        }
        viewModelScope.launch {
            prefs.announcementSystemEnabled.flow.collect { enabled ->
                if (!enabled) {
                    unreadAnnouncement = null
                }
            }
        }
        viewModelScope.launch {
            prefs.showBatteryOptimizationBanner.flow.collect { bannerEnabled ->
                showBatteryOptimizationsWarning = bannerEnabled &&
                    !powerManager.isIgnoringBatteryOptimizations(app.packageName)
            }
        }
    }

    private fun newPluginNotificationOrder(
        notification: NewPluginNotification,
        packageNames: Set<String>
    ): NewPluginNotificationOrder? {
        if (packageNames.isEmpty()) return null

        val firstInstallTime = packageNames
            .mapNotNull { packageName ->
                pm.getPackageInfo(packageName)
                    ?.firstInstallTime
                    ?.takeIf { it > 0L }
            }
            .minOrNull()
            ?: Long.MAX_VALUE
        return NewPluginNotificationOrder(notification, firstInstallTime)
    }

    fun ignoreNewDownloaderPlugins() = viewModelScope.launch {
        downloaderPluginRepository.acknowledgeAllNewPlugins()
    }

    fun ignoreNewPatcherRuntimePlugins() = viewModelScope.launch {
        patcherRuntimePluginRepository.acknowledgeAllNewPlugins()
    }

    private suspend fun checkForManagerUpdates() {
        if (!prefs.managerAutoUpdates.get() || !networkInfo.isConnected()) return

        uiSafe(app, R.string.failed_to_check_updates, "Failed to check for updates") {
            val update = reVancedAPI.getAppUpdate()
            updatedManagerRelease = update
            if (update == null && prefs.viewedManagerUpdateVersion.get().isNotEmpty()) {
                prefs.viewedManagerUpdateVersion.update("")
            }
        }
    }

    fun refreshAnnouncements(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            checkForAnnouncements(forceRefresh)
        }
    }

    private suspend fun checkForAnnouncements(forceRefresh: Boolean = false) {
        if (!prefs.announcementSystemEnabled.get() || !networkInfo.isConnected()) {
            unreadAnnouncement = null
            return
        }

        uiSafe(app, R.string.failed_to_check_announcements, "Failed to check for announcements") {
            val announcements = withContext(Dispatchers.IO) {
                announcementRepository.getAnnouncements(forceRefresh = forceRefresh)
            } ?: return@uiSafe

            if (!prefs.announcementSystemEnabled.get()) {
                unreadAnnouncement = null
                return@uiSafe
            }

            val readAnnouncements = prefs.readAnnouncements.get()
            if (readAnnouncements.isEmpty()) {
                prefs.readAnnouncements.update(announcements.mapTo(mutableSetOf()) { it.id.toString() })
                return@uiSafe
            }

            unreadAnnouncement = announcements.firstOrNull { announcement ->
                val notArchived = announcement.archivedAt
                    ?.toEpochMilliseconds()
                    ?.let { it > System.currentTimeMillis() }
                    ?: true
                val relevantTag = announcement.tags.any { tag ->
                    val normalized = announcementTagKey(tag)
                    normalized.contains("revanced") || normalized.contains("manager")
                }
                val unread = announcement.id.toString() !in readAnnouncements
                notArchived && relevantTag && unread
            }
        }
    }

    fun markUnreadAnnouncementRead() {
        viewModelScope.launch {
            unreadAnnouncement?.let { announcement ->
                prefs.edit {
                    prefs.readAnnouncements += announcement.id.toString()
                }
            }
            unreadAnnouncement = null
        }
    }

    fun markAnnouncementRead(id: Long) {
        viewModelScope.launch {
            prefs.edit {
                prefs.readAnnouncements += id.toString()
            }
            if (unreadAnnouncement?.id == id) {
                unreadAnnouncement = null
            }
        }
    }

    fun updateBatteryOptimizationsWarning() {
        viewModelScope.launch {
            val bannerEnabled = prefs.showBatteryOptimizationBanner.get()
            showBatteryOptimizationsWarning =
                bannerEnabled && !powerManager.isIgnoringBatteryOptimizations(app.packageName)
        }
    }

    fun setShowManagerUpdateDialogOnLaunch(value: Boolean) {
        viewModelScope.launch {
            prefs.showManagerUpdateDialogOnLaunch.update(value)
        }
    }

    fun applyAutoUpdatePrefs(manager: Boolean, patches: Boolean) = viewModelScope.launch {
        prefs.firstLaunch.update(false)

        prefs.managerAutoUpdates.update(manager)

        if (manager) checkForManagerUpdates()

        if (patches) {
            with(patchBundleRepository) {
                sources
                    .first()
                    .find { it.uid == 0 }
                    ?.asRemoteOrNull
                    ?.setAutoUpdate(true)

                updateCheck()
            }
        }
    }

    private fun sendEvent(event: BundleListViewModel.Event) {
        viewModelScope.launch { bundleListEventsChannel.send(event) }
    }

    fun cancelSourceSelection() = sendEvent(BundleListViewModel.Event.CANCEL)
    fun updateSources() = sendEvent(BundleListViewModel.Event.UPDATE_SELECTED)
    fun deleteSources() = sendEvent(BundleListViewModel.Event.DELETE_SELECTED)
    fun disableSources() = sendEvent(BundleListViewModel.Event.DISABLE_SELECTED)

    private suspend fun <T> withPersistentImportToast(block: suspend () -> T): T = coroutineScope {
        val progressToast = withContext(Dispatchers.Main) {
            Toast.makeText(
                app,
                app.getString(R.string.import_patch_bundles_in_progress),
                Toast.LENGTH_SHORT
            )
        }
        withContext(Dispatchers.Main) { progressToast.show() }

        val toastRepeater = launch(Dispatchers.Main) {
            try {
                while (isActive) {
                    delay(1_750)
                    progressToast.show()
                }
            } catch (_: CancellationException) {
                // Ignore cancellation.
            }
        }

        try {
            block()
        } finally {
            toastRepeater.cancel()
            withContext(Dispatchers.Main) { progressToast.cancel() }
        }
    }

    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/blob/6688aa17ea35b5ab398a3c1922be13626290cbf1/app/src/main/java/app/morphe/manager/ui/viewmodel/HomeViewModel.kt#L201-L226
    fun preparePatchBundleFileImport(fileIntent: PatchBundleFileIntent) {
        patchBundleFileManifestJob?.cancel()
        pendingPatchBundleFileImport = PatchBundleFileImportState(fileIntent)
        patchBundleFileManifestJob = viewModelScope.launch {
            val manifest = withContext(Dispatchers.IO) {
                PatchBundleFileIntentParser.readManifest(contentResolver, fileIntent.uri)
            }
            if (pendingPatchBundleFileImport?.fileIntent == fileIntent) {
                pendingPatchBundleFileImport = PatchBundleFileImportState(fileIntent, manifest)
            }
        }
    }

    fun confirmPatchBundleFileImport() {
        val fileIntent = pendingPatchBundleFileImport?.fileIntent ?: return
        dismissPatchBundleFileImport()
        createLocalSource(fileIntent.uri)
    }

    fun dismissPatchBundleFileImport() {
        patchBundleFileManifestJob?.cancel()
        patchBundleFileManifestJob = null
        pendingPatchBundleFileImport = null
    }

    @SuppressLint("Recycle")
    fun createLocalSource(patchBundle: Uri) = viewModelScope.launch {
        withContext(NonCancellable) {
            withPersistentImportToast {
                val permissionFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                var persistedPermission = false
                val size = runCatching {
                    contentResolver.openFileDescriptor(patchBundle, "r")?.use { it.statSize.takeIf { sz -> sz > 0 } }
                        ?: contentResolver.query(patchBundle, arrayOf(OpenableColumns.SIZE), null, null, null)
                            ?.use { cursor ->
                                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                                if (index != -1 && cursor.moveToFirst()) cursor.getLong(index) else null
                            }
                }.getOrNull()?.takeIf { it > 0L }
                try {
                    contentResolver.takePersistableUriPermission(patchBundle, permissionFlags)
                    persistedPermission = true
                } catch (_: SecurityException) {
                    // Provider may not support persistable permissions; fall back to transient grant.
                }

                try {
                    val displayName = runCatching {
                        contentResolver.query(patchBundle, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { cursor ->
                                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (index != -1 && cursor.moveToFirst()) cursor.getString(index) else null
                            }
                    }.getOrNull()
                    patchBundleRepository.createLocal(size, displayName) {
                        contentResolver.openInputStream(patchBundle)
                            ?: throw FileNotFoundException("Unable to open $patchBundle")
                    }
                } finally {
                    if (persistedPermission) {
                        try {
                            contentResolver.releasePersistableUriPermission(patchBundle, permissionFlags)
                        } catch (_: SecurityException) {
                            // Ignore if provider revoked or already released.
                        }
                    }
                }
            }
        }
    }

    fun createRemoteSource(apiUrl: String, autoUpdate: Boolean, searchUpdate: Boolean) = viewModelScope.launch {
        withContext(NonCancellable) {
            patchBundleRepository.createRemote(apiUrl, searchUpdate, autoUpdate)
        }
    }

    fun createLocalSourceFromFile(path: String) = viewModelScope.launch {
        withContext(NonCancellable) {
            withPersistentImportToast {
                val file = File(path)
                val length = file.length().takeIf { it > 0L }
                patchBundleRepository.createLocal(length, file.name) {
                    FileInputStream(file)
                }
            }
        }
    }

    fun startSplitMergeFromPath(inputPath: String) {
        splitMergeCancellationJob?.cancel()
        splitMergeCancellationJob = null
        splitMergePluginJob?.cancel()
        splitMergePluginJob = null
        splitMergePlugin = null
        splitMergeJob?.cancel()
        clearPendingSplitMergeSource()
        val inputFile = File(inputPath)
        setSplitMergeSelectionPreparing(inputFile.name)
        splitMergeJob = viewModelScope.launch {
            try {
                prepareSplitMergeSelection(
                    inputFile = inputFile,
                    inputDisplayName = inputFile.name,
                    openScreen = true
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (splitMergeJob === coroutineContext[Job]) {
                    splitMergeStateFlow.value = SplitMergeState()
                    app.toast(e.message ?: app.getString(R.string.merge_split_apk_failed))
                }
            } finally {
                if (splitMergeJob === coroutineContext[Job]) {
                    splitMergeJob = null
                }
            }
        }
    }

    fun startSplitMergeFromUri(
        inputUri: Uri,
        inputDisplayName: String? = null
    ) {
        splitMergeCancellationJob?.cancel()
        splitMergeCancellationJob = null
        splitMergePluginJob?.cancel()
        splitMergePluginJob = null
        splitMergePlugin = null
        splitMergeJob?.cancel()
        clearPendingSplitMergeSource()
        setSplitMergeSelectionPreparing(inputDisplayName)
        splitMergeJob = viewModelScope.launch {
            try {
                val tempInput = copyUriToTempFile(inputUri, inputDisplayName)
                prepareSplitMergeSelection(
                    inputFile = tempInput,
                    inputDisplayName = inputDisplayName ?: tempInput.name,
                    cleanup = { runCatching { tempInput.delete() } },
                    openScreen = true
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (splitMergeJob === coroutineContext[Job]) {
                    splitMergeStateFlow.value = SplitMergeState()
                    app.toast(e.message ?: app.getString(R.string.merge_split_apk_failed))
                }
            } finally {
                if (splitMergeJob === coroutineContext[Job]) {
                    splitMergeJob = null
                }
            }
        }
    }

    fun startSplitMergeFromInstalledPackage(packageName: String) {
        splitMergeCancellationJob?.cancel()
        splitMergeCancellationJob = null
        splitMergePluginJob?.cancel()
        splitMergePluginJob = null
        splitMergePlugin = null
        splitMergeJob?.cancel()
        clearPendingSplitMergeSource()
        setSplitMergeSelectionPreparing(packageName)
        splitMergeJob = viewModelScope.launch {
            try {
                val (archiveFile, displayName, cleanup) = withContext(Dispatchers.IO) {
                    val packageInfo = pm.getPackageInfo(packageName)
                        ?: throw IllegalStateException("Installed package not found: $packageName")
                    if (!pm.hasSplitApks(packageInfo)) {
                        throw IOException(app.getString(R.string.merge_split_apk_installed_not_split))
                    }
                    val archiveDir = splitMergeWorkspace.resolve("installed-splits-${System.currentTimeMillis()}")
                        .apply { mkdirs() }
                    try {
                        val archiveFile = archiveDir.resolve("${packageName.replace('.', '_')}.apks")
                        InstalledSplitArchiveBuilder.buildArchive(
                            apkFiles = InstalledSplitArchiveBuilder.collectApkFiles(packageInfo),
                            output = archiveFile
                        )
                        val displayName = pm.run { packageInfo.label() }.ifBlank { packageName }
                        Triple(
                            archiveFile,
                            "$displayName.apks",
                            {
                                runCatching { archiveDir.deleteRecursively() }
                                Unit
                            }
                        )
                    } catch (error: Throwable) {
                        runCatching { archiveDir.deleteRecursively() }
                        throw error
                    }
                }
                prepareSplitMergeSelection(
                    inputFile = archiveFile,
                    inputDisplayName = displayName,
                    cleanup = cleanup,
                    openScreen = true
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (splitMergeJob === coroutineContext[Job]) {
                    splitMergeStateFlow.value = SplitMergeState()
                    app.toast(e.message ?: app.getString(R.string.merge_split_apk_failed))
                }
            } finally {
                if (splitMergeJob === coroutineContext[Job]) {
                    splitMergeJob = null
                }
            }
        }
    }

    fun startSplitMergeFromPlugin(
        plugin: LoadedDownloaderPlugin,
        packageName: String,
        version: String?
    ) {
        splitMergeCancellationJob?.cancel()
        splitMergeCancellationJob = null
        splitMergeJob?.cancel()
        splitMergePluginJob?.cancel()
        clearPendingSplitMergeSource()
        splitMergePlugin = plugin
        splitMergePluginJob = viewModelScope.launch {
            val ownerJob = coroutineContext[Job]
            var loadingShown = false
            try {
                val scope = object : GetScope {
                    override val hostPackageName = app.packageName
                    override val pluginPackageName = plugin.packageName
                    override suspend fun requestStartActivity(intent: Intent): Intent? =
                        withContext(Dispatchers.Main) {
                            if (launchedActivity != null) error("Previous activity has not finished")
                            try {
                                val result = with(CompletableDeferred<ActivityResult>()) {
                                    launchedActivity = this
                                    launchActivityChannel.send(intent)
                                    await()
                                }
                                when (result.resultCode) {
                                    Activity.RESULT_OK -> result.data
                                    Activity.RESULT_CANCELED -> throw UserInteractionException.Activity.Cancelled()
                                    else -> throw UserInteractionException.Activity.NotCompleted(
                                        result.resultCode,
                                        result.data
                                    )
                                }
                            } finally {
                                launchedActivity = null
                            }
                        }
                }

                val (data, _) = withContext(Dispatchers.IO) {
                    plugin.get(
                        scope,
                        packageName.trim(),
                        version?.trim()?.takeUnless { it.isBlank() }
                    )
                } ?: run {
                    if (splitMergePluginJob === ownerJob) {
                        app.toast(app.getString(R.string.downloader_app_not_found))
                    }
                    return@launch
                }

                ensureCurrentSplitMergeOwner(ownerJob)
                val downloadingMessage = app.getString(R.string.merge_split_apk_downloading)
                splitMergeStateFlow.value = SplitMergeState(
                    preparingSelection = true,
                    inputName = packageName.trim().takeIf { it.isNotBlank() },
                    currentMessage = downloadingMessage,
                    downloadStep = SplitMergeStepState(
                        status = SplitMergeStepStatus.RUNNING,
                        message = downloadingMessage,
                        progressCurrent = 0L,
                        progressTotal = null
                    ),
                    mergeStep = SplitMergeStepState(
                        status = SplitMergeStepStatus.WAITING,
                        message = downloadingMessage
                    )
                )
                appendSplitMergeLog(downloadingMessage)
                loadingShown = true
                val downloaded = downloadSplitInputFromPlugin(plugin, data)
                ensureCurrentSplitMergeOwner(coroutineContext[Job])
                prepareSplitMergeSelection(
                    inputFile = downloaded,
                    inputDisplayName = downloaded.name,
                    cleanup = { runCatching { downloaded.delete() } },
                    showDownloadStep = false,
                    openScreen = true
                )
            } catch (e: UserInteractionException.Activity) {
                if (splitMergePluginJob === ownerJob) {
                    if (loadingShown) {
                        splitMergeStateFlow.value = splitMergeStateFlow.value.copy(
                            preparingSelection = false,
                            inProgress = false,
                            completed = false,
                            canSaveAgain = false,
                            showDownloadStep = false,
                            error = e.message ?: app.getString(R.string.merge_split_apk_cancelled),
                            currentMessage = e.message ?: app.getString(R.string.merge_split_apk_cancelled),
                            downloadStep = splitMergeStateFlow.value.downloadStep.copy(
                                status = SplitMergeStepStatus.FAILED,
                                message = e.message ?: app.getString(R.string.merge_split_apk_cancelled)
                            )
                        )
                    }
                    app.toast(e.message ?: app.getString(R.string.merge_split_apk_cancelled))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (splitMergePluginJob === ownerJob) {
                    if (loadingShown) {
                        splitMergeStateFlow.value = splitMergeStateFlow.value.copy(
                            preparingSelection = false,
                            inProgress = false,
                            completed = false,
                            canSaveAgain = false,
                            showDownloadStep = false,
                            error = e.message ?: app.getString(R.string.merge_split_apk_failed),
                            currentMessage = e.message ?: app.getString(R.string.merge_split_apk_failed),
                            downloadStep = splitMergeStateFlow.value.downloadStep.copy(
                                status = SplitMergeStepStatus.FAILED,
                                message = e.message ?: app.getString(R.string.merge_split_apk_failed)
                            )
                        )
                    }
                    app.toast(
                        app.getString(
                            R.string.downloader_error,
                            e.message ?: e::class.simpleName ?: "Unknown error"
                        )
                    )
                }
            } finally {
                if (splitMergePluginJob === ownerJob) {
                    if (!splitMergeStateFlow.value.inProgress) {
                        SplitMergeNotification.clear(app)
                    }
                    splitMergePlugin = null
                    splitMergePluginJob = null
                }
            }
        }
    }

    fun handlePluginActivityResult(result: ActivityResult) {
        launchedActivity?.complete(result)
    }

    fun confirmSplitMergeSelection(
        includedModules: Set<String>,
        stripNativeLibs: Boolean
    ) {
        val pendingSource = pendingSplitMergeSource ?: return
        val excludedModules = splitMergeStateFlow.value.selection
            ?.modules
            ?.map { it.name }
            ?.filterNot { it in includedModules }
            ?.toSet()
            .orEmpty()
        pendingSplitMergeSource = null
        splitMergeJob?.cancel()
        splitMergeJob = viewModelScope.launch {
            runSplitMerge(
                inputFile = pendingSource.inputFile,
                inputDisplayName = pendingSource.inputDisplayName,
                sourceCleanup = pendingSource.cleanup,
                showDownloadStep = pendingSource.showDownloadStep,
                includedModules = includedModules,
                stripNativeLibs = stripNativeLibs,
                excludedModules = excludedModules,
                pendingCacheUseToken = pendingSource.cacheUseToken
            )
        }
    }

    private fun clearPendingSplitMergeSource() {
        val pendingSource = pendingSplitMergeSource ?: return
        pendingSplitMergeSource = null
        runCatching { pendingSource.cleanup() }
        runCatching { pendingSource.cacheUseToken?.close() }
    }

    private fun newSplitMergeRunWorkspace(): File =
        splitMergeWorkspace.resolve("run-${System.currentTimeMillis()}").apply { mkdirs() }

    private fun cleanupSplitMergeRunWorkspace(workspace: File?) {
        val target = workspace ?: return
        runCatching { target.deleteRecursively() }
    }

    private fun cleanupCachedMergedApk(file: File?) {
        val target = file ?: return
        runCatching { target.delete() }
        val parent = target.parentFile
        if (parent?.parentFile == splitMergeWorkspace && parent.name.startsWith("run-")) {
            runCatching { parent.deleteRecursively() }
        }
    }

    private fun invalidateCachedSplitMergeOutput() {
        splitMergeInstallJob?.cancel()
        splitMergeInstallJob = null
        splitMergeExternalInstall?.let(installerManager::cleanup)
        splitMergeExternalInstall = null
        cachedMergedApk?.let(::cleanupCachedMergedApk)
        cachedMergedApk = null
    }

    private fun splitMergeRunWorkspaceFor(file: File?): File? {
        val parent = file?.parentFile ?: return null
        return parent.takeIf { it.parentFile == splitMergeWorkspace && it.name.startsWith("run-") }
    }

    private fun cleanupLegacySplitMergeArtifacts(protectedFiles: Set<File> = emptySet()) {
        val protectedDirs = buildSet {
            activeSplitMergeRunWorkspace?.absoluteFile?.let(::add)
            splitMergeRunWorkspaceFor(cachedMergedApk)?.absoluteFile?.let(::add)
        }
        val protectedFilePaths = protectedFiles.mapTo(mutableSetOf()) { file ->
            runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }.toPath()
        }
        fun File.isProtectedFile(): Boolean {
            val path = runCatching { canonicalFile }.getOrElse { absoluteFile }.toPath()
            return path in protectedFilePaths
        }
        runCatching { splitMergeWorkspace.resolve("selected-modules.txt").delete() }
        runCatching { splitMergeWorkspace.resolve("last-merged-unsigned.apk").delete() }
        splitMergeWorkspace.listFiles()
            ?.forEach { entry ->
                when {
                    entry.isDirectory &&
                        (entry.name.startsWith("split-") || entry.name.startsWith("run-")) &&
                        entry.absoluteFile !in protectedDirs ->
                        runCatching { entry.deleteRecursively() }
                    entry.isFile && entry.name.startsWith("plugin-input-") && !entry.isProtectedFile() ->
                        runCatching { entry.delete() }
                }
            }
    }

    private fun isCurrentSplitMergeOwner(ownerJob: Job?): Boolean =
        ownerJob != null &&
            ownerJob.isActive &&
            !splitMergeStateFlow.value.cancellationInProgress &&
            (splitMergeJob === ownerJob || splitMergePluginJob === ownerJob)

    private fun ensureCurrentSplitMergeOwner(ownerJob: Job?) {
        ownerJob?.ensureActive()
        if (!isCurrentSplitMergeOwner(ownerJob)) {
            throw CancellationException(app.getString(R.string.merge_split_apk_cancelled))
        }
    }

    private inline fun updateSplitMergeStateIfCurrent(
        ownerJob: Job?,
        update: (SplitMergeState) -> SplitMergeState
    ) {
        if (!isCurrentSplitMergeOwner(ownerJob)) return
        splitMergeStateFlow.value = update(splitMergeStateFlow.value)
        updateSplitMergeNotification()
    }

    private fun setSplitMergeSelectionPreparing(inputName: String?) {
        resetSplitMergeNotificationProgressTracking()
        val preparingMessage = app.getString(R.string.merge_split_apk_preparing)
        splitMergeStateFlow.value = SplitMergeState(
            preparingSelection = true,
            inputName = inputName,
            currentMessage = preparingMessage,
            mergeStep = SplitMergeStepState(
                status = SplitMergeStepStatus.WAITING,
                message = preparingMessage
            )
        )
        appendSplitMergeLog(
            inputName?.takeIf { it.isNotBlank() }?.let { "Selected split archive: $it" }
                ?: "Selected split archive."
        )
        appendSplitMergeLog(preparingMessage)
    }

    private suspend fun prepareSplitMergeSelection(
        inputFile: File,
        inputDisplayName: String,
        cleanup: () -> Unit = {},
        showDownloadStep: Boolean = false,
        openScreen: Boolean
    ) {
        val cacheUseToken = if (inputFile.isInDirectory(app.cacheDir)) {
            CacheCleanupGuard.begin()
        } else {
            null
        }
        val inspection = try {
            withContext(Dispatchers.IO) {
                if (!inputFile.exists()) {
                    throw IOException(app.getString(R.string.merge_split_apk_input_missing))
                }
                if (!SplitApkPreparer.isSplitArchive(inputFile)) {
                    throw IOException(app.getString(R.string.merge_split_apk_input_invalid))
                }
                SplitApkPreparer.inspect(inputFile)
            }
        } catch (error: Throwable) {
            runCatching { cleanup() }
            runCatching { cacheUseToken?.close() }
            throw error
        }
        try {
            ensureCurrentSplitMergeOwner(coroutineContext[Job])
            cleanupLegacySplitMergeArtifacts(protectedFiles = setOf(inputFile))
        } catch (error: Throwable) {
            runCatching { cleanup() }
            runCatching { cacheUseToken?.close() }
            throw error
        }
        val defaultSelection = resolveDefaultSplitSelection(inspection)

        clearPendingSplitMergeSource()
        pendingSplitMergeSource = PendingSplitMergeSource(
            inputFile = inputFile,
            inputDisplayName = inputDisplayName,
            showDownloadStep = showDownloadStep,
            cleanup = cleanup,
            cacheUseToken = cacheUseToken
        )
        splitMergeStateFlow.value = SplitMergeState(
            inProgress = false,
            showDownloadStep = showDownloadStep,
            downloadStep = if (showDownloadStep) {
                splitMergeStateFlow.value.downloadStep.copy(
                    status = SplitMergeStepStatus.COMPLETED,
                    message = app.getString(R.string.merge_split_apk_downloaded)
                )
            } else {
                SplitMergeStepState()
            },
            mergeStep = SplitMergeStepState(
                status = SplitMergeStepStatus.WAITING,
                message = app.getString(R.string.merge_split_apk_selection_ready)
            ),
            writeStep = SplitMergeStepState(),
            signStep = SplitMergeStepState(),
            outputName = defaultMergedOutputName(inputDisplayName),
            currentMessage = app.getString(R.string.merge_split_apk_selection_ready),
            inputName = inputDisplayName,
            selection = inspection,
            selectionIncludedModules = defaultSelection.includedModules,
            selectionStripNativeLibs = defaultSelection.excludeExtraNativeLibs,
            selectionPresetKey = defaultSelection.presetKey
        )
        SplitMergeNotification.clear(app)
        appendSplitMergeLog(app.getString(R.string.merge_split_apk_selection_ready))
        if (openScreen) {
            openSplitMergeScreenChannel.send(Unit)
        }
    }

    fun rememberSplitMergeFilterState(
        presetKey: String?,
        excludeUnusedLanguages: Boolean,
        excludeExtraDensities: Boolean,
        excludeExtraNativeLibs: Boolean
    ) {
        viewModelScope.launch {
            prefs.edit {
                prefs.splitMergeSelectionPreset.value = normalizeSplitMergePresetKey(presetKey)
                prefs.splitMergeExcludeUnusedLanguages.value = excludeUnusedLanguages
                prefs.splitMergeExcludeExtraDensities.value = excludeExtraDensities
                prefs.splitMergeExcludeExtraNativeLibs.value = excludeExtraNativeLibs
            }
        }
    }

    fun rememberSplitMergeSelectionPreset(
        presetKey: String,
        excludeUnusedLanguages: Boolean = false,
        excludeExtraDensities: Boolean = false,
        excludeExtraNativeLibs: Boolean = false
    ) = rememberSplitMergeFilterState(
        presetKey = presetKey,
        excludeUnusedLanguages = excludeUnusedLanguages,
        excludeExtraDensities = excludeExtraDensities,
        excludeExtraNativeLibs = excludeExtraNativeLibs
    )

    fun rememberSplitMergeCleanupFilters(
        excludeUnusedLanguages: Boolean,
        excludeExtraDensities: Boolean,
        excludeExtraNativeLibs: Boolean
    ) {
        viewModelScope.launch {
            prefs.edit {
                val storedPreset = normalizeSplitMergePresetKey(prefs.splitMergeSelectionPreset.value)
                prefs.splitMergeSelectionPreset.value = storedPreset
                prefs.splitMergeExcludeUnusedLanguages.value = excludeUnusedLanguages
                prefs.splitMergeExcludeExtraDensities.value = excludeExtraDensities
                prefs.splitMergeExcludeExtraNativeLibs.value = excludeExtraNativeLibs
            }
        }
    }

    private fun resolveDefaultSplitSelection(
        inspection: SplitApkPreparer.SplitArchiveInspection
    ): SplitMergeDefaultSelection {
        val storedPresetKey = prefs.splitMergeSelectionPreset.getBlocking()
        val presetKey = normalizeSplitMergePresetKey(storedPresetKey)
        val legacyLanguagesPreset = storedPresetKey == "languages"
        val legacyDensityPreset = storedPresetKey == "density"
        val allModules = inspection.modules.mapTo(linkedSetOf()) { it.name }
        val requiredModules = buildSet {
            inspection.baseModuleName?.let(::add)
            if (isEmpty()) {
                inspection.modules.firstOrNull()?.name?.let(::add)
            }
        }
        val languageModules = inspection.modules
            .filter { it.kind == SplitApkPreparer.SplitArchiveModuleKind.LANGUAGE }
            .mapTo(linkedSetOf()) { it.name }
        val densityModules = inspection.modules
            .filter { it.kind == SplitApkPreparer.SplitArchiveModuleKind.DENSITY }
            .mapTo(linkedSetOf()) { it.name }
        val abiModules = inspection.modules
            .filter { it.kind == SplitApkPreparer.SplitArchiveModuleKind.ABI }
            .mapTo(linkedSetOf()) { it.name }
        val optionalLanguageModules = languageModules - requiredModules
        val optionalDensityModules = densityModules - requiredModules
        val optionalAbiModules = abiModules - requiredModules
        val trimmedLanguageModules = inspection.languageTrimmedModules intersect optionalLanguageModules
        val trimmedDensityModules = inspection.densityTrimmedModules intersect optionalDensityModules
        val trimmedAbiModules = inspection.abiTrimmedModules intersect optionalAbiModules
        val storedExcludeUnusedLanguages = prefs.splitMergeExcludeUnusedLanguages.getBlocking()
        val storedExcludeExtraDensities = prefs.splitMergeExcludeExtraDensities.getBlocking()
        val excludeUnusedLanguages = storedExcludeUnusedLanguages || legacyLanguagesPreset
        val excludeExtraDensities = storedExcludeExtraDensities || legacyDensityPreset
        val excludeExtraNativeLibs = prefs.splitMergeExcludeExtraNativeLibs.getBlocking()
        if (
            storedPresetKey != presetKey ||
            legacyLanguagesPreset ||
            legacyDensityPreset
        ) {
            viewModelScope.launch {
                prefs.edit {
                    prefs.splitMergeSelectionPreset.value = presetKey
                    prefs.splitMergeExcludeUnusedLanguages.value = excludeUnusedLanguages
                    prefs.splitMergeExcludeExtraDensities.value = excludeExtraDensities
                    prefs.splitMergeExcludeExtraNativeLibs.value = excludeExtraNativeLibs
                }
            }
        }
        val baseModules = if (presetKey == "none") requiredModules else allModules
        var modules = baseModules
        if (excludeUnusedLanguages) {
            modules = (modules - optionalLanguageModules) + trimmedLanguageModules
        }
        if (excludeExtraDensities) {
            modules = (modules - optionalDensityModules) + trimmedDensityModules
        }
        if (excludeExtraNativeLibs) {
            modules = (modules - optionalAbiModules) + trimmedAbiModules
        }
        modules = (modules + requiredModules)
            .takeIf { it.isNotEmpty() }
            ?: requiredModules.ifEmpty { allModules }
        return SplitMergeDefaultSelection(
            includedModules = modules,
            excludeExtraNativeLibs = excludeExtraNativeLibs,
            presetKey = presetKey
        )
    }

    fun saveLastMergedToPath(outputPath: String) = viewModelScope.launch {
        val current = splitMergeStateFlow.value
        if (current.savingOutput || current.installing) return@launch
        val merged = cachedMergedApk
        if (merged == null || !merged.exists()) {
            reportMissingSplitMergeOutput()
            return@launch
        }
        val outputFile = File(outputPath)
        updateSplitMergeExporting(true)
        runCatching {
            withContext(Dispatchers.IO) {
                outputFile.parentFile?.mkdirs()
                merged.copyTo(outputFile, overwrite = true)
            }
        }.onSuccess {
            completeSplitMergeExport(outputFile.name)
        }.onFailure(::failSplitMergeExport)
    }

    fun saveLastMergedToUri(
        outputUri: Uri,
        outputDisplayName: String? = null
    ) = viewModelScope.launch {
        val current = splitMergeStateFlow.value
        if (current.savingOutput || current.installing) return@launch
        val merged = cachedMergedApk
        if (merged == null || !merged.exists()) {
            reportMissingSplitMergeOutput()
            return@launch
        }
        updateSplitMergeExporting(true)
        runCatching {
            saveFileToUri(merged, outputUri)
        }.onSuccess {
            completeSplitMergeExport(outputDisplayName ?: merged.name)
        }.onFailure(::failSplitMergeExport)
    }

    fun splitMergeInstallerOptions(): List<InstallerManager.Entry> {
        val output = cachedMergedApk
        val entries = if (output?.exists() == true) {
            installerManager.listEntriesForFile(
                target = InstallerManager.InstallTarget.PATCHER,
                includeNone = false,
                sourceFile = output
            )
        } else {
            installerManager.listEntries(
                InstallerManager.InstallTarget.PATCHER,
                includeNone = false
            )
        }
        return entries.filterNot { it.token == InstallerManager.Token.AutoSaved }
    }

    fun openSplitMergeShizukuApp(): Boolean = installerManager.openShizukuApp()

    fun installLastMerged(installerToken: InstallerManager.Token? = null) {
        val current = splitMergeStateFlow.value
        if (current.inProgress || current.savingOutput || current.installing) return
        val merged = cachedMergedApk
        if (merged == null || !merged.exists()) {
            reportMissingSplitMergeOutput()
            return
        }

        splitMergeInstallJob?.cancel()
        splitMergeExternalInstall?.let(installerManager::cleanup)
        splitMergeExternalInstall = null
        splitMergeInstallJob = viewModelScope.launch {
            val ownerJob = coroutineContext[Job]
            val cacheUseToken = CacheCleanupGuard.begin()
            try {
                val installingMessage = app.getString(R.string.installing_ellipsis)
                splitMergeStateFlow.update {
                    it.copy(
                        installing = true,
                        installStatus = installingMessage,
                        error = null
                    )
                }
                appendSplitMergeLog(installingMessage)

                val packageInfo = withContext(Dispatchers.IO) { pm.getPackageInfo(merged) }
                    ?: throw IOException(app.getString(R.string.failed_to_load_apk))
                val packageName = packageInfo.packageName
                val label = with(pm) { packageInfo.label() }
                val plan = withContext(Dispatchers.IO) {
                    installerToken?.let { token ->
                        require(token != InstallerManager.Token.AutoSaved) {
                            app.getString(R.string.installer_status_not_supported)
                        }
                        installerManager.resolvePlanForToken(
                            token = token,
                            target = InstallerManager.InstallTarget.PATCHER,
                            sourceFile = merged,
                            expectedPackage = packageName,
                            sourceLabel = label
                        ) ?: throw IOException(
                            app.getString(R.string.installer_status_not_supported)
                        )
                    } ?: resolveConfiguredSplitMergeInstallerPlan(
                        output = merged,
                        packageName = packageName,
                        label = label
                    )
                }
                appendSplitMergeLog("Installer plan: ${plan::class.java.simpleName}")

                val installed = when (plan) {
                    is InstallerManager.InstallPlan.Internal ->
                        installMergedApkInternally(merged, packageName, label)
                    is InstallerManager.InstallPlan.Mount ->
                        throw IOException(app.getString(R.string.installer_status_not_supported))
                    is InstallerManager.InstallPlan.Shizuku -> {
                        val result = shizukuInstaller.install(
                            merged,
                            packageName,
                            plan.installerPackageNameOverride
                        )
                        if (result.status != AndroidPackageInstaller.STATUS_SUCCESS) {
                            throw IOException(
                                result.message ?: app.getString(R.string.split_installer_failed)
                            )
                        }
                        true
                    }
                    is InstallerManager.InstallPlan.External -> {
                        splitMergeExternalInstall = plan
                        launchAndMonitorSplitMergeExternalInstall(plan)
                    }
                }
                if (installed) {
                    completeSplitMergeInstall(packageName)
                } else {
                    splitMergeStateFlow.update { it.copy(installStatus = null) }
                    appendSplitMergeLog(
                        app.getString(R.string.installation_cancelled_dialog_title)
                    )
                }
            } catch (_: CancellationException) {
                splitMergeStateFlow.update { it.copy(installStatus = null) }
                appendSplitMergeLog(app.getString(R.string.installation_cancelled_dialog_title))
            } catch (error: Throwable) {
                failSplitMergeInstall(error)
            } finally {
                splitMergeExternalInstall?.let(installerManager::cleanup)
                splitMergeExternalInstall = null
                splitMergeStateFlow.update { it.copy(installing = false) }
                runCatching { cacheUseToken.close() }
                if (splitMergeInstallJob === ownerJob) {
                    splitMergeInstallJob = null
                }
            }
        }
    }

    fun clearSplitMergeState() {
        splitMergeCancellationJob?.cancel()
        splitMergeCancellationJob = null
        splitMergeJob?.cancel()
        splitMergeJob = null
        splitMergePluginJob?.cancel()
        splitMergePluginJob = null
        splitMergeInstallJob?.cancel()
        splitMergeInstallJob = null
        splitMergeExternalInstall?.let(installerManager::cleanup)
        splitMergeExternalInstall = null
        splitMergePlugin = null
        clearPendingSplitMergeSource()
        cleanupSplitMergeRunWorkspace(activeSplitMergeRunWorkspace)
        activeSplitMergeRunWorkspace = null
        invalidateCachedSplitMergeOutput()
        cleanupLegacySplitMergeArtifacts()
        resetSplitMergeNotificationProgressTracking()
        SplitMergeNotification.clear(app)
        splitMergeStateFlow.value = SplitMergeState()
    }

    fun cancelSplitMerge() {
        if (splitMergeCancellationJob?.isActive == true) return
        val job = splitMergeJob
        val pluginJob = splitMergePluginJob
        if (job?.isActive != true && pluginJob?.isActive != true) {
            clearPendingSplitMergeSource()
            cleanupSplitMergeRunWorkspace(activeSplitMergeRunWorkspace)
            activeSplitMergeRunWorkspace = null
            cleanupLegacySplitMergeArtifacts()
            splitMergePlugin = null
            splitMergeStateFlow.value = cancelledSplitMergeState(splitMergeStateFlow.value)
            appendSplitMergeLog(app.getString(R.string.merge_split_apk_cancelled))
            SplitMergeNotification.clear(app)
            return
        }
        val isPluginDownloadLoading = pluginJob?.isActive == true &&
            job?.isActive != true &&
            splitMergeStateFlow.value.preparingSelection &&
            splitMergeStateFlow.value.downloadStep.status == SplitMergeStepStatus.RUNNING
        if (isPluginDownloadLoading) {
            val cancelException = CancellationException(app.getString(R.string.merge_split_apk_cancelled))
            pluginJob.cancel(cancelException)
            if (splitMergePluginJob === pluginJob) {
                splitMergePluginJob = null
            }
            splitMergePlugin = null
            clearPendingSplitMergeSource()
            cleanupLegacySplitMergeArtifacts()
            splitMergeStateFlow.value = cancelledSplitMergeState(splitMergeStateFlow.value)
            appendSplitMergeLog(app.getString(R.string.merge_split_apk_cancelled))
            SplitMergeNotification.clear(app)
            return
        }

        val stoppingMessage = app.getString(R.string.merge_split_apk_stopping)
        splitMergeStateFlow.value = splitMergeStateFlow.value.copy(
            cancellationInProgress = true,
            currentMessage = stoppingMessage,
            error = null,
            downloadStep = splitMergeStateFlow.value.downloadStep.copy(
                message = if (splitMergeStateFlow.value.downloadStep.status == SplitMergeStepStatus.RUNNING) {
                    stoppingMessage
                } else {
                    splitMergeStateFlow.value.downloadStep.message
                }
            ),
            mergeStep = splitMergeStateFlow.value.mergeStep.copy(
                message = if (splitMergeStateFlow.value.mergeStep.status == SplitMergeStepStatus.RUNNING) {
                    stoppingMessage
                } else {
                    splitMergeStateFlow.value.mergeStep.message
                }
            ),
            writeStep = splitMergeStateFlow.value.writeStep.copy(
                message = if (splitMergeStateFlow.value.writeStep.status == SplitMergeStepStatus.RUNNING) {
                    stoppingMessage
                } else {
                    splitMergeStateFlow.value.writeStep.message
                }
            ),
            signStep = splitMergeStateFlow.value.signStep.copy(
                message = if (splitMergeStateFlow.value.signStep.status == SplitMergeStepStatus.RUNNING) {
                    stoppingMessage
                } else {
                    splitMergeStateFlow.value.signStep.message
                }
            )
        )
        updateSplitMergeNotification()
        splitMergeRuntime.cancelActiveExecution()
        splitMergeCancellationJob = viewModelScope.launch {
            val cancellationJob = coroutineContext[Job]
            try {
                val cancelException = CancellationException(app.getString(R.string.merge_split_apk_cancelled))
                if (job?.isActive == true) {
                    job.cancel(cancelException)
                    runCatching { job.join() }
                }
                if (pluginJob?.isActive == true) {
                    pluginJob.cancel(cancelException)
                    runCatching { pluginJob.join() }
                }
            } finally {
                if (splitMergeCancellationJob !== cancellationJob) return@launch
                if (splitMergeJob === job) {
                    splitMergeJob = null
                }
                if (splitMergePluginJob === pluginJob) {
                    splitMergePluginJob = null
                }
                if (splitMergePluginJob == null) {
                    splitMergePlugin = null
                }
                clearPendingSplitMergeSource()
                cleanupSplitMergeRunWorkspace(activeSplitMergeRunWorkspace)
                activeSplitMergeRunWorkspace = null
                cleanupLegacySplitMergeArtifacts()
                splitMergeStateFlow.value = cancelledSplitMergeState(splitMergeStateFlow.value)
                appendSplitMergeLog(app.getString(R.string.merge_split_apk_cancelled))
                splitMergeCancellationJob = null
            }
        }
    }

    private fun cancelledSplitMergeState(previous: SplitMergeState): SplitMergeState {
        val cancelledMessage = app.getString(R.string.merge_split_apk_cancelled)
        return previous.copy(
            preparingSelection = false,
            inProgress = false,
            completed = false,
            canSaveAgain = false,
            outputName = null,
            currentMessage = cancelledMessage,
            error = cancelledMessage,
            selection = null,
            selectionIncludedModules = emptySet(),
            selectionStripNativeLibs = false,
            cancellationInProgress = false,
            savingOutput = false,
            installing = false,
            installStatus = null,
            downloadStep = previous.downloadStep.copy(
                status = if (previous.downloadStep.status == SplitMergeStepStatus.RUNNING) {
                    SplitMergeStepStatus.FAILED
                } else {
                    previous.downloadStep.status
                },
                message = if (previous.downloadStep.status == SplitMergeStepStatus.RUNNING) {
                    cancelledMessage
                } else {
                    previous.downloadStep.message
                }
            ),
            mergeStep = previous.mergeStep.copy(
                status = SplitMergeStepStatus.FAILED,
                message = cancelledMessage
            ),
            signStep = previous.signStep.copy(
                status = if (previous.signStep.status == SplitMergeStepStatus.RUNNING) {
                    SplitMergeStepStatus.FAILED
                } else {
                    previous.signStep.status
                },
                message = if (previous.signStep.status == SplitMergeStepStatus.RUNNING) {
                    cancelledMessage
                } else {
                    previous.signStep.message
                }
            ),
            writeStep = previous.writeStep.copy(
                status = if (previous.writeStep.status == SplitMergeStepStatus.RUNNING) {
                    SplitMergeStepStatus.FAILED
                } else {
                    previous.writeStep.status
                },
                message = if (previous.writeStep.status == SplitMergeStepStatus.RUNNING) {
                    cancelledMessage
                } else {
                    previous.writeStep.message
                }
            )
        )
    }

    private suspend fun runSplitMerge(
        inputFile: File,
        inputDisplayName: String,
        sourceCleanup: () -> Unit = {},
        showDownloadStep: Boolean = false,
        includedModules: Set<String>? = null,
        stripNativeLibs: Boolean = false,
        excludedModules: Set<String> = emptySet(),
        pendingCacheUseToken: AutoCloseable? = null
    ) {
        val ownerJob = coroutineContext[Job]
        val runCacheUseToken = CacheCleanupGuard.begin()
        val runWorkspace = newSplitMergeRunWorkspace()
        var keepRunWorkspace = false
        // Code adapted from Morphe, see third-party/NOTICE for more information
        // https://github.com/MorpheApp/morphe-manager/blob/a2c3d31bd7ab42e6bc4b9dd528ed856fc72fb948/app/src/main/java/app/morphe/manager/patcher/runtime/ProcessRuntime.kt
        val processMemoryLimit = MemoryLimitConfig.resolveMemoryLimitMb(
            app,
            prefs.processMemoryLimit.getBlocking()
        )
        activeSplitMergeRunWorkspace = runWorkspace
        invalidateCachedSplitMergeOutput()
        resetSplitMergeNotificationProgressTracking()
        val currentDownloadStep = splitMergeStateFlow.value.downloadStep
        splitMergeStateFlow.value = SplitMergeState(
            inProgress = true,
            showDownloadStep = showDownloadStep,
            downloadStep = if (showDownloadStep) {
                currentDownloadStep.copy(
                    status = if (currentDownloadStep.status == SplitMergeStepStatus.COMPLETED) {
                        SplitMergeStepStatus.COMPLETED
                    } else {
                        SplitMergeStepStatus.WAITING
                    },
                    message = if (currentDownloadStep.status == SplitMergeStepStatus.COMPLETED) {
                        currentDownloadStep.message ?: app.getString(R.string.merge_split_apk_downloaded)
                    } else {
                        null
                    }
                )
            } else {
                SplitMergeStepState()
            },
            mergeStep = SplitMergeStepState(
                status = SplitMergeStepStatus.RUNNING,
                message = app.getString(R.string.merge_split_apk_preparing)
            ),
            writeStep = SplitMergeStepState(
                status = SplitMergeStepStatus.WAITING,
                message = null
            ),
            signStep = SplitMergeStepState(
                status = SplitMergeStepStatus.WAITING,
                message = null
            ),
            outputName = defaultMergedOutputName(inputDisplayName),
            currentMessage = app.getString(R.string.merge_split_apk_preparing),
            inputName = inputDisplayName,
            selection = null,
            logEntries = splitMergeStateFlow.value.logEntries,
            selectionIncludedModules = includedModules.orEmpty(),
            selectionStripNativeLibs = stripNativeLibs,
            excludedModules = excludedModules,
            memoryUsageSamples = emptyList()
        )
        appendSplitMergeLog("Starting split merge: $inputDisplayName")
        appendSplitMergeLog(app.getString(R.string.merge_split_apk_preparing))
        updateSplitMergeNotification()

        runCatching {
            withContext(Dispatchers.IO) {
                if (!inputFile.exists()) {
                    throw IOException(app.getString(R.string.merge_split_apk_input_missing))
                }
                if (!SplitApkPreparer.isSplitArchive(inputFile)) {
                    throw IOException(app.getString(R.string.merge_split_apk_input_invalid))
                }

                val unsignedCopy = splitMergeRuntime.execute(
                    inputFile = inputFile,
                    workspace = runWorkspace,
                    stripNativeLibs = stripNativeLibs,
                    skipUnneededSplits = false,
                    includedModules = includedModules,
                    memoryLimitMb = processMemoryLimit,
                    onProgress = { message ->
                        if (isCurrentSplitMergeOwner(ownerJob)) {
                            appendSplitMergeLog(message)
                        }
                        updateSplitMergeStateIfCurrent(ownerJob) { current ->
                            if (isSplitMergeWriteProgressMessage(message)) {
                                current.copy(
                                    currentMessage = message,
                                    mergeStep = current.mergeStep.copy(
                                        status = SplitMergeStepStatus.COMPLETED,
                                        message = app.getString(R.string.merge_split_apk_merged)
                                    ),
                                    writeStep = current.writeStep.copy(
                                        status = SplitMergeStepStatus.RUNNING,
                                        message = message
                                    )
                                )
                            } else {
                                current.copy(
                                    currentMessage = message,
                                    mergeStep = current.mergeStep.copy(
                                        status = SplitMergeStepStatus.RUNNING,
                                        message = message
                                    )
                                )
                            }
                        }
                    },
                    onSubSteps = { subSteps ->
                        updateSplitMergeStateIfCurrent(ownerJob) { current ->
                            current.copy(mergeSubSteps = subSteps)
                        }
                    },
                    onMemoryUsage = { sample ->
                        recordSplitMergeMemoryUsage(
                            ownerJob = ownerJob,
                            sample = sample.copy(
                                requestedMaxMb = processMemoryLimit.toLong().coerceAtLeast(1L)
                            )
                        )
                    }
                )

                ensureCurrentSplitMergeOwner(ownerJob)
                splitMergeStateFlow.value = splitMergeStateFlow.value.copy(
                    mergeStep = splitMergeStateFlow.value.mergeStep.copy(
                        status = SplitMergeStepStatus.COMPLETED,
                        message = app.getString(R.string.merge_split_apk_merged)
                    ),
                    writeStep = splitMergeStateFlow.value.writeStep.copy(
                        status = SplitMergeStepStatus.COMPLETED,
                        message = app.getString(R.string.merge_split_apk_written)
                    ),
                    currentMessage = app.getString(R.string.merge_split_apk_written)
                )
                updateSplitMergeNotification()
                appendSplitMergeLog(app.getString(R.string.merge_split_apk_merged))
                appendSplitMergeLog(app.getString(R.string.merge_split_apk_written))

                val signedCopy = runWorkspace.resolve("last-merged.apk")
                signedCopy.parentFile?.mkdirs()

                ensureCurrentSplitMergeOwner(ownerJob)
                splitMergeStateFlow.value = splitMergeStateFlow.value.copy(
                    currentMessage = app.getString(R.string.merge_split_apk_signing),
                    signStep = splitMergeStateFlow.value.signStep.copy(
                        status = SplitMergeStepStatus.RUNNING,
                        message = app.getString(R.string.merge_split_apk_signing)
                    )
                )
                updateSplitMergeNotification()
                appendSplitMergeLog(app.getString(R.string.merge_split_apk_signing))
                ensureCurrentSplitMergeOwner(ownerJob)
                keystoreManager.sign(unsignedCopy, signedCopy)
                runCatching { unsignedCopy.delete() }

                cachedMergedApk?.let(::cleanupCachedMergedApk)
                cachedMergedApk = signedCopy
                keepRunWorkspace = true
                val mergedOutputName = resolveMergedOutputName(
                    mergedApk = signedCopy,
                    fallbackSourceName = inputDisplayName
                )

                ensureCurrentSplitMergeOwner(ownerJob)
                splitMergeStateFlow.value = splitMergeStateFlow.value.copy(
                    inProgress = false,
                    completed = true,
                    canSaveAgain = true,
                    error = null,
                    outputName = mergedOutputName,
                    mergeStep = splitMergeStateFlow.value.mergeStep.copy(
                        status = SplitMergeStepStatus.COMPLETED,
                        message = app.getString(R.string.merge_split_apk_merged)
                    ),
                    signStep = splitMergeStateFlow.value.signStep.copy(
                        status = SplitMergeStepStatus.COMPLETED,
                        message = app.getString(R.string.merge_split_apk_signed)
                    ),
                    writeStep = splitMergeStateFlow.value.writeStep.copy(
                        status = SplitMergeStepStatus.COMPLETED,
                        message = app.getString(R.string.merge_split_apk_written)
                    ),
                    currentMessage = app.getString(R.string.merge_split_apk_signed)
                )
                appendSplitMergeLog(app.getString(R.string.merge_split_apk_signed))
            }
        }.onFailure { error ->
            if (error is CancellationException) {
                return@onFailure
            }
            val resolvedErrorMessage = when {
                error is SplitMergeProcessRuntime.ProcessExitException &&
                    SplitMergeProcessRuntime.isMemoryFailureExitCode(error.exitCode) ->
                    app.getString(R.string.merge_split_apk_process_killed_low_memory)
                else -> error.message ?: app.getString(R.string.merge_split_apk_failed)
            }

            updateSplitMergeStateIfCurrent(ownerJob) { current ->
                current.copy(
                    inProgress = false,
                    completed = false,
                    canSaveAgain = cachedMergedApk?.exists() == true,
                    error = resolvedErrorMessage,
                    mergeStep = current.mergeStep.copy(
                        status = if (
                            current.writeStep.status != SplitMergeStepStatus.WAITING ||
                            current.signStep.status != SplitMergeStepStatus.WAITING
                        ) {
                            SplitMergeStepStatus.COMPLETED
                        } else {
                            SplitMergeStepStatus.FAILED
                        },
                        message = if (
                            current.writeStep.status != SplitMergeStepStatus.WAITING ||
                            current.signStep.status != SplitMergeStepStatus.WAITING
                        ) {
                            app.getString(R.string.merge_split_apk_merged)
                        } else {
                            resolvedErrorMessage
                        }
                    ),
                    signStep = current.signStep.copy(
                        status = if (current.signStep.status == SplitMergeStepStatus.RUNNING) {
                            SplitMergeStepStatus.FAILED
                        } else {
                            current.signStep.status
                        },
                        message = if (current.signStep.status == SplitMergeStepStatus.RUNNING) {
                            resolvedErrorMessage
                        } else {
                            current.signStep.message
                        }
                    ),
                    writeStep = current.writeStep.copy(
                        status = if (current.writeStep.status == SplitMergeStepStatus.RUNNING) {
                            SplitMergeStepStatus.FAILED
                        } else {
                            current.writeStep.status
                        },
                        message = if (current.writeStep.status == SplitMergeStepStatus.RUNNING) {
                            resolvedErrorMessage
                        } else {
                            current.writeStep.message
                        }
                    ),
                    downloadStep = current.downloadStep.copy(
                        status = if (current.downloadStep.status == SplitMergeStepStatus.RUNNING) {
                            SplitMergeStepStatus.FAILED
                        } else {
                            current.downloadStep.status
                        }
                    ),
                    currentMessage = resolvedErrorMessage
                )
            }
            appendSplitMergeLog(resolvedErrorMessage)
        }
        SplitMergeNotification.clear(app)
        runCatching { sourceCleanup() }
        runCatching { pendingCacheUseToken?.close() }
        runCatching { runCacheUseToken.close() }
        if (activeSplitMergeRunWorkspace == runWorkspace) {
            activeSplitMergeRunWorkspace = null
        }
        if (!keepRunWorkspace) {
            cleanupSplitMergeRunWorkspace(runWorkspace)
        }
        cleanupLegacySplitMergeArtifacts()
        if (splitMergeJob === ownerJob) {
            splitMergeJob = null
        }
    }

    private fun recordSplitMergeMemoryUsage(ownerJob: Job?, sample: PatcherMemoryUsage) {
        val normalized = sample.copy(
            usedMb = sample.usedMb.coerceAtLeast(0L),
            maxMb = sample.maxMb.coerceAtLeast(1L),
            requestedMaxMb = sample.requestedMaxMb.coerceAtLeast(1L)
        )
        updateSplitMergeStateIfCurrent(ownerJob) { current ->
            current.copy(
                memoryUsageSamples = current.memoryUsageSamples + normalized
            )
        }
    }

    private data class SplitMergeNotificationSubStep(
        val title: String,
        val skipped: Boolean
    )

    private fun updateSplitMergeNotification(state: SplitMergeState = splitMergeStateFlow.value) {
        val shouldShow = !state.cancellationInProgress &&
            (state.inProgress || state.writeStep.status == SplitMergeStepStatus.RUNNING)
        if (!shouldShow) {
            SplitMergeNotification.clear(app)
            return
        }
        SplitMergeNotification.show(
            context = app,
            contentText = splitMergeNotificationContentText(state),
            progress = splitMergeNotificationProgress(state)
        )
    }

    private fun splitMergeNotificationContentText(state: SplitMergeState): String {
        val (stepText, detailText) = when {
            state.downloadStep.status == SplitMergeStepStatus.RUNNING ->
                app.getString(R.string.merge_split_apk_step_download) to
                    splitMergeNotificationDetail(state.downloadStep.message, state.currentMessage)
            state.mergeStep.status == SplitMergeStepStatus.RUNNING ->
                app.getString(R.string.merge_split_apk_step_merge) to
                    splitMergeNotificationDetail(state.mergeStep.message, state.currentMessage)
            state.writeStep.status == SplitMergeStepStatus.RUNNING ->
                app.getString(R.string.merge_split_apk_step_write) to
                    splitMergeNotificationDetail(state.writeStep.message, state.currentMessage)
            state.signStep.status == SplitMergeStepStatus.RUNNING ->
                app.getString(R.string.merge_split_apk_step_sign) to
                    splitMergeNotificationDetail(state.signStep.message, state.currentMessage)
            else -> null to splitMergeNotificationDetail(state.currentMessage, null)
        }
        return when {
            stepText != null && detailText != null -> "$stepText • $detailText"
            stepText != null -> stepText
            detailText != null -> detailText
            else -> app.getString(R.string.merge_split_notification_text)
        }
    }

    private fun splitMergeNotificationDetail(
        primary: String?,
        fallback: String?
    ): String? = primary
        ?.takeIf { it.isNotBlank() }
        ?: fallback?.takeIf { it.isNotBlank() }

    private fun splitMergeNotificationProgress(
        state: SplitMergeState
    ): SplitMergeNotification.Progress {
        val calculated = (splitMergeNotificationProgressFraction(state) *
            SPLIT_MERGE_NOTIFICATION_PROGRESS_MAX)
            .toInt()
            .coerceIn(0, SPLIT_MERGE_NOTIFICATION_PROGRESS_MAX)
        val current = if (state.inProgress) {
            maxOf(lastSplitMergeNotificationProgress, calculated).also {
                lastSplitMergeNotificationProgress = it
            }
        } else {
            calculated
        }
        return SplitMergeNotification.Progress(
            max = SPLIT_MERGE_NOTIFICATION_PROGRESS_MAX,
            current = current
        )
    }

    private fun splitMergeNotificationProgressFraction(state: SplitMergeState): Float {
        if (
            !state.showDownloadStep &&
            state.mergeStep.status == SplitMergeStepStatus.WAITING &&
            state.writeStep.status == SplitMergeStepStatus.WAITING &&
            state.signStep.status == SplitMergeStepStatus.WAITING
        ) {
            return 0f
        }

        var completedUnits = 0f
        var totalUnits = 0f

        if (state.showDownloadStep) {
            totalUnits += 1f
            completedUnits += state.downloadStep.notificationProgressFraction(
                defaultRunningFraction = 0.2f
            )
        }

        totalUnits += 1f
        completedUnits += splitMergeNotificationMergeFraction(state)

        totalUnits += 1f
        completedUnits += state.writeStep.notificationProgressFraction(defaultRunningFraction = 0.5f)

        totalUnits += 1f
        completedUnits += state.signStep.notificationProgressFraction(defaultRunningFraction = 0.5f)

        return if (totalUnits <= 0f) 0f else (completedUnits / totalUnits).coerceIn(0f, 1f)
    }

    private fun splitMergeNotificationMergeFraction(state: SplitMergeState): Float {
        return when (state.mergeStep.status) {
            SplitMergeStepStatus.WAITING -> 0f
            SplitMergeStepStatus.COMPLETED -> 1f
            SplitMergeStepStatus.RUNNING,
            SplitMergeStepStatus.FAILED -> {
                val entries = splitMergeNotificationSubSteps(state)
                if (entries.isEmpty()) {
                    resetSplitMergeNotificationSubStepIndex()
                    0f
                } else {
                    val matchedIndex = splitMergeNotificationCurrentSubStepIndex(
                        entries,
                        state.currentMessage
                    )
                    val currentIndex = when {
                        matchedIndex >= 0 -> {
                            lastSplitMergeNotificationSubStepIndex = matchedIndex
                            matchedIndex
                        }
                        lastSplitMergeNotificationSubStepIndex in entries.indices ->
                            lastSplitMergeNotificationSubStepIndex
                        else -> -1
                    }
                    val completedEntries = entries
                        .take(currentIndex.coerceAtLeast(0))
                        .count { !it.skipped }
                        .toFloat()
                    val currentEntryRunning = currentIndex in entries.indices &&
                        !entries[currentIndex].skipped &&
                        state.mergeStep.status == SplitMergeStepStatus.RUNNING
                    val totalEntries = entries.count { !it.skipped }.coerceAtLeast(1).toFloat()
                    ((completedEntries + if (currentEntryRunning) 0.5f else 0f) / totalEntries)
                        .coerceIn(0f, 1f)
                }
            }
        }
    }

    private fun isSplitMergeWriteProgressMessage(message: String): Boolean =
        when (message.trim().lowercase()) {
            "writing merged apk",
            "stripping native libraries",
            "finalizing merged apk" -> true
            else -> false
        }

    private fun splitMergeNotificationSubSteps(
        state: SplitMergeState
    ): List<SplitMergeNotificationSubStep> {
        val entries = state.mergeSubSteps
            .filterNot { raw ->
                isSplitMergeWriteProgressMessage(raw.removePrefix("[skipped]").trim())
            }
            .map { raw ->
                val skipped = raw.startsWith("[skipped]")
                SplitMergeNotificationSubStep(
                    title = raw.removePrefix("[skipped]").trim(),
                    skipped = skipped
                )
            }
        val extraction = entries.filter {
            it.title.equals("Extracting split APKs", ignoreCase = true)
        }
        val remaining = entries.filterNot {
            it.title.equals("Extracting split APKs", ignoreCase = true)
        }
        return extraction + remaining.filter { it.skipped } + remaining.filter { !it.skipped }
    }

    private fun splitMergeNotificationCurrentSubStepIndex(
        entries: List<SplitMergeNotificationSubStep>,
        currentMessage: String?
    ): Int {
        if (currentMessage.isNullOrBlank()) return -1
        return entries.indexOfFirst { step ->
            step.title.equals(currentMessage, ignoreCase = true)
        }
    }

    private fun SplitMergeStepState.notificationProgressFraction(
        defaultRunningFraction: Float
    ): Float = when (status) {
        SplitMergeStepStatus.WAITING -> 0f
        SplitMergeStepStatus.COMPLETED -> 1f
        SplitMergeStepStatus.RUNNING,
        SplitMergeStepStatus.FAILED -> {
            if (progressCurrent != null && progressTotal != null && progressTotal > 0L) {
                (progressCurrent.toFloat() / progressTotal.toFloat()).coerceIn(0f, 1f)
            } else if (status == SplitMergeStepStatus.RUNNING) {
                defaultRunningFraction
            } else {
                0f
            }
        }
    }

    private fun resetSplitMergeNotificationSubStepIndex() {
        lastSplitMergeNotificationSubStepIndex = -1
    }

    private fun resetSplitMergeNotificationProgressTracking() {
        resetSplitMergeNotificationSubStepIndex()
        lastSplitMergeNotificationProgress = 0
    }

    private suspend fun copyUriToTempFile(uri: Uri, displayName: String?): File =
        CacheCleanupGuard.withCacheInUse {
            withContext(Dispatchers.IO) {
                val baseName = displayName?.takeIf { it.isNotBlank() } ?: "split-input.apks"
                val safeName = baseName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val destination = splitMergeWorkspace.resolve("input-$safeName")
                destination.parentFile?.mkdirs()
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destination).use { output ->
                        input.copyTo(output)
                    }
                } ?: throw FileNotFoundException("Unable to open $uri")
                destination
            }
        }

    private suspend fun saveFileToUri(source: File, uri: Uri) {
        withContext(Dispatchers.IO) {
            contentResolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: throw IOException("Unable to open output destination.")
        }
    }

    private suspend fun downloadSplitInputFromPlugin(
        plugin: LoadedDownloaderPlugin,
        data: Parcelable
    ): File = CacheCleanupGuard.withCacheInUse {
        withContext(Dispatchers.IO) {
            val tempInput = splitMergeWorkspace.resolve("plugin-input-${System.currentTimeMillis()}.apk")
            tempInput.parentFile?.mkdirs()
            val downloadJob = coroutineContext[Job]
            fun ensureDownloadActive() {
                downloadJob?.ensureActive()
            }
            try {
                tempInput.outputStream().buffered().use { baseOutput ->
                    var downloadedBytes = 0L
                    var totalBytes: Long? = null
                    var lastUpdateAt = 0L
                    fun maybePublishProgress(force: Boolean = false) {
                        ensureDownloadActive()
                        val now = System.currentTimeMillis()
                        if (!force && now - lastUpdateAt < 120L) return
                        lastUpdateAt = now
                        updateDownloadStepRunning(downloadedBytes, totalBytes)
                    }
                    val progressOutput = object : java.io.OutputStream() {
                        override fun write(b: Int) {
                            ensureDownloadActive()
                            baseOutput.write(b)
                            downloadedBytes += 1L
                            maybePublishProgress()
                        }

                        override fun write(b: ByteArray, off: Int, len: Int) {
                            ensureDownloadActive()
                            baseOutput.write(b, off, len)
                            downloadedBytes += len.toLong()
                            maybePublishProgress()
                        }

                        override fun flush() {
                            ensureDownloadActive()
                            baseOutput.flush()
                        }
                    }
                    val scope = object : OutputDownloadScope {
                        override val hostPackageName = app.packageName
                        override val pluginPackageName = plugin.packageName
                        override suspend fun reportSize(size: Long) {
                            ensureDownloadActive()
                            totalBytes = size
                            maybePublishProgress(force = true)
                        }
                    }
                    plugin.download(scope, data, progressOutput)
                    maybePublishProgress(force = true)
                }
                ensureDownloadActive()
                if (!tempInput.exists() || tempInput.length() <= 0L) {
                    throw IOException("Downloader plugin returned an empty file.")
                }
                tempInput
            } catch (error: Throwable) {
                runCatching { tempInput.delete() }
                throw error
            }
        }
    }

    private fun File.isInDirectory(directory: File): Boolean {
        val basePath = runCatching { directory.canonicalFile.toPath() }
            .getOrElse { directory.absoluteFile.toPath() }
        val filePath = runCatching { canonicalFile.toPath() }
            .getOrElse { absoluteFile.toPath() }
        return filePath.startsWith(basePath)
    }

    private fun reportMissingSplitMergeOutput() {
        val message = app.getString(R.string.merge_split_apk_no_output)
        splitMergeStateFlow.update {
            it.copy(
                completed = false,
                canSaveAgain = false,
                outputName = null,
                installStatus = null,
                error = message
            )
        }
        appendSplitMergeLog(message)
        app.toast(message)
    }

    private fun updateSplitMergeExporting(exporting: Boolean) {
        splitMergeStateFlow.update {
            it.copy(
                savingOutput = exporting,
                error = if (exporting) null else it.error
            )
        }
    }

    private fun completeSplitMergeExport(outputName: String) {
        val message = app.getString(R.string.merge_split_apk_saved)
        splitMergeStateFlow.update {
            it.copy(
                savingOutput = false,
                outputName = outputName,
                error = null
            )
        }
        appendSplitMergeLog("$message ($outputName)")
        app.toast(message)
    }

    private fun failSplitMergeExport(error: Throwable) {
        val message = error.simpleMessage()
            ?: error.message
            ?: app.getString(R.string.merge_split_apk_failed)
        splitMergeStateFlow.update {
            it.copy(
                savingOutput = false,
                error = message
            )
        }
        appendSplitMergeLog(message)
        app.toast(message)
    }

    private fun resolveConfiguredSplitMergeInstallerPlan(
        output: File,
        packageName: String,
        label: String?
    ): InstallerManager.InstallPlan {
        val target = InstallerManager.InstallTarget.PATCHER
        fun resolve(token: InstallerManager.Token): InstallerManager.InstallPlan? {
            if (
                token == InstallerManager.Token.None ||
                token == InstallerManager.Token.AutoSaved
            ) {
                return null
            }
            return installerManager.resolvePlanForToken(
                token = token,
                target = target,
                sourceFile = output,
                expectedPackage = packageName,
                sourceLabel = label
            )
        }

        val primary = installerManager.getPrimaryToken()
        resolve(primary)?.let { return it }
        val fallback = installerManager.getFallbackToken()
        if (fallback != primary) {
            resolve(fallback)?.let {
                appendSplitMergeLog(
                    "Primary installer is unavailable for merged APK output; using fallback"
                )
                return it
            }
        }
        appendSplitMergeLog(
            "Configured installers are unavailable for merged APK output; using system installer"
        )
        return InstallerManager.InstallPlan.Internal(target)
    }

    private suspend fun installMergedApkInternally(
        apk: File,
        packageName: String,
        label: String?
    ): Boolean {
        if (!pm.requestInstallPackagesPermission()) {
            throw IOException(
                app.getString(R.string.downloaded_app_install_permission_required)
            )
        }
        val result = try {
            sessionInstaller.install(apk, packageName)
        } catch (_: InstallCancelledException) {
            return false
        } catch (_: SessionDeadException) {
            val fallbackPlan = installerManager.createSystemFallbackPlan(
                target = InstallerManager.InstallTarget.PATCHER,
                sourceFile = apk,
                expectedPackage = packageName,
                sourceLabel = label
            )
            return launchAndMonitorSplitMergeExternalInstall(fallbackPlan)
        }
        return when (result) {
            InstallResult.Success -> true
            is InstallResult.Conflict -> throw IOException(
                installerManager.formatFailureHint(
                    AndroidPackageInstaller.STATUS_FAILURE_CONFLICT,
                    result.message
                ) ?: result.message ?: app.getString(R.string.installer_hint_generic)
            )
            is InstallResult.Failure -> throw IOException(
                installerManager.formatFailureHint(result.status, result.message)
                    ?: result.message
                    ?: app.getString(R.string.installer_hint_generic)
            )
        }
    }

    private suspend fun launchAndMonitorSplitMergeExternalInstall(
        plan: InstallerManager.InstallPlan.External
    ): Boolean {
        val baseline = withContext(Dispatchers.IO) {
            pm.getPackageInfo(plan.expectedPackage)?.let { info ->
                pm.getVersionCode(info) to info.lastUpdateTime
            }
        }
        try {
            ContextCompat.startActivity(app, plan.intent, null)
        } catch (error: ActivityNotFoundException) {
            throw IOException(error.simpleMessage(), error)
        }
        val launchedMessage = app.getString(
            R.string.installer_external_launched,
            plan.installerLabel
        )
        splitMergeStateFlow.update { it.copy(installStatus = launchedMessage) }
        appendSplitMergeLog(launchedMessage)
        app.toast(launchedMessage)

        return withTimeoutOrNull(SPLIT_MERGE_EXTERNAL_INSTALL_TIMEOUT_MS) {
            do {
                val current = withContext(Dispatchers.IO) {
                    pm.getPackageInfo(plan.expectedPackage)
                }
                val changed = if (baseline == null) {
                    current != null
                } else {
                    current != null &&
                        (pm.getVersionCode(current) != baseline.first ||
                            current.lastUpdateTime != baseline.second)
                }
                if (!changed) {
                    delay(SPLIT_MERGE_INSTALL_POLL_INTERVAL_MS)
                }
            } while (!changed)
            true
        } ?: throw IOException(
            app.getString(R.string.installer_external_timeout, plan.installerLabel)
        )
    }

    private fun completeSplitMergeInstall(packageName: String) {
        val message = app.getString(R.string.install_app_success)
        splitMergeStateFlow.update {
            it.copy(
                installStatus = message,
                error = null
            )
        }
        appendSplitMergeLog("$message ($packageName)")
        app.toast(message)
    }

    private fun failSplitMergeInstall(error: Throwable) {
        val reason = error.simpleMessage()
            ?: error.message
            ?: app.getString(R.string.installer_hint_generic)
        val message = app.getString(R.string.install_app_fail, reason)
        splitMergeStateFlow.update {
            it.copy(
                installStatus = null,
                error = message
            )
        }
        appendSplitMergeLog(message)
        app.toast(message)
    }

    private fun updateDownloadStepRunning(downloaded: Long, total: Long?) {
        val previousStatus = splitMergeStateFlow.value.downloadStep.status
        val downloadingMessage = app.getString(R.string.merge_split_apk_downloading)
        splitMergeStateFlow.value = splitMergeStateFlow.value.copy(
            preparingSelection = true,
            inProgress = false,
            showDownloadStep = false,
            currentMessage = downloadingMessage,
            error = null,
            downloadStep = splitMergeStateFlow.value.downloadStep.copy(
                status = SplitMergeStepStatus.RUNNING,
                message = downloadingMessage,
                progressCurrent = downloaded,
                progressTotal = total
            )
        )
        if (previousStatus != SplitMergeStepStatus.RUNNING) {
            appendSplitMergeLog(downloadingMessage)
        }
    }

    private fun updateDownloadStepCompleted() {
        splitMergeStateFlow.value = splitMergeStateFlow.value.copy(
            showDownloadStep = true,
            downloadStep = splitMergeStateFlow.value.downloadStep.copy(
                status = SplitMergeStepStatus.COMPLETED,
                message = app.getString(R.string.merge_split_apk_downloaded)
            )
        )
        updateSplitMergeNotification()
        appendSplitMergeLog(app.getString(R.string.merge_split_apk_downloaded))
    }

    private fun appendSplitMergeLog(message: String) {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return
        splitMergeStateFlow.update { current ->
            val lastMessage = current.logEntries.lastOrNull()?.substringAfter("] ", "")
            if (lastMessage == trimmed) {
                current
            } else {
                val timestamp = "%1\$tH:%1\$tM:%1\$tS".format(Date())
                current.copy(logEntries = current.logEntries + "[$timestamp] $trimmed")
            }
        }
    }

    private fun buildSplitMergeLogContent(): String {
        val state = splitMergeStateFlow.value
        val excludedModulesLabel = state.excludedModules
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
            ?: "None"
        return buildString {
            appendLine("------------")
            appendLine(app.getString(R.string.merge_split_apk_log_header_title))
            appendLine("------------")
            appendLine("Generated at: ${Date()}")
            appendLine("Input: ${state.inputName ?: "n/a"}")
            appendLine("Output: ${state.outputName ?: "n/a"}")
            appendLine("Excluded splits: $excludedModulesLabel")
            appendLine()
            appendLine("------------")
            appendLine("Merge Log:")
            appendLine("------------")
            if (state.logEntries.isEmpty()) {
                appendLine("No log messages recorded.")
            } else {
                state.logEntries.forEach { appendLine(it) }
            }
        }
    }

    fun getSplitMergeLogContent(): String = buildSplitMergeLogContent()

    fun exportSplitMergeLogsToPath(
        target: Path,
        onResult: (Boolean) -> Unit = {}
    ) = viewModelScope.launch {
        val exportSucceeded = runCatching {
            withContext(Dispatchers.IO) {
                target.parent?.let { Files.createDirectories(it) }
                Files.newBufferedWriter(
                    target,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                ).use { writer ->
                    writer.write(buildSplitMergeLogContent())
                }
            }
        }.isSuccess

        if (!exportSucceeded) {
            app.toast(app.getString(R.string.merge_split_apk_log_export_failed))
            onResult(false)
            return@launch
        }

        app.toast(app.getString(R.string.merge_split_apk_log_export_success))
        onResult(true)
    }

    fun exportSplitMergeLogsToUri(
        target: Uri?,
        onResult: (Boolean) -> Unit = {}
    ) = viewModelScope.launch {
        if (target == null) {
            onResult(false)
            return@launch
        }

        val exportSucceeded = runCatching {
            withContext(Dispatchers.IO) {
                app.contentResolver.openOutputStream(target, "wt")
                    ?.bufferedWriter(StandardCharsets.UTF_8)
                    ?.use { writer ->
                        writer.write(buildSplitMergeLogContent())
                    }
                    ?: throw IOException("Could not open output stream for split merge log export")
            }
        }.isSuccess

        if (!exportSucceeded) {
            app.toast(app.getString(R.string.merge_split_apk_log_export_failed))
            onResult(false)
            return@launch
        }

        app.toast(app.getString(R.string.merge_split_apk_log_export_success))
        onResult(true)
    }

    private fun defaultMergedOutputName(sourceName: String?): String {
        val fileName = sourceName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.takeIf { it.isNotBlank() }
            ?: "split.apks"
        val base = fileName.substringBeforeLast('.', fileName)
        return "$base-merged.apk"
    }

    private suspend fun resolveMergedOutputName(
        mergedApk: File,
        fallbackSourceName: String?
    ): String {
        val packageInfo = pm.getPackageInfo(mergedApk) ?: return defaultMergedOutputName(fallbackSourceName)
        val packageName = packageInfo.packageName.takeIf { it.isNotBlank() }
            ?: return defaultMergedOutputName(fallbackSourceName)
        val appName = runCatching { pm.run { packageInfo.label() } }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        return ExportNameFormatter.format(
            prefs.mergedApkExportFormat.get(),
            PatchedAppExportData(
                appName = appName,
                packageName = packageName,
                appVersion = packageInfo.versionName,
                patchBundleNames = listOf("Merged")
            ),
            ExportNameFormatter.DEFAULT_MERGED_APK_TEMPLATE
        )
    }

    override fun onCleared() {
        splitMergeRuntime.cancelActiveExecution()
        splitMergeJob?.cancel()
        splitMergeJob = null
        splitMergePluginJob?.cancel()
        splitMergePluginJob = null
        splitMergeInstallJob?.cancel()
        splitMergeInstallJob = null
        splitMergeExternalInstall?.let(installerManager::cleanup)
        splitMergeExternalInstall = null
        splitMergePlugin = null
        clearPendingSplitMergeSource()
        cleanupSplitMergeRunWorkspace(activeSplitMergeRunWorkspace)
        activeSplitMergeRunWorkspace = null
        cachedMergedApk?.let(::cleanupCachedMergedApk)
        cachedMergedApk = null
        cleanupLegacySplitMergeArtifacts()
        SplitMergeNotification.clear(app)
        super.onCleared()
    }
}


private fun normalizeSplitMergePresetKey(value: String?): String = when (value) {
    "none", "recommended", SPLIT_MERGE_PRESET_UNSELECTED -> value
    else -> "all"
}

private data class SplitMergeDefaultSelection(
    val includedModules: Set<String>,
    val excludeExtraNativeLibs: Boolean,
    val presetKey: String
)

data class SplitMergeState(
    val preparingSelection: Boolean = false,
    val cancellationInProgress: Boolean = false,
    val inProgress: Boolean = false,
    val completed: Boolean = false,
    val canSaveAgain: Boolean = false,
    val savingOutput: Boolean = false,
    val installing: Boolean = false,
    val installStatus: String? = null,
    val showDownloadStep: Boolean = false,
    val inputName: String? = null,
    val outputName: String? = null,
    val currentMessage: String? = null,
    val error: String? = null,
    val mergeSubSteps: List<String> = emptyList(),
    val logEntries: List<String> = emptyList(),
    val excludedModules: Set<String> = emptySet(),
    val selection: SplitApkPreparer.SplitArchiveInspection? = null,
    val selectionIncludedModules: Set<String> = emptySet(),
    val selectionStripNativeLibs: Boolean = false,
    val selectionPresetKey: String = "all",
    val downloadStep: SplitMergeStepState = SplitMergeStepState(),
    val mergeStep: SplitMergeStepState = SplitMergeStepState(),
    val writeStep: SplitMergeStepState = SplitMergeStepState(),
    val signStep: SplitMergeStepState = SplitMergeStepState(),
    val memoryUsageSamples: List<PatcherMemoryUsage> = emptyList()
)

private data class PendingSplitMergeSource(
    val inputFile: File,
    val inputDisplayName: String,
    val showDownloadStep: Boolean,
    val cleanup: () -> Unit = {},
    val cacheUseToken: AutoCloseable? = null
)

data class SplitMergeStepState(
    val status: SplitMergeStepStatus = SplitMergeStepStatus.WAITING,
    val message: String? = null,
    val progressCurrent: Long? = null,
    val progressTotal: Long? = null
)

enum class SplitMergeStepStatus {
    WAITING,
    RUNNING,
    COMPLETED,
    FAILED
}
