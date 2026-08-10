/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.urv.manager.ui.screen

import android.content.Context
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.PostAdd
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.universal.revanced.manager.R
import app.urv.manager.domain.batch.BatchInstallOutcome
import app.urv.manager.domain.batch.BatchItemState
import app.urv.manager.domain.batch.BatchPhase
import app.urv.manager.domain.manager.PreferencesManager
import app.urv.manager.patcher.ProgressEvent
import app.urv.manager.patcher.StepId
import app.urv.manager.ui.component.AppScaffold
import app.urv.manager.ui.component.AppTopBar
import app.urv.manager.ui.component.TransparentLoadingDialog
import app.urv.manager.ui.component.haptics.HapticExtendedFloatingActionButton
import app.urv.manager.ui.component.patcher.LegacyAndroidMemoryWarning
import app.urv.manager.ui.component.patcher.PatcherInformation
import app.urv.manager.ui.component.patcher.PatcherInformationCard
import app.urv.manager.ui.component.patcher.PatcherMemoryUsageCard
import app.urv.manager.ui.component.patcher.Steps
import app.urv.manager.ui.model.SelectedApp
import app.urv.manager.patcher.parsePatcherSessionInfo
import app.urv.manager.patcher.withFallback
import app.urv.manager.ui.model.State
import app.urv.manager.ui.model.Step
import app.urv.manager.ui.model.StepCategory
import app.urv.manager.ui.model.StepDetail
import app.urv.manager.ui.model.withState
import app.urv.manager.ui.viewmodel.BatchPatcherViewModel
import app.urv.manager.ui.viewmodel.PatcherViewModel
import app.urv.manager.util.PatchSelection
import app.urv.manager.util.mutableStateSetOf
import app.urv.manager.util.saver.snapshotStateSetSaver
import app.urv.manager.patcher.split.SplitApkPreparer
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

// Code adapted from Morphe, see third-party/NOTICE for more information
// https://github.com/MorpheApp/morphe-manager/pull/795
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchPatchDetailsScreen(
    packageName: String,
    onBackClick: () -> Unit,
    viewModel: BatchPatcherViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val prefs: PreferencesManager = koinInject()
    val autoCollapsePatcherSteps by prefs.autoCollapsePatcherSteps.getAsState()
    val showPatcherMemoryUsageGraph by prefs.showPatcherMemoryUsageGraph.getAsState()
    val patcherInformationExpanded by prefs.patcherInformationExpanded.getAsState()
    val autoExpandRunningSteps by prefs.autoExpandRunningSteps.getAsState()
    val autoExpandRunningStepsExclusive by prefs.autoExpandRunningStepsExclusive.getAsState()
    val continueOnPatchError by prefs.continueOnPatchError.getAsState()
    val skipApkSigning by prefs.skipApkSigning.getAsState()
    val coroutineScope = rememberCoroutineScope()
    val useExclusiveAutoExpand =
        autoExpandRunningSteps && autoExpandRunningStepsExclusive
    val item = state?.items?.firstOrNull { it.packageName == packageName }
    val awaitingProgress = item?.let {
        it.state == BatchItemState.RUNNING &&
            (it.input == null ||
                (it.progressEvents.isEmpty() && it.memoryUsageSamples.isEmpty()))
    } == true
    val showLoadingOverlay = state == null ||
        state?.phase == BatchPhase.PLANNING ||
        state?.phase == BatchPhase.CANCELLING ||
        awaitingProgress
    val resultActions = rememberBatchResultActions(item, viewModel)
    val canExport = item?.let {
        it.hasAvailablePatchedFile && !it.saving && !it.installing
    } == true
    val canUseLogs = item?.let {
        it.state == BatchItemState.SUCCEEDED || it.state == BatchItemState.FAILED
    } == true
    val canInstallOrOpen = item?.let {
        !it.saving &&
            !it.installing &&
            (it.installOutcome == BatchInstallOutcome.INSTALLED || it.hasAvailablePatchedFile)
    } == true
    AppScaffold(
        topBar = { scrollBehavior ->
            AppTopBar(
                title = item?.appName
                    ?: stringResource(R.string.batch_patch_progress_title),
                onBackClick = onBackClick,
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = {
            if (item != null) {
                BottomAppBar(
                    actions = {
                        IconButton(
                            onClick = resultActions.exportApk,
                            enabled = canExport
                        ) {
                            Icon(
                                Icons.Outlined.Save,
                                stringResource(R.string.save_apk)
                            )
                        }
                        IconButton(
                            onClick = resultActions.showLogs,
                            enabled = canUseLogs
                        ) {
                            Icon(
                                Icons.Outlined.PostAdd,
                                stringResource(R.string.save_logs)
                            )
                        }
                        IconButton(onClick = onBackClick) {
                            Icon(
                                Icons.Outlined.Check,
                                stringResource(R.string.done)
                            )
                        }
                    },
                    floatingActionButton = {
                        AnimatedVisibility(visible = canInstallOrOpen) {
                            HapticExtendedFloatingActionButton(
                                text = {
                                    Text(
                                        stringResource(
                                            if (
                                                item.installOutcome ==
                                                BatchInstallOutcome.INSTALLED
                                            ) {
                                                R.string.open_app
                                            } else {
                                                R.string.install_app
                                            }
                                        )
                                    )
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (
                                            item.installOutcome ==
                                            BatchInstallOutcome.INSTALLED
                                        ) {
                                            Icons.AutoMirrored.Outlined.OpenInNew
                                        } else {
                                            Icons.Outlined.FileDownload
                                        },
                                        contentDescription = stringResource(
                                            if (
                                                item.installOutcome ==
                                                BatchInstallOutcome.INSTALLED
                                            ) {
                                                R.string.open_app
                                            } else {
                                                R.string.install_app
                                            }
                                        )
                                    )
                                },
                                onClick = resultActions.installOrOpen,
                                shape = RoundedCornerShape(16.dp)
                            )
                        }
                    }
                )
            }
        }
    ) { padding ->
        if (showLoadingOverlay) return@AppScaffold

        if (item == null) {
            BatchDetailsMessage(
                text = stringResource(R.string.batch_patch_details_unavailable),
                modifier = Modifier.padding(padding)
            )
            return@AppScaffold
        }

        val input = item.input
        val context = LocalContext.current
        val progressUi = remember(
            input,
            item.selection,
            item.progressEvents,
            item.memoryUsageSamples,
            item.state,
            skipApkSigning
        ) {
            input?.takeIf {
                item.progressEvents.isNotEmpty() || item.memoryUsageSamples.isNotEmpty()
            }?.let { selectedApp ->
                buildBatchProgressUiState(
                    context = context,
                    selectedApp = selectedApp,
                    selectedPatches = item.selection,
                    splitStepActive = item.progressEvents.any {
                        it.stepId == StepId.PrepareSplitApk
                    },
                    skipApkSigning = skipApkSigning,
                    events = item.progressEvents,
                    cancelled = item.state == BatchItemState.CANCELLED,
                    cancelledMessage = context.getString(R.string.batch_patch_state_cancelled)
                )
            }
        }
        val groupedSteps = remember(progressUi?.steps) {
            progressUi?.steps?.groupBy { it.category }.orEmpty()
        }
        val expandedCategories = rememberSaveable(
            saver = snapshotStateSetSaver()
        ) {
            mutableStateSetOf<StepCategory>()
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LinearProgressIndicator(
                progress = {
                    progressUi?.progress ?: if (item.state.isTerminal) 1f else 0f
                },
                modifier = Modifier.fillMaxWidth(),
                drawStopIndicator = {}
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                item(key = "app-info") {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(item.appName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                item.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            item.message?.let {
                                Text(it, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                if (
                    showPatcherMemoryUsageGraph &&
                    item.memoryUsageSamples.isNotEmpty()
                ) {
                    item(key = "memory-usage") {
                        PatcherMemoryUsageCard(
                            samples = item.memoryUsageSamples,
                            isActive = item.state == BatchItemState.RUNNING
                        )
                    }
                }

                item(key = "patcher-information") {
                    val parsedSessionInfo = remember(item.logLines) {
                        parsePatcherSessionInfo(
                            item.logLines.map { line -> line.substringAfter("]: ", line) }
                        )
                    }
                    val sessionInfo = item.patcherSessionInfo.withFallback(parsedSessionInfo)
                    val activeBundleIds = remember(item.selection) {
                        item.selection.filterValues { patches -> patches.isNotEmpty() }.keys
                    }
                    val patchBundleLabels = remember(item.bundles, activeBundleIds) {
                        item.bundles
                            .filter { bundle -> bundle.uid in activeBundleIds }
                            .map { bundle ->
                                bundle.version
                                    ?.takeIf(String::isNotBlank)
                                    ?.let { version -> "${bundle.name} $version" }
                                    ?: bundle.name
                            }
                    }
                    val localInput = item.input as? SelectedApp.Local
                    val fallbackSplitApk by produceState<Boolean?>(
                        initialValue = null,
                        key1 = localInput?.file
                    ) {
                        value = localInput?.file?.let { file ->
                            withContext(Dispatchers.IO) {
                                SplitApkPreparer.isSplitArchive(file)
                            }
                        }
                    }
                    PatcherInformationCard(
                        information = PatcherInformation(
                            appVersion = item.version,
                            appVersionCode = item.versionCode,
                            patchCount = item.patchCount,
                            patchBundles = patchBundleLabels,
                            fallbackApkSizeBytes = localInput?.file
                                ?.takeIf { it.isFile }
                                ?.length(),
                            fallbackSplitApk = fallbackSplitApk,
                            fallbackPatcherEngine = item.patcherEngine,
                            session = sessionInfo
                        ),
                        expanded = patcherInformationExpanded,
                        onExpandedChange = { expanded ->
                            coroutineScope.launch {
                                prefs.patcherInformationExpanded.update(expanded)
                            }
                        }
                    )
                }

                items(
                    items = groupedSteps.toList(),
                    key = { (category, _) -> category }
                ) { (category, steps) ->
                    Steps(
                        category = category,
                        steps = steps,
                        subStepsById = progressUi?.subStepsById.orEmpty(),
                        isExpanded = expandedCategories.contains(category),
                        autoExpandRunning = autoExpandRunningSteps,
                        autoExpandRunningMainOnly = useExclusiveAutoExpand,
                        continueOnPatchError = continueOnPatchError,
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
                        },
                        autoCollapseCompleted = autoCollapsePatcherSteps
                    )
                }

                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                    item(key = "legacy-memory-warning") {
                        LegacyAndroidMemoryWarning()
                    }
                }
            }
        }
    }

    if (showLoadingOverlay) {
        TransparentLoadingDialog()
    }
}

@Composable
private fun BatchDetailsMessage(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private data class BatchProgressUiState(
    val steps: List<Step>,
    val subStepsById: Map<StepId, List<StepDetail>>,
    val progress: Float
)

private data class BatchProgressUnits(
    val completed: Double,
    val total: Int
)

private fun buildBatchProgressUiState(
    context: Context,
    selectedApp: SelectedApp,
    selectedPatches: PatchSelection,
    splitStepActive: Boolean,
    skipApkSigning: Boolean,
    events: List<ProgressEvent>,
    cancelled: Boolean,
    cancelledMessage: String
): BatchProgressUiState {
    val steps = PatcherViewModel.generateSteps(
        context = context,
        selectedApp = selectedApp,
        selectedPatches = selectedPatches,
        splitStepActive = splitStepActive,
        skipApkSigning = skipApkSigning
    ).toMutableList()
    val subStepsById = mutableMapOf<StepId, List<StepDetail>>()
    var visualProgress = 0f

    events.forEach { event ->
        val eventStepId = event.stepId
        if (eventStepId != null && isExpandableBatchStep(eventStepId)) {
            when (event) {
                is ProgressEvent.Started -> {
                    if (event.subSteps.isNullOrEmpty()) {
                        subStepsById.remove(eventStepId)
                    } else {
                        subStepsById[eventStepId] = syncBatchSubSteps(
                            stepId = eventStepId,
                            titles = event.subSteps,
                            existing = subStepsById[eventStepId]
                        )
                    }
                }

                is ProgressEvent.Progress -> {
                    event.subSteps?.let { titles ->
                        subStepsById[eventStepId] = syncBatchSubSteps(
                            stepId = eventStepId,
                            titles = titles,
                            existing = subStepsById[eventStepId]
                        )
                    }
                    val progress = event.current?.let { it to event.total }
                    if (!event.message.isNullOrBlank() || progress != null) {
                        subStepsById[eventStepId] = updateBatchSubStep(
                            stepId = eventStepId,
                            existing = subStepsById[eventStepId].orEmpty(),
                            message = event.message,
                            progress = progress
                        )
                    }
                }

                is ProgressEvent.Completed -> {
                    subStepsById[eventStepId] = finalizeBatchSubSteps(
                        existing = subStepsById[eventStepId].orEmpty(),
                        failed = false
                    )
                }

                is ProgressEvent.Failed -> {
                    subStepsById[eventStepId] = finalizeBatchSubSteps(
                        existing = subStepsById[eventStepId].orEmpty(),
                        failed = true,
                        errorMessage = event.error.message ?: event.error.type
                    )
                }
            }
        }

        val stepIndex = steps.indexOfFirst { step ->
            eventStepId?.let { id -> id == step.id }
                ?: (step.state == State.RUNNING || step.state == State.WAITING)
        }
        if (stepIndex != -1) {
            val step = steps[stepIndex]
            val updatedStep = when (event) {
                is ProgressEvent.Started -> {
                    if (step.state == State.COMPLETED || step.state == State.FAILED) {
                        null
                    } else {
                        step.withState(State.RUNNING)
                    }
                }

                is ProgressEvent.Progress -> {
                    if (step.state == State.COMPLETED || step.state == State.FAILED) {
                        null
                    } else {
                        step.withState(
                            state = if (step.state == State.WAITING) {
                                State.RUNNING
                            } else {
                                step.state
                            },
                            message = if (eventStepId == StepId.LoadPatches) {
                                null
                            } else {
                                event.message ?: step.message
                            },
                            progress = event.current?.let {
                                event.current to event.total
                            } ?: step.progress
                        )
                    }
                }

                is ProgressEvent.Completed -> {
                    if (step.state == State.FAILED) {
                        null
                    } else {
                        step.withState(State.COMPLETED, progress = null)
                    }
                }

                is ProgressEvent.Failed -> {
                    if (event.stepId == null && steps.any { it.state == State.FAILED }) {
                        null
                    } else {
                        step.withState(
                            state = State.FAILED,
                            message = formatBatchFailure(event),
                            progress = null
                        )
                    }
                }
            }

            if (updatedStep != null) {
                steps[stepIndex] = updatedStep
                if (event is ProgressEvent.Completed && updatedStep.state == State.COMPLETED) {
                    promoteImmediateBatchSignStep(steps, stepIndex)
                    promoteNextBatchSectionStep(steps, stepIndex)
                }
            }
        }

        visualProgress = maxOf(
            visualProgress,
            calculateBatchProgress(steps, subStepsById)
        )
    }

    if (cancelled) {
        val runningIndex = steps.indexOfFirst { it.state == State.RUNNING }
        if (runningIndex != -1) {
            steps[runningIndex] = steps[runningIndex].withState(
                state = State.FAILED,
                message = cancelledMessage,
                progress = null
            )
        }
    }

    return BatchProgressUiState(
        steps = steps,
        subStepsById = subStepsById,
        progress = visualProgress
    )
}

private fun isExpandableBatchStep(stepId: StepId): Boolean = when (stepId) {
    StepId.PrepareSplitApk,
    StepId.WriteAPK -> true
    else -> false
}

private fun syncBatchSubSteps(
    stepId: StepId,
    titles: List<String>,
    existing: List<StepDetail>?
): List<StepDetail> = if (stepId == StepId.WriteAPK) {
    syncBatchWriteApkSubSteps(titles, existing)
} else {
    syncGenericBatchSubSteps(titles, existing)
}

private fun syncGenericBatchSubSteps(
    titles: List<String>,
    existing: List<StepDetail>?
): List<StepDetail> = titles
    .asSequence()
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinctBy { it.lowercase() }
    .map { rawTitle ->
        val skipped = rawTitle.startsWith(SKIPPED_SUBSTEP_PREFIX)
        val title = if (skipped) {
            rawTitle.removePrefix(SKIPPED_SUBSTEP_PREFIX).trim()
        } else {
            rawTitle
        }
        val previous = existing?.firstOrNull {
            it.title.equals(title, ignoreCase = true)
        }
        previous?.copy(
            title = title,
            state = when {
                skipped && previous.state != State.FAILED -> State.COMPLETED
                else -> previous.state
            },
            skipped = skipped || previous.skipped
        ) ?: StepDetail(
            title = title,
            state = if (skipped) State.COMPLETED else State.WAITING,
            skipped = skipped
        )
    }
    .toList()

private fun updateBatchSubStep(
    stepId: StepId,
    existing: List<StepDetail>,
    message: String?,
    progress: Pair<Long, Long?>?
): List<StepDetail> = if (stepId == StepId.WriteAPK) {
    updateBatchWriteApkSubStep(existing, message, progress)
} else {
    updateGenericBatchSubStep(existing, message, progress)
}

private fun updateGenericBatchSubStep(
    existing: List<StepDetail>,
    message: String?,
    progress: Pair<Long, Long?>?
): List<StepDetail> {
    if (message.isNullOrBlank()) {
        if (progress == null || existing.isEmpty()) return existing
        val targetIndex = existing.indexOfFirst { it.state == State.RUNNING }
            .takeIf { it != -1 }
            ?: existing.lastIndex
        return existing.mapIndexed { index, detail ->
            if (index == targetIndex) detail.copy(progress = progress) else detail
        }
    }

    val title = message.trim()
    val matchingIndex = findBatchSubStepIndex(existing, title)
    val targetIndex = if (matchingIndex == -1) existing.size else matchingIndex
    val withTarget = if (matchingIndex == -1) {
        existing + StepDetail(title = title)
    } else {
        existing
    }

    return withTarget.mapIndexed { index, detail ->
        when {
            detail.skipped -> detail
            index == targetIndex -> detail.copy(
                state = State.RUNNING,
                message = null,
                progress = progress
            )
            detail.state == State.RUNNING -> detail.copy(
                state = State.COMPLETED,
                message = null,
                progress = null
            )
            else -> detail
        }
    }
}

private fun syncBatchWriteApkSubSteps(
    titles: List<String>,
    existing: List<StepDetail>?
): List<StepDetail> {
    val normalizedTitles = titles
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .map { rawTitle ->
            val skipped = rawTitle.startsWith(SKIPPED_SUBSTEP_PREFIX)
            val withoutPrefix = if (skipped) {
                rawTitle.removePrefix(SKIPPED_SUBSTEP_PREFIX).trim()
            } else {
                rawTitle
            }
            normalizeBatchWriteApkTitle(withoutPrefix) to skipped
        }
        .toList()

    val morpheLayout = isBatchMorpheWriteApkLayout(
        titles = normalizedTitles.map { it.first },
        existing = existing
    )
    val parsedTitles = normalizedTitles
        .asSequence()
        .filter { (title, _) ->
            title.isNotBlank() &&
                !title.equals("Writing patched files...", ignoreCase = true) &&
                !(morpheLayout && isBatchHiddenMorpheDexDetail(title))
        }
        .distinctBy { (title, _) -> title.lowercase() }
        .toList()
    val existingDexGroup = existing.orEmpty().firstOrNull(::isBatchWriteApkDexGroup)
    val previousDetails = existing.orEmpty() + existingDexGroup?.children.orEmpty()
    val incomingDexGroupTitle = parsedTitles
        .firstOrNull { (title, _) -> isBatchWriteApkDexGroupTitle(title) }
        ?.first
    val dexGroupTitle = incomingDexGroupTitle
        ?: existingDexGroup?.title
        ?: WRITE_APK_DEX_GROUP_TITLE

    val incomingDexChildren = parsedTitles
        .filter { (title, _) -> isBatchWriteApkDexChildTitle(title) }
    val topLevelTitles = parsedTitles
        .filterNot { (title, _) ->
            isBatchWriteApkDexChildTitle(title) || isBatchWriteApkDexGroupTitle(title)
        }
        .toMutableList()

    if (
        incomingDexChildren.isNotEmpty() ||
        existingDexGroup != null ||
        incomingDexGroupTitle != null
    ) {
        val insertIndex = batchWriteApkDexInsertIndex(topLevelTitles.map { it.first })
        topLevelTitles.add(insertIndex, dexGroupTitle to false)
    }

    val result = topLevelTitles.map { (title, skipped) ->
        val previous = previousDetails.firstOrNull { detail ->
            detail.title.equals(title, ignoreCase = true) ||
                (isBatchWriteApkDexGroupTitle(title) && isBatchWriteApkDexGroup(detail))
        }
        val effectiveSkipped = skipped || previous?.skipped == true
        previous?.copy(
            title = title,
            state = when {
                effectiveSkipped && previous.state != State.FAILED -> State.COMPLETED
                else -> previous.state
            },
            skipped = effectiveSkipped,
            expandable = previous.expandable || isBatchWriteApkDexGroupTitle(title)
        ) ?: StepDetail(
            title = title,
            state = if (effectiveSkipped) State.COMPLETED else State.WAITING,
            skipped = effectiveSkipped,
            expandable = isBatchWriteApkDexGroupTitle(title)
        )
    }.toMutableList()

    val groupIndex = result.indexOfFirst(::isBatchWriteApkDexGroup)
    if (groupIndex != -1) {
        val group = result[groupIndex]
        val retainedExistingChildren = existingDexGroup?.children.orEmpty()
            .filterNot { morpheLayout && isBatchHiddenMorpheDexDetail(it.title) }
        val mergedChildren = (retainedExistingChildren + incomingDexChildren.map { (title, skipped) ->
            val previous = retainedExistingChildren.firstOrNull {
                it.title.equals(title, ignoreCase = true)
            }
            val effectiveSkipped = skipped || previous?.skipped == true
            previous?.copy(
                title = title,
                state = when {
                    effectiveSkipped && previous.state != State.FAILED -> State.COMPLETED
                    else -> previous.state
                },
                skipped = effectiveSkipped
            ) ?: StepDetail(
                title = title,
                state = if (effectiveSkipped) State.COMPLETED else State.WAITING,
                skipped = effectiveSkipped
            )
        })
            .distinctBy { it.title.lowercase() }

        val hasChildActivity = mergedChildren.any {
            it.state == State.RUNNING || it.state == State.COMPLETED
        }
        result[groupIndex] = group.copy(
            state = if (group.state == State.WAITING && hasChildActivity) State.RUNNING else group.state,
            expandable = true,
            children = mergedChildren
        )
    }

    return result
}

private fun updateBatchWriteApkSubStep(
    existing: List<StepDetail>,
    message: String?,
    progress: Pair<Long, Long?>?
): List<StepDetail> {
    if (message.isNullOrBlank()) {
        return updateGenericBatchSubStep(existing, message, progress)
    }

    val normalized = normalizeBatchWriteApkTitle(message.trim())
    if (normalized.isBlank()) return existing
    val morpheLayout = isBatchMorpheWriteApkLayout(
        titles = listOf(normalized),
        existing = existing
    )

    return when {
        normalized.equals("Writing patched files...", ignoreCase = true) ->
            activateBatchWriteApkFromWritingPatchedFiles(existing)

        morpheLayout && isBatchHiddenMorpheDexDetail(normalized) ->
            existing

        morpheLayout && isBatchMorpheVisibleDexChildTitle(normalized) ->
            updateBatchWriteApkDexChild(existing, normalized)

        isBatchWriteApkDexChildTitle(normalized) ->
            updateBatchWriteApkDexChild(existing, normalized)

        isBatchWriteApkDexGroupTitle(normalized) ->
            activateBatchWriteApkStep(existing, normalized, progress)

        isBatchWriteApkResourceTitle(normalized) ||
            normalized.equals("Writing output APK", ignoreCase = true) ||
            normalized.equals("Finalizing output", ignoreCase = true) ||
            normalized.equals("Stripping native libraries", ignoreCase = true) ->
            activateBatchWriteApkStep(existing, normalized, progress)

        else -> {
            val matchingIndex = findBatchSubStepIndex(existing, normalized)
            if (matchingIndex == -1 && existing.isNotEmpty()) existing
            else updateGenericBatchSubStep(existing, normalized, progress)
        }
    }
}

private fun activateBatchWriteApkFromWritingPatchedFiles(
    existing: List<StepDetail>
): List<StepDetail> {
    val applyIndex = existing.indexOfFirst {
        it.title.equals("Applying patched changes", ignoreCase = true)
    }.takeIf { it != -1 }
        ?: existing.indexOfFirst {
            it.title.equals("Copy base APK", ignoreCase = true)
        }.takeIf { it != -1 }
        ?: return existing

    if (hasBatchWriteApkAdvancedPast(existing, applyIndex)) return existing

    val updated = existing.toMutableList()
    completeBatchWriteApkPriorSteps(updated, applyIndex + 1)
    val nextIndex = ((applyIndex + 1) until updated.size).firstOrNull { index ->
        val detail = updated[index]
        !detail.skipped && detail.state == State.WAITING
    }
    if (nextIndex != null) {
        updated[nextIndex] = updated[nextIndex].copy(
            state = State.RUNNING,
            progress = null
        )
    }
    return updated
}

private fun updateBatchWriteApkDexChild(
    existing: List<StepDetail>,
    childTitle: String
): List<StepDetail> {
    val updated = ensureBatchWriteApkDexGroup(existing)
    val groupIndex = updated.indexOfFirst(::isBatchWriteApkDexGroup)
    if (groupIndex == -1) return existing

    val advancedPastGroup = hasBatchWriteApkAdvancedPast(updated, groupIndex)
    completeBatchWriteApkPriorSteps(updated, groupIndex)
    val group = updated[groupIndex]
    val children = group.children.toMutableList()
    val runningChildIndex = children.indexOfFirst {
        !it.skipped && it.state == State.RUNNING
    }
    if (runningChildIndex != -1) {
        children[runningChildIndex] = children[runningChildIndex].copy(
            state = State.COMPLETED,
            progress = null
        )
    }

    val existingChildIndex = children.indexOfFirst {
        it.title.equals(childTitle, ignoreCase = true)
    }
    val targetState = if (advancedPastGroup) State.COMPLETED else State.RUNNING
    if (existingChildIndex == -1) {
        children.add(StepDetail(title = childTitle, state = targetState))
    } else {
        children[existingChildIndex] = children[existingChildIndex].copy(
            state = targetState,
            progress = null
        )
    }

    updated[groupIndex] = group.copy(
        state = if (advancedPastGroup) State.COMPLETED else State.RUNNING,
        progress = null,
        expandable = true,
        children = if (advancedPastGroup) {
            children.map { child ->
                if (child.skipped) child else child.copy(state = State.COMPLETED, progress = null)
            }
        } else {
            children
        }
    )
    return updated
}

private fun activateBatchWriteApkStep(
    existing: List<StepDetail>,
    title: String,
    progress: Pair<Long, Long?>?
): List<StepDetail> {
    val prepared = if (isBatchWriteApkDexGroupTitle(title)) {
        ensureBatchWriteApkDexGroup(existing, title)
    } else {
        existing.toMutableList()
    }
    val targetIndex = prepared.indexOfFirst { detail ->
        detail.title.equals(title, ignoreCase = true) ||
            (isBatchWriteApkDexGroupTitle(title) && isBatchWriteApkDexGroup(detail))
    }
    if (targetIndex == -1 || hasBatchWriteApkAdvancedPast(prepared, targetIndex)) return existing

    completeBatchWriteApkPriorSteps(prepared, targetIndex)
    prepared.indices.forEach { index ->
        if (index == targetIndex) return@forEach
        val detail = prepared[index]
        if (detail.state == State.RUNNING) {
            prepared[index] = completeBatchWriteApkDetail(detail)
        }
    }

    val target = prepared[targetIndex]
    prepared[targetIndex] = target.copy(
        title = if (isBatchWriteApkDexGroup(target)) target.title else title,
        state = if (target.state == State.COMPLETED) State.COMPLETED else State.RUNNING,
        progress = progress,
        expandable = target.expandable || isBatchWriteApkDexGroup(target)
    )
    return prepared
}

private fun ensureBatchWriteApkDexGroup(
    existing: List<StepDetail>,
    preferredTitle: String? = null
): MutableList<StepDetail> {
    val updated = existing.toMutableList()
    if (updated.any(::isBatchWriteApkDexGroup)) return updated
    val groupTitle = preferredTitle
        ?.takeIf(::isBatchWriteApkDexGroupTitle)
        ?: WRITE_APK_DEX_GROUP_TITLE
    val insertIndex = batchWriteApkDexInsertIndex(updated.map { it.title })
    updated.add(
        insertIndex,
        StepDetail(
            title = groupTitle,
            state = State.WAITING,
            expandable = true
        )
    )
    return updated
}

private fun completeBatchWriteApkPriorSteps(
    steps: MutableList<StepDetail>,
    untilExclusive: Int
) {
    for (index in 0 until untilExclusive.coerceAtMost(steps.size)) {
        val detail = steps[index]
        if (detail.skipped || detail.state == State.COMPLETED) continue
        steps[index] = completeBatchWriteApkDetail(detail)
    }
}

private fun completeBatchWriteApkDetail(detail: StepDetail): StepDetail = detail.copy(
    state = State.COMPLETED,
    message = null,
    progress = null,
    children = detail.children.map { child ->
        if (child.skipped) child else completeBatchWriteApkDetail(child)
    }
)

private fun hasBatchWriteApkAdvancedPast(
    steps: List<StepDetail>,
    index: Int
): Boolean = steps.drop(index + 1).any { detail ->
    detail.state == State.RUNNING ||
        detail.state == State.COMPLETED ||
        detail.children.any { child ->
            child.state == State.RUNNING || child.state == State.COMPLETED
        }
}

private fun batchWriteApkDexInsertIndex(titles: List<String>): Int =
    titles.indexOfFirst(::isBatchWriteApkResourceTitle).takeIf { it != -1 }
        ?: titles.indexOfFirst {
            it.equals("Writing output APK", ignoreCase = true)
        }.takeIf { it != -1 }
        ?: titles.indexOfFirst {
            it.equals("Finalizing output", ignoreCase = true)
        }.takeIf { it != -1 }
        ?: titles.size

private fun normalizeBatchWriteApkTitle(title: String): String {
    val trimmed = title.trim()
    return when {
        trimmed.equals("Copying base APK", ignoreCase = true) -> "Copy base APK"
        trimmed.equals("Compiling patched dex files", ignoreCase = true) -> WRITE_APK_DEX_GROUP_TITLE
        trimmed.startsWith("Compiling patched dex files (mode:", ignoreCase = true) -> {
            val mode = trimmed.substringAfter("mode:", "")
                .substringBefore(')')
                .trim()
                .uppercase()
            if (mode.isBlank()) WRITE_APK_DEX_GROUP_TITLE else "$WRITE_APK_DEX_GROUP_TITLE: $mode"
        }
        trimmed.equals("Compiling patched resources", ignoreCase = true) ||
            trimmed.equals("Compiled patched resources", ignoreCase = true) ->
            "Compiling modified resources"
        BATCH_MORPHE_PROCESSING_CLASSES_PATTERN.containsMatchIn(trimmed) ->
            BATCH_MORPHE_PROCESSING_CLASSES_PATTERN.find(trimmed)?.value ?: trimmed
        BATCH_MORPHE_WROTE_DEX_FILES_PATTERN.containsMatchIn(trimmed) ->
            BATCH_MORPHE_WROTE_DEX_FILES_PATTERN.find(trimmed)?.value ?: trimmed
        BATCH_MORPHE_STRIPPED_DEX_PATTERN.containsMatchIn(trimmed) -> {
            val dexName = BATCH_MORPHE_STRIPPED_DEX_PATTERN.find(trimmed)
                ?.groupValues
                ?.getOrNull(1)
            dexName?.let { "Modified $it" } ?: trimmed
        }
        trimmed.startsWith("Compiled ", ignoreCase = true) ->
            "Compiling " + trimmed.substringAfter(' ').trim()
        else -> trimmed
    }
}

private fun isBatchWriteApkDexGroup(detail: StepDetail): Boolean =
    isBatchWriteApkDexGroupTitle(detail.title)

private fun isBatchWriteApkDexGroupTitle(title: String): Boolean =
    title.equals(WRITE_APK_DEX_GROUP_TITLE, ignoreCase = true) ||
        title.startsWith("$WRITE_APK_DEX_GROUP_TITLE:", ignoreCase = true)

private fun isBatchMorpheWriteApkLayout(
    titles: List<String>,
    existing: List<StepDetail>?
): Boolean {
    val existingTitles = existing.orEmpty().flatMap { detail ->
        listOf(detail.title) + detail.children.map { it.title }
    }
    return (titles + existingTitles).any { title ->
        title.startsWith("$WRITE_APK_DEX_GROUP_TITLE:", ignoreCase = true) ||
            title.startsWith("Compiling patched dex files (mode:", ignoreCase = true) ||
            BATCH_MORPHE_PROCESSING_CLASSES_PATTERN.containsMatchIn(title) ||
            BATCH_MORPHE_WROTE_DEX_FILES_PATTERN.containsMatchIn(title) ||
            BATCH_MORPHE_MODIFIED_DEX_PATTERN.matches(title) ||
            BATCH_MORPHE_STRIPPED_DEX_PATTERN.containsMatchIn(title)
    }
}

private fun isBatchWriteApkResourceTitle(title: String): Boolean =
    title.equals("Compiling modified resources", ignoreCase = true) ||
        title.equals("Compiling patched resources", ignoreCase = true)

private fun isBatchMorpheVisibleDexChildTitle(title: String): Boolean =
    BATCH_MORPHE_PROCESSING_CLASSES_PATTERN.matches(title) ||
        BATCH_MORPHE_WROTE_DEX_FILES_PATTERN.matches(title)

private fun isBatchHiddenMorpheDexDetail(title: String): Boolean =
    BATCH_DEX_COMPILE_PATTERN.matches(title) ||
        BATCH_DEX_WRITE_PATTERN.containsMatchIn(title) ||
        BATCH_MORPHE_MODIFIED_DEX_PATTERN.matches(title) ||
        BATCH_MORPHE_STRIPPED_DEX_PATTERN.containsMatchIn(title)

private fun isBatchWriteApkDexChildTitle(title: String): Boolean =
    BATCH_DEX_COMPILE_PATTERN.matches(title) ||
        isBatchMorpheVisibleDexChildTitle(title) ||
        BATCH_MORPHE_MODIFIED_DEX_PATTERN.matches(title)

private fun findBatchSubStepIndex(
    subSteps: List<StepDetail>,
    message: String
): Int {
    val needle = message.lowercase()
    val exactIndex = subSteps.indexOfFirst {
        it.title.equals(message, ignoreCase = true)
    }
    if (exactIndex != -1) return exactIndex
    val prefixIndex = subSteps.indexOfFirst {
        needle.startsWith(it.title.lowercase())
    }
    if (prefixIndex != -1) return prefixIndex
    return subSteps.indexOfFirst {
        it.title.lowercase().startsWith(needle) ||
            needle.contains(it.title.lowercase())
    }
}

private fun finalizeBatchSubSteps(
    existing: List<StepDetail>,
    failed: Boolean,
    errorMessage: String? = null
): List<StepDetail> {
    if (existing.isEmpty()) return existing
    if (!failed) {
        return existing.map { detail ->
            detail.copy(
                state = State.COMPLETED,
                message = null,
                progress = null,
                children = detail.children.map {
                    it.copy(state = State.COMPLETED, progress = null)
                }
            )
        }
    }

    val failedIndex = existing.indexOfFirst {
        !it.skipped && it.state == State.RUNNING
    }.takeIf { it != -1 }
        ?: existing.indexOfFirst {
            !it.skipped && it.state != State.COMPLETED
        }.takeIf { it != -1 }
        ?: existing.lastIndex

    return existing.mapIndexed { index, detail ->
        when {
            detail.skipped -> detail.copy(progress = null)
            index == failedIndex -> detail.copy(
                state = State.FAILED,
                message = errorMessage,
                progress = null
            )
            detail.state == State.RUNNING -> detail.copy(
                state = State.WAITING,
                progress = null
            )
            else -> detail.copy(progress = null)
        }
    }
}

private fun formatBatchFailure(event: ProgressEvent.Failed): String {
    val error = event.error
    return if (error.type.contains("UserInteractionException")) {
        error.message ?: "Downloader search cancelled by user."
    } else {
        error.stackTrace
    }
}

private fun promoteImmediateBatchSignStep(
    steps: MutableList<Step>,
    completedIndex: Int
) {
    val completedStep = steps.getOrNull(completedIndex) ?: return
    if (completedStep.id != StepId.WriteAPK || completedStep.hide) return
    val signIndex = ((completedIndex + 1) until steps.size)
        .firstOrNull { index ->
            val step = steps[index]
            !step.hide && step.id == StepId.SignAPK
        }
        ?: return
    val signStep = steps[signIndex]
    if (signStep.state != State.WAITING) return
    if (hasAnotherVisibleRunningStep(steps, signIndex)) return
    steps[signIndex] = signStep.withState(
        state = State.RUNNING,
        message = null,
        progress = null
    )
}

private fun promoteNextBatchSectionStep(
    steps: MutableList<Step>,
    completedIndex: Int
) {
    val completedStep = steps.getOrNull(completedIndex) ?: return
    if (completedStep.hide) return
    val isLastVisibleInSection = ((completedIndex + 1) until steps.size).none { index ->
        !steps[index].hide && steps[index].category == completedStep.category
    }
    if (!isLastVisibleInSection) return

    val nextVisibleIndex = ((completedIndex + 1) until steps.size)
        .firstOrNull { !steps[it].hide }
        ?: return
    val nextStep = steps[nextVisibleIndex]
    if (nextStep.category == completedStep.category) return
    if (nextStep.state != State.WAITING) return
    if (hasAnotherVisibleRunningStep(steps, nextVisibleIndex)) return

    steps[nextVisibleIndex] = nextStep.withState(
        state = State.RUNNING,
        message = null,
        progress = null
    )
}

private fun hasAnotherVisibleRunningStep(
    steps: List<Step>,
    excludedIndex: Int
): Boolean = steps.indices.any { index ->
    index != excludedIndex &&
        !steps[index].hide &&
        steps[index].state == State.RUNNING
}

private fun calculateBatchProgress(
    steps: List<Step>,
    subStepsById: Map<StepId, List<StepDetail>>
): Float {
    if (steps.isEmpty()) return 0f
    val completed = steps.sumOf { step ->
        calculateBatchProgressFraction(step, subStepsById[step.id].orEmpty())
    }
    return (completed / steps.size.toDouble()).toFloat().coerceIn(0f, 1f)
}

private fun calculateBatchProgressFraction(
    step: Step,
    subSteps: List<StepDetail>
): Double {
    if (step.state == State.COMPLETED) return 1.0
    val units = subSteps
        .filterNot { it.skipped }
        .map(::calculateBatchProgressUnits)
    val total = units.sumOf { it.total }
    if (total > 0) {
        return (units.sumOf { it.completed } / total.toDouble())
            .coerceIn(0.0, 1.0)
    }
    return step.state.batchProgressFraction(step.progress)
}

private fun calculateBatchProgressUnits(detail: StepDetail): BatchProgressUnits {
    val childUnits = detail.children
        .filterNot { it.skipped }
        .map(::calculateBatchProgressUnits)
    val childTotal = childUnits.sumOf { it.total }
    if (childTotal > 0) {
        val completed = if (detail.state == State.COMPLETED) {
            childTotal.toDouble()
        } else {
            childUnits.sumOf { it.completed }
        }
        return BatchProgressUnits(completed = completed, total = childTotal)
    }
    return BatchProgressUnits(
        completed = detail.state.batchProgressFraction(detail.progress),
        total = 1
    )
}

private fun State.batchProgressFraction(
    progress: Pair<Long, Long?>?
): Double = when (this) {
    State.COMPLETED -> 1.0
    State.RUNNING -> {
        val current = progress?.first
        val total = progress?.second?.takeIf { it > 0L }
        if (current != null && total != null) {
            (current.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
    }
    else -> 0.0
}

private const val SKIPPED_SUBSTEP_PREFIX = "[skipped]"
private const val WRITE_APK_DEX_GROUP_TITLE = "Compiling DEX files"

private val BATCH_DEX_COMPILE_PATTERN =
    Regex("Compiling classes\\d*\\.dex", RegexOption.IGNORE_CASE)
private val BATCH_DEX_WRITE_PATTERN =
    Regex("Write\\s+\\[[^\\]]+\\]\\s+classes\\d*\\.dex", RegexOption.IGNORE_CASE)
private val BATCH_MORPHE_PROCESSING_CLASSES_PATTERN =
    Regex("Processing\\s+\\d+\\s+classes\\b", RegexOption.IGNORE_CASE)
private val BATCH_MORPHE_WROTE_DEX_FILES_PATTERN =
    Regex("Wrote\\s+\\d+\\s+dex\\s+files\\b", RegexOption.IGNORE_CASE)
private val BATCH_MORPHE_MODIFIED_DEX_PATTERN =
    Regex("Modified classes\\d*\\.dex", RegexOption.IGNORE_CASE)
private val BATCH_MORPHE_STRIPPED_DEX_PATTERN =
    Regex(
        "Stripped\\s+\\d+\\s+class_def\\s+entries\\s+from\\s+(classes\\d*\\.dex)",
        RegexOption.IGNORE_CASE
    )
