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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.revanced.manager.R
import app.revanced.manager.ui.component.AlertDialogExtended
import app.revanced.manager.ui.component.AppTopBar
import app.revanced.manager.ui.component.GroupHeader
import app.revanced.manager.ui.component.settings.BooleanItem
import app.revanced.manager.ui.component.settings.ExpressiveSettingsCard
import app.revanced.manager.ui.component.settings.ExpressiveSettingsDivider
import app.revanced.manager.ui.component.settings.ExpressiveSettingsItem
import app.revanced.manager.ui.component.settings.SettingsSearchHighlight
import app.revanced.manager.ui.model.navigation.Settings
import app.revanced.manager.ui.screen.settings.SettingsSearchState
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
    val searchTarget by SettingsSearchState.target.collectAsStateWithLifecycle()
    var highlightTarget by rememberSaveable { mutableStateOf<Int?>(null) }
    val showBatteryOptimizationBanner by vm.prefs.showBatteryOptimizationBanner.getAsState()
    val allowPatchProfileBundleOverride by vm.prefs.allowPatchProfileBundleOverride.getAsState()
    var showBatteryOptimizationDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(searchTarget) {
        val target = searchTarget
        if (target?.destination == Settings.Developer) {
            highlightTarget = target.targetId
            SettingsSearchState.clear()
        }
    }

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
                SettingsSearchHighlight(
                    targetKey = R.string.battery_optimization_banner_title,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
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
            }
            GroupHeader(stringResource(R.string.patch_bundles))
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SettingsSearchHighlight(
                    targetKey = R.string.patches_force_download,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsItem(
                        headlineContent = stringResource(R.string.patches_force_download),
                        modifier = highlightModifier.clickable(onClick = vm::redownloadBundles)
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.patches_reset,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsItem(
                        headlineContent = stringResource(R.string.patches_reset),
                        modifier = highlightModifier.clickable(onClick = vm::redownloadBundles)
                    )
                }
            }
            GroupHeader(stringResource(R.string.tab_profiles))
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.patch_profile_bundle_override_title,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        value = allowPatchProfileBundleOverride,
                        onValueChange = { enabled ->
                            coroutineScope.launch {
                                vm.prefs.allowPatchProfileBundleOverride.update(enabled)
                            }
                        },
                        headline = R.string.patch_profile_bundle_override_title,
                        description = R.string.patch_profile_bundle_override_description
                    )
                }
            }
        }
    }
}
