package app.revanced.manager.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.horizontalScroll
import app.revanced.manager.util.consumeHorizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowRight
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.revanced.manager.R
import app.revanced.manager.data.platform.Filesystem
import app.revanced.manager.data.platform.NetworkInfo
import app.revanced.manager.data.room.apps.downloaded.DownloadedApp
import app.revanced.manager.data.room.apps.installed.InstallType
import app.revanced.manager.data.room.apps.installed.InstalledApp
import app.revanced.manager.domain.repository.PatchBundleRepository
import app.revanced.manager.network.downloader.LoadedDownloaderPlugin
import app.revanced.manager.ui.component.AlertDialogExtended
import app.revanced.manager.ui.component.AppInfo
import app.revanced.manager.ui.component.AppTopBar
import app.revanced.manager.ui.component.AppliedPatchBundleUi
import app.revanced.manager.ui.component.AppliedPatchesDialog
import app.revanced.manager.ui.component.ColumnWithScrollbar
import app.revanced.manager.ui.component.LoadingIndicator
import app.revanced.manager.ui.component.NotificationCard
import app.revanced.manager.ui.component.haptics.HapticExtendedFloatingActionButton
import app.revanced.manager.ui.component.SafeguardHintCard
import app.revanced.manager.ui.component.patches.PathSelectorDialog
import app.revanced.manager.ui.model.SelectedApp
import app.revanced.manager.ui.viewmodel.SelectedAppInfoViewModel
import app.revanced.manager.ui.viewmodel.BundleRecommendationDetail
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import app.revanced.manager.util.EventEffect
import app.revanced.manager.util.Options
import app.revanced.manager.util.PatchSelection
import app.revanced.manager.util.enabled
import app.revanced.manager.util.isAllowedApkFile
import app.revanced.manager.util.toast
import app.revanced.manager.util.transparentListItemColors
import java.util.Locale
import java.io.File
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectedAppInfoScreen(
    onPatchSelectorClick: (SelectedApp, PatchSelection?, Options) -> Unit,
    onRequiredOptions: (SelectedApp, PatchSelection?, Options) -> Unit,
    onPatchClick: () -> Unit,
    onBackClick: () -> Unit,
    vm: SelectedAppInfoViewModel
) {
    val context = LocalContext.current
    val networkInfo = koinInject<NetworkInfo>()
    val patchBundleRepository: PatchBundleRepository = koinInject()
    val networkConnected = remember { networkInfo.isConnected() }
    val networkMetered = remember { !networkInfo.isUnmetered() }

    val packageName = vm.selectedApp.packageName
    val version = vm.selectedApp.version
    val bundles by vm.bundleInfoFlow.collectAsStateWithLifecycle(emptyList())
    val selectedBundleUid by vm.selectedBundleUidFlow.collectAsStateWithLifecycle(null)
    val selectedBundleOverride by vm.selectedBundleVersionOverrideFlow.collectAsStateWithLifecycle(null)
    val bundleTargetsAllVersions by vm.preferredBundleTargetsAllVersionsFlow.collectAsStateWithLifecycle(false)
    val preferredBundleVersion by vm.preferredBundleVersionFlow.collectAsStateWithLifecycle(null)
    val bundleRecommendationDetails by vm.bundleRecommendationDetailsFlow.collectAsStateWithLifecycle(emptyList())
    var showBundleRecommendationDialog by rememberSaveable { mutableStateOf(false) }
    var showMixedBundleDialog by rememberSaveable { mutableStateOf(false) }
    var showPatchSummaryDialog by rememberSaveable { mutableStateOf(false) }

    val allowIncompatiblePatches by vm.prefs.disablePatchVersionCompatCheck.getAsState()
    val suggestedVersionSafeguard by vm.prefs.suggestedVersionSafeguard.getAsState()
    val showPatchSummaryDialogSetting by vm.prefs.showPatchSelectionSummary.getAsState()
    val bundleRecommendationsEnabled = allowIncompatiblePatches && !suggestedVersionSafeguard
    val patches = vm.getPatches(bundles, allowIncompatiblePatches)
    val selectedPatchCount = patches.values.sumOf { it.size }
    val downloadedApps by vm.downloadedApps.collectAsStateWithLifecycle(emptyList())
    val resolveNavigationVersion: (SelectedApp) -> SelectedApp = remember(
        downloadedApps,
        vm.selectedAppInfo,
        vm.selectedApp,
        selectedBundleOverride,
        preferredBundleVersion,
        bundleTargetsAllVersions,
        selectedBundleUid
    ) {
        { app ->
            val preferredVersion = if (bundleTargetsAllVersions && selectedBundleUid != null) {
                null
            } else {
                selectedBundleOverride?.takeUnless { it.isBlank() }
                    ?: preferredBundleVersion?.takeUnless { it.isBlank() }
            }
            val fileVersion = when (app) {
                is SelectedApp.Local, is SelectedApp.Download -> app.version?.takeUnless { it.isNullOrBlank() }
                else -> null
            }
            val versionOverride = fileVersion
                ?: preferredVersion
                ?: vm.selectedAppInfo?.versionName?.takeUnless { it.isNullOrBlank() }
                ?: app.version?.takeUnless { it.isNullOrBlank() }
                ?: downloadedApps.firstOrNull()?.version?.takeUnless { it.isNullOrBlank() }
            if (versionOverride.isNullOrBlank()) return@remember app
            when (app) {
                is SelectedApp.Download -> app.copy(version = versionOverride)
                is SelectedApp.Search -> app.copy(version = versionOverride)
                is SelectedApp.Local -> app.copy(version = versionOverride)
                is SelectedApp.Installed -> app.copy(version = versionOverride)
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = vm::handlePluginActivityResult
    )
    EventEffect(flow = vm.launchActivityFlow) { intent ->
        launcher.launch(intent)
    }
    val fs = koinInject<Filesystem>()
    val storageRoots = remember { fs.storageRoots() }
    var showStorageDialog by rememberSaveable { mutableStateOf(false) }
    val (permissionContract, permissionName) = remember { fs.permissionContract() }
    val permissionLauncher =
        rememberLauncherForActivityResult(permissionContract) { granted ->
            if (granted) {
                showStorageDialog = true
            }
        }
    val openStoragePicker = {
        if (fs.hasStoragePermission()) {
            showStorageDialog = true
        } else {
            permissionLauncher.launch(permissionName)
        }
    }
    EventEffect(flow = vm.requestStorageSelection) {
        openStoragePicker()
    }
    if (showStorageDialog) {
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                showStorageDialog = false
                vm.handleStorageFile(path?.let { File(it.toString()) })
            },
            fileFilter = ::isAllowedApkFile,
            allowDirectorySelection = false
        )
    }
    val composableScope = rememberCoroutineScope()
    val selectedPatchSummary = remember(bundles, patches, context) {
        if (patches.isEmpty()) return@remember emptyList<AppliedPatchBundleUi>()
        patches.entries.mapNotNull { (bundleUid, patchNames) ->
            if (patchNames.isEmpty()) return@mapNotNull null
            val bundle = bundles.firstOrNull { it.uid == bundleUid }
            val patchInfos = bundle?.patches
                ?.filter { it.name in patchNames }
                ?.distinctBy { it.name }
                ?.sortedBy { it.name }
                ?: emptyList()
            val missingNames = patchNames
                .filterNot { name -> patchInfos.any { it.name == name } }
                .distinct()
                .sorted()
            val fallbackName = if (bundleUid == 0) {
                context.getString(R.string.patches_name_default)
            } else {
                context.getString(R.string.patches_name_fallback)
            }
            val title = bundle?.name ?: "$fallbackName (#$bundleUid)"

            AppliedPatchBundleUi(
                uid = bundleUid,
                title = title,
                version = bundle?.version,
                patchInfos = patchInfos,
                fallbackNames = missingNames,
                bundleAvailable = bundle != null
            )
        }.sortedBy { it.title.lowercase(Locale.ROOT) }
    }
    val selectedPatchOptions = vm.getOptionsFiltered(bundles)
    val launchPatchFlow: () -> Unit = launch@{
        if (selectedPatchCount == 0) {
            context.toast(context.getString(R.string.no_patches_selected))
            return@launch
        }

        composableScope.launch {
            if (patchBundleRepository.selectionHasMixedBundleTypes(patches)) {
                showMixedBundleDialog = true
                return@launch
            }
            if (!vm.hasSetRequiredOptions(patches)) {
                val optionsSnapshot = vm.awaitOptions()
                onRequiredOptions(
                    vm.selectedApp,
                    vm.getCustomPatches(bundles, allowIncompatiblePatches),
                    optionsSnapshot
                )
                return@launch
            }

            if (showPatchSummaryDialogSetting) {
                showPatchSummaryDialog = true
                return@launch
            }

            onPatchClick()
        }
    }

    val error by vm.errorFlow.collectAsStateWithLifecycle(null)
    val profileLaunchState by vm.profileLaunchState.collectAsStateWithLifecycle(null)

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    LaunchedEffect(profileLaunchState, vm.selectedApp) {
        val launchState = profileLaunchState ?: return@LaunchedEffect
        if (!vm.shouldAutoLaunchProfile()) return@LaunchedEffect
        val appSource = vm.selectedApp
        if (appSource is SelectedApp.Search) return@LaunchedEffect
        val autoPatch = vm.shouldAutoPatchProfile()
        vm.markProfileAutoLaunchConsumed()
        if (autoPatch) {
            launchPatchFlow()
        } else {
            onPatchSelectorClick(resolveNavigationVersion(appSource), launchState.selection, launchState.options)
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.app_info),
                scrollBehavior = scrollBehavior,
                onBackClick = onBackClick
            )
        },
        floatingActionButton = {
            if (error != null) return@Scaffold

            HapticExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.patch)) },
                icon = {
                    Icon(
                        Icons.Default.AutoFixHigh,
                        stringResource(R.string.patch)
                    )
                },
                onClick = { launchPatchFlow() }
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        val plugins by vm.plugins.collectAsStateWithLifecycle(emptyList())

        if (vm.showSourceSelector) {
            val requiredVersion by vm.requiredVersion.collectAsStateWithLifecycle(null)
            val effectiveVersion =
                if (bundleTargetsAllVersions && selectedBundleUid != null) null
                else preferredBundleVersion ?: vm.desiredVersion

            AppSourceSelectorDialog(
                plugins = plugins,
                installedApp = vm.installedAppData,
                searchApp = SelectedApp.Search(
                    vm.packageName,
                    effectiveVersion
                ),
                activeSearchJob = vm.activePluginAction,
                hasRoot = vm.hasRoot,
                downloadedApps = downloadedApps,
                includeAutoOption = !vm.sourceSelectionRequired,
                includeInstalledOption = !vm.sourceSelectionRequired,
                requiredVersion = requiredVersion,
                onDismissRequest = vm::dismissSourceSelector,
                onSelectPlugin = vm::searchUsingPlugin,
                onSelectDownloaded = vm::selectDownloadedApp,
                onSelectLocal = vm::requestLocalSelection,
                onSelect = {
                    vm.selectedApp = it
                    vm.dismissSourceSelector()
                }
            )
        }

        ColumnWithScrollbar(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            AppInfo(vm.selectedAppInfo, placeholderLabel = packageName) {
                val versionLabel = when {
                    bundleTargetsAllVersions && selectedBundleUid != null ->
                        stringResource(R.string.bundle_version_all_versions)
                    version != null -> version
                    else -> stringResource(R.string.selected_app_meta_any_version)
                }
                Text(
                    versionLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            PageItem(
                R.string.patch_selector_item,
                stringResource(
                    R.string.patch_selector_item_description,
                    selectedPatchCount
                ),
                onClick = {
                    composableScope.launch {
                        val optionsSnapshot = vm.awaitOptions()
                        onPatchSelectorClick(
                            resolveNavigationVersion(vm.selectedApp),
                            vm.getCustomPatches(
                                bundles,
                                allowIncompatiblePatches
                            ),
                            optionsSnapshot
                        )
                    }
                }
            )
            if (bundleRecommendationDetails.isNotEmpty()) {
                val selectedDetail = bundleRecommendationDetails.firstOrNull { it.bundleUid == selectedBundleUid }
                val overrideVersion = selectedBundleOverride
                val bundleSummary = if (selectedDetail != null) {
                    val versionLabel = when {
                        overrideVersion != null ->
                            stringResource(R.string.version_label, overrideVersion)

                        selectedDetail.recommendedVersion != null ->
                            stringResource(R.string.version_label, selectedDetail.recommendedVersion)

                        selectedDetail.supportsAllVersions ->
                            stringResource(R.string.bundle_version_all_versions)

                        selectedDetail.otherSupportedVersions.isNotEmpty() ->
                            stringResource(R.string.version_label, selectedDetail.otherSupportedVersions.first())

                        else -> stringResource(R.string.bundle_version_no_version)
                    }
                    stringResource(
                        R.string.bundle_version_item_description_selected,
                        selectedDetail.name,
                        versionLabel
                    )
                } else {
                    stringResource(R.string.bundle_version_item_description_default)
                }
                PageItem(
                    R.string.bundle_version_item,
                    bundleSummary,
                    onClick = { showBundleRecommendationDialog = true }
                )
            }
            PageItem(
                R.string.apk_source_selector_item,
                when (val app = vm.selectedApp) {
                    is SelectedApp.Search -> stringResource(R.string.apk_source_auto)
                    is SelectedApp.Installed -> stringResource(R.string.apk_source_installed)
                    is SelectedApp.Download -> stringResource(
                        R.string.apk_source_downloader,
                        plugins.find { it.packageName == app.data.pluginPackageName }?.name
                            ?: app.data.pluginPackageName
                    )

                    is SelectedApp.Local ->
                        if (app.temporary)
                            stringResource(R.string.apk_source_local)
                        else
                            stringResource(R.string.apk_source_downloaded)
                },
                onClick = {
                    vm.showSourceSelector()
                }
            )
            error?.let {
                Text(
                    stringResource(it.resourceId),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }

            Column(
                modifier = Modifier.padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val needsInternet =
                    vm.selectedApp.let { it is SelectedApp.Search || it is SelectedApp.Download }

                when {
                    !needsInternet -> {}
                    !networkConnected -> {
                        NotificationCard(
                            isWarning = true,
                            icon = Icons.Outlined.WarningAmber,
                            text = stringResource(R.string.network_unavailable_warning),
                            onDismiss = null
                        )
                    }

                    networkMetered -> {
                        NotificationCard(
                            isWarning = true,
                            icon = Icons.Outlined.WarningAmber,
                            text = stringResource(R.string.network_metered_warning),
                            onDismiss = null
                        )
                    }
                }
            }
        }
    }

    if (showBundleRecommendationDialog && bundleRecommendationDetails.isNotEmpty()) {
        BundleVersionSelectionDialog(
            details = bundleRecommendationDetails,
            selectedUid = selectedBundleUid,
            selectedOverride = selectedBundleOverride,
            recommendationsEnabled = bundleRecommendationsEnabled,
            onSelect = { uid, override, targetsAllVersions ->
                vm.selectBundleRecommendation(uid, override, targetsAllVersions)
            },
            onDismissRequest = { showBundleRecommendationDialog = false }
        )
    }

    if (showMixedBundleDialog) {
        AlertDialogExtended(
            onDismissRequest = { showMixedBundleDialog = false },
            confirmButton = {
                TextButton(onClick = { showMixedBundleDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            },
            title = { Text(stringResource(R.string.mixed_patch_bundles_title)) },
            text = { Text(stringResource(R.string.mixed_patch_bundles_description)) }
        )
    }

    if (showPatchSummaryDialog) {
        AppliedPatchesDialog(
            bundles = selectedPatchSummary,
            onDismissRequest = { showPatchSummaryDialog = false },
            titleRes = R.string.patch_confirmation_title,
            sectionHeaderRes = R.string.selected_patches_title,
            sectionSubtitleRes = R.string.patch_confirmation_subtitle,
            optionsByBundle = selectedPatchOptions,
            confirmTextRes = R.string.continue_,
            onConfirm = {
                showPatchSummaryDialog = false
                onPatchClick()
            }
        )
    }
}

@Composable
private fun BundleVersionSelectionDialog(
    details: List<BundleRecommendationDetail>,
    selectedUid: Int?,
    selectedOverride: String?,
    recommendationsEnabled: Boolean,
    onSelect: (Int?, String?, Boolean) -> Unit,
    onDismissRequest: () -> Unit
) {
    var otherVersionsTarget by rememberSaveable { mutableStateOf<BundleRecommendationDetail?>(null) }

    AlertDialogExtended(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.close))
            }
        },
        title = { Text(stringResource(R.string.bundle_version_dialog_title)) },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                item(key = "description") {
                    Text(
                        stringResource(R.string.bundle_version_dialog_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
                item(key = "default") {
                    BundleVersionOptionRow(
                        title = stringResource(R.string.bundle_version_dialog_default),
                        subtitle = stringResource(R.string.bundle_version_dialog_default_subtitle),
                        selected = selectedUid == null,
                        onClick = { onSelect(null, null, false) },
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
                if (!recommendationsEnabled) {
                    item(key = "locked_hint") {
                        SafeguardHintCard(
                            title = stringResource(R.string.bundle_version_dialog_locked_title),
                            description = stringResource(R.string.bundle_version_dialog_locked_hint),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                        )
                    }
                }
                items(
                    items = details,
                    key = { it.bundleUid }
                ) { detail ->
                    val isSelectedBundle = selectedUid == detail.bundleUid
                    BundleRecommendationCard(
                        detail = detail,
                        isActive = isSelectedBundle,
                        selectedOverride = if (isSelectedBundle) selectedOverride else null,
                        enabled = recommendationsEnabled,
                        onSelectRecommended = { useAllVersions ->
                            onSelect(detail.bundleUid, null, useAllVersions)
                        },
                        onShowOtherVersions = {
                            if (recommendationsEnabled) {
                                otherVersionsTarget = detail
                            }
                        }
                    )
                }
            }
        }
    )

    val targetDetail = if (recommendationsEnabled) otherVersionsTarget else null
    if (targetDetail != null) {
        OtherSupportedVersionsSelectionDialog(
            detail = targetDetail,
            selectedOverride = if (selectedUid == targetDetail.bundleUid) selectedOverride else null,
            onSelect = { version ->
                onSelect(targetDetail.bundleUid, version, false)
                otherVersionsTarget = null
            },
            onDismissRequest = { otherVersionsTarget = null }
        )
    }

}

@Composable
private fun BundleRecommendationCard(
    detail: BundleRecommendationDetail,
    isActive: Boolean,
    selectedOverride: String?,
    enabled: Boolean,
    onSelectRecommended: (Boolean) -> Unit,
    onShowOtherVersions: () -> Unit
) {
    val containerColor = if (isActive) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isActive) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .alpha(if (enabled) 1f else 0.5f),
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        tonalElevation = if (isActive) 6.dp else 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val nameScrollState = rememberScrollState()
                Text(
                    detail.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .consumeHorizontalScroll(nameScrollState)
                        .horizontalScroll(nameScrollState)
                )
                if (isActive && selectedOverride == null) {
                    SelectionBadge(
                        text = stringResource(R.string.bundle_version_dialog_selected_indicator),
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        contentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }

            val recommendedLabel = when {
                detail.recommendedVersion != null ->
                    stringResource(R.string.version_label, detail.recommendedVersion)
                detail.supportsAllVersions ->
                    stringResource(R.string.bundle_version_all_versions)
                else -> stringResource(R.string.bundle_version_no_version)
            }

            BundleVersionOptionRow(
                title = stringResource(R.string.bundle_version_dialog_recommended, recommendedLabel),
                subtitle = null,
                selected = isActive && selectedOverride == null,
                enabled = enabled,
                onClick = {
                    val useAllVersions =
                        detail.supportsAllVersions && detail.recommendedVersion == null
                    onSelectRecommended(useAllVersions)
                },
                modifier = Modifier.fillMaxWidth()
            )

            if (selectedOverride != null) {
                SelectionBadge(
                    text = stringResource(
                        R.string.bundle_version_dialog_selected_override,
                        stringResource(R.string.version_label, selectedOverride)
                    ),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.align(Alignment.Start)
                )
            }

            when {
                detail.otherSupportedVersions.isNotEmpty() -> {
                    TextButton(
                        onClick = onShowOtherVersions,
                        enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 0.dp),
                        modifier = Modifier.align(Alignment.Start)
                    ) {
                        Text(
                            stringResource(R.string.show_other_versions),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                detail.supportsAllVersions -> {
                    Text(
                        stringResource(R.string.other_supported_versions_all),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OtherSupportedVersionsSelectionDialog(
    detail: BundleRecommendationDetail,
    selectedOverride: String?,
    onSelect: (String?) -> Unit,
    onDismissRequest: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.close))
            }
        },
        title = { Text(stringResource(R.string.other_supported_versions_title, detail.name)) },
        text = {
            when {
                detail.otherSupportedVersions.isNotEmpty() -> {
                    val context = LocalContext.current

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            stringResource(R.string.bundle_version_dialog_other_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            detail.otherSupportedVersions.forEach { version ->
                                val label = context.getString(R.string.version_label, version)
                                FilterChip(
                                    selected = selectedOverride == version,
                                    onClick = { onSelect(version) },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                }
                detail.supportsAllVersions -> {
                    Text(
                        stringResource(R.string.other_supported_versions_all),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                else -> {
                    Text(
                        stringResource(R.string.other_supported_versions_empty),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    )
}

@Composable
private fun BundleVersionOptionRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val primaryContentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val secondaryContentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    ElevatedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = containerColor,
            contentColor = primaryContentColor
        ),
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = if (selected) 6.dp else 2.dp,
            pressedElevation = if (selected) 8.dp else 4.dp
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            RadioButton(
                selected = selected,
                enabled = enabled,
                onClick = null,
                colors = RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.primary,
                    unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = primaryContentColor)
                subtitle?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondaryContentColor
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionBadge(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
    contentColor: Color = MaterialTheme.colorScheme.primary
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun PageItem(@StringRes title: Int, description: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(start = 8.dp),
        headlineContent = {
            Text(
                stringResource(title),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge
            )
        },
        supportingContent = {
            Text(
                description,
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        trailingContent = {
            Icon(Icons.AutoMirrored.Outlined.ArrowRight, null)
        }
    )
}

@Composable
private fun AppSourceSelectorDialog(
    plugins: List<LoadedDownloaderPlugin>,
    installedApp: Pair<SelectedApp.Installed, InstalledApp?>?,
    searchApp: SelectedApp.Search,
    activeSearchJob: String?,
    hasRoot: Boolean,
    downloadedApps: List<DownloadedApp>,
    includeAutoOption: Boolean = true,
    includeInstalledOption: Boolean = true,
    requiredVersion: String?,
    onDismissRequest: () -> Unit,
    onSelectPlugin: (LoadedDownloaderPlugin) -> Unit,
    onSelectDownloaded: (DownloadedApp) -> Unit,
    onSelectLocal: (() -> Unit)?,
    onSelect: (SelectedApp) -> Unit,
) {
    val canSelect = activeSearchJob == null
    var showDownloadedApps by remember { mutableStateOf(false) }

    AlertDialogExtended(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = { Text(stringResource(R.string.app_source_dialog_title)) },
        textHorizontalPadding = PaddingValues(horizontal = 0.dp),
        text = {
            LazyColumn {
                if (downloadedApps.isNotEmpty()) {
                    item(key = "downloaded_header") {
                        val icon = if (showDownloadedApps) Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore
                        ListItem(
                            modifier = Modifier
                                .clickable { showDownloadedApps = !showDownloadedApps }
                                .enabled(downloadedApps.isNotEmpty()),
                            headlineContent = { Text(stringResource(R.string.downloaded_apps)) },
                            trailingContent = { Icon(icon, null) },
                            colors = transparentListItemColors
                        )
                    }
                    if (showDownloadedApps) {
                        items(
                            items = downloadedApps,
                            key = { "downloaded_${it.packageName}_${it.version}" }
                        ) { downloadedApp ->
                            ListItem(
                                modifier = Modifier
                                    .clickable(enabled = canSelect) { onSelectDownloaded(downloadedApp) }
                                    .padding(start = 16.dp),
                                headlineContent = { Text(downloadedApp.version) },
                                supportingContent = { Text(downloadedApp.packageName) },
                                colors = transparentListItemColors
                            )
                        }
                    }
                }

                if (includeAutoOption) {
                    item(key = "auto") {
                        val hasPlugins = plugins.isNotEmpty()
                        ListItem(
                            modifier = Modifier
                                .clickable(enabled = canSelect && hasPlugins) { onSelect(searchApp) }
                                .enabled(hasPlugins),
                            headlineContent = { Text(stringResource(R.string.app_source_dialog_option_auto)) },
                            supportingContent = {
                                Text(
                                    if (hasPlugins)
                                        stringResource(R.string.app_source_dialog_option_auto_description)
                                    else
                                        stringResource(R.string.app_source_dialog_option_auto_unavailable)
                                )
                            },
                            colors = transparentListItemColors
                        )
                    }
                }

                if (includeInstalledOption) installedApp?.let { (app, meta) ->
                    item(key = "installed") {
                        val (usable, text) = when {
                            meta?.installType == InstallType.MOUNT && !hasRoot -> false to stringResource(
                                R.string.app_source_dialog_option_installed_no_root
                            )
                            meta?.installType == InstallType.DEFAULT || meta?.installType == InstallType.CUSTOM ->
                                false to stringResource(R.string.already_patched)
                            requiredVersion != null && app.version != requiredVersion -> false to stringResource(
                                R.string.app_source_dialog_option_installed_version_not_suggested,
                                app.version
                            )

                            else -> true to app.version
                        }
                        ListItem(
                            modifier = Modifier
                                .clickable(enabled = canSelect && usable) { onSelect(app) }
                                .enabled(usable),
                            headlineContent = { Text(stringResource(R.string.installed)) },
                            supportingContent = { Text(text) },
                            colors = transparentListItemColors
                        )
                    }
                }

                onSelectLocal?.let { selectLocal ->
                    item(key = "storage") {
                        ListItem(
                            modifier = Modifier.clickable(enabled = canSelect) { selectLocal() },
                            headlineContent = { Text(stringResource(R.string.app_source_dialog_option_storage)) },
                            supportingContent = {
                                Text(stringResource(R.string.app_source_dialog_option_storage_description))
                            },
                            colors = transparentListItemColors
                        )
                    }
                }

                items(plugins, key = { "plugin_${it.packageName}" }) { plugin ->
                    ListItem(
                        modifier = Modifier.clickable(enabled = canSelect) { onSelectPlugin(plugin) },
                        headlineContent = { Text(plugin.name) },
                        trailingContent = (@Composable { LoadingIndicator() }).takeIf { activeSearchJob == plugin.packageName },
                        colors = transparentListItemColors
                    )
                }
            }
        }
    )
}
