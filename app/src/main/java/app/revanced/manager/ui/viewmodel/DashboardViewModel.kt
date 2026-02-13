package app.revanced.manager.ui.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.getSystemService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.revanced.manager.R
import app.revanced.manager.data.platform.NetworkInfo
import app.revanced.manager.domain.bundles.PatchBundleSource.Extensions.asRemoteOrNull
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.domain.repository.DownloaderPluginRepository
import app.revanced.manager.domain.repository.PatchBundleRepository
import app.revanced.manager.network.api.ReVancedAPI
import app.revanced.manager.patcher.split.SplitApkPreparer
import app.revanced.manager.util.PM
import app.revanced.manager.util.uiSafe
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import kotlin.coroutines.coroutineContext

class DashboardViewModel(
    private val app: Application,
    private val patchBundleRepository: PatchBundleRepository,
    private val downloaderPluginRepository: DownloaderPluginRepository,
    private val reVancedAPI: ReVancedAPI,
    private val networkInfo: NetworkInfo,
    val prefs: PreferencesManager,
    private val pm: PM,
) : ViewModel() {
    val availablePatches =
        patchBundleRepository.enabledBundlesInfoFlow.map { it.values.sumOf { bundle -> bundle.patches.size } }
    val bundleUpdateProgress = patchBundleRepository.bundleUpdateProgress
    val bundleImportProgress = patchBundleRepository.bundleImportProgress
    private val contentResolver: ContentResolver = app.contentResolver
    private val powerManager = app.getSystemService<PowerManager>()!!

    val newDownloaderPluginsAvailable =
        downloaderPluginRepository.newPluginPackageNames.map { it.isNotEmpty() }

    /**
     * Android 11 kills the app process after granting the "install apps" permission, which is a problem for the patcher screen.
     * This value is true when the conditions that trigger the bug are met.
     *
     * See: https://github.com/ReVanced/revanced-manager/issues/2138
     */
    val android11BugActive get() = Build.VERSION.SDK_INT == Build.VERSION_CODES.R && !pm.canInstallPackages()

    var updatedManagerVersion: String? by mutableStateOf(null)
        private set
    var showBatteryOptimizationsWarning by mutableStateOf(false)
        private set

    private val bundleListEventsChannel = Channel<BundleListViewModel.Event>()
    val bundleListEventsFlow = bundleListEventsChannel.receiveAsFlow()
    private val splitMergeStateFlow = MutableStateFlow(SplitMergeState())
    val splitMergeState = splitMergeStateFlow.asStateFlow()
    private val splitMergeWorkspace = app.cacheDir.resolve("split-merge-tools").apply { mkdirs() }
    private var cachedMergedApk: File? = null
    private var splitMergeJob: Job? = null

    init {
        viewModelScope.launch {
            checkForManagerUpdates()
            updateBatteryOptimizationsWarning()
        }
        viewModelScope.launch {
            prefs.showBatteryOptimizationBanner.flow.collect { bannerEnabled ->
                showBatteryOptimizationsWarning = bannerEnabled &&
                    !powerManager.isIgnoringBatteryOptimizations(app.packageName)
            }
        }
    }

    fun ignoreNewDownloaderPlugins() = viewModelScope.launch {
        downloaderPluginRepository.acknowledgeAllNewPlugins()
    }

    private suspend fun checkForManagerUpdates() {
        if (!prefs.managerAutoUpdates.get() || !networkInfo.isConnected()) return

        uiSafe(app, R.string.failed_to_check_updates, "Failed to check for updates") {
            updatedManagerVersion = reVancedAPI.getAppUpdate()?.version
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
        splitMergeJob?.cancel()
        splitMergeJob = viewModelScope.launch {
            val inputFile = File(inputPath)
            runSplitMerge(
                inputFile = inputFile,
                inputDisplayName = inputFile.name
            )
        }
    }

    fun startSplitMergeFromUri(
        inputUri: Uri,
        inputDisplayName: String? = null
    ) {
        splitMergeJob?.cancel()
        splitMergeJob = viewModelScope.launch {
            val tempInput = copyUriToTempFile(inputUri, inputDisplayName)
            runSplitMerge(
                inputFile = tempInput,
                inputDisplayName = inputDisplayName ?: tempInput.name
            )
            runCatching { tempInput.delete() }
        }
    }

    fun saveLastMergedToPath(outputPath: String) = viewModelScope.launch {
        val merged = cachedMergedApk
        if (merged == null || !merged.exists()) {
            splitMergeStateFlow.value = splitMergeStateFlow.value.copy(
                error = app.getString(R.string.merge_split_apk_no_output),
                saveStep = splitMergeStateFlow.value.saveStep.copy(
                    status = SplitMergeStepStatus.FAILED,
                    message = app.getString(R.string.merge_split_apk_no_output)
                )
            )
            return@launch
        }
        val outputFile = File(outputPath)
        updateSaveStepRunning(app.getString(R.string.merge_split_apk_saving))
        runCatching {
            withContext(Dispatchers.IO) {
                outputFile.parentFile?.mkdirs()
                merged.copyTo(outputFile, overwrite = true)
            }
        }.onSuccess {
            updateSaveStepCompleted(outputFile.name)
        }.onFailure { error ->
            updateSaveStepFailed(error)
        }
    }

    fun saveLastMergedToUri(
        outputUri: Uri,
        outputDisplayName: String? = null
    ) = viewModelScope.launch {
        val merged = cachedMergedApk
        if (merged == null || !merged.exists()) {
            splitMergeStateFlow.value = splitMergeStateFlow.value.copy(
                error = app.getString(R.string.merge_split_apk_no_output),
                saveStep = splitMergeStateFlow.value.saveStep.copy(
                    status = SplitMergeStepStatus.FAILED,
                    message = app.getString(R.string.merge_split_apk_no_output)
                )
            )
            return@launch
        }
        updateSaveStepRunning(app.getString(R.string.merge_split_apk_saving))
        runCatching {
            saveFileToUri(merged, outputUri)
        }.onSuccess {
            updateSaveStepCompleted(outputDisplayName ?: merged.name)
        }.onFailure { error ->
            updateSaveStepFailed(error)
        }
    }

    fun clearSplitMergeState() {
        splitMergeJob?.cancel()
        splitMergeJob = null
        splitMergeStateFlow.value = SplitMergeState()
    }

    fun cancelSplitMerge() {
        val job = splitMergeJob
        if (job?.isActive == true) {
            job.cancel(CancellationException(app.getString(R.string.merge_split_apk_cancelled)))
        }
        splitMergeJob = null
        splitMergeStateFlow.value = splitMergeStateFlow.value.copy(
            inProgress = false,
            completed = false,
            canSaveAgain = false,
            outputName = null,
            error = app.getString(R.string.merge_split_apk_cancelled),
            currentMessage = app.getString(R.string.merge_split_apk_cancelled),
            mergeStep = splitMergeStateFlow.value.mergeStep.copy(
                status = SplitMergeStepStatus.FAILED,
                message = app.getString(R.string.merge_split_apk_cancelled)
            ),
            saveStep = splitMergeStateFlow.value.saveStep.copy(
                status = SplitMergeStepStatus.WAITING,
                message = null
            )
        )
    }

    private suspend fun runSplitMerge(
        inputFile: File,
        inputDisplayName: String
    ) {
        val ownerJob = coroutineContext[Job]
        splitMergeStateFlow.value = SplitMergeState(
            inProgress = true,
            mergeStep = SplitMergeStepState(
                status = SplitMergeStepStatus.RUNNING,
                message = app.getString(R.string.merge_split_apk_preparing)
            ),
            saveStep = SplitMergeStepState(
                status = SplitMergeStepStatus.WAITING,
                message = null
            ),
            outputName = defaultMergedOutputName(inputDisplayName),
            currentMessage = app.getString(R.string.merge_split_apk_preparing),
            inputName = inputDisplayName
        )

        runCatching {
            withContext(Dispatchers.IO) {
                if (!inputFile.exists()) {
                    throw IOException(app.getString(R.string.merge_split_apk_input_missing))
                }
                if (!SplitApkPreparer.isSplitArchive(inputFile)) {
                    throw IOException(app.getString(R.string.merge_split_apk_input_invalid))
                }

                val stripNativeLibs = prefs.stripUnusedNativeLibs.get()
                val skipUnneededSplits = prefs.skipUnneededSplitApks.get()

                val preparation = SplitApkPreparer.prepareIfNeeded(
                    source = inputFile,
                    workspace = splitMergeWorkspace,
                    stripNativeLibs = stripNativeLibs,
                    skipUnneededSplits = skipUnneededSplits,
                    onProgress = { message ->
                        splitMergeStateFlow.value = splitMergeStateFlow.value.copy(
                            currentMessage = message,
                            mergeStep = splitMergeStateFlow.value.mergeStep.copy(
                                status = SplitMergeStepStatus.RUNNING,
                                message = message
                            )
                        )
                    },
                    onSubSteps = { subSteps ->
                        splitMergeStateFlow.value = splitMergeStateFlow.value.copy(
                            mergeSubSteps = subSteps
                        )
                    }
                )

                try {
                    splitMergeStateFlow.value = splitMergeStateFlow.value.copy(
                        mergeStep = splitMergeStateFlow.value.mergeStep.copy(
                            status = SplitMergeStepStatus.COMPLETED,
                            message = app.getString(R.string.merge_split_apk_merged)
                        ),
                        currentMessage = app.getString(R.string.merge_split_apk_merged)
                    )

                    val mergedCopy = splitMergeWorkspace.resolve("last-merged.apk")
                    mergedCopy.parentFile?.mkdirs()
                    preparation.file.copyTo(mergedCopy, overwrite = true)
                    cachedMergedApk?.let { previous ->
                        if (previous.exists() && previous.absolutePath != mergedCopy.absolutePath) {
                            runCatching { previous.delete() }
                        }
                    }
                    cachedMergedApk = mergedCopy

                    splitMergeStateFlow.value = splitMergeStateFlow.value.copy(
                        inProgress = false,
                        completed = true,
                        canSaveAgain = true,
                        error = null,
                        outputName = defaultMergedOutputName(inputDisplayName),
                        mergeStep = splitMergeStateFlow.value.mergeStep.copy(
                            status = SplitMergeStepStatus.COMPLETED,
                            message = app.getString(R.string.merge_split_apk_merged)
                        ),
                        saveStep = splitMergeStateFlow.value.saveStep.copy(
                            status = SplitMergeStepStatus.COMPLETED,
                            message = null
                        ),
                        currentMessage = app.getString(R.string.merge_split_apk_merged)
                    )
                } finally {
                    preparation.cleanup()
                }
            }
        }.onFailure { error ->
            if (error is CancellationException) {
                splitMergeStateFlow.value = splitMergeStateFlow.value.copy(
                    inProgress = false,
                    completed = false,
                    canSaveAgain = false,
                    outputName = null,
                    error = app.getString(R.string.merge_split_apk_cancelled),
                    currentMessage = app.getString(R.string.merge_split_apk_cancelled),
                    mergeStep = splitMergeStateFlow.value.mergeStep.copy(
                        status = SplitMergeStepStatus.FAILED,
                        message = app.getString(R.string.merge_split_apk_cancelled)
                    ),
                    saveStep = splitMergeStateFlow.value.saveStep.copy(
                        status = SplitMergeStepStatus.WAITING,
                        message = null
                    )
                )
                return@onFailure
            }
            splitMergeStateFlow.value = splitMergeStateFlow.value.copy(
                inProgress = false,
                completed = false,
                canSaveAgain = cachedMergedApk?.exists() == true,
                error = error.message ?: app.getString(R.string.merge_split_apk_failed),
                mergeStep = splitMergeStateFlow.value.mergeStep.copy(
                    status = SplitMergeStepStatus.FAILED,
                    message = error.message ?: app.getString(R.string.merge_split_apk_failed)
                ),
                saveStep = splitMergeStateFlow.value.saveStep.copy(
                    status = if (splitMergeStateFlow.value.saveStep.status == SplitMergeStepStatus.RUNNING) {
                        SplitMergeStepStatus.FAILED
                    } else {
                        splitMergeStateFlow.value.saveStep.status
                    },
                    message = splitMergeStateFlow.value.saveStep.message
                ),
                currentMessage = error.message ?: app.getString(R.string.merge_split_apk_failed)
            )
        }
        if (splitMergeJob === ownerJob) {
            splitMergeJob = null
        }
    }

    private suspend fun copyUriToTempFile(uri: Uri, displayName: String?): File =
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

    private suspend fun saveFileToUri(source: File, uri: Uri) {
        withContext(Dispatchers.IO) {
            contentResolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: throw IOException("Unable to open output destination.")
        }
    }

    private fun updateSaveStepRunning(message: String) {
        splitMergeStateFlow.value = splitMergeStateFlow.value.copy(
            inProgress = false,
            saveStep = splitMergeStateFlow.value.saveStep.copy(
                status = SplitMergeStepStatus.RUNNING,
                message = message
            ),
            currentMessage = message,
            error = null
        )
    }

    private fun updateSaveStepCompleted(outputName: String) {
        splitMergeStateFlow.value = splitMergeStateFlow.value.copy(
            completed = true,
            canSaveAgain = true,
            outputName = outputName,
            error = null,
            saveStep = splitMergeStateFlow.value.saveStep.copy(
                status = SplitMergeStepStatus.COMPLETED,
                message = app.getString(R.string.merge_split_apk_saved)
            ),
            currentMessage = app.getString(R.string.merge_split_apk_saved)
        )
    }

    private fun updateSaveStepFailed(error: Throwable) {
        splitMergeStateFlow.value = splitMergeStateFlow.value.copy(
            error = error.message ?: app.getString(R.string.merge_split_apk_failed),
            saveStep = splitMergeStateFlow.value.saveStep.copy(
                status = SplitMergeStepStatus.FAILED,
                message = error.message ?: app.getString(R.string.merge_split_apk_failed)
            ),
            currentMessage = error.message ?: app.getString(R.string.merge_split_apk_failed)
        )
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

    override fun onCleared() {
        cachedMergedApk?.let { runCatching { it.delete() } }
        super.onCleared()
    }
}

data class SplitMergeState(
    val inProgress: Boolean = false,
    val completed: Boolean = false,
    val canSaveAgain: Boolean = false,
    val inputName: String? = null,
    val outputName: String? = null,
    val currentMessage: String? = null,
    val error: String? = null,
    val mergeSubSteps: List<String> = emptyList(),
    val mergeStep: SplitMergeStepState = SplitMergeStepState(),
    val saveStep: SplitMergeStepState = SplitMergeStepState()
)

data class SplitMergeStepState(
    val status: SplitMergeStepStatus = SplitMergeStepStatus.WAITING,
    val message: String? = null
)

enum class SplitMergeStepStatus {
    WAITING,
    RUNNING,
    COMPLETED,
    FAILED
}
