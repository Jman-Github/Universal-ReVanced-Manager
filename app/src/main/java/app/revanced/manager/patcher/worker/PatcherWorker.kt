package app.revanced.manager.patcher.worker

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Parcelable
import android.os.PowerManager
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.revanced.manager.MainActivity
import app.universal.revanced.manager.R
import app.revanced.manager.data.platform.Filesystem
import app.revanced.manager.data.room.apps.installed.InstallType
import app.revanced.manager.domain.installer.RootInstaller
import app.revanced.manager.domain.manager.KeystoreManager
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.domain.repository.DownloadResult
import app.revanced.manager.domain.repository.DownloadedAppRepository
import app.revanced.manager.domain.repository.DownloaderPluginRepository
import app.revanced.manager.domain.repository.InstalledAppRepository
import app.revanced.manager.domain.repository.PatchBundleRepository
import app.revanced.manager.domain.worker.Worker
import app.revanced.manager.domain.worker.WorkerRepository
import app.revanced.manager.network.downloader.LoadedDownloaderPlugin
import app.revanced.manager.patcher.ProgressEvent
import app.revanced.manager.patcher.RemoteError
import app.revanced.manager.patcher.StepId
import app.revanced.manager.patcher.logger.Logger
import app.revanced.manager.patcher.split.SplitApkPreparer
import app.revanced.manager.patcher.runtime.CoroutineRuntime
import app.revanced.manager.patcher.runtime.ProcessRuntime
import app.revanced.manager.patcher.runtime.MemoryLimitConfig
import app.revanced.manager.patcher.ample.AmpleBridgeFailureException
import app.revanced.manager.patcher.morphe.MorpheBridgeFailureException
import app.revanced.manager.patcher.runtime.ample.AmpleBridgeRuntime
import app.revanced.manager.patcher.runtime.ample.AmpleProcessRuntime
import app.revanced.manager.patcher.runtime.morphe.MorpheBridgeRuntime
import app.revanced.manager.patcher.runtime.morphe.MorpheProcessRuntime
import app.revanced.manager.patcher.runStep
import app.revanced.manager.patcher.toRemoteError
import app.revanced.manager.patcher.patch.PatchBundleType
import app.revanced.manager.plugin.downloader.GetScope
import app.revanced.manager.plugin.downloader.PluginHostApi
import app.revanced.manager.plugin.downloader.UserInteractionException
import app.revanced.manager.ui.model.SelectedApp
import app.revanced.manager.util.Options
import app.revanced.manager.util.PM
import app.revanced.manager.util.PatchSelection
import app.revanced.manager.util.tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import kotlin.math.min

@OptIn(PluginHostApi::class)
class PatcherWorker(
    context: Context,
    parameters: WorkerParameters
) : Worker<PatcherWorker.Args>(context, parameters), KoinComponent {
    private val workerRepository: WorkerRepository by inject()
    private val prefs: PreferencesManager by inject()
    private val keystoreManager: KeystoreManager by inject()
    private val downloaderPluginRepository: DownloaderPluginRepository by inject()
    private val downloadedAppRepository: DownloadedAppRepository by inject()
    private val pm: PM by inject()
    private val fs: Filesystem by inject()
    private val installedAppRepository: InstalledAppRepository by inject()
    private val rootInstaller: RootInstaller by inject()
    private val patchBundleRepository: PatchBundleRepository by inject()
    private var activeRuntime: app.revanced.manager.patcher.runtime.Runtime? = null
    private var activeMorpheRuntime: app.revanced.manager.patcher.runtime.morphe.MorpheRuntime? = null
    private var activeAmpleRuntime: app.revanced.manager.patcher.runtime.ample.AmpleRuntime? = null
    private val notificationManager by lazy {
        applicationContext.getSystemService(NotificationManager::class.java)
    }
    private val patchingServiceType by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
    }

    class Args(
        val input: SelectedApp,
        val output: String,
        val selectedPatches: PatchSelection,
        val options: Options,
        val logger: Logger,
        val handleStartActivityRequest: suspend (LoadedDownloaderPlugin, Intent) -> ActivityResult,
        val setInputFile: suspend (File, Boolean, Boolean) -> Unit,
        val onEvent: (ProgressEvent) -> Unit
    ) {
        val packageName get() = input.packageName
    }

    override suspend fun getForegroundInfo() = createForegroundInfo(event = null, totalPatchCount = 0)

    private fun createForegroundInfo(
        event: ProgressEvent?,
        totalPatchCount: Int
    ): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID,
        createNotification(event, totalPatchCount),
        patchingServiceType
    )

    private fun createNotification(
        event: ProgressEvent?,
        totalPatchCount: Int
    ): Notification {
        val notificationIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val channel = NotificationChannel(
            PATCHING_NOTIFICATION_CHANNEL_ID,
            applicationContext.getString(R.string.notification_channel_patching_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description =
            applicationContext.getString(R.string.notification_channel_patching_description)
        notificationManager.createNotificationChannel(channel)
        val progress = notificationProgress(event, totalPatchCount)
        val contentText = notificationContentText(event, totalPatchCount)
        return Notification.Builder(applicationContext, channel.id)
            .setContentTitle(applicationContext.getText(R.string.patcher_notification_title))
            .setContentText(contentText)
            .setSmallIcon(Icon.createWithResource(applicationContext, R.drawable.ic_notification))
            .setContentIntent(pendingIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (progress != null) {
                    setProgress(progress.max, progress.current, progress.indeterminate)
                } else {
                    setProgress(0, 0, false)
                }
            }
            .build()
    }

    private fun notificationContentText(
        event: ProgressEvent?,
        totalPatchCount: Int
    ): CharSequence {
        val stepText = event?.stepId?.let { step ->
            when (step) {
                StepId.DownloadAPK -> applicationContext.getString(R.string.download_apk)
                StepId.LoadPatches -> applicationContext.getString(R.string.patcher_step_load_patches)
                StepId.PrepareSplitApk -> applicationContext.getString(R.string.patcher_step_prepare_split_apk)
                StepId.ReadAPK -> applicationContext.getString(R.string.patcher_step_unpack)
                StepId.ExecutePatches -> applicationContext.getString(R.string.patcher_step_group_patching)
                is StepId.ExecutePatch -> {
                    if (totalPatchCount > 0) {
                        val current = (step.index + 1).coerceIn(1, totalPatchCount)
                        "${applicationContext.getString(R.string.patcher_step_group_patching)} ($current/$totalPatchCount)"
                    } else {
                        applicationContext.getString(R.string.patcher_step_group_patching)
                    }
                }
                StepId.WriteAPK -> applicationContext.getString(R.string.patcher_step_group_saving)
                StepId.SignAPK -> applicationContext.getString(R.string.patcher_step_sign_apk)
            }
        }

        val detail = when (event) {
            is ProgressEvent.Progress -> event.message?.takeIf { it.isNotBlank() }
            else -> null
        }

        return when {
            stepText != null && detail != null -> "$stepText • $detail"
            stepText != null -> stepText
            else -> applicationContext.getText(R.string.patcher_notification_text)
        }
    }

    private data class NotificationProgress(
        val max: Int,
        val current: Int,
        val indeterminate: Boolean
    )

    private fun notificationProgress(
        event: ProgressEvent?,
        totalPatchCount: Int
    ): NotificationProgress? {
        if (event == null) return NotificationProgress(max = 0, current = 0, indeterminate = true)
        return when (event) {
            is ProgressEvent.Started -> NotificationProgress(max = 0, current = 0, indeterminate = true)
            is ProgressEvent.Progress -> {
                val total = event.total?.takeIf { it > 0L }
                val current = event.current
                if (total != null && current != null) {
                    val maxInt = min(total, Int.MAX_VALUE.toLong()).toInt()
                    val curInt = min(current, maxInt.toLong()).toInt()
                    NotificationProgress(max = maxInt, current = curInt, indeterminate = false)
                } else {
                    NotificationProgress(max = 0, current = 0, indeterminate = true)
                }
            }
            is ProgressEvent.Completed -> {
                when (val step = event.stepId) {
                    is StepId.ExecutePatch -> {
                        if (totalPatchCount <= 0) {
                            NotificationProgress(max = 0, current = 0, indeterminate = true)
                        } else {
                            val current = (step.index + 1).coerceIn(0, totalPatchCount)
                            NotificationProgress(
                                max = totalPatchCount,
                                current = current,
                                indeterminate = false
                            )
                        }
                    }
                    else -> NotificationProgress(max = 0, current = 0, indeterminate = true)
                }
            }
            is ProgressEvent.Failed -> null
        }
    }

    private fun updateForegroundNotification(event: ProgressEvent?, totalPatchCount: Int) {
        try {
            runBlocking {
                setForeground(createForegroundInfo(event, totalPatchCount))
            }
        } catch (e: Exception) {
            Log.d(tag, "Failed to update foreground notification:", e)
        }
    }

    override suspend fun doWork(): Result {
        kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion { cause ->
            if (cause != null) {
                activeRuntime?.cancel()
                activeMorpheRuntime?.cancel()
                activeAmpleRuntime?.cancel()
            }
        }
        if (runAttemptCount > 0) {
            Log.d(tag, "Android requested retrying but retrying is disabled.".logFmt())
            return Result.failure()
        }

        val wakeLock: PowerManager.WakeLock =
            (applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$tag::Patcher")
                .apply {
                    acquire(10 * 60 * 1000L)
                    Log.d(tag, "Acquired wakelock.")
                }

        val args = workerRepository.claimInput(this)
        val totalPatchCount = args.selectedPatches.values.sumOf { it.size }

        try {
            setForeground(createForegroundInfo(event = null, totalPatchCount = totalPatchCount))
        } catch (e: Exception) {
            Log.d(tag, "Failed to set foreground info:", e)
        }

        val result = try {
            runPatcher(args, totalPatchCount)
        } finally {
            wakeLock.release()
        }

        if (result is Result.Success && args.input is SelectedApp.Local && args.input.temporary) {
            args.input.file.delete()
        }

        return result
    }

    private suspend fun runPatcher(args: Args, totalPatchCount: Int): Result {
        val patchedApk = fs.tempDir.resolve("patched.apk")
        var downloadCleanup: (() -> Unit)? = null
        val eventDispatcher: (ProgressEvent) -> Unit = { event ->
            args.onEvent(event)
            updateForegroundNotification(event, totalPatchCount)
        }

        return try {
            val startTime = System.currentTimeMillis()
            val autoSaveDownloads = prefs.autoSaveDownloaderApks.get()

            if (args.input is SelectedApp.Installed) {
                installedAppRepository.get(args.packageName)?.let {
                    if (it.installType == InstallType.MOUNT) {
                        rootInstaller.unmount(args.packageName)
                    }
                }
            }

            suspend fun download(plugin: LoadedDownloaderPlugin, data: Parcelable) =
                downloadedAppRepository.download(
                    plugin,
                    data,
                    args.packageName,
                    args.input.version,
                    prefs.suggestedVersionSafeguard.get(),
                    !prefs.disablePatchVersionCompatCheck.get(),
                    onDownload = { progress ->
                        eventDispatcher(
                            ProgressEvent.Progress(
                                stepId = StepId.DownloadAPK,
                                current = progress.first,
                                total = progress.second
                            )
                        )
                    },
                    persistDownload = autoSaveDownloads
                ).also { result ->
                    args.setInputFile(result.file, result.needsSplit, result.merged)
                }

            val downloadResult = when (val selectedApp = args.input) {
                is SelectedApp.Download -> runStep(StepId.DownloadAPK, eventDispatcher) {
                    val (plugin, data) = downloaderPluginRepository.unwrapParceledData(selectedApp.data)
                    download(plugin, data)
                }

                is SelectedApp.Search -> runStep(StepId.DownloadAPK, eventDispatcher) {
                    downloaderPluginRepository.loadedPluginsFlow.first()
                        .firstNotNullOfOrNull { plugin ->
                            try {
                                val getScope = object : GetScope {
                                    override val pluginPackageName = plugin.packageName
                                    override val hostPackageName = applicationContext.packageName
                                    override suspend fun requestStartActivity(intent: Intent): Intent? {
                                        val result = args.handleStartActivityRequest(plugin, intent)
                                        return when (result.resultCode) {
                                            Activity.RESULT_OK -> result.data
                                            Activity.RESULT_CANCELED -> throw UserInteractionException.Activity.Cancelled()
                                            else -> throw UserInteractionException.Activity.NotCompleted(
                                                result.resultCode,
                                                result.data
                                            )
                                        }
                                    }
                                }
                                withContext(Dispatchers.IO) {
                                    plugin.get(
                                        getScope,
                                        selectedApp.packageName,
                                        selectedApp.version
                                    )
                                }?.takeIf { (_, version) -> selectedApp.version == null || version == selectedApp.version }
                            } catch (e: UserInteractionException.Activity.NotCompleted) {
                                throw e
                            } catch (_: UserInteractionException) {
                                null
                            }?.let { (data, _) -> download(plugin, data) }
                        } ?: throw Exception("App is not available.")
                }

                is SelectedApp.Local -> {
                    val needsSplit = SplitApkPreparer.isSplitArchive(selectedApp.file)
                    args.setInputFile(selectedApp.file, needsSplit, false)
                    DownloadResult(selectedApp.file, needsSplit)
                }

                is SelectedApp.Installed -> {
                    val source = File(pm.getPackageInfo(selectedApp.packageName)!!.applicationInfo!!.sourceDir)
                    args.setInputFile(source, false, false)
                    DownloadResult(source, false)
                }
            }
            downloadCleanup = downloadResult.cleanup
            val inputFile = downloadResult.file

            val bundleType = patchBundleRepository.selectionBundleType(args.selectedPatches)
                ?: throw IllegalStateException("Cannot patch with mixed ReVanced, Morphe, or Ample bundles.")
            val stripNativeLibs = prefs.stripUnusedNativeLibs.get()
            val skipUnneededSplits = prefs.skipUnneededSplitApks.get()
            val inputIsSplitArchive = SplitApkPreparer.isSplitArchive(inputFile)
            val selectedCount = totalPatchCount
            val experimentalRuntimeEnabled = prefs.useProcessRuntime.get()
            val requestedLimit = prefs.patcherProcessMemoryLimit.get()
            val aggressiveLimit = prefs.patcherProcessMemoryAggressive.get()
            val effectiveLimit = MemoryLimitConfig.clampLimitMb(
                applicationContext,
                if (aggressiveLimit) {
                    MemoryLimitConfig.maxLimitMb(applicationContext)
                } else {
                    requestedLimit
                }
            )

            args.logger.info(
                "Patching started at ${System.currentTimeMillis()} " +
                        "pkg=${args.packageName} version=${args.input.version} " +
                        "input=${inputFile.absolutePath} size=${inputFile.length()} " +
                        "split=$inputIsSplitArchive patches=$selectedCount"
            )
            args.logger.info(
                "Patcher runtime: bundle=$bundleType experimental=$experimentalRuntimeEnabled " +
                    "memoryLimit=${if (experimentalRuntimeEnabled) "${effectiveLimit}MB" else "disabled"} " +
                    "(requested=${requestedLimit}MB${if (aggressiveLimit) ", aggressive" else ""})"
            )

            when (bundleType) {
                PatchBundleType.MORPHE -> {
                    val runtime = if (experimentalRuntimeEnabled) {
                        MorpheProcessRuntime(applicationContext, useMemoryOverride = true)
                    } else {
                        MorpheBridgeRuntime(applicationContext)
                    }
                    activeMorpheRuntime = runtime
                    runtime.execute(
                        inputFile.absolutePath,
                        patchedApk.absolutePath,
                        args.packageName,
                        args.selectedPatches,
                        args.options,
                        args.logger,
                        eventDispatcher,
                        stripNativeLibs,
                        skipUnneededSplits
                    )
                }
                PatchBundleType.AMPLE -> {
                    val runtime = if (experimentalRuntimeEnabled) {
                        AmpleProcessRuntime(applicationContext, useMemoryOverride = true)
                    } else {
                        AmpleBridgeRuntime(applicationContext)
                    }
                    activeAmpleRuntime = runtime
                    runtime.execute(
                        inputFile.absolutePath,
                        patchedApk.absolutePath,
                        args.packageName,
                        args.selectedPatches,
                        args.options,
                        args.logger,
                        eventDispatcher,
                        stripNativeLibs,
                        skipUnneededSplits
                    )
                }
                PatchBundleType.REVANCED -> {
                    val runtime = ProcessRuntime(applicationContext)
                    activeRuntime = runtime
                    try {
                        runtime.execute(
                            inputFile.absolutePath,
                            patchedApk.absolutePath,
                            args.packageName,
                            args.selectedPatches,
                            args.options,
                            args.logger,
                            eventDispatcher,
                            stripNativeLibs,
                            skipUnneededSplits
                        )
                    } catch (e: ProcessRuntime.ProcessExitException) {
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                            Log.w(tag, "app_process exited with code ${e.exitCode}; retrying in-process runtime".logFmt())
                            val fallback = CoroutineRuntime(applicationContext)
                            activeRuntime = fallback
                            fallback.execute(
                                inputFile.absolutePath,
                                patchedApk.absolutePath,
                                args.packageName,
                                args.selectedPatches,
                                args.options,
                                args.logger,
                                eventDispatcher,
                                stripNativeLibs,
                                skipUnneededSplits
                            )
                        } else {
                            throw e
                        }
                    }
                }
            }

            runStep(StepId.SignAPK, eventDispatcher) {
                keystoreManager.sign(patchedApk, File(args.output))
            }

            val elapsed = System.currentTimeMillis() - startTime
            val rt = Runtime.getRuntime()
            val usedMem = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val totalMem = rt.totalMemory() / (1024 * 1024)

            args.logger.info(
                "Patching succeeded: output=${args.output} size=${File(args.output).length()} " +
                        "elapsed=${elapsed}ms memory=${usedMem}MB/${totalMem}MB"
            )

            Log.i(tag, "Patching succeeded".logFmt())
            Result.success()
        } catch (e: ProcessRuntime.ProcessExitException) {
            Log.e(
                tag,
                "Patcher process exited with code ${e.exitCode}".logFmt(),
                e
            )
            val message = applicationContext.getString(
                R.string.patcher_process_exit_message,
                e.exitCode
            )
            eventDispatcher(ProgressEvent.Failed(null, Exception(message).toRemoteError()))
            val previousLimit = prefs.patcherProcessMemoryLimit.get()
            Result.failure(
                workDataOf(
                    PROCESS_EXIT_CODE_KEY to e.exitCode,
                    PROCESS_PREVIOUS_LIMIT_KEY to previousLimit,
                    PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(message)
                )
            )
        } catch (e: ProcessRuntime.RemoteFailureException) {
            Log.e(
                tag,
                "An exception occurred in the remote process while patching. ${e.originalStackTrace}".logFmt()
            )
            eventDispatcher(
                ProgressEvent.Failed(
                    null,
                    RemoteError(
                        type = e::class.java.name,
                        message = e.message,
                        stackTrace = e.originalStackTrace
                    )
                )
            )
            Result.failure(
                workDataOf(PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(e.originalStackTrace))
            )
        } catch (e: MorpheProcessRuntime.ProcessExitException) {
            Log.e(
                tag,
                "Morphe patcher process exited with code ${e.exitCode}".logFmt(),
                e
            )
            val message = applicationContext.getString(
                R.string.patcher_process_exit_message,
                e.exitCode
            )
            eventDispatcher(ProgressEvent.Failed(null, Exception(message).toRemoteError()))
            val previousLimit = prefs.patcherProcessMemoryLimit.get()
            Result.failure(
                workDataOf(
                    PROCESS_EXIT_CODE_KEY to e.exitCode,
                    PROCESS_PREVIOUS_LIMIT_KEY to previousLimit,
                    PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(message)
                )
            )
        } catch (e: MorpheProcessRuntime.RemoteFailureException) {
            Log.e(
                tag,
                "An exception occurred in the Morphe remote process while patching. ${e.originalStackTrace}".logFmt()
            )
            eventDispatcher(
                ProgressEvent.Failed(
                    null,
                    RemoteError(
                        type = e::class.java.name,
                        message = e.message,
                        stackTrace = e.originalStackTrace
                    )
                )
            )
            Result.failure(
                workDataOf(PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(e.originalStackTrace))
            )
        } catch (e: MorpheBridgeFailureException) {
            Log.e(
                tag,
                "An exception occurred in the Morphe bridge runtime while patching. ${e.originalStackTrace}".logFmt()
            )
            eventDispatcher(
                ProgressEvent.Failed(
                    null,
                    RemoteError(
                        type = e::class.java.name,
                        message = e.message,
                        stackTrace = e.originalStackTrace
                    )
                )
            )
            Result.failure(
                workDataOf(PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(e.originalStackTrace))
            )
        } catch (e: AmpleProcessRuntime.ProcessExitException) {
            Log.e(
                tag,
                "Ample patcher process exited with code ${e.exitCode}".logFmt(),
                e
            )
            val message = applicationContext.getString(
                R.string.patcher_process_exit_message,
                e.exitCode
            )
            eventDispatcher(ProgressEvent.Failed(null, Exception(message).toRemoteError()))
            val previousLimit = prefs.patcherProcessMemoryLimit.get()
            Result.failure(
                workDataOf(
                    PROCESS_EXIT_CODE_KEY to e.exitCode,
                    PROCESS_PREVIOUS_LIMIT_KEY to previousLimit,
                    PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(message)
                )
            )
        } catch (e: AmpleProcessRuntime.RemoteFailureException) {
            Log.e(
                tag,
                "An exception occurred in the Ample remote process while patching. ${e.originalStackTrace}".logFmt()
            )
            eventDispatcher(
                ProgressEvent.Failed(
                    null,
                    RemoteError(
                        type = e::class.java.name,
                        message = e.message,
                        stackTrace = e.originalStackTrace
                    )
                )
            )
            Result.failure(
                workDataOf(PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(e.originalStackTrace))
            )
        } catch (e: AmpleBridgeFailureException) {
            Log.e(
                tag,
                "An exception occurred in the Ample bridge runtime while patching. ${e.originalStackTrace}".logFmt()
            )
            eventDispatcher(
                ProgressEvent.Failed(
                    null,
                    RemoteError(
                        type = e::class.java.name,
                        message = e.message,
                        stackTrace = e.originalStackTrace
                    )
                )
            )
            Result.failure(
                workDataOf(PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(e.originalStackTrace))
            )
        } catch (e: Exception) {
            Log.e(tag, "An exception occurred while patching".logFmt(), e)
            eventDispatcher(ProgressEvent.Failed(null, e.toRemoteError()))
            Result.failure(
                workDataOf(PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(e.stackTraceToString()))
            )
        } finally {
            activeRuntime = null
            activeMorpheRuntime = null
            activeAmpleRuntime = null
            patchedApk.delete()
            downloadCleanup?.invoke()
        }
    }

    companion object {
        private const val LOG_PREFIX = "[Worker]"
        private fun String.logFmt() = "$LOG_PREFIX $this"
        private const val PATCHING_NOTIFICATION_CHANNEL_ID = "revanced-patcher-patching"
        private const val NOTIFICATION_ID = 1
        const val PROCESS_EXIT_CODE_KEY = "process_exit_code"
        const val PROCESS_PREVIOUS_LIMIT_KEY = "process_previous_limit"
        const val PROCESS_FAILURE_MESSAGE_KEY = "process_failure_message"
        private const val WORK_DATA_MAX_BYTES = 9000
    }

    private fun trimForWorkData(message: String?): String? {
        if (message.isNullOrEmpty()) return message
        val utf8 = Charsets.UTF_8
        if (message.toByteArray(utf8).size <= WORK_DATA_MAX_BYTES) return message
        var end = message.length
        while (end > 0) {
            val candidate = message.substring(0, end)
            if (candidate.toByteArray(utf8).size <= WORK_DATA_MAX_BYTES) {
                return candidate + "\n[truncated]"
            }
            end -= 1
        }
        return message.take(512) + "\n[truncated]"
    }
}
