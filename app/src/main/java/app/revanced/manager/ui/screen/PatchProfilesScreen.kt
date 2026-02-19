package app.revanced.manager.ui.screen

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.revanced.manager.ui.component.AppIcon
import app.revanced.manager.ui.component.LazyColumnWithScrollbar
import app.revanced.manager.ui.component.Scrollbar
import app.revanced.manager.ui.component.TextInputDialog
import app.revanced.manager.ui.component.haptics.HapticCheckbox
import app.revanced.manager.ui.component.patches.PathSelectorDialog
import app.revanced.manager.data.platform.Filesystem
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.patcher.split.SplitArchiveDisplayResolver
import app.revanced.manager.patcher.split.SplitApkPreparer
import app.revanced.manager.ui.viewmodel.BundleSourceType
import app.revanced.manager.ui.viewmodel.BundleOptionDisplay
import app.revanced.manager.ui.viewmodel.PatchProfileLaunchData
import app.revanced.manager.ui.viewmodel.PatchProfileListItem
import app.revanced.manager.ui.viewmodel.PatchProfilesViewModel
import app.revanced.manager.ui.viewmodel.PatchProfilesViewModel.RenameResult
import app.revanced.manager.util.APK_FILE_EXTENSIONS
import app.revanced.manager.util.PM
import app.revanced.manager.util.relativeTime
import app.revanced.manager.util.toast
import app.revanced.manager.util.isAllowedApkFile
import app.universal.revanced.manager.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PatchProfilesScreen(
    onProfileClick: (PatchProfileLaunchData) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PatchProfilesViewModel,
    showOrderDialog: Boolean = false,
    onDismissOrderDialog: () -> Unit = {},
    searchQuery: String = ""
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val remoteBundleOptions by viewModel.remoteBundleOptions.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = koinInject<PreferencesManager>()
    val useCustomFilePicker by prefs.useCustomFilePicker.flow.collectAsStateWithLifecycle(
        initialValue = prefs.useCustomFilePicker.default
    )
    val allowUniversal by prefs.disableUniversalPatchCheck.flow.collectAsStateWithLifecycle(
        initialValue = prefs.disableUniversalPatchCheck.default
    )
    val allowBundleOverride by prefs.allowPatchProfileBundleOverride.flow.collectAsStateWithLifecycle(
        initialValue = prefs.allowPatchProfileBundleOverride.default
    )
    val filesystem = koinInject<Filesystem>()
    val pm = koinInject<PM>()
    val storageRoots = remember { filesystem.storageRoots() }
    var loadingProfileId by remember { mutableStateOf<Int?>(null) }
    var blockedProfile by remember { mutableStateOf<PatchProfileLaunchData?>(null) }
    var renameProfileId by rememberSaveable { mutableStateOf<Int?>(null) }
    var renameProfileName by rememberSaveable { mutableStateOf("") }
    var versionDialogProfile by remember { mutableStateOf<PatchProfileListItem?>(null) }
    var versionDialogValue by rememberSaveable { mutableStateOf("") }
    var versionDialogAllVersions by rememberSaveable { mutableStateOf(false) }
    var versionDialogSaving by remember { mutableStateOf(false) }
    var settingsDialogProfile by remember { mutableStateOf<PatchProfileListItem?>(null) }
    var apkPickerProfile by remember { mutableStateOf<PatchProfileListItem?>(null) }
    var pendingDocumentApkPickerProfile by remember { mutableStateOf<PatchProfileListItem?>(null) }
    var apkPickerBusy by remember { mutableStateOf(false) }
    data class ChangeUidTarget(val profileId: Int, val bundleUid: Int, val bundleName: String?)
    var changeUidTarget by remember { mutableStateOf<ChangeUidTarget?>(null) }
    data class RemoteBundleTarget(
        val profileId: Int,
        val bundleUid: Int,
        val bundleName: String?,
        val requiredPatchesLowercase: Set<String>
    )
    var remoteBundleTarget by remember { mutableStateOf<RemoteBundleTarget?>(null) }
    var remoteBundleSelectionUid by rememberSaveable { mutableStateOf<Int?>(null) }
    var remoteBundleSaving by remember { mutableStateOf(false) }
    data class RemoteBundleOverrideTarget(
        val profileId: Int,
        val bundleUid: Int,
        val targetUid: Int,
        val displayName: String
    )
    var remoteBundleIncompatibleTarget by remember { mutableStateOf<RemoteBundleOverrideTarget?>(null) }
    val expandedProfiles = remember { mutableStateMapOf<Int, Boolean>() }
    val selectionActive = viewModel.selectedProfiles.isNotEmpty()
    data class OptionDialogData(val patchName: String, val entries: List<BundleOptionDisplay>)
    var optionDialogData by remember { mutableStateOf<OptionDialogData?>(null) }
    val normalizedQuery = searchQuery.trim().lowercase()
    val filteredProfiles = if (normalizedQuery.isBlank()) {
        profiles
    } else {
        profiles.filter { profile ->
            val searchText = buildString {
                append(profile.name)
                append(' ')
                append(profile.packageName)
                profile.appVersion?.let { version ->
                    append(' ')
                    append(version)
                }
                profile.bundleNames.forEach { name ->
                    append(' ')
                    append(name)
                }
            }.lowercase()
            searchText.contains(normalizedQuery)
        }
    }

    BackHandler(enabled = selectionActive) { viewModel.handleEvent(PatchProfilesViewModel.Event.CANCEL) }

    fun handleApkSelectionResult(profileName: String, result: PatchProfilesViewModel.ApkSelectionResult) {
        when (result) {
            PatchProfilesViewModel.ApkSelectionResult.SUCCESS -> context.toast(
                context.getString(R.string.patch_profile_apk_saved_toast, profileName)
            )
            PatchProfilesViewModel.ApkSelectionResult.INVALID_FILE -> context.toast(
                context.getString(R.string.patch_profile_apk_invalid_toast)
            )
            PatchProfilesViewModel.ApkSelectionResult.PACKAGE_MISMATCH -> context.toast(
                context.getString(R.string.patch_profile_apk_mismatch_toast)
            )
            PatchProfilesViewModel.ApkSelectionResult.PROFILE_NOT_FOUND,
            PatchProfilesViewModel.ApkSelectionResult.FAILED,
            PatchProfilesViewModel.ApkSelectionResult.CLEARED -> context.toast(
                context.getString(R.string.patch_profile_apk_failed_toast)
            )
        }
    }
    val apkDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val profile = pendingDocumentApkPickerProfile
        pendingDocumentApkPickerProfile = null
        apkPickerProfile = null
        if (profile == null || uri == null) return@rememberLauncherForActivityResult
        apkPickerBusy = true
        scope.launch {
            val tempFile = withContext(Dispatchers.IO) {
                val file = File.createTempFile("patch-profile-apk", ".apk", context.cacheDir)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                file
            }
            try {
                val result = viewModel.updateProfileApk(profile.id, tempFile)
                handleApkSelectionResult(profile.name, result)
            } finally {
                withContext(Dispatchers.IO) { tempFile.delete() }
                apkPickerBusy = false
            }
        }
    }

    apkPickerProfile?.let { profile ->
        if (!useCustomFilePicker) return@let
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                apkPickerProfile = null
                if (path == null) return@PathSelectorDialog
                apkPickerBusy = true
                scope.launch {
                    try {
                        val result = viewModel.updateProfileApk(profile.id, File(path.toString()))
                        handleApkSelectionResult(profile.name, result)
                    } finally {
                        apkPickerBusy = false
                    }
                }
            },
            fileFilter = ::isAllowedApkFile,
            allowDirectorySelection = false,
            fileTypeLabel = stringResource(R.string.apk_file_type)
        )
    }
    LaunchedEffect(apkPickerProfile?.id, useCustomFilePicker) {
        val profile = apkPickerProfile
        if (profile != null && !useCustomFilePicker) {
            pendingDocumentApkPickerProfile = profile
            apkDocumentLauncher.launch(arrayOf("*/*"))
        }
    }

    renameProfileId?.let { targetId ->
        TextInputDialog(
            initial = renameProfileName,
            title = stringResource(R.string.patch_profile_rename_title),
            onDismissRequest = { renameProfileId = null },
            onConfirm = { newName ->
                val trimmed = newName.trim()
                if (trimmed.isEmpty()) return@TextInputDialog
                scope.launch {
                    when (viewModel.renameProfile(targetId, trimmed)) {
                        RenameResult.SUCCESS -> {
                            context.toast(context.getString(R.string.patch_profile_updated_toast, trimmed))
                            renameProfileId = null
                        }
                        RenameResult.DUPLICATE_NAME -> {
                            context.toast(context.getString(R.string.patch_profile_duplicate_toast, trimmed))
                            renameProfileName = trimmed
                        }
                        RenameResult.FAILED -> {
                            context.toast(context.getString(R.string.patch_profile_save_failed_toast))
                            renameProfileId = null
                        }
                    }
                }
            },
            validator = { it.isNotBlank() }
        )
    }

    changeUidTarget?.let { target ->
        TextInputDialog(
            initial = target.bundleUid.toString(),
            title = stringResource(
                R.string.patch_profile_bundle_change_uid_title,
                target.bundleName ?: target.bundleUid.toString()
            ),
            onDismissRequest = { changeUidTarget = null },
            onConfirm = { newValue ->
                val trimmed = newValue.trim()
                val newUid = trimmed.toIntOrNull()
                if (newUid == null) {
                    context.toast(context.getString(R.string.patch_profile_bundle_change_uid_invalid))
                    return@TextInputDialog
                }
                scope.launch {
                    val result = viewModel.changeLocalBundleUid(target.profileId, target.bundleUid, newUid)
                    when (result) {
                        PatchProfilesViewModel.ChangeUidResult.SUCCESS -> context.toast(
                            context.getString(R.string.patch_profile_bundle_change_uid_success, newUid)
                        )

                        PatchProfilesViewModel.ChangeUidResult.PROFILE_OR_BUNDLE_NOT_FOUND,
                        PatchProfilesViewModel.ChangeUidResult.TARGET_NOT_FOUND -> context.toast(
                            context.getString(R.string.patch_profile_bundle_change_uid_not_found, newUid)
                        )
                    }
                    changeUidTarget = null
                }
            },
            validator = { it.trim().toIntOrNull() != null }
        )
    }

    if (profiles.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.patch_profile_empty_state),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
            )
        }
        return
    }

    if (filteredProfiles.isEmpty() && normalizedQuery.isNotBlank()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.search_no_results),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    LazyColumnWithScrollbar(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(filteredProfiles, key = { it.id }) { profile ->
            val patchCount = profile.bundleDetails.sumOf { it.patchCount }
            val patchCountText = pluralStringResource(R.plurals.patch_count, patchCount, patchCount)
            val bundleCountText = pluralStringResource(
                R.plurals.patch_profile_bundle_count,
                profile.bundleCount,
                profile.bundleCount
            )
            val versionLabel = when (val version = profile.appVersion) {
                null -> stringResource(R.string.bundle_version_all_versions)
                else -> if (version.startsWith("v", ignoreCase = true)) version else "v$version"
            }
            val creationText = profile.createdAt.takeIf { it > 0 }?.relativeTime(context)?.let {
                stringResource(R.string.patch_profile_created_at, it)
            }
            val apkPath = profile.apkPath?.takeIf { it.isNotBlank() }
            val expanded = expandedProfiles[profile.id] == true
            val isSelected = profile.id in viewModel.selectedProfiles
            val cardShape = RoundedCornerShape(16.dp)
            val cardColor = if (isSelected) {
                MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp)
            } else {
                MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(cardShape)
                    .combinedClickable(
                        enabled = loadingProfileId == null,
                        onClick = {
                            if (selectionActive) {
                                viewModel.toggleSelection(profile.id)
                                return@combinedClickable
                            }
                            if (loadingProfileId != null) return@combinedClickable
                            loadingProfileId = profile.id
                            scope.launch {
                                try {
                                    val launchData = viewModel.resolveProfile(profile.id)
                                    if (launchData != null) {
                                        if (launchData.availableBundleCount == 0) {
                                            context.toast(
                                                context.getString(R.string.patch_profile_no_available_bundles_toast)
                                            )
                                            return@launch
                                        }
                                        if (launchData.missingBundles.isNotEmpty()) {
                                            context.toast(
                                                context.getString(R.string.patch_profile_missing_bundles_toast)
                                            )
                                        }
                                        if (launchData.changedBundles.isNotEmpty()) {
                                            context.toast(
                                                context.getString(R.string.patch_profile_changed_patches_toast)
                                            )
                                        }
                                        if (!allowUniversal && launchData.containsUniversalPatches) {
                                            blockedProfile = launchData
                                            return@launch
                                        }
                                        onProfileClick(launchData)
                                    } else {
                                        context.toast(
                                            context.getString(R.string.patch_profile_launch_error)
                                        )
                                    }
                                } finally {
                                    loadingProfileId = null
                                }
                            }
                        },
                        onLongClick = { viewModel.toggleSelection(profile.id) }
                    ),
                shape = cardShape,
                tonalElevation = if (isSelected) 4.dp else 2.dp,
                color = cardColor
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (selectionActive) {
                                HapticCheckbox(
                                    checked = isSelected,
                                    onCheckedChange = { viewModel.setSelection(profile.id, it) }
                                )
                            }
                            if (apkPath != null) {
                                PatchProfileApkIcon(
                                    apkPath = apkPath,
                                    pm = pm,
                                    filesystem = filesystem,
                                    modifier = Modifier.size(44.dp)
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = profile.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = profile.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Divider(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }
                            if (loadingProfileId == profile.id) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            ProfileMetaPill(text = patchCountText)
                            ProfileMetaPill(text = bundleCountText)
                            ProfileMetaPill(text = versionLabel)
                        }
                        creationText?.let { created ->
                            Text(
                                text = created,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!selectionActive) {
                            val actionScrollState = rememberScrollState()
                            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(
                                        8.dp,
                                        Alignment.CenterHorizontally
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .widthIn(min = maxWidth)
                                        .horizontalScroll(actionScrollState)
                                ) {
                                    ProfileActionPill(
                                        text = stringResource(R.string.patch_profile_rename),
                                        icon = Icons.Outlined.Edit
                                    ) {
                                        renameProfileId = profile.id
                                        renameProfileName = profile.name
                                    }
                                    ProfileActionPill(
                                        text = stringResource(
                                            if (expanded) R.string.patch_profile_show_less
                                            else R.string.patch_profile_show_more
                                        ),
                                        icon = Icons.Outlined.Extension
                                    ) {
                                        expandedProfiles[profile.id] = !expanded
                                    }
                                    ProfileActionPill(
                                        text = stringResource(R.string.settings),
                                        icon = Icons.Outlined.Settings
                                    ) {
                                        settingsDialogProfile = profile
                                    }
                                }
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = expanded,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            profile.bundleDetails.forEach { detail ->
                                val baseName = detail.displayName
                                    ?: stringResource(R.string.patches_name_fallback)
                                val displayName = if (detail.isAvailable) {
                                    baseName
                                } else {
                                    stringResource(
                                        R.string.patch_profile_bundle_unavailable_suffix,
                                        baseName
                                    )
                                }
                                val typeLabel = stringResource(
                                    when (detail.type) {
                                        BundleSourceType.Preinstalled -> R.string.bundle_type_preinstalled
                                        BundleSourceType.Remote -> R.string.bundle_type_remote
                                        else -> R.string.bundle_type_local
                                    }
                                )
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = typeLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    if (!detail.isAvailable && detail.type == BundleSourceType.Remote && !selectionActive) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        ProfileActionText(
                                            text = stringResource(R.string.patch_profile_bundle_select_remote)
                                        ) {
                                            remoteBundleTarget = RemoteBundleTarget(
                                                profileId = profile.id,
                                                bundleUid = detail.uid,
                                                bundleName = detail.displayName ?: detail.uid.toString(),
                                                requiredPatchesLowercase = detail.patches
                                                    .mapTo(mutableSetOf()) { it.trim().lowercase() }
                                            )
                                            remoteBundleSelectionUid = null
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    if (detail.type == BundleSourceType.Local) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = stringResource(
                                                    R.string.patch_profile_bundle_uid_label,
                                                    detail.uid
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (!selectionActive) {
                                                ProfileActionText(
                                                    text = stringResource(R.string.patch_profile_bundle_change_uid)
                                                ) {
                                                    changeUidTarget = ChangeUidTarget(
                                                        profileId = profile.id,
                                                        bundleUid = detail.uid,
                                                        bundleName = detail.displayName
                                                            ?: detail.uid.toString()
                                                    )
                                                }
                                            }
                                        }
                                    }
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    tonalElevation = 1.dp,
                                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                                ) {
                                    val patchScrollState = rememberScrollState()
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp)
                                            .heightIn(max = 220.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(end = 8.dp)
                                                .verticalScroll(patchScrollState),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            detail.patches.forEachIndexed { index, patchName ->
                                                val optionList = detail.options[patchName]
                                                    ?: detail.options[patchName.trim()]
                                                    ?: detail.options.entries.firstOrNull {
                                                        it.key.equals(patchName, ignoreCase = true)
                                                    }?.value
                                                    ?: emptyList()
                                                val hasOptions = optionList.isNotEmpty()
                                                Column(
                                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                                    modifier = Modifier
                                                        .then(
                                                            if (hasOptions) Modifier.clickable {
                                                                optionDialogData = OptionDialogData(
                                                                    patchName = patchName,
                                                                    entries = optionList
                                                                )
                                                            } else Modifier
                                                        )
                                                ) {
                                                    Text(
                                                        text = patchName,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    if (hasOptions) {
                                                        Text(
                                                            text = stringResource(R.string.patch_profile_view_options),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                    }
                                                }
                                                if (index != detail.patches.lastIndex) {
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                }
                                            }
                                        }
                                        Scrollbar(
                                            scrollState = patchScrollState,
                                            modifier = Modifier.align(Alignment.CenterEnd),
                                            prominent = true
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }

    if (showOrderDialog) {
        PatchProfilesOrderDialog(
            profiles = profiles,
            onDismissRequest = onDismissOrderDialog,
            onConfirm = { ordered ->
                viewModel.reorderProfiles(ordered.map { it.id })
                onDismissOrderDialog()
            }
        )
    }

    optionDialogData?.let { data ->
        AlertDialog(
            onDismissRequest = { optionDialogData = null },
            confirmButton = {
                TextButton(onClick = { optionDialogData = null }) {
                    Text(stringResource(R.string.close))
                }
            },
            title = { Text(stringResource(R.string.patch_profile_patch_options_title, data.patchName)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    data.entries.forEach { entry ->
                        Text(
                            text = "${entry.label}: ${entry.displayValue}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        )
    }

    remoteBundleTarget?.let { target ->
        val options = remoteBundleOptions
        AlertDialog(
            onDismissRequest = {
                if (remoteBundleSaving) return@AlertDialog
                remoteBundleTarget = null
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selectedId = remoteBundleSelectionUid ?: return@TextButton
                        val selected = options.firstOrNull { it.uid == selectedId } ?: return@TextButton
                        if (remoteBundleSaving) return@TextButton
                        val isCompatible = target.requiredPatchesLowercase.all {
                            it in selected.patchNamesLowercase
                        }
                        if (!isCompatible) {
                            remoteBundleIncompatibleTarget = RemoteBundleOverrideTarget(
                                profileId = target.profileId,
                                bundleUid = target.bundleUid,
                                targetUid = selected.uid,
                                displayName = selected.displayName
                            )
                            return@TextButton
                        }
                        remoteBundleSaving = true
                        scope.launch {
                            try {
                                when (
                                    viewModel.replaceRemoteBundle(
                                        target.profileId,
                                        target.bundleUid,
                                        selected.uid,
                                        target.requiredPatchesLowercase,
                                        false
                                    )
                                ) {
                                    PatchProfilesViewModel.ReplaceRemoteBundleResult.SUCCESS -> context.toast(
                                        context.getString(
                                            R.string.patch_profile_bundle_select_remote_success,
                                            selected.displayName
                                        )
                                    )
                                    PatchProfilesViewModel.ReplaceRemoteBundleResult.INCOMPATIBLE -> {
                                        remoteBundleIncompatibleTarget = RemoteBundleOverrideTarget(
                                            profileId = target.profileId,
                                            bundleUid = target.bundleUid,
                                            targetUid = selected.uid,
                                            displayName = selected.displayName
                                        )
                                    }
                                    PatchProfilesViewModel.ReplaceRemoteBundleResult.TARGET_NOT_FOUND,
                                    PatchProfilesViewModel.ReplaceRemoteBundleResult.PROFILE_OR_BUNDLE_NOT_FOUND,
                                    PatchProfilesViewModel.ReplaceRemoteBundleResult.FAILED -> context.toast(
                                        context.getString(R.string.patch_profile_bundle_select_remote_error)
                                    )
                                }
                            } finally {
                                remoteBundleSaving = false
                                remoteBundleTarget = null
                            }
                        }
                    },
                    enabled = !remoteBundleSaving && remoteBundleSelectionUid != null && options.isNotEmpty()
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (remoteBundleSaving) return@TextButton
                        remoteBundleTarget = null
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = {
                Text(
                    stringResource(
                        R.string.patch_profile_bundle_select_remote_title,
                        target.bundleName ?: target.bundleUid.toString()
                    )
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = stringResource(R.string.patch_profile_bundle_select_remote_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (options.isEmpty()) {
                        Text(
                            text = stringResource(R.string.patch_profile_bundle_select_remote_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        options.forEach { option ->
                            val patchCountText = pluralStringResource(
                                R.plurals.patch_profile_bundle_patch_count,
                                option.patchCount,
                                option.patchCount
                            )
                            val versionLabel = option.version?.let { version ->
                                if (version.startsWith("v", ignoreCase = true)) version else "v$version"
                            }
                            val subtitle = if (versionLabel != null) {
                                "$versionLabel - $patchCountText"
                            } else {
                                patchCountText
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        remoteBundleSelectionUid = option.uid
                                    }
                                    .padding(vertical = 6.dp)
                            ) {
                                RadioButton(
                                    selected = remoteBundleSelectionUid == option.uid,
                                    onClick = { remoteBundleSelectionUid = option.uid }
                                )
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = option.displayName,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    settingsDialogProfile?.let { profile ->
        val settingsProfile = profiles.firstOrNull { it.id == profile.id } ?: profile
        val apkPath = settingsProfile.apkPath?.takeIf { it.isNotBlank() }
        val apkDisplayPath = settingsProfile.apkSourcePath?.takeIf { it.isNotBlank() } ?: apkPath
        val versionSummary = settingsProfile.appVersion?.takeIf { it.isNotBlank() }?.let { version ->
            if (version.startsWith("v", ignoreCase = true)) version else "v$version"
        } ?: stringResource(R.string.bundle_version_all_versions)
        var autoPatchUpdating by remember(settingsProfile.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = {
                if (apkPickerBusy) return@AlertDialog
                settingsDialogProfile = null
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (apkPickerBusy) return@TextButton
                        settingsDialogProfile = null
                    }
                ) {
                    Text(stringResource(R.string.close))
                }
            },
            title = { Text(stringResource(R.string.patch_profile_settings_title, settingsProfile.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.patch_profile_apk_section_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        val apkScrollState = rememberScrollState()
                        Text(
                            text = apkDisplayPath ?: stringResource(R.string.patch_profile_apk_not_set),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Visible,
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(apkScrollState)
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    if (apkPickerBusy) return@TextButton
                                    apkPickerProfile = settingsProfile
                                }
                            ) {
                                Text(stringResource(R.string.patch_profile_apk_select))
                            }
                            if (apkPath != null) {
                                TextButton(
                                    onClick = {
                                        if (apkPickerBusy) return@TextButton
                                        apkPickerBusy = true
                                        scope.launch {
                                            try {
                                                when (viewModel.updateProfileApk(settingsProfile.id, null)) {
                                                    PatchProfilesViewModel.ApkSelectionResult.CLEARED -> context.toast(
                                                        context.getString(
                                                            R.string.patch_profile_apk_cleared_toast,
                                                            settingsProfile.name
                                                        )
                                                    )
                                                    PatchProfilesViewModel.ApkSelectionResult.PROFILE_NOT_FOUND,
                                                    PatchProfilesViewModel.ApkSelectionResult.FAILED,
                                                    PatchProfilesViewModel.ApkSelectionResult.SUCCESS,
                                                    PatchProfilesViewModel.ApkSelectionResult.INVALID_FILE,
                                                    PatchProfilesViewModel.ApkSelectionResult.PACKAGE_MISMATCH -> context.toast(
                                                        context.getString(R.string.patch_profile_apk_failed_toast)
                                                    )
                                                }
                                            } finally {
                                                apkPickerBusy = false
                                            }
                                        }
                                    }
                                ) {
                                    Text(stringResource(R.string.patch_profile_apk_clear))
                                }
                            }
                        }
                    }
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.patch_profile_version_override_action),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = versionSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = {
                                if (apkPickerBusy) return@TextButton
                                versionDialogProfile = settingsProfile
                                versionDialogValue = settingsProfile.appVersion.orEmpty()
                                versionDialogAllVersions = settingsProfile.appVersion.isNullOrBlank()
                                settingsDialogProfile = null
                            }
                        ) {
                            Text(stringResource(R.string.edit))
                        }
                    }
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        HapticCheckbox(
                            checked = settingsProfile.autoPatch,
                            onCheckedChange = { enabled ->
                                if (autoPatchUpdating) return@HapticCheckbox
                                autoPatchUpdating = true
                                scope.launch {
                                    val updated = viewModel.updateProfileAutoPatch(settingsProfile.id, enabled)
                                    if (!updated) {
                                        context.toast(context.getString(R.string.patch_profile_save_failed_toast))
                                    }
                                    autoPatchUpdating = false
                                }
                            }
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.patch_profile_auto_patch_label),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.patch_profile_auto_patch_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        )
    }

    versionDialogProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = {
                if (versionDialogSaving) return@AlertDialog
                versionDialogProfile = null
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (versionDialogSaving) return@TextButton
                        versionDialogSaving = true
                        scope.launch {
                            val versionToSave =
                                if (versionDialogAllVersions) null else versionDialogValue.trim().takeUnless { it.isBlank() }
                            if (versionToSave == null && !versionDialogAllVersions) {
                                val quoted = "\"${context.getString(R.string.bundle_version_all_versions)}\""
                                context.toast(context.getString(R.string.patch_profile_version_override_set_to_all, quoted))
                            }
                            try {
                                when (viewModel.updateProfileVersion(profile.id, versionToSave)) {
                                    PatchProfilesViewModel.VersionUpdateResult.SUCCESS -> context.toast(
                                        context.getString(R.string.patch_profile_version_override_saved_toast)
                                    )

                                    PatchProfilesViewModel.VersionUpdateResult.PROFILE_NOT_FOUND -> context.toast(
                                        context.getString(R.string.patch_profile_launch_error)
                                    )

                                    PatchProfilesViewModel.VersionUpdateResult.FAILED -> context.toast(
                                        context.getString(R.string.patch_profile_version_override_failed_toast)
                                    )
                                }
                            } finally {
                                versionDialogSaving = false
                                versionDialogProfile = null
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (versionDialogSaving) return@TextButton
                        versionDialogProfile = null
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { Text(stringResource(R.string.patch_profile_version_override_title, profile.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.patch_profile_version_override_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextField(
                        value = versionDialogValue,
                        onValueChange = {
                            versionDialogValue = it
                            if (versionDialogAllVersions && it.isNotBlank()) {
                                versionDialogAllVersions = false
                            }
                        },
                        label = { Text(stringResource(R.string.patch_profile_version_override_label)) },
                        placeholder = { Text(stringResource(R.string.patch_profile_version_override_hint)) },
                        singleLine = true,
                        enabled = !versionDialogAllVersions
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HapticCheckbox(
                            checked = versionDialogAllVersions,
                            onCheckedChange = { versionDialogAllVersions = it }
                        )
                        Text(text = stringResource(R.string.patch_profile_version_override_all_versions))
                    }
                }
            }
        )
    }

    remoteBundleIncompatibleTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { remoteBundleIncompatibleTarget = null },
            confirmButton = {
                if (allowBundleOverride) {
                    TextButton(
                        onClick = {
                            if (remoteBundleSaving) return@TextButton
                            remoteBundleSaving = true
                            scope.launch {
                                try {
                                    when (
                                        viewModel.replaceRemoteBundle(
                                            target.profileId,
                                            target.bundleUid,
                                            target.targetUid,
                                            emptySet(),
                                            true
                                        )
                                    ) {
                                        PatchProfilesViewModel.ReplaceRemoteBundleResult.SUCCESS -> context.toast(
                                            context.getString(
                                                R.string.patch_profile_bundle_select_remote_success,
                                                target.displayName
                                            )
                                        )
                                        PatchProfilesViewModel.ReplaceRemoteBundleResult.FAILED,
                                        PatchProfilesViewModel.ReplaceRemoteBundleResult.PROFILE_OR_BUNDLE_NOT_FOUND,
                                        PatchProfilesViewModel.ReplaceRemoteBundleResult.TARGET_NOT_FOUND,
                                        PatchProfilesViewModel.ReplaceRemoteBundleResult.INCOMPATIBLE -> context.toast(
                                            context.getString(R.string.patch_profile_bundle_select_remote_error)
                                        )
                                    }
                                } finally {
                                    remoteBundleSaving = false
                                    remoteBundleIncompatibleTarget = null
                                    remoteBundleTarget = null
                                }
                            }
                        }
                    ) {
                        Text(stringResource(R.string.patch_profile_bundle_select_remote_override))
                    }
                } else {
                    TextButton(onClick = { remoteBundleIncompatibleTarget = null }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            },
            dismissButton = {
                if (allowBundleOverride) {
                    TextButton(onClick = { remoteBundleIncompatibleTarget = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            },
            title = { Text(stringResource(R.string.patch_profile_bundle_select_remote_incompatible_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.patch_profile_bundle_select_remote_incompatible_message,
                        target.displayName
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
    }

    blockedProfile?.let {
        AlertDialog(
            onDismissRequest = { blockedProfile = null },
            confirmButton = {
                TextButton(onClick = { blockedProfile = null }) {
                    Text(stringResource(R.string.close))
                }
            },
            title = { Text(stringResource(R.string.universal_patches_profile_blocked_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.universal_patches_profile_blocked_description,
                        stringResource(R.string.universal_patches_safeguard)
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        )
    }
}

@Composable
private fun PatchProfilesOrderDialog(
    profiles: List<PatchProfileListItem>,
    onDismissRequest: () -> Unit,
    onConfirm: (List<PatchProfileListItem>) -> Unit
) {
    val workingOrder = remember(profiles) { profiles.toMutableStateList() }
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        workingOrder.add(to.index, workingOrder.removeAt(from.index))
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = { onConfirm(workingOrder.toList()) }, enabled = workingOrder.isNotEmpty()) {
                Text(text = stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(R.string.cancel))
            }
        },
        title = { Text(text = stringResource(R.string.patch_profiles_reorder_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.patch_profiles_reorder_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumnWithScrollbar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    state = lazyListState
                ) {
                    itemsIndexed(workingOrder, key = { _, profile -> profile.id }) { index, profile ->
                        val interactionSource = remember { MutableInteractionSource() }
                        ReorderableItem(reorderableState, key = profile.id) { _ ->
                            PatchProfileOrderRow(
                                index = index,
                                profile = profile,
                                interactionSource = interactionSource
                            )
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun ReorderableCollectionItemScope.PatchProfileOrderRow(
    index: Int,
    profile: PatchProfileListItem,
    interactionSource: MutableInteractionSource
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = (index + 1).toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = profile.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(
            onClick = {},
            interactionSource = interactionSource,
            modifier = Modifier.longPressDraggableHandle()
        ) {
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = stringResource(R.string.drag_handle)
            )
        }
    }
}


@Composable
private fun ProfileActionText(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun ProfileActionPill(
    text: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val background = MaterialTheme.colorScheme.surface.copy(alpha = if (enabled) 0.9f else 0.5f)
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.6f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
                onLongClick = null
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = contentColor,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ProfileMetaPill(
    text: String,
    modifier: Modifier = Modifier
) {
    val background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        tonalElevation = 0.dp,
        color = background
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun PatchProfileApkIcon(
    apkPath: String,
    pm: PM,
    filesystem: Filesystem,
    modifier: Modifier = Modifier
) {
    val containerShape = RoundedCornerShape(12.dp)
    val containerColor = MaterialTheme.colorScheme.surfaceVariant
    val containerBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val apkFile = remember(apkPath) { File(apkPath) }
    var iconInfo by remember(apkPath) { mutableStateOf<PatchProfileApkIconInfo?>(null) }
    LaunchedEffect(apkPath) {
        iconInfo?.cleanup?.invoke()
        iconInfo = loadPatchProfileApkIconInfo(apkFile, pm, filesystem)
    }
    DisposableEffect(apkPath) {
        onDispose { iconInfo?.cleanup?.invoke() }
    }
    val packageInfo = iconInfo?.packageInfo
    Surface(
        modifier = modifier,
        shape = containerShape,
        tonalElevation = 0.dp,
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, containerBorder)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            contentAlignment = Alignment.Center
        ) {
            if (packageInfo != null) {
                AppIcon(
                    packageInfo = packageInfo,
                    iconOverride = iconInfo?.iconOverride,
                    contentDescription = null,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Android,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private data class PatchProfileApkIconInfo(
    val packageInfo: android.content.pm.PackageInfo?,
    val iconOverride: android.graphics.drawable.Drawable? = null,
    val cleanup: (() -> Unit)?
)

private suspend fun loadPatchProfileApkIconInfo(
    file: File,
    pm: PM,
    filesystem: Filesystem
): PatchProfileApkIconInfo? = withContext(Dispatchers.IO) {
    if (!file.exists()) return@withContext null
    val extension = file.extension.lowercase()
    if (extension !in APK_FILE_EXTENSIONS) return@withContext null
    val isSplitArchive = extension != "apk" && SplitApkPreparer.isSplitArchive(file)
    if (extension != "apk" && !isSplitArchive) return@withContext null

    if (isSplitArchive) {
        val resolved = SplitArchiveDisplayResolver.resolve(
            source = file,
            workspace = filesystem.tempDir,
            app = pm.application,
            pm = pm
        ) ?: return@withContext null
        PatchProfileApkIconInfo(
            packageInfo = resolved.packageInfo,
            iconOverride = resolved.icon,
            cleanup = null
        )
    } else {
        PatchProfileApkIconInfo(
            packageInfo = pm.getPackageInfo(file),
            iconOverride = null,
            cleanup = null
        )
    }
}
