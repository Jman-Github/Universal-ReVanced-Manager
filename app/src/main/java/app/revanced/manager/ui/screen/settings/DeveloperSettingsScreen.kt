package app.revanced.manager.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.universal.revanced.manager.R
import app.revanced.manager.ui.component.AlertDialogExtended
import app.revanced.manager.ui.component.AppTopBar
import app.revanced.manager.ui.component.GroupHeader
import app.revanced.manager.ui.component.settings.ExpressiveSettingsCard
import app.revanced.manager.ui.component.settings.ExpressiveSettingsDivider
import app.revanced.manager.ui.component.settings.ExpressiveSettingsItem
import app.revanced.manager.ui.component.settings.BooleanItem
import app.revanced.manager.ui.viewmodel.DeveloperOptionsViewModel
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperSettingsScreen(
    onBackClick: () -> Unit,
    vm: DeveloperOptionsViewModel = koinViewModel()
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val coroutineScope = rememberCoroutineScope()
    val showBatteryOptimizationBanner by vm.prefs.showBatteryOptimizationBanner.getAsState()
    var showBatteryOptimizationDialog by rememberSaveable { mutableStateOf(false) }

    if (showBatteryOptimizationDialog) {
        AlertDialogExtended(
            onDismissRequest = { showBatteryOptimizationDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            vm.prefs.showBatteryOptimizationBanner.update(false)
                        }
                        showBatteryOptimizationDialog = false
                    }
                ) {
                    Text(stringResource(R.string.battery_optimization_banner_disable_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatteryOptimizationDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            icon = { androidx.compose.material3.Icon(Icons.Default.BatteryAlert, null) },
            title = { Text(stringResource(R.string.battery_optimization_banner_disable_title)) },
            text = { Text(stringResource(R.string.battery_optimization_banner_disable_description)) }
        )
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.developer_options),
                scrollBehavior = scrollBehavior,
                onBackClick = onBackClick
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            GroupHeader(stringResource(R.string.manager))
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                BooleanItem(
                    value = showBatteryOptimizationBanner,
                    onValueChange = { enabled ->
                        if (enabled) {
                            coroutineScope.launch {
                                vm.prefs.showBatteryOptimizationBanner.update(true)
                            }
                        } else {
                            showBatteryOptimizationDialog = true
                        }
                    },
                    headline = R.string.battery_optimization_banner_title,
                    description = R.string.battery_optimization_banner_description
                )
            }
            GroupHeader(stringResource(R.string.patch_bundles))
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                ExpressiveSettingsItem(
                    headlineContent = stringResource(R.string.patches_force_download),
                    modifier = Modifier.clickable(onClick = vm::redownloadBundles)
                )
                ExpressiveSettingsDivider()
                ExpressiveSettingsItem(
                    headlineContent = stringResource(R.string.patches_reset),
                    modifier = Modifier.clickable(onClick = vm::redownloadBundles)
                )
            }
        }
    }
}
