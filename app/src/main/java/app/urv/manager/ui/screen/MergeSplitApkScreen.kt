package app.urv.manager.ui.screen

import android.os.Build
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.view.WindowManager
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PostAdd
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.urv.manager.data.platform.Filesystem
import app.urv.manager.domain.manager.PreferencesManager
import app.urv.manager.patcher.StepId
import app.urv.manager.patcher.split.SplitApkPreparer
import app.urv.manager.ui.component.AppScaffold
import app.urv.manager.ui.component.AppTopBar
import app.urv.manager.ui.component.CheckedFilterChip
import app.urv.manager.ui.component.ConfirmDialog
import app.urv.manager.ui.component.ExportSavedApkFileNameDialog
import app.urv.manager.ui.component.FullscreenDialog
import app.urv.manager.ui.component.InterceptBackHandler
import app.urv.manager.ui.component.ProgressPercentageBadge
import app.urv.manager.ui.component.haptics.HapticExtendedFloatingActionButton
import app.urv.manager.ui.component.patcher.LegacyAndroidMemoryWarning
import app.urv.manager.ui.component.patcher.PatcherMemoryUsageCard
import app.urv.manager.ui.component.patcher.Steps
import app.urv.manager.ui.component.patches.PathSelectorDialog
import app.urv.manager.ui.model.State
import app.urv.manager.ui.model.Step
import app.urv.manager.ui.model.StepCategory
import app.urv.manager.ui.model.StepDetail
import app.urv.manager.ui.viewmodel.DashboardViewModel
import app.urv.manager.ui.viewmodel.SplitMergeState
import app.urv.manager.ui.viewmodel.SplitMergeStepState
import app.urv.manager.ui.viewmodel.SplitMergeStepStatus
import app.urv.manager.util.FilenameUtils
import app.urv.manager.util.mutableStateSetOf
import app.urv.manager.util.saver.snapshotStateSetSaver
import app.urv.manager.util.toast
import app.universal.revanced.manager.R
import java.nio.file.Files
import java.nio.file.Path
import org.koin.compose.koinInject
import app.urv.manager.ui.component.CenteredDialogTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeSplitApkScreen(
    onBackClick: () -> Unit,
    vm: DashboardViewModel
) {
    val context = LocalContext.current
    val state by vm.splitMergeState.collectAsStateWithLifecycle()
    val fs: Filesystem = koinInject()
    val prefs: PreferencesManager = koinInject()
    val useCustomFilePicker by prefs.useCustomFilePicker.getAsState()
    val splitMergeAutoCollapseSteps by prefs.splitMergeAutoCollapseSteps.getAsState()
    val showSplitMergeMemoryUsageGraph by prefs.showSplitMergeMemoryUsageGraph.getAsState()
    val splitMergeAutoExpandRunningSteps by prefs.splitMergeAutoExpandRunningSteps.getAsState()
    val splitMergeAutoExpandRunningStepsExclusive by
        prefs.splitMergeAutoExpandRunningStepsExclusive.getAsState()
    val useExclusiveAutoExpand =
        splitMergeAutoExpandRunningSteps && splitMergeAutoExpandRunningStepsExclusive
    val storageRoots = remember { fs.storageRoots() }
    val (permissionContract, permissionName) = remember { fs.permissionContract() }

    var showOutputPicker by rememberSaveable { mutableStateOf(false) }
    var outputFileDialogState by remember { mutableStateOf<OutputFileDialogState?>(null) }
    var showLogActionsDialog by rememberSaveable { mutableStateOf(false) }
    var showLogExportPicker by rememberSaveable { mutableStateOf(false) }
    var logExportFileDialogState by remember { mutableStateOf<OutputFileDialogState?>(null) }
    var logExportInProgress by rememberSaveable { mutableStateOf(false) }
    var pendingLogExportFileName by rememberSaveable { mutableStateOf<String?>(null) }
    var showDismissConfirmationDialog by rememberSaveable { mutableStateOf(false) }
    var pendingPermissionRequest by rememberSaveable {
        mutableStateOf<PermissionRequest?>(null)
    }

    val permissionLauncher = rememberLauncherForActivityResult(permissionContract) { granted ->
        if (granted) {
            when (pendingPermissionRequest) {
                PermissionRequest.OUTPUT -> showOutputPicker = true
                PermissionRequest.LOG_EXPORT -> showLogExportPicker = true
                null -> Unit
            }
        } else if (pendingPermissionRequest == PermissionRequest.LOG_EXPORT) {
            pendingLogExportFileName = null
        }
        pendingPermissionRequest = null
    }

    val outputDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        vm.saveLastMergedToUri(
            outputUri = uri,
            outputDisplayName = preferredMergedOutputName(state.outputName, state.inputName)
        )
    }
    val logExportDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        vm.exportSplitMergeLogsToUri(uri)
        showLogExportPicker = false
        pendingLogExportFileName = null
    }

    val canSaveNow = state.canSaveAgain &&
        !state.inProgress &&
        state.saveStep.status != SplitMergeStepStatus.RUNNING
    val mergeCancelledMessage = stringResource(R.string.merge_split_apk_cancelled)
    val canOpenLogActions = state.logEntries.isNotEmpty() &&
        !state.preparingSelection &&
        !state.inProgress &&
        (state.canSaveAgain || (state.error != null && state.error != mergeCancelledMessage))

    fun requestSave() {
        if (!canSaveNow) return
        val defaultName = preferredMergedOutputName(state.outputName, state.inputName)
        if (useCustomFilePicker) {
            if (fs.hasStoragePermission()) {
                showOutputPicker = true
            } else {
                pendingPermissionRequest = PermissionRequest.OUTPUT
                permissionLauncher.launch(permissionName)
            }
        } else {
            outputDocumentLauncher.launch(defaultName)
        }
    }

    fun openLogExportPicker() {
        val logFileName = FilenameUtils.timestampedLogFileName("merger")
        pendingLogExportFileName = logFileName
        if (useCustomFilePicker) {
            if (fs.hasStoragePermission()) {
                showLogExportPicker = true
            } else {
                pendingPermissionRequest = PermissionRequest.LOG_EXPORT
                permissionLauncher.launch(permissionName)
            }
        } else {
            logExportDocumentLauncher.launch(logFileName)
        }
    }

    LaunchedEffect(useCustomFilePicker) {
        if (!useCustomFilePicker) {
            showOutputPicker = false
            outputFileDialogState = null
            showLogExportPicker = false
            logExportFileDialogState = null
            pendingLogExportFileName = null
            pendingPermissionRequest = null
        }
    }

    fun onPageBack() {
        when {
            state.cancellationInProgress -> Unit
            state.selection != null -> {
                vm.clearSplitMergeState()
                onBackClick()
            }
            state.inProgress -> {
                showDismissConfirmationDialog = true
            }
            else -> onBackClick()
        }
    }

    InterceptBackHandler(onBack = ::onPageBack)

    if (state.inProgress) {
        DisposableEffect(context) {
            val window = (context as? Activity)?.window
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    if (showDismissConfirmationDialog) {
        ConfirmDialog(
            onDismiss = { showDismissConfirmationDialog = false },
            onConfirm = {
                showDismissConfirmationDialog = false
                vm.cancelSplitMerge()
                onBackClick()
            },
            title = stringResource(R.string.merge_split_apk_stop_confirm_title),
            description = stringResource(R.string.merge_split_apk_stop_confirm_description),
            icon = Icons.Outlined.Cancel
        )
    }

    state.selection?.let { selection ->
        SplitMergeSelectionDialog(
            selection = selection,
            initialModules = state.selectionIncludedModules,
            initialStripNativeLibs = state.selectionStripNativeLibs,
            initialPresetKey = state.selectionPresetKey,
            onDismissRequest = {
                vm.clearSplitMergeState()
                onBackClick()
            },
            onFilterSelectionChanged = vm::rememberSplitMergeFilterState,
            onConfirm = { includedModules, stripNativeLibs ->
                vm.confirmSplitMergeSelection(
                    includedModules = includedModules,
                    stripNativeLibs = stripNativeLibs
                )
            }
        )
    }

    if (showLogActionsDialog) {
        MergeLogActionsDialog(
            onDismiss = { showLogActionsDialog = false },
            onCopy = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                if (clipboard != null) {
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("Merge log", vm.getSplitMergeLogContent())
                    )
                    context.toast(context.getString(R.string.merge_split_apk_log_copy_success))
                }
                showLogActionsDialog = false
            },
            onExport = {
                showLogActionsDialog = false
                openLogExportPicker()
            }
        )
    }

    if (showOutputPicker && useCustomFilePicker) {
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                if (path == null) {
                    showOutputPicker = false
                }
            },
            fileFilter = { false },
            allowDirectorySelection = true,
            confirmButtonText = stringResource(R.string.save),
            onConfirm = { selection ->
                val exportDirectory = if (Files.isDirectory(selection)) {
                    selection
                } else {
                    selection.parent ?: selection
                }
                outputFileDialogState = OutputFileDialogState(
                    directory = exportDirectory,
                    fileName = preferredMergedOutputName(state.outputName, state.inputName)
                )
            }
        )
    }
    if (showLogExportPicker && useCustomFilePicker) {
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                if (path == null) {
                    showLogExportPicker = false
                    pendingLogExportFileName = null
                }
            },
            fileFilter = { false },
            allowDirectorySelection = true,
            fileTypeLabel = ".txt",
            confirmButtonText = stringResource(R.string.save),
            onConfirm = { selection ->
                val exportDirectory = if (Files.isDirectory(selection)) {
                    selection
                } else {
                    selection.parent ?: selection
                }
                logExportFileDialogState = OutputFileDialogState(
                    directory = exportDirectory,
                    fileName = pendingLogExportFileName
                        ?: FilenameUtils.timestampedLogFileName("merger")
                )
            }
        )
    }
    LaunchedEffect(showLogExportPicker, useCustomFilePicker, pendingLogExportFileName) {
        if (showLogExportPicker && !useCustomFilePicker) {
            val logFileName = pendingLogExportFileName
                ?: FilenameUtils.timestampedLogFileName("merger")
            logExportDocumentLauncher.launch(logFileName)
        }
    }

    outputFileDialogState?.let { dialogState ->
        ExportSavedApkFileNameDialog(
            initialName = dialogState.fileName,
            onDismiss = { outputFileDialogState = null },
            onConfirm = { fileName ->
                val trimmed = fileName.trim()
                if (trimmed.isBlank()) return@ExportSavedApkFileNameDialog
                outputFileDialogState = null
                showOutputPicker = false
                val target = dialogState.directory.resolve(trimmed).toString()
                vm.saveLastMergedToPath(target)
            }
        )
    }
    logExportFileDialogState?.let { dialogState ->
        ExportSavedApkFileNameDialog(
            initialName = dialogState.fileName,
            onDismiss = {
                logExportFileDialogState = null
                pendingLogExportFileName = null
            },
            onConfirm = { fileName ->
                val trimmed = fileName.trim()
                if (trimmed.isBlank()) return@ExportSavedApkFileNameDialog
                logExportFileDialogState = null
                pendingLogExportFileName = null
                logExportInProgress = true
                vm.exportSplitMergeLogsToPath(dialogState.directory.resolve(trimmed)) { success ->
                    logExportInProgress = false
                    if (success) {
                        showLogExportPicker = false
                    }
                }
            }
        )
    }
    if (logExportInProgress) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { CenteredDialogTitle(stringResource(R.string.merge_split_apk_log_exporting_title)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(R.string.merge_split_apk_log_exporting),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth(0.75f)
                            .height(4.dp)
                    )
                }
            }
        )
    }

    val stepsByCategory by remember(state) {
        derivedStateOf {
            val preparingSteps = buildList {
                if (state.showDownloadStep) {
                    add(
                        Step(
                            id = StepId.DownloadAPK,
                            title = context.getString(R.string.merge_split_apk_step_download),
                            category = StepCategory.PREPARING,
                            state = state.downloadStep.status.toUiState(),
                            message = state.downloadStep.message,
                            progress = state.downloadStep.progressCurrent?.let { current ->
                                current to state.downloadStep.progressTotal
                            }
                        )
                    )
                }
                add(
                    Step(
                        id = StepId.PrepareSplitApk,
                        title = context.getString(R.string.merge_split_apk_step_merge),
                        category = StepCategory.PREPARING,
                        state = state.mergeStep.status.toUiState(),
                        message = state.mergeStep.message
                    )
                )
            }
            linkedMapOf(
                StepCategory.PREPARING to preparingSteps,
                StepCategory.SAVING to listOf(
                    Step(
                        id = StepId.SignAPK,
                        title = context.getString(R.string.merge_split_apk_step_sign),
                        category = StepCategory.SAVING,
                        state = state.signStep.status.toUiState(),
                        message = state.signStep.message
                    ),
                    Step(
                        id = StepId.WriteAPK,
                        title = context.getString(R.string.merge_split_apk_step_save),
                        category = StepCategory.SAVING,
                        state = state.saveStep.status.toUiState(),
                        message = state.saveStep.message
                    )
                )
            )
        }
    }

    var currentSubStepIndex by rememberSaveable(state.inProgress, state.inputName) {
        mutableIntStateOf(-1)
    }
    LaunchedEffect(
        state.mergeStep.status,
        state.currentMessage,
        state.mergeSubSteps,
        state.selection
    ) {
        val entries = parseMergeSubSteps(state)
        currentSubStepIndex = when {
            state.selection != null || entries.isEmpty() || state.mergeStep.status == SplitMergeStepStatus.WAITING -> -1
            state.mergeStep.status == SplitMergeStepStatus.COMPLETED -> entries.lastIndex
            else -> {
                val matchedIndex = findCurrentSubStepIndex(entries, state.currentMessage)
                when {
                    matchedIndex >= 0 -> matchedIndex
                    currentSubStepIndex in entries.indices -> currentSubStepIndex
                    else -> -1
                }
            }
        }
    }
    val rawMergeProgress by remember(state, currentSubStepIndex) {
        derivedStateOf {
            calculateSplitMergeProgress(
                state = state,
                currentSubStepIndex = currentSubStepIndex
            )
        }
    }
    val progressTracker = remember(state.inProgress, state.inputName) {
        MonotonicProgressTracker()
    }
    val mergeProgress = if (state.inProgress) {
        progressTracker.record(rawMergeProgress)
    } else {
        rawMergeProgress
    }

    val subStepsById by remember(state, currentSubStepIndex) {
        derivedStateOf {
            val entries = parseMergeSubSteps(state)
            val resolvedCurrentIndex = currentSubStepIndex
                .takeIf { it in entries.indices }
                ?: -1
            mapOf<StepId, List<StepDetail>>(
                StepId.PrepareSplitApk to entries.mapIndexed { index, entry ->
                    StepDetail(
                        title = entry.title,
                        state = resolveSubStepState(
                            index = index,
                            skipped = entry.skipped,
                            currentIndex = resolvedCurrentIndex,
                            mergeStatus = state.mergeStep.status
                        ),
                        skipped = entry.skipped
                    )
                }
            )
        }
    }

    val expandedCategories = rememberSaveable(
        saver = snapshotStateSetSaver<StepCategory>()
    ) {
        mutableStateSetOf<StepCategory>()
    }

    AppScaffold(
        topBar = { scrollBehavior ->
            AppTopBar(
                title = stringResource(R.string.tools_merge_split_screen_title),
                scrollBehavior = scrollBehavior,
                onBackClick = ::onPageBack,
                actions = {
                    ProgressPercentageBadge(progress = mergeProgress)
                }
            )
        },
        bottomBar = {
            BottomAppBar(
                actions = {
                    IconButton(
                        onClick = { showLogActionsDialog = true },
                        enabled = canOpenLogActions
                    ) {
                        Icon(Icons.Outlined.PostAdd, stringResource(R.string.save_logs))
                    }
                },
                floatingActionButton = {
                    AnimatedVisibility(visible = canSaveNow) {
                        HapticExtendedFloatingActionButton(
                            text = { Text(stringResource(R.string.save)) },
                            icon = { Icon(Icons.Outlined.Save, null) },
                            onClick = ::requestSave
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            LinearProgressIndicator(
                progress = { mergeProgress },
                modifier = Modifier.fillMaxWidth(),
                drawStopIndicator = {}
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (showSplitMergeMemoryUsageGraph && state.memoryUsageSamples.isNotEmpty()) {
                    item(key = "memory-usage") {
                        PatcherMemoryUsageCard(samples = state.memoryUsageSamples)
                    }
                }
                items(stepsByCategory.toList(), key = { it.first }) { (category, steps) ->
                    Steps(
                        category = category,
                        steps = steps,
                        subStepsById = subStepsById,
                        isExpanded = expandedCategories.contains(category),
                        autoExpandRunning = splitMergeAutoExpandRunningSteps,
                        autoExpandRunningMainOnly = useExclusiveAutoExpand,
                        autoCollapseCompleted = splitMergeAutoCollapseSteps,
                        onExpand = {
                            if (useExclusiveAutoExpand) {
                                expandedCategories.clear()
                            }
                            expandedCategories.add(category)
                        },
                        onClick = {
                            if (expandedCategories.contains(category)) {
                                expandedCategories.remove(category)
                            } else {
                                expandedCategories.add(category)
                            }
                        }
                    )
                }
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                    item(key = "legacy-android-memory-warning") {
                        LegacyAndroidMemoryWarning()
                    }
                }
            }
        }
    }
}

private class MonotonicProgressTracker {
    private var highestProgress = 0f

    fun record(progress: Float): Float {
        highestProgress = maxOf(highestProgress, progress)
        return highestProgress
    }
}

private data class OutputFileDialogState(
    val directory: Path,
    val fileName: String
)

private enum class PermissionRequest {
    OUTPUT,
    LOG_EXPORT
}

private data class MergeSubStep(
    val title: String,
    val skipped: Boolean
)

private fun parseMergeSubSteps(state: SplitMergeState): List<MergeSubStep> {
    val entries = state.mergeSubSteps.map { raw ->
        val skipped = raw.startsWith("[skipped]")
        MergeSubStep(
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

private fun findCurrentSubStepIndex(entries: List<MergeSubStep>, currentMessage: String?): Int {
    if (currentMessage.isNullOrBlank()) return -1
    return entries.indexOfFirst { step ->
        step.title.equals(currentMessage, ignoreCase = true)
    }
}

private fun resolveSubStepState(
    index: Int,
    skipped: Boolean,
    currentIndex: Int,
    mergeStatus: SplitMergeStepStatus
): State {
    if (skipped) return State.COMPLETED
    return when (mergeStatus) {
        SplitMergeStepStatus.WAITING -> State.WAITING
        SplitMergeStepStatus.RUNNING -> when {
            currentIndex == -1 -> if (index == 0) State.RUNNING else State.WAITING
            index < currentIndex -> State.COMPLETED
            index == currentIndex -> State.RUNNING
            else -> State.WAITING
        }

        SplitMergeStepStatus.COMPLETED -> State.COMPLETED
        SplitMergeStepStatus.FAILED -> when {
            currentIndex == -1 -> if (index == 0) State.FAILED else State.WAITING
            index < currentIndex -> State.COMPLETED
            index == currentIndex -> State.FAILED
            else -> State.WAITING
        }
    }
}

private fun calculateSplitMergeProgress(
    state: SplitMergeState,
    currentSubStepIndex: Int
): Float {
    if (
        !state.showDownloadStep &&
        state.mergeStep.status == SplitMergeStepStatus.WAITING &&
        state.signStep.status == SplitMergeStepStatus.WAITING
    ) {
        return 0f
    }

    var completedPhases = 0f
    var totalPhases = 0

    if (state.showDownloadStep) {
        totalPhases += 1
        completedPhases += state.downloadStep.progressFraction(defaultRunningFraction = 0f)
    }

    val mergeEntries = parseMergeSubSteps(state)
    totalPhases += 1
    completedPhases += calculateMergePhaseFraction(
        state = state,
        entries = mergeEntries,
        currentSubStepIndex = currentSubStepIndex
    )

    totalPhases += 1
    completedPhases += state.signStep.progressFraction(defaultRunningFraction = 0f)

    return if (totalPhases <= 0) {
        0f
    } else {
        (completedPhases / totalPhases.toFloat()).coerceIn(0f, 1f)
    }
}

private fun calculateMergePhaseFraction(
    state: SplitMergeState,
    entries: List<MergeSubStep>,
    currentSubStepIndex: Int
): Float {
    val totalEntries = entries.count { !it.skipped }.coerceAtLeast(1)
    return when (state.mergeStep.status) {
        SplitMergeStepStatus.WAITING -> 0f
        SplitMergeStepStatus.COMPLETED -> 1f
        SplitMergeStepStatus.RUNNING,
        SplitMergeStepStatus.FAILED -> entries
            .take(currentSubStepIndex.coerceAtLeast(0))
            .count { !it.skipped }
            .toFloat()
            .div(totalEntries.toFloat())
            .coerceIn(0f, 1f)
    }
}

private fun SplitMergeStepState.progressFraction(defaultRunningFraction: Float): Float = when (status) {
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

@Composable
private fun MergeLogActionsDialog(
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onExport: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.PostAdd, null) },
        title = { CenteredDialogTitle(stringResource(R.string.merge_split_apk_log_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.merge_split_apk_log_dialog_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onCopy)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.merge_split_apk_log_dialog_copy),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(R.string.merge_split_apk_log_dialog_copy_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onExport)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.merge_split_apk_log_dialog_export),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(R.string.merge_split_apk_log_dialog_export_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        dismissButton = {}
    )
}

private data class SplitMergePresetOption(
    val key: String,
    @StringRes val labelRes: Int,
    val modules: Set<String>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SplitMergeSelectionDialog(
    selection: SplitApkPreparer.SplitArchiveInspection,
    initialModules: Set<String>,
    initialStripNativeLibs: Boolean,
    initialPresetKey: String,
    onDismissRequest: () -> Unit,
    onFilterSelectionChanged: (String, Boolean, Boolean, Boolean) -> Unit,
    onConfirm: (Set<String>, Boolean) -> Unit
) {
    val requiredModules = remember(selection) {
        buildSet {
            selection.baseModuleName?.let(::add)
            if (isEmpty()) {
                selection.modules.firstOrNull()?.name?.let(::add)
            }
        }
    }
    val allModules = remember(selection) { selection.modules.map { it.name }.toSet() }
    val effectiveInitialModules = remember(selection, initialModules, requiredModules, allModules) {
        ((initialModules.takeIf { it.isNotEmpty() } ?: allModules) + requiredModules)
            .takeIf { it.isNotEmpty() }
            ?: allModules.ifEmpty { requiredModules }
    }
    var selectedModules by remember(selection, effectiveInitialModules) { mutableStateOf(effectiveInitialModules) }
    var stripNativeLibs by remember(selection, initialStripNativeLibs) { mutableStateOf(initialStripNativeLibs) }
    val chipColors = FilterChipDefaults.filterChipColors(
        containerColor = MaterialTheme.colorScheme.surface,
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
    )
    val abiModules = remember(selection) {
        selection.modules
            .filter { it.kind == SplitApkPreparer.SplitArchiveModuleKind.ABI }
            .map { it.name }
            .toSet()
    }
    val densityModules = remember(selection) {
        selection.modules
            .filter { it.kind == SplitApkPreparer.SplitArchiveModuleKind.DENSITY }
            .map { it.name }
            .toSet()
    }
    val languageModules = remember(selection) {
        selection.modules
            .filter { it.kind == SplitApkPreparer.SplitArchiveModuleKind.LANGUAGE }
            .map { it.name }
            .toSet()
    }
    val recommendedModules = remember(selection, allModules, requiredModules) {
        (selection.recommendedModules + requiredModules)
            .ifEmpty { requiredModules.ifEmpty { allModules } }
    }
    val trimmedLanguageModules = remember(selection, languageModules) {
        languageModules.filterTo(linkedSetOf()) { it in selection.languageTrimmedModules }
    }
    val trimmedDensityModules = remember(selection, densityModules) {
        densityModules.filterTo(linkedSetOf()) { it in selection.densityTrimmedModules }
    }
    val optionalLanguageModules = remember(languageModules, requiredModules) { languageModules - requiredModules }
    val optionalDensityModules = remember(densityModules, requiredModules) { densityModules - requiredModules }
    val optionalAbiModules = remember(abiModules, requiredModules) { abiModules - requiredModules }
    val trimmedOptionalLanguageModules = remember(trimmedLanguageModules, optionalLanguageModules) {
        trimmedLanguageModules intersect optionalLanguageModules
    }
    val trimmedOptionalDensityModules = remember(trimmedDensityModules, optionalDensityModules) {
        trimmedDensityModules intersect optionalDensityModules
    }
    val trimmedOptionalAbiModules = remember(selection, optionalAbiModules) {
        selection.abiTrimmedModules intersect optionalAbiModules
    }
    val languageCleanupAvailable = remember(optionalLanguageModules, trimmedOptionalLanguageModules) {
        optionalLanguageModules.isNotEmpty() && trimmedOptionalLanguageModules != optionalLanguageModules
    }
    val densityCleanupAvailable = remember(optionalDensityModules, trimmedOptionalDensityModules) {
        optionalDensityModules.isNotEmpty() && trimmedOptionalDensityModules != optionalDensityModules
    }
    val presetOptions = remember(
        allModules,
        requiredModules,
        recommendedModules
    ) {
        buildList {
            add(
                SplitMergePresetOption(
                    "all",
                    R.string.merge_split_apk_selection_preset_all,
                    allModules
                )
            )
            add(
                SplitMergePresetOption(
                    "none",
                    R.string.merge_split_apk_selection_preset_none,
                    requiredModules
                )
            )
            add(
                SplitMergePresetOption(
                    "recommended",
                    R.string.merge_split_apk_selection_preset_recommended,
                    recommendedModules
                )
            )
        }
    }

    fun matchingPresetKeys(modules: Set<String>): Set<String> =
        presetOptions.asSequence()
            .filter { preset -> preset.modules == modules }
            .map { preset -> preset.key }
            .toSet()

    fun inferPresetKey(modules: Set<String>): String? {
        val matchingKeys = matchingPresetKeys(modules)
        return when {
            modules == allModules && matchingKeys.contains("all") -> "all"
            modules == requiredModules && matchingKeys.contains("none") -> "none"
            matchingKeys.size == 1 -> matchingKeys.first()
            else -> null
        }
    }

    val rememberedInitialPresetKey = remember(initialPresetKey) {
        initialPresetKey.takeIf { it == "all" || it == "none" || it == "recommended" } ?: "all"
    }
    var selectedPresetKey by remember(selection, rememberedInitialPresetKey) {
        mutableStateOf<String?>(rememberedInitialPresetKey)
    }
    val selectedModuleCount by remember(selectedModules, requiredModules) {
        derivedStateOf { (selectedModules + requiredModules).size }
    }
    fun isLanguageCleanupSelected(modules: Set<String>): Boolean =
        languageCleanupAvailable && ((modules + requiredModules) intersect optionalLanguageModules) ==
            trimmedOptionalLanguageModules

    fun isDensityCleanupSelected(modules: Set<String>): Boolean =
        densityCleanupAvailable && ((modules + requiredModules) intersect optionalDensityModules) ==
            trimmedOptionalDensityModules

    val languageCleanupSelected by remember(
        selectedModules,
        requiredModules,
        optionalLanguageModules,
        trimmedOptionalLanguageModules,
        languageCleanupAvailable
    ) {
        derivedStateOf { isLanguageCleanupSelected(selectedModules) }
    }
    val densityCleanupSelected by remember(
        selectedModules,
        requiredModules,
        optionalDensityModules,
        trimmedOptionalDensityModules,
        densityCleanupAvailable
    ) {
        derivedStateOf { isDensityCleanupSelected(selectedModules) }
    }

    fun updateSelection(
        modules: Set<String>,
        stripUnusedNativeLibs: Boolean,
        preferredPresetKey: String? = null,
        inferPresetFromModules: Boolean = false
    ): Set<String> {
        val abiAdjustedModules = if (stripUnusedNativeLibs) {
            (modules - optionalAbiModules) + trimmedOptionalAbiModules
        } else {
            modules
        }
        val normalizedModules = (abiAdjustedModules + requiredModules)
            .takeIf { it.isNotEmpty() }
            ?: requiredModules.ifEmpty { allModules }
        selectedModules = normalizedModules
        stripNativeLibs = stripUnusedNativeLibs
        selectedPresetKey = when {
            preferredPresetKey != null -> preferredPresetKey
            inferPresetFromModules -> inferPresetKey(normalizedModules)
            else -> selectedPresetKey
        }
        return normalizedModules
    }

    fun rememberCurrentFilterSelection(
        modules: Set<String>,
        stripUnusedNativeLibs: Boolean,
        presetKey: String = selectedPresetKey ?: "all"
    ) {
        onFilterSelectionChanged(
            presetKey,
            isLanguageCleanupSelected(modules),
            isDensityCleanupSelected(modules),
            stripUnusedNativeLibs
        )
    }

    FullscreenDialog(onDismissRequest = onDismissRequest) {
        AppScaffold(
            topBar = { scrollBehavior ->
                AppTopBar(
                    title = stringResource(R.string.merge_split_apk_selection_title),
                    scrollBehavior = scrollBehavior,
                    onBackClick = onDismissRequest
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.merge_split_apk_selection_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presetOptions.forEach { preset ->
                            CheckedFilterChip(
                                selected = selectedPresetKey == preset.key,
                                onClick = {
                                    val normalizedModules = updateSelection(
                                        modules = preset.modules,
                                        stripUnusedNativeLibs = false,
                                        preferredPresetKey = preset.key
                                    )
                                    rememberCurrentFilterSelection(
                                        modules = normalizedModules,
                                        stripUnusedNativeLibs = false,
                                        presetKey = preset.key
                                    )
                                },
                                colors = chipColors,
                                label = { Text(stringResource(preset.labelRes)) }
                            )
                        }
                        if (languageCleanupAvailable) {
                            CheckedFilterChip(
                                selected = languageCleanupSelected,
                                onClick = {
                                    val toggledLanguageCleanup = !languageCleanupSelected
                                    val nextModules = if (toggledLanguageCleanup) {
                                        (selectedModules - optionalLanguageModules) + trimmedOptionalLanguageModules
                                    } else {
                                        selectedModules + optionalLanguageModules
                                    }
                                    val normalizedModules = updateSelection(nextModules, stripNativeLibs)
                                    rememberCurrentFilterSelection(
                                        modules = normalizedModules,
                                        stripUnusedNativeLibs = stripNativeLibs
                                    )
                                },
                                colors = chipColors,
                                label = {
                                    Text(stringResource(R.string.merge_split_apk_selection_preset_languages))
                                }
                            )
                        }
                        if (densityCleanupAvailable) {
                            CheckedFilterChip(
                                selected = densityCleanupSelected,
                                onClick = {
                                    val toggledDensityCleanup = !densityCleanupSelected
                                    val nextModules = if (toggledDensityCleanup) {
                                        (selectedModules - optionalDensityModules) + trimmedOptionalDensityModules
                                    } else {
                                        selectedModules + optionalDensityModules
                                    }
                                    val normalizedModules = updateSelection(nextModules, stripNativeLibs)
                                    rememberCurrentFilterSelection(
                                        modules = normalizedModules,
                                        stripUnusedNativeLibs = stripNativeLibs
                                    )
                                },
                                colors = chipColors,
                                label = {
                                    Text(stringResource(R.string.merge_split_apk_selection_preset_densities))
                                }
                            )
                        }
                        CheckedFilterChip(
                            selected = stripNativeLibs,
                            onClick = {
                                val toggledStripNativeLibs = !stripNativeLibs
                                val nextModules = if (toggledStripNativeLibs) {
                                    selectedModules
                                } else {
                                    selectedModules + optionalAbiModules
                                }
                                val normalizedModules = updateSelection(
                                    modules = nextModules,
                                    stripUnusedNativeLibs = toggledStripNativeLibs
                                )
                                rememberCurrentFilterSelection(
                                    modules = normalizedModules,
                                    stripUnusedNativeLibs = toggledStripNativeLibs
                                )
                            },
                            colors = chipColors,
                            label = {
                                Text(stringResource(R.string.merge_split_apk_selection_strip_native_libs_title))
                            }
                        )
                    }
                    selection.modules.forEach { module ->
                        val required = requiredModules.contains(module.name)
                        val forcedOffByNativeStrip =
                            stripNativeLibs &&
                                module.kind == SplitApkPreparer.SplitArchiveModuleKind.ABI &&
                                module.name in optionalAbiModules &&
                                module.name !in trimmedOptionalAbiModules
                        SplitMergeModuleRow(
                            module = module,
                            checked = required || selectedModules.contains(module.name),
                            enabled = !required && !forcedOffByNativeStrip,
                            onCheckedChange = { checked ->
                                val updatedModules = selectedModules.toMutableSet().apply {
                                    if (checked) add(module.name) else remove(module.name)
                                }
                                val normalizedModules = updateSelection(
                                    modules = updatedModules,
                                    stripUnusedNativeLibs = stripNativeLibs,
                                    inferPresetFromModules = true
                                )
                                rememberCurrentFilterSelection(
                                    modules = normalizedModules,
                                    stripUnusedNativeLibs = stripNativeLibs
                                )
                            }
                        )
                    }
                }
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TextButton(onClick = onDismissRequest) {
                            Text(stringResource(R.string.cancel))
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        HapticExtendedFloatingActionButton(
                            text = {
                                Text(
                                    stringResource(
                                        R.string.merge_split_apk_selection_confirm_with_count,
                                        selectedModuleCount
                                    )
                                )
                            },
                            icon = { Icon(Icons.Default.AutoFixHigh, null) },
                            onClick = { onConfirm(selectedModules + requiredModules, stripNativeLibs) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SplitMergeModuleRow(
    module: SplitApkPreparer.SplitArchiveModule,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        onClick = { if (enabled) onCheckedChange(!checked) },
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = if (enabled) onCheckedChange else null,
                    enabled = enabled
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val kindLabel = stringResource(module.kind.labelRes())
                Text(text = module.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = module.detail?.let { "$it • $kindLabel" } ?: kindLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@StringRes
private fun SplitApkPreparer.SplitArchiveModuleKind.labelRes(): Int = when (this) {
    SplitApkPreparer.SplitArchiveModuleKind.BASE -> R.string.merge_split_apk_module_kind_base
    SplitApkPreparer.SplitArchiveModuleKind.LANGUAGE -> R.string.merge_split_apk_module_kind_language
    SplitApkPreparer.SplitArchiveModuleKind.DENSITY -> R.string.merge_split_apk_module_kind_density
    SplitApkPreparer.SplitArchiveModuleKind.ABI -> R.string.merge_split_apk_module_kind_abi
    SplitApkPreparer.SplitArchiveModuleKind.FEATURE -> R.string.merge_split_apk_module_kind_feature
    SplitApkPreparer.SplitArchiveModuleKind.OTHER -> R.string.merge_split_apk_module_kind_other
}

private fun defaultMergedOutputName(sourceName: String?): String {
    val fileName = sourceName
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.takeIf { it.isNotBlank() }
        ?: "split.apks"
    val base = fileName.substringBeforeLast('.', fileName)
    return if (base.lowercase().endsWith("-merged")) "$base.apk" else "$base-merged.apk"
}

private fun preferredMergedOutputName(outputName: String?, inputName: String?): String {
    val explicitName = outputName
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.takeIf { it.isNotBlank() }
    return explicitName ?: defaultMergedOutputName(inputName)
}

private fun SplitMergeStepStatus.toUiState(): State = when (this) {
    SplitMergeStepStatus.WAITING -> State.WAITING
    SplitMergeStepStatus.RUNNING -> State.RUNNING
    SplitMergeStepStatus.COMPLETED -> State.COMPLETED
    SplitMergeStepStatus.FAILED -> State.FAILED
}
