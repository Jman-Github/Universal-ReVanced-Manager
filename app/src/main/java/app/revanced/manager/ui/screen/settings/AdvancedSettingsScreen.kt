package app.revanced.manager.ui.screen.settings

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import androidx.core.content.getSystemService
import androidx.lifecycle.viewModelScope
import app.universal.revanced.manager.BuildConfig
import app.universal.revanced.manager.R
import app.revanced.manager.ui.component.AppTopBar
import app.revanced.manager.ui.component.ColumnWithScrollbar
import app.revanced.manager.ui.component.GroupHeader
import app.revanced.manager.ui.component.settings.BooleanItem
import app.revanced.manager.ui.component.settings.IntegerItem
import app.revanced.manager.ui.component.settings.SafeguardBooleanItem
import app.revanced.manager.ui.component.settings.SettingsListItem
import app.revanced.manager.ui.viewmodel.AdvancedSettingsViewModel
import app.revanced.manager.domain.installer.InstallerManager
import app.revanced.manager.util.toast
import app.revanced.manager.util.transparentListItemColors
import app.revanced.manager.util.withHapticFeedback
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AdvancedSettingsScreen(
    onBackClick: () -> Unit,
    viewModel: AdvancedSettingsViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val installerManager: InstallerManager = koinInject()
    var installerDialogTarget by rememberSaveable { mutableStateOf<InstallerDialogTarget?>(null) }
    val memoryLimit = remember {
        val activityManager = context.getSystemService<ActivityManager>()!!
        context.getString(
            R.string.device_memory_limit_format,
            activityManager.memoryClass,
            activityManager.largeMemoryClass
        )
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.advanced),
                scrollBehavior = scrollBehavior,
                onBackClick = onBackClick
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        ColumnWithScrollbar(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            GroupHeader(stringResource(R.string.manager))

            val apiUrl by viewModel.prefs.api.getAsState()
            var showApiUrlDialog by rememberSaveable { mutableStateOf(false) }

            if (showApiUrlDialog) {
                APIUrlDialog(
                    currentUrl = apiUrl,
                    defaultUrl = viewModel.prefs.api.default,
                    onSubmit = {
                        showApiUrlDialog = false
                        it?.let(viewModel::setApiUrl)
                    }
                )
            }
            SettingsListItem(
                headlineContent = stringResource(R.string.api_url),
                supportingContent = stringResource(R.string.api_url_description),
                modifier = Modifier.clickable {
                    showApiUrlDialog = true
                }
            )

            val installTarget = InstallerManager.InstallTarget.PATCHER
            val primaryPreference by viewModel.prefs.installerPrimary.getAsState()
            val fallbackPreference by viewModel.prefs.installerFallback.getAsState()
            val primaryToken = remember(primaryPreference) { installerManager.parseToken(primaryPreference) }
            val fallbackToken = remember(fallbackPreference) { installerManager.parseToken(fallbackPreference) }
            val primaryEntries = installerManager.listEntries(installTarget, includeNone = false)
            val fallbackEntries = installerManager.listEntries(installTarget, includeNone = true)

            val primaryEntry = primaryEntries.find { it.token == primaryToken } ?: primaryEntries.first()
            val fallbackEntry = fallbackEntries.find { it.token == fallbackToken } ?: fallbackEntries.first()

            @Composable
            fun entrySupporting(entry: InstallerManager.Entry): String? {
                val lines = buildList {
                    entry.description?.takeIf { it.isNotBlank() }?.let { add(it) }
                    entry.availability.reason?.let { add(stringResource(it)) }
                }
                return if (lines.isEmpty()) null else lines.joinToString("\n")
            }

            val primarySupporting = entrySupporting(primaryEntry)
            val fallbackSupporting = entrySupporting(fallbackEntry)
            fun installerLeadingContent(entry: InstallerManager.Entry): (@Composable () -> Unit)? =
                when (entry.token) {
                    InstallerManager.Token.Internal,
                    InstallerManager.Token.None,
                    InstallerManager.Token.Root -> null
                    is InstallerManager.Token.Component -> entry.icon?.let { drawable ->
                        {
                            InstallerIcon(
                                drawable = drawable,
                                selected = false,
                                enabled = entry.availability.available
                            )
                        }
                    }
                }
            val primaryLeadingContent = installerLeadingContent(primaryEntry)
            val fallbackLeadingContent = installerLeadingContent(fallbackEntry)

            SettingsListItem(
                headlineContent = stringResource(R.string.installer_primary_title),
                supportingContent = primarySupporting,
                modifier = Modifier.clickable { installerDialogTarget = InstallerDialogTarget.Primary },
                leadingContent = primaryLeadingContent
            )
            SettingsListItem(
                headlineContent = stringResource(R.string.installer_fallback_title),
                supportingContent = fallbackSupporting,
                modifier = Modifier.clickable { installerDialogTarget = InstallerDialogTarget.Fallback },
                leadingContent = fallbackLeadingContent
            )

            installerDialogTarget?.let { target ->
                val isPrimary = target == InstallerDialogTarget.Primary
                val options = if (isPrimary) primaryEntries else fallbackEntries
                InstallerSelectionDialog(
                    title = stringResource(
                        if (isPrimary) R.string.installer_primary_title else R.string.installer_fallback_title
                    ),
                    options = options,
                    selected = if (isPrimary) primaryToken else fallbackToken,
                    onDismiss = { installerDialogTarget = null },
                    onConfirm = { selection ->
                        if (isPrimary) {
                            viewModel.setPrimaryInstaller(selection)
                        } else {
                            viewModel.setFallbackInstaller(selection)
                        }
                        installerDialogTarget = null
                    }
                )
            }

            GroupHeader(stringResource(R.string.safeguards))
            SafeguardBooleanItem(
                preference = viewModel.prefs.disablePatchVersionCompatCheck,
                coroutineScope = viewModel.viewModelScope,
                headline = R.string.patch_compat_check,
                description = R.string.patch_compat_check_description,
                confirmationText = R.string.patch_compat_check_confirmation
            )
            SafeguardBooleanItem(
                preference = viewModel.prefs.suggestedVersionSafeguard,
                coroutineScope = viewModel.viewModelScope,
                headline = R.string.suggested_version_safeguard,
                description = R.string.suggested_version_safeguard_description,
                confirmationText = R.string.suggested_version_safeguard_confirmation
            )
            SafeguardBooleanItem(
                preference = viewModel.prefs.disableSelectionWarning,
                coroutineScope = viewModel.viewModelScope,
                headline = R.string.patch_selection_safeguard,
                description = R.string.patch_selection_safeguard_description,
                confirmationText = R.string.patch_selection_safeguard_confirmation
            )
            BooleanItem(
                preference = viewModel.prefs.disableUniversalPatchCheck,
                coroutineScope = viewModel.viewModelScope,
                headline = R.string.universal_patches_safeguard,
                description = R.string.universal_patches_safeguard_description,
            )

            GroupHeader(stringResource(R.string.patcher))
            BooleanItem(
                preference = viewModel.prefs.stripUnusedNativeLibs,
                coroutineScope = viewModel.viewModelScope,
                headline = R.string.strip_unused_libs,
                description = R.string.strip_unused_libs_description,
            )
            BooleanItem(
                preference = viewModel.prefs.useProcessRuntime,
                coroutineScope = viewModel.viewModelScope,
                headline = R.string.process_runtime,
                description = R.string.process_runtime_description,
            )
            IntegerItem(
                preference = viewModel.prefs.patcherProcessMemoryLimit,
                coroutineScope = viewModel.viewModelScope,
                headline = R.string.process_runtime_memory_limit,
                description = R.string.process_runtime_memory_limit_description,
            )

            GroupHeader(stringResource(R.string.debugging))
            val exportDebugLogsLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
                    it?.let(viewModel::exportDebugLogs)
                }
            SettingsListItem(
                headlineContent = stringResource(R.string.debug_logs_export),
                modifier = Modifier.clickable { exportDebugLogsLauncher.launch(viewModel.debugLogFileName) }
            )
            val clipboard = remember { context.getSystemService<ClipboardManager>()!! }
            val deviceContent = """
                    Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
                    Build type: ${BuildConfig.BUILD_TYPE}
                    Model: ${Build.MODEL}
                    Android version: ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})
                    Supported Archs: ${Build.SUPPORTED_ABIS.joinToString(", ")}
                    Memory limit: $memoryLimit
                """.trimIndent()
            SettingsListItem(
                modifier = Modifier.combinedClickable(
                    onClick = { },
                    onLongClickLabel = stringResource(R.string.copy_to_clipboard),
                    onLongClick = {
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("Device Information", deviceContent)
                        )

                        context.toast(context.getString(R.string.toast_copied_to_clipboard))
                    }.withHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                ),
                headlineContent = stringResource(R.string.about_device),
                supportingContent = deviceContent
            )
        }
    }
}

private enum class InstallerDialogTarget {
    Primary,
    Fallback
}

@Composable
private fun InstallerSelectionDialog(
    title: String,
    options: List<InstallerManager.Entry>,
    selected: InstallerManager.Token,
    onDismiss: () -> Unit,
    onConfirm: (InstallerManager.Token) -> Unit
) {
    val initialSelection = remember(options, selected) {
        options.firstOrNull { it.token == selected && it.availability.available }?.token
            ?: options.firstOrNull { it.availability.available }?.token
            ?: selected
    }
    var currentSelection by remember(options, selected) { mutableStateOf(initialSelection) }
    val confirmEnabled = options.find { it.token == currentSelection }?.availability?.available != false
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(currentSelection) },
                enabled = confirmEnabled
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(0.dp),
                modifier = Modifier.verticalScroll(scrollState)
            ) {
                options.forEach { option ->
                    val enabled = option.availability.available
                    val selectedOption = currentSelection == option.token
                    ListItem(
                        modifier = Modifier.clickable(enabled = enabled) {
                            if (enabled) currentSelection = option.token
                        },
                        colors = transparentListItemColors,
                        leadingContent = {
                            when (val token = option.token) {
                                InstallerManager.Token.Internal,
                                InstallerManager.Token.None,
                                InstallerManager.Token.Root -> {
                                    RadioButton(
                                        selected = selectedOption,
                                        onClick = null,
                                        enabled = enabled
                                    )
                                }

                                is InstallerManager.Token.Component -> {
                                    option.icon?.let { drawable ->
                                        InstallerIcon(
                                            drawable = drawable,
                                            selected = selectedOption,
                                            enabled = enabled
                                        )
                                    } ?: RadioButton(
                                        selected = selectedOption,
                                        onClick = null,
                                        enabled = enabled
                                    )
                                }
                            }
                        },
                        headlineContent = {
                            Text(
                                option.label,
                                color = if (enabled) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        },
                        supportingContent = {
                            val lines = buildList {
                                option.description?.takeIf { it.isNotBlank() }?.let { add(it) }
                                option.availability.reason?.let { add(stringResource(it)) }
                            }
                            if (lines.isNotEmpty()) {
                                Text(
                                    lines.joinToString("\n"),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    )
                }
            }
        }
    )
}

@Composable
private fun InstallerIcon(
    drawable: Drawable?,
    selected: Boolean,
    enabled: Boolean
) {
    val colors = MaterialTheme.colorScheme
    val borderColor = if (selected) colors.primary else colors.outlineVariant
    val background = colors.surfaceVariant.copy(alpha = if (enabled) 1f else 0.6f)
    val contentAlpha = if (enabled) 1f else 0.4f

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (drawable != null) {
            Image(
                painter = rememberDrawablePainter(drawable = drawable),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                alpha = contentAlpha
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Android,
                contentDescription = null,
                tint = colors.onSurface.copy(alpha = contentAlpha)
            )
        }
    }
}

@Composable
private fun APIUrlDialog(currentUrl: String, defaultUrl: String, onSubmit: (String?) -> Unit) {
    var url by rememberSaveable(currentUrl) { mutableStateOf(currentUrl) }

    AlertDialog(
        onDismissRequest = { onSubmit(null) },
        confirmButton = {
            TextButton(
                onClick = {
                    onSubmit(url)
                }
            ) {
                Text(stringResource(R.string.api_url_dialog_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { onSubmit(null) }) {
                Text(stringResource(R.string.cancel))
            }
        },
        icon = {
            Icon(Icons.Outlined.Api, null)
        },
        title = {
            Text(
                text = stringResource(R.string.api_url_dialog_title),
                style = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.api_url_dialog_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.api_url_dialog_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.api_url)) },
                    trailingIcon = {
                        IconButton(onClick = { url = defaultUrl }) {
                            Icon(Icons.Outlined.Restore, stringResource(R.string.api_url_dialog_reset))
                        }
                    }
                )
            }
        }
    )
}
