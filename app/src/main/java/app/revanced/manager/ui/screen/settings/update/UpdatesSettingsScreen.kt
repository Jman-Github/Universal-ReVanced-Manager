package app.revanced.manager.ui.screen.settings.update

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.universal.revanced.manager.R
import app.revanced.manager.domain.manager.SearchForUpdatesBackgroundInterval
import app.revanced.manager.ui.component.AppTopBar
import app.revanced.manager.ui.component.ColumnWithScrollbar
import app.revanced.manager.ui.component.GroupHeader
import app.revanced.manager.ui.component.settings.BooleanItem
import app.revanced.manager.ui.component.settings.ExpressiveSettingsCard
import app.revanced.manager.ui.component.settings.ExpressiveSettingsDivider
import app.revanced.manager.ui.component.settings.ExpressiveSettingsItem
import app.revanced.manager.ui.viewmodel.UpdatesSettingsViewModel
import app.revanced.manager.util.permission.hasNotificationPermission
import app.revanced.manager.util.toast
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import androidx.activity.compose.rememberLauncherForActivityResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesSettingsScreen(
    onBackClick: () -> Unit,
    onChangelogClick: () -> Unit,
    onUpdateClick: () -> Unit,
    vm: UpdatesSettingsViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val backgroundInterval by vm.backgroundBundleUpdateInterval.getAsState()
    var showBackgroundUpdateDialog by rememberSaveable { mutableStateOf(false) }
    var pendingInterval by rememberSaveable {
        mutableStateOf<SearchForUpdatesBackgroundInterval?>(null)
    }
    var showNotificationPermissionDialog by rememberSaveable { mutableStateOf(false) }
    var showBatteryOptimizationDialog by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val batteryOptimizationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val powerManager = context.getSystemService(PowerManager::class.java)
            val batteryOptimizationDisabled =
                powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
            if (!batteryOptimizationDisabled) {
                if (pendingInterval != null) {
                    showBatteryOptimizationDialog = true
                }
                return@rememberLauncherForActivityResult
            }

            pendingInterval?.let { interval ->
                if (!context.hasNotificationPermission()) {
                    showNotificationPermissionDialog = true
                } else {
                    vm.updateBackgroundBundleUpdateTime(interval)
                    pendingInterval = null
                }
            }
        }

    DisposableEffect(lifecycleOwner, backgroundInterval) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver

            val powerManager = context.getSystemService(PowerManager::class.java)
            val batteryOptimizationDisabled =
                powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
            if (backgroundInterval != SearchForUpdatesBackgroundInterval.NEVER &&
                !batteryOptimizationDisabled
            ) {
                showNotificationPermissionDialog = false
                showBatteryOptimizationDialog = false
                pendingInterval = null
                vm.updateBackgroundBundleUpdateTime(SearchForUpdatesBackgroundInterval.NEVER)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        pendingInterval?.let { interval ->
            vm.updateBackgroundBundleUpdateTime(interval)
        }
        pendingInterval = null
    }

    if (showNotificationPermissionDialog) {
        AlertDialog(
            onDismissRequest = {
                showNotificationPermissionDialog = false
                pendingInterval = null
            },
            title = { Text(stringResource(R.string.background_bundle_ask_notification)) },
            text = { Text(stringResource(R.string.background_bundle_ask_notification_description)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNotificationPermissionDialog = false
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                ) {
                    Text(stringResource(R.string.continue_))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showNotificationPermissionDialog = false
                        pendingInterval = null
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showBackgroundUpdateDialog) {
        BackgroundBundleUpdateTimeDialog(
            current = backgroundInterval,
            onDismiss = { showBackgroundUpdateDialog = false },
            onConfirm = { interval ->
                if (interval == SearchForUpdatesBackgroundInterval.NEVER) {
                    vm.updateBackgroundBundleUpdateTime(interval)
                    pendingInterval = null
                    return@BackgroundBundleUpdateTimeDialog
                }

                val powerManager = context.getSystemService(PowerManager::class.java)
                val batteryOptimizationDisabled =
                    powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
                if (!batteryOptimizationDisabled) {
                    pendingInterval = interval
                    showBatteryOptimizationDialog = true
                    return@BackgroundBundleUpdateTimeDialog
                }

                if (!context.hasNotificationPermission()) {
                    pendingInterval = interval
                    showNotificationPermissionDialog = true
                    return@BackgroundBundleUpdateTimeDialog
                }

                vm.updateBackgroundBundleUpdateTime(interval)
                pendingInterval = null
            }
        )
    }
    if (showBatteryOptimizationDialog) {
        AlertDialog(
            onDismissRequest = {
                showBatteryOptimizationDialog = false
                pendingInterval = null
            },
            title = { Text(stringResource(R.string.battery_optimization_dialog_title)) },
            text = { Text(stringResource(R.string.battery_optimization_dialog_description)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBatteryOptimizationDialog = false
                        batteryOptimizationLauncher.launch(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.fromParts("package", context.packageName, null)
                            )
                        )
                    }
                ) {
                    Text(stringResource(R.string.disable_battery_optimization))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.updates),
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
            GroupHeader(stringResource(R.string.patches_and_manager))

            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                BooleanItem(
                    preference = vm.allowMeteredUpdates,
                    headline = R.string.update_on_metered_connections,
                    description = R.string.update_on_metered_connections_description
                )
            }

            GroupHeader(stringResource(R.string.manager))

            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                ExpressiveSettingsItem(
                    headlineContent = stringResource(R.string.manual_update_check),
                    supportingContent = stringResource(R.string.manual_update_check_description),
                    onClick = {
                        coroutineScope.launch {
                            if (!vm.isConnected) {
                                context.toast(context.getString(R.string.no_network_toast))
                                return@launch
                            }
                            if (vm.checkForUpdates()) onUpdateClick()
                        }
                    }
                )
                ExpressiveSettingsDivider()
                ExpressiveSettingsItem(
                    headlineContent = stringResource(R.string.changelog),
                    supportingContent = stringResource(R.string.changelog_description),
                    onClick = {
                        if (!vm.isConnected) {
                            context.toast(context.getString(R.string.no_network_toast))
                            return@ExpressiveSettingsItem
                        }
                        onChangelogClick()
                    }
                )
                ExpressiveSettingsDivider()
                BooleanItem(
                    preference = vm.managerAutoUpdates,
                    headline = R.string.update_checking_manager,
                    description = R.string.update_checking_manager_description
                )
                ExpressiveSettingsDivider()
                BooleanItem(
                    preference = vm.showManagerUpdateDialogOnLaunch,
                    headline = R.string.show_manager_update_dialog_on_launch,
                    description = R.string.show_manager_update_dialog_on_launch_description
                )
                ExpressiveSettingsDivider()
                BooleanItem(
                    preference = vm.useManagerPrereleases,
                    headline = R.string.manager_prereleases,
                    description = R.string.manager_prereleases_description
                )
                ExpressiveSettingsDivider()
                ExpressiveSettingsItem(
                    headlineContent = stringResource(R.string.background_bundle_update),
                    supportingContent = stringResource(R.string.background_bundle_update_description),
                    onClick = { showBackgroundUpdateDialog = true }
                )
            }
        }
    }
}

@Composable
private fun BackgroundBundleUpdateTimeDialog(
    current: SearchForUpdatesBackgroundInterval,
    onDismiss: () -> Unit,
    onConfirm: (SearchForUpdatesBackgroundInterval) -> Unit
) {
    var selected by rememberSaveable(current) { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.background_radio_menu_title)) },
        text = {
            Column {
                SearchForUpdatesBackgroundInterval.entries.forEach { interval ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = interval }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = selected == interval,
                            onClick = { selected = interval }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(interval.displayName),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected); onDismiss() }) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
