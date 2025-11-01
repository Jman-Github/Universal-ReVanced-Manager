package app.revanced.manager.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.revanced.manager.ui.component.TextInputDialog
import app.revanced.manager.ui.component.haptics.HapticCheckbox
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.ui.viewmodel.BundleSourceType
import app.revanced.manager.ui.viewmodel.PatchProfileLaunchData
import app.revanced.manager.ui.viewmodel.PatchProfileListItem
import app.revanced.manager.ui.viewmodel.PatchProfilesViewModel
import app.revanced.manager.ui.viewmodel.PatchProfilesViewModel.RenameResult
import app.revanced.manager.util.toast
import app.revanced.manager.util.transparentListItemColors
import app.universal.revanced.manager.R
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PatchProfilesScreen(
    onProfileClick: (PatchProfileLaunchData) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PatchProfilesViewModel
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = koinInject<PreferencesManager>()
    val allowUniversal by prefs.disableUniversalPatchCheck.flow.collectAsStateWithLifecycle(
        initialValue = prefs.disableUniversalPatchCheck.default
    )
    var loadingProfileId by remember { mutableStateOf<Int?>(null) }
    var blockedProfile by remember { mutableStateOf<PatchProfileLaunchData?>(null) }
    var renameProfileId by rememberSaveable { mutableStateOf<Int?>(null) }
    var renameProfileName by rememberSaveable { mutableStateOf("") }
    data class ChangeUidTarget(val profileId: Int, val bundleUid: Int, val bundleName: String?)
    var changeUidTarget by remember { mutableStateOf<ChangeUidTarget?>(null) }
    val expandedProfiles = remember { mutableStateMapOf<Int, Boolean>() }
    val selectionActive = viewModel.selectedProfiles.isNotEmpty()

    BackHandler(enabled = selectionActive) { viewModel.handleEvent(PatchProfilesViewModel.Event.CANCEL) }

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
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Bookmarks,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = stringResource(R.string.patch_profile_empty_state),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(profiles, key = { it.id }) { profile ->
            val bundleCountText = pluralStringResource(
                R.plurals.patch_profile_bundle_count,
                profile.bundleCount,
                profile.bundleCount
            )

            val detailLine = buildList {
                profile.appVersion?.let { add(it) }
                add(bundleCountText)
            }.joinToString("  ")
            val expanded = expandedProfiles[profile.id] == true
            val isSelected = profile.id in viewModel.selectedProfiles

            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
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
                colors = transparentListItemColors,
                leadingContent = if (selectionActive) {
                    {
                        HapticCheckbox(
                            checked = isSelected,
                            onCheckedChange = { viewModel.setSelection(profile.id, it) }
                        )
                    }
                } else null,
                overlineContent = {
                    Text(
                        text = profile.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                },
                headlineContent = {
                    Text(
                        text = profile.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                supportingContent = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (detailLine.isNotEmpty()) {
                            Text(
                                text = detailLine,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        AnimatedVisibility(
                            visible = expanded,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                    val patchCountText = pluralStringResource(
                                        R.plurals.patch_profile_bundle_patch_count,
                                        detail.patchCount,
                                        detail.patchCount
                                    )
                                    val typeLabel = stringResource(
                                        if (detail.type == BundleSourceType.Remote) R.string.bundle_type_remote else R.string.bundle_type_local
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.patch_profile_bundle_header,
                                            displayName,
                                            patchCountText
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = typeLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
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
                                                Text(
                                                    text = stringResource(R.string.patch_profile_bundle_change_uid),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .clickable {
                                                            changeUidTarget = ChangeUidTarget(
                                                                profileId = profile.id,
                                                                bundleUid = detail.uid,
                                                                bundleName = detail.displayName
                                                                    ?: detail.uid.toString()
                                                            )
                                                        }
                                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = detail.patches.joinToString(", "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                },
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(
                                    if (expanded) R.string.patch_profile_show_less
                                    else R.string.patch_profile_show_more
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !selectionActive) {
                                        expandedProfiles[profile.id] = !expanded
                                    }
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        if (!selectionActive) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.patch_profile_rename),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        renameProfileId = profile.id
                                        renameProfileName = profile.name
                                    }
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        if (loadingProfileId == profile.id) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }
            )
        }
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
