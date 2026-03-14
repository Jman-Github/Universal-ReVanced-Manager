package app.revanced.manager.ui.screen

import android.net.Uri
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import app.revanced.manager.data.platform.Filesystem
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.patcher.StepId
import app.revanced.manager.patcher.split.SplitApkPreparer
import app.revanced.manager.ui.component.AppScaffold
import app.revanced.manager.ui.component.AppTopBar
import app.revanced.manager.ui.component.CheckedFilterChip
import app.revanced.manager.ui.component.ConfirmDialog
import app.revanced.manager.ui.component.ExportSavedApkFileNameDialog
import app.revanced.manager.ui.component.FullscreenDialog
import app.revanced.manager.ui.component.InterceptBackHandler
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
    val autoExpandRunningStepsExclusive by prefs.autoExpandRunningStepsExclusive.getAsState()
    val useExclusiveAutoExpand = autoExpandRunningSteps && autoExpandRunningStepsExclusive
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
            outputDisplayName = preferredMergedOutputName(state.outputName, state.inputName)
        )
    }

    val canSaveNow = state.canSaveAgain &&
        !state.inProgress &&
        state.saveStep.status != SplitMergeStepStatus.RUNNING

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

    LaunchedEffect(useCustomFilePicker) {
        if (!useCustomFilePicker) {
            showOutputPicker = false
            outputFileDialogState = null
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

    InterceptBackHandler(
        enabled = state.inProgress || state.selection != null || state.cancellationInProgress,
        onBack = ::onPageBack
    )

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
            onDismissRequest = {
                vm.clearSplitMergeState()
                onBackClick()
            },
            onConfirm = { includedModules, stripNativeLibs ->
                vm.confirmSplitMergeSelection(
                    includedModules = includedModules,
                    stripNativeLibs = stripNativeLibs
                )
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

    var currentSubStepIndex by rememberSaveable { mutableIntStateOf(-1) }
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
            AnimatedVisibility(visible = canSaveNow) {
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
                    autoExpandRunningMainOnly = useExclusiveAutoExpand,
                    autoCollapseCompleted = autoCollapsePatcherSteps,
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
    onDismissRequest: () -> Unit,
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
    val abiPresetModules = remember(selection, allModules, abiModules) {
        (allModules - abiModules) +
            abiModules.filterTo(linkedSetOf()) { it in selection.abiTrimmedModules }
    }
    val recommendedModules = remember(selection, allModules, requiredModules) {
        (selection.recommendedModules + requiredModules)
            .ifEmpty { requiredModules.ifEmpty { allModules } }
    }
    val languagePresetModules = remember(selection, allModules, languageModules) {
        (allModules - languageModules) +
            languageModules.filterTo(linkedSetOf()) { it in selection.languageTrimmedModules }
    }
    val densityPresetModules = remember(selection, allModules, densityModules) {
        (allModules - densityModules) +
            densityModules.filterTo(linkedSetOf()) { it in selection.densityTrimmedModules }
    }
    val presetOptions = remember(
        selection,
        allModules,
        requiredModules,
        recommendedModules,
        languagePresetModules,
        densityPresetModules
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
            if (languagePresetModules != allModules) {
                add(
                    SplitMergePresetOption(
                        "languages",
                        R.string.merge_split_apk_selection_preset_languages,
                        languagePresetModules
                    )
                )
            }
            if (densityPresetModules != allModules) {
                add(
                    SplitMergePresetOption(
                        "density",
                        R.string.merge_split_apk_selection_preset_densities,
                        densityPresetModules
                    )
                )
            }
        }
    }

    fun matchingPresetKeys(modules: Set<String>): Set<String> =
        presetOptions.asSequence()
            .filter { preset -> preset.modules == modules }
            .map { preset -> preset.key }
            .toSet()

    val initialPresetKey = remember(presetOptions, effectiveInitialModules, requiredModules, initialStripNativeLibs) {
        if (initialStripNativeLibs) {
            null
        } else {
            val matchingKeys = matchingPresetKeys(effectiveInitialModules)
            when {
                effectiveInitialModules == allModules && matchingKeys.contains("all") -> "all"
                effectiveInitialModules == requiredModules && matchingKeys.contains("none") -> "none"
                matchingKeys.size == 1 -> matchingKeys.first()
                else -> null
            }
        }
    }
    var selectedPresetKey by remember(selection, initialPresetKey) { mutableStateOf<String?>(initialPresetKey) }

    fun updateSelection(
        modules: Set<String>,
        stripUnusedNativeLibs: Boolean,
        preferredPresetKey: String? = null
    ) {
        val abiAdjustedModules = if (stripUnusedNativeLibs) {
            (modules - abiModules) + (abiPresetModules intersect abiModules)
        } else {
            modules
        }
        val normalizedModules = (abiAdjustedModules + requiredModules)
            .takeIf { it.isNotEmpty() }
            ?: requiredModules.ifEmpty { allModules }
        val matchingKeys = matchingPresetKeys(normalizedModules)
        val currentPresetKey = selectedPresetKey
        selectedModules = normalizedModules
        stripNativeLibs = stripUnusedNativeLibs
        selectedPresetKey = when {
            stripUnusedNativeLibs -> null
            preferredPresetKey != null -> preferredPresetKey
            currentPresetKey != null && matchingKeys.contains(currentPresetKey) -> currentPresetKey
            matchingKeys.size == 1 -> matchingKeys.first()
            else -> null
        }
    }

    FullscreenDialog(onDismissRequest = onDismissRequest) {
        AppScaffold(
            topBar = { scrollBehavior ->
                AppTopBar(
                    title = stringResource(R.string.merge_split_apk_selection_title),
                    scrollBehavior = scrollBehavior,
                    onBackClick = onDismissRequest,
                    actions = {
                        TextButton(onClick = onDismissRequest) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
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
                                    updateSelection(
                                        modules = preset.modules,
                                        stripUnusedNativeLibs = false,
                                        preferredPresetKey = preset.key
                                    )
                                },
                                colors = chipColors,
                                label = { Text(stringResource(preset.labelRes)) }
                            )
                        }
                        CheckedFilterChip(
                            selected = stripNativeLibs,
                            onClick = {
                                updateSelection(
                                    modules = selectedModules,
                                    stripUnusedNativeLibs = !stripNativeLibs
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
                                module.name !in selection.abiTrimmedModules
                        SplitMergeModuleRow(
                            module = module,
                            checked = required || selectedModules.contains(module.name),
                            enabled = !required && !forcedOffByNativeStrip,
                            onCheckedChange = { checked ->
                                val updatedModules = selectedModules.toMutableSet().apply {
                                    if (checked) add(module.name) else remove(module.name)
                                }
                                updateSelection(updatedModules, stripNativeLibs)
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
                        TextButton(onClick = { onConfirm(selectedModules + requiredModules, stripNativeLibs) }) {
                            Text(stringResource(R.string.merge_split_apk_selection_confirm))
                        }
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
