package app.revanced.manager.ui.screen

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.universal.revanced.manager.R
import app.revanced.manager.ui.component.AppTopBar
import app.revanced.manager.ui.component.ColumnWithScrollbar
import app.revanced.manager.ui.component.settings.ExpressiveSettingsCard
import app.revanced.manager.ui.component.settings.ExpressiveSettingsDivider
import app.revanced.manager.ui.component.settings.ExpressiveSettingsItem
import app.revanced.manager.ui.model.navigation.Settings
import app.revanced.manager.ui.screen.settings.SettingsSearchState
import app.revanced.manager.ui.screen.settings.SettingsSearchTarget

private data class Section(
    @StringRes val name: Int,
    @StringRes val description: Int,
    val image: ImageVector,
    val destination: Settings.Destination,
    val keywords: List<Int> = emptyList(),
)

private data class SearchEntry(
    @StringRes val title: Int,
    @StringRes val description: Int?,
    @StringRes val category: Int,
    val destination: Settings.Destination,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBackClick: () -> Unit, navigate: (Settings.Destination) -> Unit) {
    val settingsSections = remember {
        listOf(
            Section(
                R.string.general,
                R.string.general_description,
                Icons.Outlined.Settings,
                Settings.General,
                keywords = listOf(
                    R.string.appearance,
                    R.string.dynamic_color,
                    R.string.theme_presets,
                    R.string.theme_color,
                    R.string.accent_color,
                    R.string.theme_preview_title,
                    R.string.theme_reset,
                    R.string.language_settings,
                    R.string.app_language
                )
            ),
            Section(
                R.string.updates,
                R.string.updates_description,
                Icons.Outlined.Update,
                Settings.Updates,
                keywords = listOf(
                    R.string.update_on_metered_connections,
                    R.string.manual_update_check,
                    R.string.changelog,
                    R.string.update_checking_manager,
                    R.string.show_manager_update_dialog_on_launch,
                    R.string.manager_prereleases,
                    R.string.background_bundle_update,
                    R.string.background_radio_menu_title,
                    R.string.background_bundle_ask_notification
                )
            ),
            Section(
                R.string.downloads,
                R.string.downloads_description,
                Icons.Outlined.Download,
                Settings.Downloads,
                keywords = listOf(
                    R.string.download_settings,
                    R.string.downloader_auto_save_title,
                    R.string.downloader_plugins,
                    R.string.downloaded_apps,
                    R.string.downloaded_apps_export
                )
            ),
            Section(
                R.string.import_export,
                R.string.import_export_description,
                Icons.Outlined.SwapVert,
                Settings.ImportExport,
                keywords = listOf(
                    R.string.import_keystore,
                    R.string.import_patch_selection,
                    R.string.import_patch_bundles,
                    R.string.import_patch_profiles,
                    R.string.import_manager_settings,
                    R.string.export_keystore,
                    R.string.export_patch_selection,
                    R.string.export_patch_bundles,
                    R.string.export_patch_profiles,
                    R.string.export_manager_settings,
                    R.string.reset_patch_selection,
                    R.string.reset_patch_options,
                    R.string.regenerate_keystore
                )
            ),
            Section(
                R.string.advanced,
                R.string.advanced_description,
                Icons.Outlined.Tune,
                Settings.Advanced,
                keywords = listOf(
                    R.string.api_url,
                    R.string.github_pat,
                    R.string.installer_primary_title,
                    R.string.installer_fallback_title,
                    R.string.installer_custom_manage_title,
                    R.string.search_engine_host_title,
                    R.string.patch_compat_check,
                    R.string.suggested_version_safeguard,
                    R.string.patch_selection_safeguard,
                    R.string.disable_patch_selection_confirmations,
                    R.string.universal_patches_safeguard,
                    R.string.restore_official_bundle,
                    R.string.strip_unused_libs,
                    R.string.skip_unneeded_split_apks,
                    R.string.process_runtime,
                    R.string.process_runtime_memory_limit,
                    R.string.process_runtime_memory_aggressive,
                    R.string.patcher_auto_collapse_steps,
                    R.string.patcher_auto_expand_steps,
                    R.string.patcher_saved_apps_title,
                    R.string.show_patch_selection_summary,
                    R.string.patch_selection_collapse_on_toggle,
                    R.string.patch_selection_action_order_title,
                    R.string.patch_selection_action_visibility_title,
                    R.string.patch_selection_version_tags_title,
                    R.string.patch_bundle_action_order_title,
                    R.string.patch_bundle_action_visibility_title,
                    R.string.export_name_format,
                    R.string.debug_logs_export,
                    R.string.about_device
                )
            ),
            Section(
                R.string.about,
                R.string.app_name,
                Icons.Outlined.Info,
                Settings.About
            ),
            Section(
                R.string.developer_options,
                R.string.developer_options_description,
                Icons.Outlined.Code,
                Settings.Developer,
                keywords = listOf(
                    R.string.battery_optimization_banner_title,
                    R.string.patches_force_download,
                    R.string.patch_profile_bundle_override_title
                )
            )
        )
    }
    val sectionIconMap = remember(settingsSections) {
        settingsSections.associate { it.destination to it.image }
    }
    val settingsEntries = remember {
        listOf(
            SearchEntry(R.string.dynamic_color, R.string.dynamic_color_description, R.string.general, Settings.General),
            SearchEntry(R.string.theme_presets, R.string.theme_presets_description, R.string.general, Settings.General),
            SearchEntry(R.string.theme_color, R.string.theme_color_description, R.string.general, Settings.General),
            SearchEntry(R.string.accent_color, R.string.accent_color_description, R.string.general, Settings.General),
            SearchEntry(R.string.pure_black_follow_system, R.string.pure_black_follow_system_description, R.string.general, Settings.General),
            SearchEntry(R.string.theme_preview_title, R.string.theme_preview_description, R.string.general, Settings.General),
            SearchEntry(R.string.theme_reset, null, R.string.general, Settings.General),
            SearchEntry(R.string.app_language, null, R.string.general, Settings.General),
            SearchEntry(R.string.update_on_metered_connections, R.string.update_on_metered_connections_description, R.string.updates, Settings.Updates),
            SearchEntry(R.string.manual_update_check, R.string.manual_update_check_description, R.string.updates, Settings.Updates),
            SearchEntry(R.string.changelog, R.string.changelog_description, R.string.updates, Settings.Updates),
            SearchEntry(R.string.update_checking_manager, R.string.update_checking_manager_description, R.string.updates, Settings.Updates),
            SearchEntry(R.string.show_manager_update_dialog_on_launch, R.string.show_manager_update_dialog_on_launch_description, R.string.updates, Settings.Updates),
            SearchEntry(R.string.manager_prereleases, R.string.manager_prereleases_description, R.string.updates, Settings.Updates),
            SearchEntry(R.string.background_bundle_update, R.string.background_bundle_update_description, R.string.updates, Settings.Updates),
            SearchEntry(R.string.downloader_auto_save_title, R.string.downloader_auto_save_description, R.string.downloads, Settings.Downloads),
            SearchEntry(R.string.downloaded_apps_export, null, R.string.downloads, Settings.Downloads),
            SearchEntry(R.string.import_keystore, R.string.import_keystore_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.import_patch_selection, R.string.import_patch_selection_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.import_patch_bundles, R.string.import_patch_bundles_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.import_patch_profiles, R.string.import_patch_profiles_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.import_manager_settings, R.string.import_manager_settings_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.keystore_diagnostics, R.string.keystore_diagnostics_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.export_keystore, R.string.export_keystore_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.export_patch_selection, R.string.export_patch_selection_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.export_patch_bundles, R.string.export_patch_bundles_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.export_patch_profiles, R.string.export_patch_profiles_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.export_manager_settings, R.string.export_manager_settings_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.regenerate_keystore, R.string.regenerate_keystore_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.reset_patch_selection, R.string.reset_patch_selection_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.reset_patch_options, R.string.reset_patch_options_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.api_url, R.string.api_url_description, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.github_pat, R.string.github_pat_description, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.installer_primary_title, null, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.installer_fallback_title, null, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.installer_custom_manage_title, R.string.installer_custom_manage_description, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.search_engine_host_title, R.string.search_engine_host_description, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.patch_compat_check, R.string.patch_compat_check_description, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.suggested_version_safeguard, R.string.suggested_version_safeguard_description, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.patch_selection_safeguard, R.string.patch_selection_safeguard_description, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.disable_patch_selection_confirmations, R.string.disable_patch_selection_confirmations_description, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.universal_patches_safeguard, R.string.universal_patches_safeguard_description, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.restore_official_bundle, null, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.strip_unused_libs, R.string.strip_unused_libs_description, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.skip_unneeded_split_apks, R.string.skip_unneeded_split_apks_description, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.process_runtime, R.string.process_runtime_description, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.process_runtime_memory_limit, R.string.process_runtime_memory_limit_description, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.process_runtime_memory_aggressive, R.string.process_runtime_memory_aggressive_description, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.patcher_auto_collapse_steps, R.string.patcher_auto_collapse_steps_description, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.patcher_auto_expand_steps, R.string.patcher_auto_expand_steps_description, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.patcher_saved_apps_title, R.string.patcher_saved_apps_description, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.show_patch_selection_summary, R.string.show_patch_selection_summary_description, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.patch_selection_collapse_on_toggle, R.string.patch_selection_collapse_on_toggle_description, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.patch_selection_action_order_title, R.string.patch_selection_action_order_description, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.patch_selection_action_visibility_title, R.string.patch_selection_action_visibility_description, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.patch_selection_version_tags_title, R.string.patch_selection_version_tags_description, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.patch_bundle_action_order_title, R.string.patch_bundle_action_order_description, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.patch_bundle_action_visibility_title, R.string.patch_bundle_action_visibility_description, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.export_name_format, R.string.export_name_format_description, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.debug_logs_export, null, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.about_device, null, R.string.advanced, Settings.Advanced),
            SearchEntry(R.string.battery_optimization_banner_title, R.string.battery_optimization_banner_description, R.string.developer_options, Settings.Developer),
            SearchEntry(R.string.patches_force_download, null, R.string.developer_options, Settings.Developer),
            SearchEntry(R.string.patch_profile_bundle_override_title, R.string.patch_profile_bundle_override_description, R.string.developer_options, Settings.Developer),
        )
    }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val normalizedQuery = searchQuery.trim().lowercase()
    val filteredSections = settingsSections
    val filteredEntries = if (normalizedQuery.isBlank()) {
        emptyList()
    } else {
        settingsEntries.filter { entry ->
            val searchText = buildString {
                append(stringResource(entry.title))
                entry.description?.let { description ->
                    append(' ')
                    append(stringResource(description))
                }
            }.lowercase()
            searchText.contains(normalizedQuery)
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.settings),
                onBackClick = onBackClick,
            )
        }
    ) { paddingValues ->
        ColumnWithScrollbar(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.settings_search_hint)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Outlined.Close, null)
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    )
                )
            }
            if (normalizedQuery.isNotBlank() && filteredEntries.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_search_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            } else if (normalizedQuery.isNotBlank()) {
                val highlightStyle = SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                ExpressiveSettingsCard(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    filteredEntries.forEachIndexed { index, entry ->
                        val titleText = stringResource(entry.title)
                        val descriptionText = entry.description?.let { stringResource(it) }
                        val categoryText = stringResource(entry.category)
                        ExpressiveSettingsItem(
                            headlineContent = {
                                Column {
                                    Text(
                                        text = buildHighlightedText(categoryText, normalizedQuery, highlightStyle),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = buildHighlightedText(titleText, normalizedQuery, highlightStyle),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                            },
                            supportingContentSlot = {
                                Column {
                                    descriptionText?.let { description ->
                                        androidx.compose.material3.Text(
                                            text = buildHighlightedText(description, normalizedQuery, highlightStyle),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            leadingContent = {
                                Icon(
                                    sectionIconMap[entry.destination] ?: Icons.Outlined.Tune,
                                    null
                                )
                            },
                            onClick = {
                                SettingsSearchState.setTarget(
                                    SettingsSearchTarget(entry.destination, entry.title)
                                )
                                navigate(entry.destination)
                            }
                        )
                        if (index != filteredEntries.lastIndex) {
                            ExpressiveSettingsDivider()
                        }
                    }
                }
            } else {
                ExpressiveSettingsCard(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    filteredSections.forEachIndexed { index, (name, description, icon, destination) ->
                        ExpressiveSettingsItem(
                            headlineContent = stringResource(name),
                            supportingContent = stringResource(description),
                            leadingContent = { Icon(icon, null) },
                            onClick = { navigate(destination) }
                        )
                        if (index != filteredSections.lastIndex) {
                            ExpressiveSettingsDivider()
                        }
                    }
                }
            }
        }
    }
}

private fun buildHighlightedText(
    text: String,
    query: String,
    highlightStyle: SpanStyle
): AnnotatedString {
    if (query.isBlank()) {
        return AnnotatedString(text)
    }
    val lowerText = text.lowercase()
    val lowerQuery = query.lowercase()
    var searchIndex = 0
    return buildAnnotatedString {
        while (searchIndex < text.length) {
            val matchIndex = lowerText.indexOf(lowerQuery, searchIndex)
            if (matchIndex == -1) {
                append(text.substring(searchIndex))
                break
            }
            if (matchIndex > searchIndex) {
                append(text.substring(searchIndex, matchIndex))
            }
            withStyle(highlightStyle) {
                append(text.substring(matchIndex, matchIndex + lowerQuery.length))
            }
            searchIndex = matchIndex + lowerQuery.length
        }
    }
}
