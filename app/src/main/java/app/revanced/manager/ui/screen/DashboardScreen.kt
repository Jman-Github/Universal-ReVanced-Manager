package app.revanced.manager.ui.screen

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Source
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.revanced.manager.R
import app.revanced.manager.data.platform.Filesystem
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.patcher.aapt.Aapt
import app.revanced.manager.ui.component.AlertDialogExtended
import app.revanced.manager.ui.component.AppTopBar
import app.revanced.manager.ui.component.AutoUpdatesDialog
import app.revanced.manager.ui.component.AvailableUpdateDialog
import app.revanced.manager.ui.component.DownloadProgressBanner
import app.revanced.manager.ui.component.NotificationCard
import app.revanced.manager.ui.component.ConfirmDialog
import app.revanced.manager.ui.component.bundle.BundleTopBar
import app.revanced.manager.ui.component.bundle.ImportPatchBundleDialog
import app.revanced.manager.ui.component.haptics.HapticFloatingActionButton
import app.revanced.manager.ui.component.haptics.HapticTab
import app.revanced.manager.ui.component.patches.PathSelectorDialog
import app.revanced.manager.ui.viewmodel.DashboardViewModel
import app.revanced.manager.ui.model.SelectedApp
import app.revanced.manager.ui.viewmodel.PatchProfileLaunchData
import app.revanced.manager.ui.viewmodel.PatchProfilesViewModel
import app.revanced.manager.domain.repository.PatchBundleRepository
import app.revanced.manager.domain.repository.PatchBundleRepository.BundleUpdatePhase
import app.revanced.manager.domain.repository.PatchBundleRepository.BundleImportPhase
import app.revanced.manager.ui.viewmodel.InstalledAppsViewModel
import app.revanced.manager.ui.viewmodel.AppSelectorViewModel
import app.revanced.manager.util.RequestInstallAppsContract
import app.revanced.manager.util.BundleDeepLink
import app.revanced.manager.util.EventEffect
import app.revanced.manager.util.isAllowedApkFile
import app.revanced.manager.util.isAllowedPatchBundleFile
import app.revanced.manager.util.toast
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

enum class DashboardPage(
    val titleResId: Int,
    val icon: ImageVector
) {
    DASHBOARD(R.string.tab_apps, Icons.Outlined.Apps),
    BUNDLES(R.string.tab_patches, Icons.Outlined.Source),
    PROFILES(R.string.tab_profiles, Icons.Outlined.Bookmarks),
}

@SuppressLint("BatteryLife")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    vm: DashboardViewModel = koinViewModel(),
    onAppSelectorClick: () -> Unit,
    onStorageSelect: (SelectedApp.Local) -> Unit,
    onSettingsClick: () -> Unit,
    onUpdateClick: () -> Unit,
    onDownloaderPluginClick: () -> Unit,
    onBundleDiscoveryClick: () -> Unit,
    onAppClick: (String) -> Unit,
    onProfileLaunch: (PatchProfileLaunchData) -> Unit,
    bundleDeepLink: BundleDeepLink? = null,
    onBundleDeepLinkConsumed: () -> Unit = {}
) {
    val installedAppsViewModel: InstalledAppsViewModel = koinViewModel()
    val patchProfilesViewModel: PatchProfilesViewModel = koinViewModel()
    val patchBundleRepository: PatchBundleRepository = koinInject()
    val installedApps by installedAppsViewModel.apps.collectAsStateWithLifecycle(initialValue = emptyList())
    val profiles by patchProfilesViewModel.profiles.collectAsStateWithLifecycle(emptyList())
    val bundleSources by patchBundleRepository.sources.collectAsStateWithLifecycle(emptyList())
    var appsSearchActive by rememberSaveable { mutableStateOf(false) }
    var appsSearchQuery by rememberSaveable { mutableStateOf("") }
    var bundlesSearchActive by rememberSaveable { mutableStateOf(false) }
    var bundlesSearchQuery by rememberSaveable { mutableStateOf("") }
    var profilesSearchActive by rememberSaveable { mutableStateOf(false) }
    var profilesSearchQuery by rememberSaveable { mutableStateOf("") }
    var selectedSourceCount by rememberSaveable { mutableIntStateOf(0) }
    var selectedSourcesHasEnabled by rememberSaveable { mutableStateOf(true) }
    val bundlesSelectable by remember { derivedStateOf { selectedSourceCount > 0 } }
    val selectedProfileCount by remember { derivedStateOf { patchProfilesViewModel.selectedProfiles.size } }
    val profilesSelectable = selectedProfileCount > 0
    val availablePatches by vm.availablePatches.collectAsStateWithLifecycle(0)
    val showNewDownloaderPluginsNotification by vm.newDownloaderPluginsAvailable.collectAsStateWithLifecycle(
        false
    )
    val storageVm: AppSelectorViewModel = koinViewModel()
    val fs = koinInject<Filesystem>()
    val prefs: PreferencesManager = koinInject()
    val savedAppsEnabled by prefs.enableSavedApps.getAsState()
    val exportFormat by prefs.patchedAppExportFormat.getAsState()
    val storageRoots = remember { fs.storageRoots() }
    EventEffect(flow = storageVm.storageSelectionFlow) { selected ->
        onStorageSelect(selected)
    }
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
    val bundleUpdateProgress by vm.bundleUpdateProgress.collectAsStateWithLifecycle(null)
    val bundleImportProgress by vm.bundleImportProgress.collectAsStateWithLifecycle(null)
    val androidContext = LocalContext.current
    val composableScope = rememberCoroutineScope()
    var showBundleOrderDialog by rememberSaveable { mutableStateOf(false) }
    var showAppsOrderDialog by rememberSaveable { mutableStateOf(false) }
    var showProfilesOrderDialog by rememberSaveable { mutableStateOf(false) }
    val pagerState = rememberPagerState(
        initialPage = DashboardPage.DASHBOARD.ordinal,
        initialPageOffsetFraction = 0f
    ) { DashboardPage.entries.size }
    var highlightBundleUid by rememberSaveable { mutableStateOf<Int?>(null) }
    val appsSelectionActive = installedAppsViewModel.selectedApps.isNotEmpty()
    val selectedAppCount = installedAppsViewModel.selectedApps.size

    var showBundleFilePicker by rememberSaveable { mutableStateOf(false) }
    var selectedBundlePath by rememberSaveable { mutableStateOf<String?>(null) }
    val (bundlePermissionContract, bundlePermissionName) = remember { fs.permissionContract() }
    val bundlePermissionLauncher =
        rememberLauncherForActivityResult(bundlePermissionContract) { granted ->
            if (granted) {
                showBundleFilePicker = true
            }
        }
    fun requestBundleFilePicker() {
        if (fs.hasStoragePermission()) {
            showBundleFilePicker = true
        } else {
            bundlePermissionLauncher.launch(bundlePermissionName)
        }
    }

    var showSavedAppsExportPicker by rememberSaveable { mutableStateOf(false) }
    var savedAppsExportInProgress by rememberSaveable { mutableStateOf(false) }
    val (exportPermissionContract, exportPermissionName) = remember { fs.permissionContract() }
    val exportPermissionLauncher =
        rememberLauncherForActivityResult(exportPermissionContract) { granted ->
            if (granted) {
                showSavedAppsExportPicker = true
            }
        }
    fun requestSavedAppsExportPicker() {
        if (fs.hasStoragePermission()) {
            showSavedAppsExportPicker = true
        } else {
            exportPermissionLauncher.launch(exportPermissionName)
        }
    }

    var bundlesFabCollapsed by rememberSaveable { mutableStateOf(false) }

    val dashboardSidePadding = 16.dp

    @Composable
    fun BundleProgressBanner(modifier: Modifier = Modifier) {
        var importCollapsed by rememberSaveable { mutableStateOf(false) }
        var updateCollapsed by rememberSaveable { mutableStateOf(false) }
        val bannerSizeSpec = tween<IntSize>(durationMillis = 260, easing = FastOutSlowInEasing)
        val bannerOffsetSpec = tween<IntOffset>(durationMillis = 260, easing = FastOutSlowInEasing)
        val bannerExitAlphaSpec = tween<Float>(durationMillis = 240, easing = FastOutSlowInEasing)
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            LaunchedEffect(bundleImportProgress != null) {
                if (bundleImportProgress != null) importCollapsed = false
            }
            AnimatedVisibility(
                visible = bundleImportProgress != null,
                enter = fadeIn(animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)) +
                    expandVertically(expandFrom = Alignment.Top, animationSpec = bannerSizeSpec) +
                    slideInVertically(
                        initialOffsetY = { height -> -height / 3 },
                        animationSpec = bannerOffsetSpec
                    ),
                exit = fadeOut(animationSpec = bannerExitAlphaSpec) +
                    shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = bannerSizeSpec) +
                    slideOutVertically(
                        targetOffsetY = { height -> -height / 3 },
                        animationSpec = bannerOffsetSpec
                    )
            ) {
                bundleImportProgress?.let { progress ->
                    val context = LocalContext.current
                    val subtitleParts = buildList {
                        val total = progress.total.coerceAtLeast(1)
                        val stepLabel = if (progress.isStepBased) {
                            val step = (progress.processed + 1).coerceAtMost(total)
                            stringResource(R.string.import_patch_bundles_banner_steps, step, total)
                        } else {
                            stringResource(R.string.import_patch_bundles_banner_subtitle, progress.processed, total)
                        }
                        add(stepLabel)
                        val name = progress.currentBundleName?.takeIf { it.isNotBlank() } ?: return@buildList
                        val phaseText = if (progress.isStepBased) {
                            when (progress.phase) {
                                BundleImportPhase.Downloading ->
                                    stringResource(R.string.bundle_import_phase_copying)
                                BundleImportPhase.Processing ->
                                    stringResource(R.string.bundle_import_phase_writing)
                                BundleImportPhase.Finalizing ->
                                    stringResource(R.string.bundle_import_phase_finalizing)
                            }
                        } else {
                            when (progress.phase) {
                                BundleImportPhase.Processing ->
                                    stringResource(R.string.bundle_import_phase_processing)
                                BundleImportPhase.Downloading ->
                                    stringResource(R.string.bundle_import_phase_downloading)
                                BundleImportPhase.Finalizing ->
                                    stringResource(R.string.bundle_import_phase_finalizing_short)
                            }
                        }
                        val detail = buildString {
                            append(phaseText)
                            append(": ")
                            append(name)
                            if (progress.bytesTotal?.takeIf { it > 0L } != null) {
                                append(" (")
                                append(Formatter.formatShortFileSize(context, progress.bytesRead))
                                progress.bytesTotal?.takeIf { it > 0L }?.let { total ->
                                    append("/")
                                    append(Formatter.formatShortFileSize(context, total))
                                }
                                append(")")
                            }
                        }
                        add(detail)
                    }
                    DownloadProgressBanner(
                        title = stringResource(R.string.import_patch_bundles_banner_title),
                        subtitle = subtitleParts.joinToString(" - "),
                        progress = progress.ratio,
                        collapsed = importCollapsed,
                        onToggleCollapsed = { importCollapsed = !importCollapsed },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = dashboardSidePadding, vertical = 8.dp)
                    )
                }
            }

            LaunchedEffect(bundleUpdateProgress != null) {
                if (bundleUpdateProgress != null) updateCollapsed = false
            }
            AnimatedVisibility(
                visible = bundleUpdateProgress != null,
                enter = fadeIn(animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)) +
                    expandVertically(expandFrom = Alignment.Top, animationSpec = bannerSizeSpec) +
                    slideInVertically(
                        initialOffsetY = { height -> -height / 3 },
                        animationSpec = bannerOffsetSpec
                    ),
                exit = fadeOut(animationSpec = bannerExitAlphaSpec) +
                    shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = bannerSizeSpec) +
                    slideOutVertically(
                        targetOffsetY = { height -> -height / 3 },
                        animationSpec = bannerOffsetSpec
                    )
            ) {
                bundleUpdateProgress?.let { progress ->
                    val context = LocalContext.current
                    val perBundleFraction = progress.bytesTotal
                        ?.takeIf { it > 0L }
                        ?.let { total -> (progress.bytesRead.toFloat() / total).coerceIn(0f, 1f) }

                    val progressFraction: Float? = when {
                        progress.total == 0 -> 0f
                        progress.phase == BundleUpdatePhase.Downloading && perBundleFraction != null ->
                            ((progress.completed.toFloat() + perBundleFraction) / progress.total).coerceIn(0f, 1f)

                        else -> (progress.completed.toFloat() / progress.total).coerceIn(0f, 1f)
                    }

                    val subtitleParts = buildList {
                        add(
                            stringResource(
                                R.string.bundle_update_progress,
                                progress.completed,
                                progress.total
                            )
                        )
                        val name = progress.currentBundleName?.takeIf { it.isNotBlank() } ?: return@buildList
                        val phaseText = when (progress.phase) {
                            BundleUpdatePhase.Checking ->
                                stringResource(R.string.bundle_update_phase_checking)
                            BundleUpdatePhase.Downloading ->
                                stringResource(R.string.bundle_update_phase_downloading)
                            BundleUpdatePhase.Finalizing ->
                                stringResource(R.string.bundle_update_phase_finalizing)
                        }

                        val detail = buildString {
                            append(phaseText)
                            append(": ")
                            append(name)
                            if (progress.phase == BundleUpdatePhase.Downloading && progress.bytesRead > 0L) {
                                append(" (")
                                append(Formatter.formatShortFileSize(context, progress.bytesRead))
                                progress.bytesTotal?.takeIf { it > 0L }?.let { total ->
                                    append("/")
                                    append(Formatter.formatShortFileSize(context, total))
                                }
                                append(")")
                            }
                        }
                        add(detail)
                    }
                    DownloadProgressBanner(
                        title = stringResource(R.string.bundle_update_banner_title),
                        subtitle = subtitleParts.joinToString(" - "),
                        progress = progressFraction,
                        collapsed = updateCollapsed,
                        onToggleCollapsed = { updateCollapsed = !updateCollapsed },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = dashboardSidePadding, vertical = 8.dp)
                    )
                }
            }
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != DashboardPage.DASHBOARD.ordinal) {
            installedAppsViewModel.clearSelection()
            showAppsOrderDialog = false
        }
        if (pagerState.currentPage != DashboardPage.BUNDLES.ordinal) {
            vm.cancelSourceSelection()
            showBundleOrderDialog = false
        }
        if (pagerState.currentPage != DashboardPage.PROFILES.ordinal) {
            patchProfilesViewModel.handleEvent(PatchProfilesViewModel.Event.CANCEL)
            showProfilesOrderDialog = false
        }
    }

    LaunchedEffect(bundleDeepLink) {
        val deepLink = bundleDeepLink ?: return@LaunchedEffect
        highlightBundleUid = deepLink.bundleUid
        try {
            if (pagerState.currentPage != DashboardPage.BUNDLES.ordinal) {
                runCatching {
                    pagerState.animateScrollToPage(DashboardPage.BUNDLES.ordinal)
                }.onFailure {
                    pagerState.scrollToPage(DashboardPage.BUNDLES.ordinal)
                }
            }
        } finally {
            onBundleDeepLinkConsumed()
        }
    }

    val firstLaunch by vm.prefs.firstLaunch.getAsState()
    if (firstLaunch) AutoUpdatesDialog(vm::applyAutoUpdatePrefs)

    if (showStorageDialog) {
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                showStorageDialog = false
                path?.let { storageVm.handleStorageFile(File(it.toString())) }
            },
            fileFilter = ::isAllowedApkFile,
            allowDirectorySelection = false
        )
    }
    if (showBundleFilePicker) {
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                showBundleFilePicker = false
                path?.let { selectedBundlePath = it.toString() }
            },
            fileFilter = ::isAllowedPatchBundleFile,
            allowDirectorySelection = false
        )
    }
    if (showSavedAppsExportPicker) {
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                if (path == null) {
                    showSavedAppsExportPicker = false
                }
            },
            fileFilter = { false },
            allowDirectorySelection = true,
            confirmButtonText = stringResource(R.string.save),
            onConfirm = { selection ->
                showSavedAppsExportPicker = false
                val exportDirectory = if (Files.isDirectory(selection)) {
                    selection
                } else {
                    selection.parent ?: selection
                }
                savedAppsExportInProgress = true
                installedAppsViewModel.exportSelectedSavedAppsToDirectory(
                    androidContext,
                    exportDirectory,
                    exportFormat
                ) { result ->
                    savedAppsExportInProgress = false
                    when {
                        result.total == 0 -> androidContext.toast(
                            androidContext.getString(R.string.saved_apps_export_empty)
                        )
                        result.exported > 0 -> androidContext.toast(
                            androidContext.getString(
                                R.string.saved_apps_export_success,
                                result.exported
                            )
                        )
                        else -> androidContext.toast(
                            androidContext.getString(R.string.saved_apps_export_failed)
                        )
                    }
                }
            }
        )
    }
    if (savedAppsExportInProgress) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.export)) },
            text = { Text(stringResource(R.string.patcher_step_group_saving)) },
            icon = { CircularProgressIndicator() },
            confirmButton = {},
            dismissButton = {}
        )
    }

    var showAddBundleDialog by rememberSaveable { mutableStateOf(false) }
    if (showAddBundleDialog) {
        ImportPatchBundleDialog(
            onDismiss = { showAddBundleDialog = false },
            onLocalSubmit = { path ->
                showAddBundleDialog = false
                selectedBundlePath = null
                vm.createLocalSourceFromFile(path)
            },
            onRemoteSubmit = { url, autoUpdate, searchUpdate ->
                showAddBundleDialog = false
                vm.createRemoteSource(url, autoUpdate, searchUpdate)
            },
            onLocalPick = {
                requestBundleFilePicker()
            },
            selectedLocalPath = selectedBundlePath
        )
    }

    var showUpdateDialog by rememberSaveable { mutableStateOf(vm.prefs.showManagerUpdateDialogOnLaunch.getBlocking()) }
    val availableUpdate by remember {
        derivedStateOf { vm.updatedManagerVersion.takeIf { showUpdateDialog } }
    }

    availableUpdate?.let { version ->
        AvailableUpdateDialog(
            onDismiss = { showUpdateDialog = false },
            setShowManagerUpdateDialogOnLaunch = vm::setShowManagerUpdateDialogOnLaunch,
            onConfirm = onUpdateClick,
            newVersion = version
        )
    }

    var showAndroid11Dialog by rememberSaveable { mutableStateOf(false) }
    var pendingAppInputAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val installAppsPermissionLauncher =
        rememberLauncherForActivityResult(RequestInstallAppsContract) { granted ->
            showAndroid11Dialog = false
            if (granted) {
                (pendingAppInputAction ?: onAppSelectorClick)()
                pendingAppInputAction = null
            }
        }
    if (showAndroid11Dialog) Android11Dialog(
        onDismissRequest = {
            showAndroid11Dialog = false
            pendingAppInputAction = null
        },
        onContinue = {
            installAppsPermissionLauncher.launch(androidContext.packageName)
        }
    )

    fun attemptAppInput(action: () -> Unit) {
        pendingAppInputAction = null
        vm.cancelSourceSelection()
        installedAppsViewModel.clearSelection()
        patchProfilesViewModel.handleEvent(PatchProfilesViewModel.Event.CANCEL)

        if (availablePatches < 1) {
            androidContext.toast(androidContext.getString(R.string.no_patch_found))
            composableScope.launch {
                pagerState.animateScrollToPage(DashboardPage.BUNDLES.ordinal)
            }
            return
        }

        if (vm.android11BugActive) {
            pendingAppInputAction = action
            showAndroid11Dialog = true
            return
        }

        action()
    }

    var showDeleteSavedAppsDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirmationDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteProfilesConfirmationDialog by rememberSaveable { mutableStateOf(false) }
    if (showDeleteSavedAppsDialog) {
        ConfirmDialog(
            onDismiss = { showDeleteSavedAppsDialog = false },
            onConfirm = {
                installedAppsViewModel.deleteSelectedApps()
                showDeleteSavedAppsDialog = false
            },
            title = stringResource(R.string.delete),
            description = stringResource(R.string.selected_apps_delete_dialog_description),
            icon = Icons.Outlined.Delete
        )
    }
    if (showDeleteConfirmationDialog) {
        ConfirmDialog(
            onDismiss = { showDeleteConfirmationDialog = false },
            onConfirm = {
                vm.deleteSources()
                showDeleteConfirmationDialog = false
            },
            title = stringResource(R.string.delete),
            description = stringResource(R.string.patches_delete_multiple_dialog_description),
            icon = Icons.Outlined.Delete
        )
    }
    if (showDeleteProfilesConfirmationDialog) {
        ConfirmDialog(
            onDismiss = { showDeleteProfilesConfirmationDialog = false },
            onConfirm = {
                patchProfilesViewModel.handleEvent(PatchProfilesViewModel.Event.DELETE_SELECTED)
                showDeleteProfilesConfirmationDialog = false
            },
            title = stringResource(R.string.delete),
            description = stringResource(R.string.patch_profile_delete_multiple_dialog_description),
            icon = Icons.Outlined.Delete
        )
    }

    Scaffold(
        topBar = {
            when {
                appsSelectionActive && pagerState.currentPage == DashboardPage.DASHBOARD.ordinal -> {
                        BundleTopBar(
                            title = stringResource(R.string.selected_apps_count, selectedAppCount),
                            onBackClick = installedAppsViewModel::clearSelection,
                            backIcon = {
                                Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.back)
                            )
                            },
                            actions = {
                            IconButton(
                                onClick = { requestSavedAppsExportPicker() }
                            ) {
                                Icon(
                                    Icons.Outlined.Save,
                                    stringResource(R.string.export)
                                )
                            }
                            IconButton(
                                onClick = { showDeleteSavedAppsDialog = true }
                            ) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    stringResource(R.string.delete)
                                )
                            }
                        }
                    )
                }

                bundlesSelectable -> {
                    BundleTopBar(
                        title = stringResource(R.string.patches_selected, selectedSourceCount),
                        onBackClick = vm::cancelSourceSelection,
                        backIcon = {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.back)
                            )
                        },
                        actions = {
                            IconButton(
                                onClick = {
                                    showDeleteConfirmationDialog = true
                                }
                            ) {
                                Icon(
                                    Icons.Outlined.DeleteOutline,
                                    stringResource(R.string.delete)
                                )
                            }
                            IconButton(
                                onClick = {
                                    vm.disableSources()
                                    vm.cancelSourceSelection()
                                }
                              ) {
                                  Icon(
                                      if (selectedSourcesHasEnabled) Icons.Outlined.Block else Icons.Outlined.CheckCircle,
                                      stringResource(if (selectedSourcesHasEnabled) R.string.disable else R.string.enable)
                                  )
                              }
                            IconButton(
                                onClick = vm::updateSources
                            ) {
                                Icon(
                                    Icons.Outlined.Refresh,
                                    stringResource(R.string.refresh)
                                )
                            }
                        }
                    )
                }

                profilesSelectable -> {
                    BundleTopBar(
                        title = stringResource(R.string.patch_profiles_selected, selectedProfileCount),
                        onBackClick = { patchProfilesViewModel.handleEvent(PatchProfilesViewModel.Event.CANCEL) },
                        backIcon = {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.back)
                            )
                        },
                        actions = {
                            IconButton(
                                onClick = { showDeleteProfilesConfirmationDialog = true }
                            ) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    stringResource(R.string.delete)
                                )
                            }
                        }
                    )
                }

                else -> {
                    AppTopBar(
                        title = { Text(stringResource(R.string.main_top_title)) },
                        actions = {
                            if (!vm.updatedManagerVersion.isNullOrEmpty()) {
                                IconButton(
                                    onClick = onUpdateClick,
                                ) {
                                    BadgedBox(
                                        badge = {
                                            Badge(modifier = Modifier.size(6.dp))
                                        }
                                    ) {
                                        Icon(Icons.Outlined.Update, stringResource(R.string.update))
                                    }
                                }
                            }
                            val isAppsTab = pagerState.currentPage == DashboardPage.DASHBOARD.ordinal
                            val isBundlesTab = pagerState.currentPage == DashboardPage.BUNDLES.ordinal
                            val isProfilesTab = pagerState.currentPage == DashboardPage.PROFILES.ordinal
                            val searchActive = when {
                                isAppsTab -> appsSearchActive
                                isBundlesTab -> bundlesSearchActive
                                isProfilesTab -> profilesSearchActive
                                else -> false
                            }
                            if (isAppsTab || isBundlesTab || isProfilesTab) {
                                IconButton(
                                    onClick = {
                                        when {
                                            isAppsTab -> {
                                                appsSearchActive = !appsSearchActive
                                                if (!appsSearchActive) appsSearchQuery = ""
                                            }
                                            isBundlesTab -> {
                                                bundlesSearchActive = !bundlesSearchActive
                                                if (!bundlesSearchActive) bundlesSearchQuery = ""
                                            }
                                            isProfilesTab -> {
                                                profilesSearchActive = !profilesSearchActive
                                                if (!profilesSearchActive) profilesSearchQuery = ""
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (searchActive) Icons.Outlined.Close else Icons.Outlined.Search,
                                        contentDescription = stringResource(if (searchActive) R.string.close else R.string.search)
                                    )
                                }
                            }
                            if (pagerState.currentPage == DashboardPage.BUNDLES.ordinal && !bundlesSelectable) {
                                IconButton(
                                    onClick = {
                                        installedAppsViewModel.clearSelection()
                                        patchProfilesViewModel.handleEvent(PatchProfilesViewModel.Event.CANCEL)
                                        if (bundleSources.isEmpty()) {
                                            androidContext.toast(
                                                androidContext.getString(R.string.bundle_reorder_empty_toast)
                                            )
                                            return@IconButton
                                        }
                                        showBundleOrderDialog = true
                                    }
                                ) {
                                    Icon(Icons.Outlined.Sort, stringResource(R.string.bundle_reorder))
                                }
                            }
                            if (pagerState.currentPage == DashboardPage.DASHBOARD.ordinal && !appsSelectionActive) {
                                IconButton(
                                    onClick = {
                                        installedAppsViewModel.clearSelection()
                                        if (installedApps.isEmpty()) {
                                            androidContext.toast(
                                                androidContext.getString(R.string.apps_reorder_empty_toast)
                                            )
                                            return@IconButton
                                        }
                                        showAppsOrderDialog = true
                                    }
                                ) {
                                    Icon(Icons.Outlined.Sort, stringResource(R.string.apps_reorder))
                                }
                            }
                            if (pagerState.currentPage == DashboardPage.PROFILES.ordinal && !profilesSelectable) {
                                IconButton(
                                    onClick = {
                                        patchProfilesViewModel.handleEvent(PatchProfilesViewModel.Event.CANCEL)
                                        if (profiles.isEmpty()) {
                                            androidContext.toast(
                                                androidContext.getString(R.string.patch_profiles_reorder_empty_toast)
                                            )
                                            return@IconButton
                                        }
                                        showProfilesOrderDialog = true
                                    }
                                ) {
                                    Icon(Icons.Outlined.Sort, stringResource(R.string.patch_profiles_reorder))
                                }
                            }
                            IconButton(onClick = onSettingsClick) {
                                Icon(Icons.Outlined.Settings, stringResource(R.string.settings))
                            }
                        },
                        applyContainerColor = true
                    )
                }
            }
        },
        floatingActionButton = {
            when (pagerState.currentPage) {
                DashboardPage.BUNDLES.ordinal -> {
                    val enterExitSpec = tween<IntOffset>(durationMillis = 220, easing = FastOutSlowInEasing)
                    val sizeSpec = tween<IntSize>(durationMillis = 220, easing = FastOutSlowInEasing)
                    Row(
                        modifier = Modifier.height(56.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AnimatedVisibility(
                            visible = !bundlesFabCollapsed,
                            enter = fadeIn(animationSpec = tween(180)) +
                                expandHorizontally(expandFrom = Alignment.End, animationSpec = sizeSpec) +
                                slideInHorizontally(
                                    initialOffsetX = { it / 2 },
                                    animationSpec = enterExitSpec
                                ),
                            exit = fadeOut(animationSpec = tween(180)) +
                                shrinkHorizontally(shrinkTowards = Alignment.End, animationSpec = sizeSpec) +
                                slideOutHorizontally(
                                    targetOffsetX = { it / 2 },
                                    animationSpec = enterExitSpec
                                )
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                HapticFloatingActionButton(
                                    onClick = onBundleDiscoveryClick
                                ) {
                                    Icon(
                                        Icons.Outlined.Public,
                                        stringResource(R.string.patch_bundle_discovery_title)
                                    )
                                }
                                HapticFloatingActionButton(
                                    onClick = {
                                        vm.cancelSourceSelection()
                                        installedAppsViewModel.clearSelection()
                                        patchProfilesViewModel.handleEvent(PatchProfilesViewModel.Event.CANCEL)
                                        showAddBundleDialog = true
                                    }
                                ) { Icon(Icons.Default.Add, stringResource(R.string.add)) }
                            }
                        }
                        Spacer(modifier = Modifier.size(2.dp))
                        BundleFabHandle(
                            collapsed = bundlesFabCollapsed,
                            onToggle = { bundlesFabCollapsed = !bundlesFabCollapsed },
                            modifier = Modifier.offset(x = 6.dp)
                        )
                    }
                }

                DashboardPage.DASHBOARD.ordinal -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HapticFloatingActionButton(
                            onClick = { attemptAppInput(openStoragePicker) }
                        ) {
                            Icon(Icons.Default.Storage, stringResource(R.string.select_from_storage))
                        }
                        HapticFloatingActionButton(
                            onClick = { attemptAppInput(onAppSelectorClick) }
                        ) { Icon(Icons.Default.Add, stringResource(R.string.add)) }
                    }
                }

                else -> Unit
            }
        }
    ) { paddingValues ->
        Box(Modifier.padding(paddingValues)) {
            Column {
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.0.dp),
                indicator = {},
                divider = {}
            ) {
                DashboardPage.entries.forEachIndexed { index, page ->
                    val selected = pagerState.currentPage == index
                    HapticTab(
                        selected = selected,
                        onClick = { composableScope.launch { pagerState.animateScrollToPage(index) } },
                        text = { DashboardTabLabel(text = stringResource(page.titleResId), selected = selected) },
                        icon = { Icon(page.icon, null) },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Notifications(
                if (!Aapt.supportsDevice()) {
                    {
                        NotificationCard(
                            isWarning = true,
                            icon = Icons.Outlined.WarningAmber,
                            text = stringResource(R.string.unsupported_architecture_warning),
                            onDismiss = null
                        )
                    }
                } else null,
                if (vm.showBatteryOptimizationsWarning) {
                    {
                        val batteryOptimizationsLauncher =
                            rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                                vm.updateBatteryOptimizationsWarning()
                            }
                        NotificationCard(
                            isWarning = true,
                            icon = Icons.Default.BatteryAlert,
                            text = stringResource(R.string.battery_optimization_notification),
                            onClick = {
                                batteryOptimizationsLauncher.launch(
                                    Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.fromParts("package", androidContext.packageName, null)
                                    )
                                )
                            }
                        )
                    }
                } else null,
                if (showNewDownloaderPluginsNotification) {
                    {
                        NotificationCard(
                            text = stringResource(R.string.new_downloader_plugins_notification),
                            icon = Icons.Outlined.Download,
                            modifier = Modifier.clickable(onClick = onDownloaderPluginClick),
                            actions = {
                                TextButton(onClick = vm::ignoreNewDownloaderPlugins) {
                                    Text(stringResource(R.string.dismiss))
                                }
                            }
                        )
                    }
                } else null
            )

            val isAppsTab = pagerState.currentPage == DashboardPage.DASHBOARD.ordinal
            val isBundlesTab = pagerState.currentPage == DashboardPage.BUNDLES.ordinal
            val isProfilesTab = pagerState.currentPage == DashboardPage.PROFILES.ordinal
            val searchActive = when {
                isAppsTab -> appsSearchActive
                isBundlesTab -> bundlesSearchActive
                isProfilesTab -> profilesSearchActive
                else -> false
            }
            AnimatedVisibility(
                visible = searchActive,
                enter = fadeIn(animationSpec = spring(stiffness = 400f)) +
                    expandVertically(
                        expandFrom = Alignment.Top,
                        animationSpec = spring(stiffness = 400f)
                    ),
                exit = fadeOut(animationSpec = spring(stiffness = 400f)) +
                    shrinkVertically(
                        shrinkTowards = Alignment.Top,
                        animationSpec = spring(stiffness = 400f)
                    )
            ) {
                val (query, onQueryChange, placeholderRes) = when {
                    isAppsTab -> Triple(
                        appsSearchQuery,
                        { value: String -> appsSearchQuery = value },
                        R.string.apps_search_hint
                    )
                    isBundlesTab -> Triple(
                        bundlesSearchQuery,
                        { value: String -> bundlesSearchQuery = value },
                        R.string.bundles_search_hint
                    )
                    else -> Triple(
                        profilesSearchQuery,
                        { value: String -> profilesSearchQuery = value },
                        R.string.profiles_search_hint
                    )
                }
                DashboardSearchField(
                    query = query,
                    onQueryChange = onQueryChange,
                    onClear = {
                        when {
                            isAppsTab -> appsSearchQuery = ""
                            isBundlesTab -> bundlesSearchQuery = ""
                            else -> profilesSearchQuery = ""
                        }
                    },
                    placeholderRes = placeholderRes,
                    modifier = Modifier.padding(
                        start = dashboardSidePadding,
                        end = dashboardSidePadding,
                        top = 12.dp,
                        bottom = 0.dp
                    )
                )
            }

            BundleProgressBanner(
                modifier = Modifier.padding(top = 8.dp)
            )

            HorizontalPager(
                state = pagerState,
                userScrollEnabled = true,
                modifier = Modifier.fillMaxSize(),
                pageContent = { index ->
                    when (DashboardPage.entries[index]) {
                        DashboardPage.DASHBOARD -> {
                            BackHandler(enabled = appsSelectionActive) {
                                installedAppsViewModel.clearSelection()
                            }
                            InstalledAppsScreen(
                                onAppClick = {
                                    installedAppsViewModel.clearSelection()
                                    onAppClick(it.currentPackageName)
                                },
                                searchQuery = appsSearchQuery,
                                showOrderDialog = showAppsOrderDialog,
                                onDismissOrderDialog = { showAppsOrderDialog = false },
                                viewModel = installedAppsViewModel
                            )
                        }

                        DashboardPage.BUNDLES -> {
                            BackHandler {
                                if (bundlesSelectable) vm.cancelSourceSelection() else composableScope.launch {
                                    pagerState.animateScrollToPage(
                                        DashboardPage.DASHBOARD.ordinal
                                    )
                                }
                            }

                            BundleListScreen(
                                eventsFlow = vm.bundleListEventsFlow,
                                setSelectedSourceCount = { selectedSourceCount = it },
                                setSelectedSourceHasEnabled = { selectedSourcesHasEnabled = it },
                                searchQuery = bundlesSearchQuery,
                                showOrderDialog = showBundleOrderDialog,
                                onDismissOrderDialog = { showBundleOrderDialog = false },
                                onScrollStateChange = {},
                                highlightBundleUid = highlightBundleUid,
                                onHighlightConsumed = { highlightBundleUid = null }
                            )
                        }

                        DashboardPage.PROFILES -> {
                            PatchProfilesScreen(
                                onProfileClick = onProfileLaunch,
                                modifier = Modifier.fillMaxSize(),
                                searchQuery = profilesSearchQuery,
                                showOrderDialog = showProfilesOrderDialog,
                                onDismissOrderDialog = { showProfilesOrderDialog = false },
                                viewModel = patchProfilesViewModel
                            )
                        }
                    }
                }
            )
        }
    }
}
}

@Composable
fun Notifications(
    vararg notifications: (@Composable () -> Unit)?,
) {
    val activeNotifications = notifications.filterNotNull()

    if (activeNotifications.isNotEmpty()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            activeNotifications.forEach { notification ->
                notification()
            }
        }
    }
}

@Composable
private fun DashboardTabLabel(
    text: String,
    selected: Boolean
) {
    if (selected) {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
        }
    } else {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BundleFabHandle(
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(
        topStart = 22.dp,
        bottomStart = 22.dp,
        topEnd = 0.dp,
        bottomEnd = 0.dp
    )
    val interactionSource = remember { MutableInteractionSource() }
    val container = MaterialTheme.colorScheme.primaryContainer
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    val icon = if (collapsed) {
        Icons.Outlined.ChevronRight
    } else {
        Icons.Outlined.ChevronLeft
    }

    Box(
        modifier = modifier
            .size(width = 22.dp, height = 56.dp)
            .clip(shape)
            .background(container)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onToggle
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun DashboardSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    placeholderRes: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 3.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(stringResource(placeholderRes)) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                if (query.isNotBlank()) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.clear))
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),
                disabledContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
                unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                focusedTrailingIconColor = MaterialTheme.colorScheme.primary,
                unfocusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}

@Composable
fun Android11Dialog(onDismissRequest: () -> Unit, onContinue: () -> Unit) {
    AlertDialogExtended(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(stringResource(R.string.continue_))
            }
        },
        title = {
            Text(stringResource(R.string.android_11_bug_dialog_title))
        },
        icon = {
            Icon(Icons.Outlined.BugReport, null)
        },
        text = {
            Text(stringResource(R.string.android_11_bug_dialog_description))
        }
    )
}
