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
import app.revanced.manager.ui.component.haptics.HapticCheckbox
import app.revanced.manager.ui.viewmodel.PatchProfileLaunchData
import app.revanced.manager.ui.viewmodel.PatchProfileListItem
import app.revanced.manager.ui.viewmodel.PatchProfilesViewModel
import app.revanced.manager.util.toast
import app.revanced.manager.util.transparentListItemColors
import app.universal.revanced.manager.R
import kotlinx.coroutines.launch

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
    var loadingProfileId by remember { mutableStateOf<Int?>(null) }
    val expandedProfiles = remember { mutableStateMapOf<Int, Boolean>() }
    val selectionActive = viewModel.selectedProfiles.isNotEmpty()

    BackHandler(enabled = selectionActive) { viewModel.handleEvent(PatchProfilesViewModel.Event.CANCEL) }

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
                        Spacer(modifier = Modifier.height(4.dp))
                        if (loadingProfileId == profile.id) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = bundleCountText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            )
        }
    }
}
