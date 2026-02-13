package app.revanced.manager.ui.screen

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.revanced.manager.data.platform.Filesystem
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.patcher.StepId
import app.revanced.manager.ui.component.AppScaffold
import app.revanced.manager.ui.component.AppTopBar
import app.revanced.manager.ui.component.ConfirmDialog
import app.revanced.manager.ui.component.ExportSavedApkFileNameDialog
import app.revanced.manager.ui.component.haptics.HapticExtendedFloatingActionButton
import app.revanced.manager.ui.component.patcher.Steps
import app.revanced.manager.ui.component.patches.PathSelectorDialog
import app.revanced.manager.ui.model.State
import app.revanced.manager.ui.model.Step
import app.revanced.manager.ui.model.StepCategory
import app.revanced.manager.ui.model.StepDetail
import app.revanced.manager.ui.viewmodel.DashboardViewModel
import app.revanced.manager.ui.viewmodel.SplitMergeState
import app.revanced.manager.ui.viewmodel.SplitMergeStepStatus
import app.revanced.manager.util.mutableStateSetOf
import app.revanced.manager.util.saver.snapshotStateSetSaver
import app.universal.revanced.manager.R
import java.nio.file.Files
import java.nio.file.Path
import org.koin.compose.koinInject

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
    val autoCollapsePatcherSteps by prefs.autoCollapsePatcherSteps.getAsState()
    val autoExpandRunningSteps by prefs.autoExpandRunningSteps.getAsState()
    val storageRoots = remember { fs.storageRoots() }
    val (permissionContract, permissionName) = remember { fs.permissionContract() }

    var showOutputPicker by rememberSaveable { mutableStateOf(false) }
    var outputFileDialogState by remember { mutableStateOf<OutputFileDialogState?>(null) }
    var showDismissConfirmationDialog by rememberSaveable { mutableStateOf(false) }
    var pendingPermissionRequest by rememberSaveable {
        mutableStateOf<PermissionRequest?>(null)
    }

    val permissionLauncher = rememberLauncherForActivityResult(permissionContract) { granted ->
        if (granted && pendingPermissionRequest == PermissionRequest.OUTPUT) {
            showOutputPicker = true
        }
        pendingPermissionRequest = null
    }

    val outputDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        vm.saveLastMergedToUri(
            outputUri = uri,
            outputDisplayName = state.outputName ?: defaultMergedOutputName(state.inputName)
        )
    }

    fun requestSave() {
        if (!state.canSaveAgain) return
        val defaultName = defaultMergedOutputName(state.outputName ?: state.inputName)
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

    LaunchedEffect(useCustomFilePicker) {
        if (!useCustomFilePicker) {
            showOutputPicker = false
            outputFileDialogState = null
            pendingPermissionRequest = null
        }
    }

    fun onPageBack() {
        if (state.inProgress) {
            showDismissConfirmationDialog = true
        } else {
            onBackClick()
        }
    }

    BackHandler(onBack = ::onPageBack)

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
                    fileName = defaultMergedOutputName(state.outputName ?: state.inputName)
                )
            }
        )
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

    val stepsByCategory by remember(state) {
        derivedStateOf {
            linkedMapOf(
                StepCategory.PREPARING to listOf(
                    Step(
                        id = StepId.PrepareSplitApk,
                        title = context.getString(R.string.merge_split_apk_step_merge),
                        category = StepCategory.PREPARING,
                        state = state.mergeStep.status.toUiState(),
                        message = state.mergeStep.message
                    )
                ),
                StepCategory.SAVING to listOf(
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

    val subStepsById by remember(state) {
        derivedStateOf {
            val entries = parseMergeSubSteps(state)
            val currentSubStepIndex = findCurrentSubStepIndex(entries, state.currentMessage)
            mapOf<StepId, List<StepDetail>>(
                StepId.PrepareSplitApk to entries.mapIndexed { index, entry ->
                    StepDetail(
                        title = entry.title,
                        state = resolveSubStepState(
                            index = index,
                            skipped = entry.skipped,
                            currentIndex = currentSubStepIndex,
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
        mutableStateSetOf<StepCategory>().apply {
            addAll(stepsByCategory.keys)
        }
    }

    AppScaffold(
        topBar = { scrollBehavior ->
            AppTopBar(
                title = stringResource(R.string.tools_merge_split_title),
                scrollBehavior = scrollBehavior,
                onBackClick = ::onPageBack
            )
        },
        floatingActionButton = {
            AnimatedVisibility(visible = state.canSaveAgain && !state.inProgress) {
                HapticExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.save)) },
                    icon = { Icon(Icons.Outlined.Save, null) },
                    onClick = ::requestSave
                )
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(stepsByCategory.toList(), key = { it.first }) { (category, steps) ->
                Steps(
                    category = category,
                    steps = steps,
                    subStepsById = subStepsById,
                    isExpanded = expandedCategories.contains(category),
                    autoExpandRunning = autoExpandRunningSteps,
                    autoCollapseCompleted = autoCollapsePatcherSteps,
                    onExpand = { expandedCategories.add(category) },
                    onClick = {
                        if (expandedCategories.contains(category)) {
                            expandedCategories.remove(category)
                        } else {
                            expandedCategories.add(category)
                        }
                    }
                )
            }
        }
    }
}

private data class OutputFileDialogState(
    val directory: Path,
    val fileName: String
)

private enum class PermissionRequest {
    OUTPUT
}

private data class MergeSubStep(
    val title: String,
    val skipped: Boolean
)

private fun parseMergeSubSteps(state: SplitMergeState): List<MergeSubStep> =
    state.mergeSubSteps.map { raw ->
        val skipped = raw.startsWith("[skipped]")
        MergeSubStep(
            title = raw.removePrefix("[skipped]").trim(),
            skipped = skipped
        )
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

private fun defaultMergedOutputName(sourceName: String?): String {
    val fileName = sourceName
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.takeIf { it.isNotBlank() }
        ?: "split.apks"
    val base = fileName.substringBeforeLast('.', fileName)
    return if (base.lowercase().endsWith("-merged")) "$base.apk" else "$base-merged.apk"
}

private fun SplitMergeStepStatus.toUiState(): State = when (this) {
    SplitMergeStepStatus.WAITING -> State.WAITING
    SplitMergeStepStatus.RUNNING -> State.RUNNING
    SplitMergeStepStatus.COMPLETED -> State.COMPLETED
    SplitMergeStepStatus.FAILED -> State.FAILED
}
