package app.revanced.manager.ui.screen

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.LayersClear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.Surface
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.revanced.manager.R
import app.revanced.manager.patcher.patch.Option
import app.revanced.manager.patcher.patch.PatchBundleInfo
import app.revanced.manager.patcher.patch.PatchInfo
import app.revanced.manager.ui.component.AppTopBar
import app.revanced.manager.ui.component.CheckedFilterChip
import app.revanced.manager.ui.component.FullscreenDialog
import app.revanced.manager.ui.component.LazyColumnWithScrollbar
import app.revanced.manager.ui.component.SafeguardDialog
import app.revanced.manager.ui.component.SearchBar
import app.revanced.manager.ui.component.AlertDialogExtended
import app.revanced.manager.ui.component.haptics.HapticCheckbox
import app.revanced.manager.ui.component.haptics.HapticExtendedFloatingActionButton
import app.revanced.manager.ui.component.haptics.HapticTab
import app.revanced.manager.ui.component.patches.OptionItem
import app.revanced.manager.ui.component.patches.SelectionWarningDialog
import app.revanced.manager.ui.viewmodel.PatchesSelectorViewModel
import app.revanced.manager.ui.viewmodel.PatchesSelectorViewModel.Companion.SHOW_INCOMPATIBLE
import app.revanced.manager.ui.viewmodel.PatchesSelectorViewModel.Companion.SHOW_UNIVERSAL
import app.revanced.manager.util.Options
import app.revanced.manager.util.PatchSelection
import app.revanced.manager.util.isScrollingUp
import app.revanced.manager.util.transparentListItemColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PatchesSelectorScreen(
    onSave: (PatchSelection?, Options) -> Unit,
    onBackClick: () -> Unit,
    viewModel: PatchesSelectorViewModel
) {
    val bundles by viewModel.bundlesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val bundleDisplayNames by viewModel.bundleDisplayNames.collectAsStateWithLifecycle(initialValue = emptyMap())
    val pagerState = rememberPagerState(
        initialPage = 0,
        initialPageOffsetFraction = 0f
    ) {
        bundles.size
    }
    val composableScope = rememberCoroutineScope()
    val (query, setQuery) = rememberSaveable {
        mutableStateOf("")
    }
    val (searchExpanded, setSearchExpanded) = rememberSaveable {
        mutableStateOf(false)
    }
    var showBottomSheet by rememberSaveable { mutableStateOf(false) }
    val showSaveButton by remember {
        derivedStateOf { viewModel.selectionIsValid(bundles) }
    }
    val selectedBundleUids = remember { mutableStateListOf<Int>() }
    var showBundleDialog by rememberSaveable { mutableStateOf(false) }
    var showProfileNameDialog by rememberSaveable { mutableStateOf(false) }
    var pendingProfileName by rememberSaveable { mutableStateOf("") }
    var isSavingProfile by remember { mutableStateOf(false) }

    val defaultPatchSelectionCount by viewModel.defaultSelectionCount
        .collectAsStateWithLifecycle(initialValue = 0)

    val selectedPatchCount by remember {
        derivedStateOf {
            viewModel.customPatchSelection?.values?.sumOf { it.size } ?: defaultPatchSelectionCount
        }
    }

    val patchLazyListStates = remember(bundles) { List(bundles.size) { LazyListState() } }
    val dialogsOpen = showBundleDialog || showProfileNameDialog
    var actionsExpanded by rememberSaveable { mutableStateOf(false) }

    fun openProfileSaveDialog() {
        if (bundles.isEmpty() || isSavingProfile) return
        selectedBundleUids.clear()
        val defaultBundleUid =
            bundles.getOrNull(pagerState.currentPage)?.uid ?: bundles.firstOrNull()?.uid
        defaultBundleUid?.let { selectedBundleUids.add(it) }
        pendingProfileName = ""
        if (searchExpanded) setSearchExpanded(false)
        showBottomSheet = false
        showBundleDialog = true
    }

    if (showBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showBottomSheet = false
            }
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                Text(
                    text = stringResource(R.string.patch_selector_sheet_filter_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(
                    text = stringResource(R.string.patch_selector_sheet_filter_compat_title),
                    style = MaterialTheme.typography.titleMedium
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CheckedFilterChip(
                        selected = viewModel.filter and SHOW_INCOMPATIBLE == 0,
                        onClick = { viewModel.toggleFlag(SHOW_INCOMPATIBLE) },
                        label = { Text(stringResource(R.string.this_version)) }
                    )

                    CheckedFilterChip(
                        selected = viewModel.filter and SHOW_UNIVERSAL != 0,
                        onClick = { viewModel.toggleFlag(SHOW_UNIVERSAL) },
                        label = { Text(stringResource(R.string.universal)) },
                    )
                }
            }
        }
    }

    if (viewModel.compatibleVersions.isNotEmpty())
        IncompatiblePatchDialog(
            appVersion = viewModel.appVersion ?: stringResource(R.string.any_version),
            compatibleVersions = viewModel.compatibleVersions,
            onDismissRequest = viewModel::dismissDialogs
        )
    var showIncompatiblePatchesDialog by rememberSaveable {
        mutableStateOf(false)
    }
    if (showIncompatiblePatchesDialog)
        IncompatiblePatchesDialog(
            appVersion = viewModel.appVersion ?: stringResource(R.string.any_version),
            onDismissRequest = { showIncompatiblePatchesDialog = false }
        )

    viewModel.optionsDialog?.let { (bundle, patch) ->
        OptionsDialog(
            onDismissRequest = viewModel::dismissDialogs,
            patch = patch,
            values = viewModel.getOptions(bundle, patch),
            reset = { viewModel.resetOptions(bundle, patch) },
            set = { key, value -> viewModel.setOption(bundle, patch, key, value) },
            selectionWarningEnabled = viewModel.selectionWarningEnabled
        )
    }

    if (showBundleDialog) {
        PatchProfileBundleDialog(
            bundles = bundles,
            bundleDisplayNames = bundleDisplayNames,
            selectedBundleUids = selectedBundleUids,
            onDismiss = {
                showBundleDialog = false
                selectedBundleUids.clear()
                pendingProfileName = ""
            },
            onConfirm = {
                if (selectedBundleUids.isNotEmpty()) {
                    showBundleDialog = false
                    showProfileNameDialog = true
                }
            }
        )
    }

    if (showProfileNameDialog) {
        PatchProfileNameDialog(
            name = pendingProfileName,
            onNameChange = { pendingProfileName = it },
            isSaving = isSavingProfile,
            onDismiss = {
                if (isSavingProfile) return@PatchProfileNameDialog
                showProfileNameDialog = false
                selectedBundleUids.clear()
                pendingProfileName = ""
            },
            onConfirm = {
                if (pendingProfileName.isBlank() || isSavingProfile) return@PatchProfileNameDialog
                composableScope.launch {
                    isSavingProfile = true
                    val success = try {
                        viewModel.savePatchProfile(
                            pendingProfileName.trim(),
                            selectedBundleUids.toSet()
                        )
                    } finally {
                        isSavingProfile = false
                    }
                    if (success) {
                        showProfileNameDialog = false
                        showBundleDialog = false
                        pendingProfileName = ""
                        selectedBundleUids.clear()
                    }
                }
            }
        )
    }

    var showSelectionWarning by rememberSaveable { mutableStateOf(false) }
    var showUniversalWarning by rememberSaveable { mutableStateOf(false) }

    if (showSelectionWarning)
        SelectionWarningDialog(onDismiss = { showSelectionWarning = false })

    if (showUniversalWarning)
        UniversalPatchWarningDialog(onDismiss = { showUniversalWarning = false })

    fun LazyListScope.patchList(
        uid: Int,
        patches: List<PatchInfo>,
        visible: Boolean,
        compatible: Boolean,
        header: (@Composable () -> Unit)? = null
    ) {
        if (patches.isNotEmpty() && visible) {
            header?.let {
                item(contentType = 0) {
                    it()
                }
            }

            items(
                items = patches,
                key = { it.name },
                contentType = { 1 }
            ) { patch ->
                PatchItem(
                    patch = patch,
                    onOptionsDialog = { viewModel.optionsDialog = uid to patch },
                    selected = compatible && viewModel.isSelected(
                        uid,
                        patch
                    ),
                    onToggle = {
                        when {
                            // Open incompatible dialog if the patch is not supported
                            !compatible -> viewModel.openIncompatibleDialog(patch)

                            // Show selection warning if enabled
                            viewModel.selectionWarningEnabled -> showSelectionWarning = true

                            // Show universal warning if universal patch is selected and the toggle is off
                            patch.compatiblePackages == null && viewModel.universalPatchWarningEnabled -> showUniversalWarning = true

                            // Toggle the patch otherwise
                            else -> viewModel.togglePatch(uid, patch)
                        }
                    },
                    compatible = compatible
                )
            }
        }
    }

    Scaffold(
        topBar = {
            SearchBar(
                query = query,
                onQueryChange = setQuery,
                expanded = searchExpanded && !dialogsOpen,
                onExpandedChange = { if (!dialogsOpen) setSearchExpanded(it) },
                placeholder = {
                    Text(stringResource(R.string.search_patches))
                },
                leadingIcon = {
                    val rotation by animateFloatAsState(
                        targetValue = if (searchExpanded) 360f else 0f,
                        animationSpec = tween(durationMillis = 400, easing = EaseInOut),
                        label = "SearchBar back button"
                    )
                    IconButton(
                        onClick = {
                            if (searchExpanded) {
                                setSearchExpanded(false)
                            } else {
                                onBackClick()
                            }
                        }
                    ) {
                        Icon(
                            modifier = Modifier.rotate(rotation),
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                trailingIcon = {
                    AnimatedContent(
                        targetState = searchExpanded,
                        label = "Filter/Clear",
                        transitionSpec = { fadeIn() togetherWith fadeOut() }
                    ) { expanded ->
                        if (expanded) {
                            IconButton(
                                onClick = { setQuery("") },
                                enabled = query.isNotEmpty()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.clear)
                                )
                            }
                        } else {
                            IconButton(onClick = { showBottomSheet = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.FilterList,
                                    contentDescription = stringResource(R.string.more)
                                )
                            }
                        }
                    }
                }
            ) {
                val bundle = bundles[pagerState.currentPage]

                LazyColumnWithScrollbar(
                    modifier = Modifier.fillMaxSize()
                ) {
                    fun List<PatchInfo>.searched() = filter {
                        it.name.contains(query, true)
                    }

                    patchList(
                        uid = bundle.uid,
                        patches = bundle.compatible.searched(),
                        visible = true,
                        compatible = true
                    )
                    patchList(
                        uid = bundle.uid,
                        patches = bundle.universal.searched(),
                        visible = viewModel.filter and SHOW_UNIVERSAL != 0,
                        compatible = true
                    ) {
                        ListHeader(
                            title = stringResource(R.string.universal_patches),
                        )
                    }

                    patchList(
                        uid = bundle.uid,
                        patches = bundle.incompatible.searched(),
                        visible = viewModel.filter and SHOW_INCOMPATIBLE != 0,
                        compatible = viewModel.allowIncompatiblePatches
                    ) {
                        ListHeader(
                            title = stringResource(R.string.incompatible_patches),
                            onHelpClick = { showIncompatiblePatchesDialog = true }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (!showSaveButton) return@Scaffold

            AnimatedVisibility(
                visible = !searchExpanded,
                enter = slideInHorizontally { it } + fadeIn(),
                exit = slideOutHorizontally { it } + fadeOut()
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SelectionActionButton(
                        icon = if (actionsExpanded) Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore,
                        contentDescription = if (actionsExpanded) R.string.patch_selection_toggle_collapse else R.string.patch_selection_toggle_expand,
                        label = if (actionsExpanded) R.string.patch_selection_toggle_collapse else R.string.patch_selection_toggle_expand,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        onClick = { actionsExpanded = !actionsExpanded }
                    )
                    AnimatedVisibility(actionsExpanded) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SelectionActionButton(
                                icon = Icons.Outlined.ClearAll,
                                contentDescription = R.string.deselect_all,
                                label = R.string.patch_selection_button_label_all,
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                onClick = viewModel::deselectAll
                            )
                            SelectionActionButton(
                                icon = Icons.Outlined.LayersClear,
                                contentDescription = R.string.deselect_bundle,
                                label = R.string.patch_selection_button_label_bundle,
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                onClick = {
                                    bundles.getOrNull(pagerState.currentPage)?.let { bundle ->
                                        val displayName = bundleDisplayNames[bundle.uid] ?: bundle.name
                                        viewModel.deselectBundle(bundle.uid, displayName)
                                    }
                                },
                            )

                            SelectionActionButton(
                                icon = Icons.Outlined.Restore,
                                contentDescription = R.string.reset,
                                label = R.string.patch_selection_button_label_defaults,
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                onClick = viewModel::reset
                            )
                            SelectionActionButton(
                                icon = Icons.AutoMirrored.Outlined.PlaylistAdd,
                                contentDescription = R.string.patch_profile_save_action,
                                label = R.string.patch_profile_save_label,
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                enabled = !isSavingProfile,
                                onClick = { if (!isSavingProfile) openProfileSaveDialog() }
                            )
                        }
                    }
                    val saveButtonExpanded =
                        patchLazyListStates.getOrNull(pagerState.currentPage)?.isScrollingUp ?: true
                    val saveButtonText = stringResource(
                        R.string.save_with_count,
                        selectedPatchCount
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HapticExtendedFloatingActionButton(
                            text = {
                                Text(saveButtonText)
                            },
                            icon = {
                                SaveFabIcon(
                                    expanded = saveButtonExpanded,
                                    count = selectedPatchCount,
                                    contentDescription = saveButtonText
                                )
                            },
                            expanded = saveButtonExpanded,
                            onClick = {
                                onSave(viewModel.getCustomSelection(), viewModel.getOptions())
                            }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(top = 16.dp)
        ) {
            if (bundles.size > 1) {
                ScrollableTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.0.dp)
                ) {
                    bundles.forEachIndexed { index, bundle ->
                        HapticTab(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                composableScope.launch {
                                    pagerState.animateScrollToPage(
                                        index
                                    )
                                }
                            },
                            text = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = bundleDisplayNames[bundle.uid] ?: bundle.name,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = bundle.version.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            selectedContentColor = MaterialTheme.colorScheme.primary,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                userScrollEnabled = true,
                pageContent = { index ->
                    // Avoid crashing if the lists have not been fully initialized yet.
                    if (index > bundles.lastIndex || bundles.size != patchLazyListStates.size) return@HorizontalPager
                    val bundle = bundles[index]

                    LazyColumnWithScrollbar(
                        modifier = Modifier.fillMaxSize(),
                        state = patchLazyListStates[index]
                    ) {
                        patchList(
                            uid = bundle.uid,
                            patches = bundle.compatible,
                            visible = true,
                            compatible = true
                        )
                        patchList(
                            uid = bundle.uid,
                            patches = bundle.universal,
                            visible = viewModel.filter and SHOW_UNIVERSAL != 0,
                            compatible = true
                        ) {
                            ListHeader(
                                title = stringResource(R.string.universal_patches),
                            )
                        }
                        patchList(
                            uid = bundle.uid,
                            patches = bundle.incompatible,
                            visible = viewModel.filter and SHOW_INCOMPATIBLE != 0,
                            compatible = viewModel.allowIncompatiblePatches
                        ) {
                            ListHeader(
                                title = stringResource(R.string.incompatible_patches),
                                onHelpClick = { showIncompatiblePatchesDialog = true }
                            )
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun UniversalPatchWarningDialog(
    onDismiss: () -> Unit
) {
    SafeguardDialog(
        onDismiss = onDismiss,
        title = R.string.warning,
        body = stringResource(R.string.universal_patch_warning_description),
    )
}

@Composable
private fun PatchItem(
    patch: PatchInfo,
    onOptionsDialog: () -> Unit,
    selected: Boolean,
    onToggle: () -> Unit,
    compatible: Boolean = true
) = ListItem(
    modifier = Modifier
        .let { if (!compatible) it.alpha(0.5f) else it }
        .clickable(onClick = onToggle)
        .fillMaxSize(),
    leadingContent = {
        HapticCheckbox(
            checked = selected,
            onCheckedChange = { onToggle() },
            enabled = compatible
        )
    },
    headlineContent = { Text(patch.name) },
    supportingContent = patch.description?.let { { Text(it) } },
    trailingContent = {
        if (patch.options?.isNotEmpty() == true) {
            IconButton(onClick = onOptionsDialog, enabled = compatible) {
                Icon(Icons.Outlined.Settings, null)
            }
        }
    },
    colors = transparentListItemColors
)

@Composable
private fun SaveFabIcon(
    expanded: Boolean,
    count: Int,
    contentDescription: String
) {
    if (expanded) {
        Icon(
            imageVector = Icons.Outlined.Save,
            contentDescription = contentDescription
        )
    } else {
        BadgedBox(
            badge = {
                Badge {
                    Text(
                        text = formatPatchCountForBadge(count),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }
            }
        ) {
            Icon(
                imageVector = Icons.Outlined.Save,
                contentDescription = contentDescription
            )
        }
    }
}

private fun formatPatchCountForBadge(count: Int): String =
    if (count > 999) "999+" else count.toString()

@Composable
private fun PatchProfileBundleDialog(
    bundles: List<PatchBundleInfo.Scoped>,
    bundleDisplayNames: Map<Int, String>,
    selectedBundleUids: MutableList<Int>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val confirmEnabled = bundles.isNotEmpty() && selectedBundleUids.isNotEmpty()

    AlertDialogExtended(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(stringResource(R.string.next))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = { Text(stringResource(R.string.patch_profile_select_bundles_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.patch_profile_select_bundles_description))
                if (bundles.isEmpty()) {
                    Text(
                        text = stringResource(R.string.patch_profile_select_bundles_empty),
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(bundles, key = { it.uid }) { bundle ->
                            val selected = bundle.uid in selectedBundleUids
                            val toggle: () -> Unit = {
                                if (bundle.uid in selectedBundleUids) {
                                    selectedBundleUids.remove(bundle.uid)
                                } else {
                                    selectedBundleUids.add(bundle.uid)
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = toggle),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                HapticCheckbox(
                                    checked = selected,
                                    onCheckedChange = { toggle() }
                                )

                                Column {
                                    Text(
                                        text = bundleDisplayNames[bundle.uid] ?: bundle.name,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    bundle.version?.let { version ->
                                        Text(
                                            text = version,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun PatchProfileNameDialog(
    name: String,
    onNameChange: (String) -> Unit,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialogExtended(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = name.isNotBlank() && !isSaving
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = { Text(stringResource(R.string.patch_profile_name_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.patch_profile_name_description))
                TextField(
                    value = name,
                    onValueChange = onNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving,
                    placeholder = { Text(stringResource(R.string.patch_profile_name_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (name.isNotBlank() && !isSaving) onConfirm()
                        }
                    )
                )
            }
        }
    )
}

private val SelectionActionLabelWidth: Dp = 120.dp

@Composable
private fun SelectionActionButton(
    icon: ImageVector,
    @StringRes contentDescription: Int,
    @StringRes label: Int,
    containerColor: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    contentColor: Color = MaterialTheme.colorScheme.onTertiaryContainer
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SmallFloatingActionButton(
            onClick = {
                if (enabled) onClick()
            },
            containerColor = containerColor,
            contentColor = contentColor,
            modifier = Modifier.alpha(if (enabled) 1f else 0.4f)
        ) {
            Icon(icon, stringResource(contentDescription))
        }
        Surface(
            modifier = Modifier.width(SelectionActionLabelWidth),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp).copy(alpha = 0.85f),
            tonalElevation = 2.dp
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun ListHeader(
    title: String,
    onHelpClick: (() -> Unit)? = null
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge
            )
        },
        trailingContent = onHelpClick?.let {
            {
                IconButton(onClick = it) {
                    Icon(
                        Icons.AutoMirrored.Outlined.HelpOutline,
                        stringResource(R.string.help)
                    )
                }
            }
        },
        colors = transparentListItemColors
    )
}

@Composable
private fun IncompatiblePatchesDialog(
    appVersion: String,
    onDismissRequest: () -> Unit
) = AlertDialog(
    icon = {
        Icon(Icons.Outlined.WarningAmber, null)
    },
    onDismissRequest = onDismissRequest,
    confirmButton = {
        TextButton(onClick = onDismissRequest) {
            Text(stringResource(R.string.ok))
        }
    },
    title = { Text(stringResource(R.string.incompatible_patches)) },
    text = {
        Text(
            stringResource(
                R.string.incompatible_patches_dialog,
                appVersion
            )
        )
    }
)

@Composable
private fun IncompatiblePatchDialog(
    appVersion: String,
    compatibleVersions: List<String>,
    onDismissRequest: () -> Unit
) = AlertDialog(
    icon = {
        Icon(Icons.Outlined.WarningAmber, null)
    },
    onDismissRequest = onDismissRequest,
    confirmButton = {
        TextButton(onClick = onDismissRequest) {
            Text(stringResource(R.string.ok))
        }
    },
    title = { Text(stringResource(R.string.incompatible_patch)) },
    text = {
        Text(
            stringResource(
                R.string.app_version_not_compatible,
                appVersion,
                compatibleVersions.joinToString(", ")
            )
        )
    }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsDialog(
    patch: PatchInfo,
    values: Map<String, Any?>?,
    reset: () -> Unit,
    set: (String, Any?) -> Unit,
    onDismissRequest: () -> Unit,
    selectionWarningEnabled: Boolean
) = FullscreenDialog(onDismissRequest = onDismissRequest) {
    Scaffold(
        topBar = {
            AppTopBar(
                title = patch.name,
                onBackClick = onDismissRequest,
                actions = {
                    IconButton(onClick = reset) {
                        Icon(Icons.Outlined.Restore, stringResource(R.string.reset))
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumnWithScrollbar(
            modifier = Modifier.padding(paddingValues)
        ) {
            if (patch.options == null) return@LazyColumnWithScrollbar

            items(patch.options, key = { it.key }) { option ->
                val key = option.key
                val value =
                    if (values == null || !values.contains(key)) option.default else values[key]

                @Suppress("UNCHECKED_CAST")
                OptionItem(
                    option = option as Option<Any>,
                    value = value,
                    setValue = {
                        set(key, it)
                    },
                    selectionWarningEnabled = selectionWarningEnabled
                )
            }
        }
    }
}


