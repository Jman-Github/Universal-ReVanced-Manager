package app.revanced.manager.ui.screen.settings

import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.universal.revanced.manager.R
import app.revanced.manager.data.platform.Filesystem
import app.revanced.manager.ui.component.AppTopBar
import app.revanced.manager.ui.component.ColumnWithScrollbar
import app.revanced.manager.ui.component.GroupHeader
import app.revanced.manager.ui.component.patches.PathSelectorDialog
import app.revanced.manager.ui.component.settings.ExpressiveSettingsCard
import app.revanced.manager.ui.component.settings.ExpressiveSettingsDivider
import app.revanced.manager.ui.component.settings.ExpressiveSettingsItem
import app.revanced.manager.ui.component.settings.ExpressiveSettingsSwitch
import app.revanced.manager.ui.component.settings.BooleanItem
import app.revanced.manager.ui.component.settings.SettingsSearchHighlight
import app.revanced.manager.ui.model.navigation.Settings
import app.revanced.manager.ui.theme.Theme
import app.revanced.manager.ui.viewmodel.GeneralSettingsViewModel
import app.revanced.manager.ui.viewmodel.ThemePreset
import app.revanced.manager.ui.screen.settings.SettingsSearchState
import app.revanced.manager.util.toColorOrNull
import app.revanced.manager.util.toHexString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.nio.file.Path
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GeneralSettingsScreen(
    onBackClick: () -> Unit,
    viewModel: GeneralSettingsViewModel = koinViewModel()
) {
    val prefs = viewModel.prefs
    val searchTarget by SettingsSearchState.target.collectAsStateWithLifecycle()
    var highlightTarget by rememberSaveable { mutableStateOf<Int?>(null) }
    var showAccentPicker by rememberSaveable { mutableStateOf(false) }
    var showThemeColorPicker by rememberSaveable { mutableStateOf(false) }

    val customAccentColorHex by prefs.customAccentColor.getAsState()
    val customThemeColorHex by prefs.customThemeColor.getAsState()
    val customBackgroundImageUri by prefs.customBackgroundImageUri.getAsState()
    val customBackgroundImageOpacity by prefs.customBackgroundImageOpacity.getAsState()
    val showPatchProfilesTab by prefs.showPatchProfilesTab.getAsState()
    val showToolsTab by prefs.showToolsTab.getAsState()
    val useCustomFilePicker by prefs.useCustomFilePicker.getAsState()
    val theme by prefs.theme.getAsState()
    val appLanguage by prefs.appLanguage.getAsState()
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var showCustomBackgroundImagePicker by rememberSaveable { mutableStateOf(false) }
    var showCustomBackgroundImagePreview by rememberSaveable { mutableStateOf(false) }
    // Allow selecting the AMOLED preset regardless of the current theme since selecting it switches to dark mode anyway.
    val allowPureBlackPreset = true
    val dynamicColorEnabled by prefs.dynamicColor.getAsState()
    val themePresetSelectionEnabled by prefs.themePresetSelectionEnabled.getAsState()
    val selectedThemePresetName by prefs.themePresetSelectionName.getAsState()
    val pureBlackOnSystemDark by prefs.pureBlackOnSystemDark.getAsState()
    val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    LaunchedEffect(searchTarget) {
        val target = searchTarget
        if (target?.destination == Settings.General) {
            highlightTarget = target.targetId
            SettingsSearchState.clear()
        }
    }
    val selectedThemePreset = remember(selectedThemePresetName, themePresetSelectionEnabled, supportsDynamicColor) {
        if (!themePresetSelectionEnabled) null else selectedThemePresetName.takeIf { it.isNotBlank() }?.let {
            val preset = runCatching { ThemePreset.valueOf(it) }.getOrNull()
            if (!supportsDynamicColor && preset == ThemePreset.DYNAMIC) ThemePreset.DEFAULT else preset
        }
    }
    val canAdjustThemeColor = selectedThemePreset == null
    val canAdjustAccentColor = selectedThemePreset != ThemePreset.DYNAMIC
    val themeControlsAlpha = if (canAdjustThemeColor) 1f else 0.5f
    val accentControlsAlpha = if (canAdjustAccentColor) 1f else 0.5f
    val languageOptions = remember {
        listOf(
            LanguageOption("system", R.string.language_option_system),
            LanguageOption("en", R.string.language_option_english),
            LanguageOption("fr", R.string.language_option_french),
            LanguageOption("zh-CN", R.string.language_option_chinese_simplified),
            LanguageOption("in", R.string.language_option_indonesian),
            LanguageOption("hi", R.string.language_option_hindi),
            LanguageOption("gu", R.string.language_option_gujarati),
            LanguageOption("pt-BR", R.string.language_option_portuguese_brazil),
            LanguageOption("vi", R.string.language_option_vietnamese),
            LanguageOption("ko", R.string.language_option_korean),
            LanguageOption("ja", R.string.language_option_japanese),
            LanguageOption("ru", R.string.language_option_russian),
            LanguageOption("uk", R.string.language_option_ukrainian)
        )
    }

    if (!canAdjustThemeColor && showThemeColorPicker) showThemeColorPicker = false
    if (!canAdjustAccentColor && showAccentPicker) showAccentPicker = false
    val context = LocalContext.current
    val filesystem: Filesystem = koinInject()
    val storageRoots = remember { filesystem.storageRoots() }
    val supportedBackgroundImageExtensions = remember {
        setOf("jpg", "jpeg", "png", "gif", "svg", "tif", "tiff", "webp")
    }
    val supportedBackgroundImageLabel = ".jpg .jpeg .png .gif .svg .tif .tiff .webp"
    val customBackgroundPreviewUri = remember(customBackgroundImageUri) {
        customBackgroundImageUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
    }
    LaunchedEffect(customBackgroundImageUri) {
        if (customBackgroundImageUri.isBlank()) {
            showCustomBackgroundImagePreview = false
        }
    }
    val backgroundImageDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        showCustomBackgroundImagePicker = false
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.importCustomBackgroundImageUri(context, uri)
    }
    if (showCustomBackgroundImagePicker && useCustomFilePicker) {
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                showCustomBackgroundImagePicker = false
                if (path == null) return@PathSelectorDialog
                viewModel.importCustomBackgroundImagePath(context, path)
            },
            fileFilter = { isSupportedBackgroundImageFile(it, supportedBackgroundImageExtensions) },
            allowDirectorySelection = false,
            fileTypeLabel = supportedBackgroundImageLabel
        )
    }
    LaunchedEffect(showCustomBackgroundImagePicker, useCustomFilePicker) {
        if (showCustomBackgroundImagePicker && !useCustomFilePicker) {
            backgroundImageDocumentLauncher.launch("image/*")
        }
    }

    if (showThemeColorPicker) {
        val currentThemeColor = customThemeColorHex.toColorOrNull()
        ColorPickerDialog(
            titleRes = R.string.theme_color_picker_title,
            previewLabelRes = R.string.theme_color_preview,
            resetLabelRes = R.string.theme_color_reset,
            initialColor = currentThemeColor ?: MaterialTheme.colorScheme.surface,
            allowReset = currentThemeColor != null,
            onReset = { viewModel.setCustomThemeColor(null) },
            onConfirm = { color -> viewModel.setCustomThemeColor(color) },
            onDismiss = { showThemeColorPicker = false }
        )
    }
    if (showAccentPicker) {
        val currentAccent = customAccentColorHex.toColorOrNull()
        ColorPickerDialog(
            titleRes = R.string.accent_color_picker_title,
            previewLabelRes = R.string.accent_color_preview,
            resetLabelRes = R.string.accent_color_reset,
            initialColor = currentAccent ?: MaterialTheme.colorScheme.primary,
            allowReset = currentAccent != null,
            onReset = { viewModel.setCustomAccentColor(null) },
            onConfirm = { color -> viewModel.setCustomAccentColor(color) },
            onDismiss = { showAccentPicker = false }
        )
    }
    if (showLanguageDialog) {
        LanguageDialog(
            options = languageOptions,
            selectedCode = appLanguage,
            onSelect = {
                viewModel.setAppLanguage(it)
                // Force activity recreation so every screen picks up the new locale immediately.
                (context as? android.app.Activity)?.recreate()
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false }
        )
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.general),
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
            GroupHeader(stringResource(R.string.appearance))

            val selectedLanguageLabel = when (appLanguage) {
                "system" -> R.string.language_option_system
                else -> languageOptions.firstOrNull { it.code == appLanguage }?.labelRes
                    ?: R.string.language_option_english
            }

            val baseThemeSwatches = remember(supportsDynamicColor) {
                buildList {
                    add(ThemePresetSwatch(ThemePreset.DEFAULT, R.string.theme_preset_default, listOf(Color(0xFF4CD964), Color(0xFF4A90E2))))
                    add(ThemePresetSwatch(ThemePreset.LIGHT, R.string.light, listOf(Color(0xFFEEF2FF), Color(0xFFE2E6FB))))
                    add(ThemePresetSwatch(ThemePreset.DARK, R.string.dark, listOf(Color(0xFF1C1B1F), Color(0xFF2A2830))))
                    if (supportsDynamicColor) {
                        add(ThemePresetSwatch(ThemePreset.DYNAMIC, R.string.theme_preset_dynamic, listOf(Color(0xFF6750A4), Color(0xFF4285F4))))
                    }
                    add(ThemePresetSwatch(ThemePreset.PURE_BLACK, R.string.theme_preset_amoled, listOf(Color(0xFF000000), Color(0xFF1C1B1F))))
                }
            }

            SettingsSearchHighlight(
                targetKey = R.string.theme_presets,
                activeKey = highlightTarget,
                extraKeys = setOf(R.string.dynamic_color),
                onHighlightComplete = { highlightTarget = null },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) { highlightModifier ->
                ExpressiveSettingsCard(
                    modifier = highlightModifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.theme_presets),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.theme_presets_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        baseThemeSwatches.forEach { option ->
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                ThemeSwatchChip(
                                    modifier = Modifier.fillMaxWidth(),
                                    label = stringResource(option.labelRes),
                                    colors = option.colors,
                                    isSelected = selectedThemePreset == option.preset,
                                    enabled = option.preset != ThemePreset.PURE_BLACK || allowPureBlackPreset,
                                    onClick = { viewModel.toggleThemePreset(option.preset) }
                                )
                            }
                        }
                    }
                }
            }


            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                SettingsSearchHighlight(
                    targetKey = R.string.theme_color,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsItem(
                        modifier = highlightModifier.alpha(themeControlsAlpha),
                        headlineContent = stringResource(R.string.theme_color),
                        supportingContent = stringResource(R.string.theme_color_description),
                        trailingContent = {
                            val previewColor = customThemeColorHex.toColorOrNull() ?: MaterialTheme.colorScheme.surface
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outline,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .background(previewColor, RoundedCornerShape(12.dp))
                            )
                        },
                        enabled = canAdjustThemeColor,
                        onClick = { showThemeColorPicker = true }
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.accent_color,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsItem(
                        modifier = highlightModifier.alpha(accentControlsAlpha),
                        headlineContent = stringResource(R.string.accent_color),
                        supportingContent = stringResource(R.string.accent_color_description),
                        trailingContent = {
                            val previewColor = customAccentColorHex.toColorOrNull() ?: MaterialTheme.colorScheme.primary
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outline,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .background(previewColor, RoundedCornerShape(12.dp))
                            )
                        },
                        enabled = canAdjustAccentColor,
                        onClick = { showAccentPicker = true }
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.pure_black_follow_system,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(R.string.pure_black_follow_system),
                        supportingContent = stringResource(R.string.pure_black_follow_system_description),
                        trailingContent = {
                            ExpressiveSettingsSwitch(
                                checked = pureBlackOnSystemDark,
                                onCheckedChange = viewModel::setPureBlackOnSystemDark,
                                enabled = theme == Theme.SYSTEM
                            )
                        },
                        enabled = theme == Theme.SYSTEM,
                        onClick = { viewModel.setPureBlackOnSystemDark(!pureBlackOnSystemDark) }
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.hide_main_tab_labels,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        preference = prefs.hideMainTabLabels,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.hide_main_tab_labels,
                        description = R.string.hide_main_tab_labels_description
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.disable_main_tab_swipe,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        preference = prefs.disableMainTabSwipe,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.disable_main_tab_swipe,
                        description = R.string.disable_main_tab_swipe_description
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.disable_patch_selection_tab_swipe,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        preference = prefs.disablePatchSelectionTabSwipe,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.disable_patch_selection_tab_swipe,
                        description = R.string.disable_patch_selection_tab_swipe_description
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.hide_patch_profiles_tab,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        value = !showPatchProfilesTab,
                        onValueChange = { hide ->
                            viewModel.viewModelScope.launch {
                                prefs.showPatchProfilesTab.update(!hide)
                            }
                        },
                        headline = R.string.hide_patch_profiles_tab,
                        description = R.string.hide_patch_profiles_tab_description,
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.hide_tools_tab,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        value = !showToolsTab,
                        onValueChange = { hide ->
                            viewModel.viewModelScope.launch {
                                prefs.showToolsTab.update(!hide)
                            }
                        },
                        headline = R.string.hide_tools_tab,
                        description = R.string.hide_tools_tab_description,
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.custom_background_image,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    Column(
                        modifier = highlightModifier.fillMaxWidth()
                    ) {
                        ExpressiveSettingsItem(
                            headlineContent = stringResource(R.string.custom_background_image),
                            supportingContent = stringResource(R.string.custom_background_image_description),
                            onClick = {
                                showCustomBackgroundImagePicker = true
                            }
                        )

                        if (customBackgroundPreviewUri != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showCustomBackgroundImagePreview = !showCustomBackgroundImagePreview }
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.custom_background_image_preview),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Icon(
                                    imageVector = if (showCustomBackgroundImagePreview) {
                                        Icons.Outlined.ExpandLess
                                    } else {
                                        Icons.Outlined.ExpandMore
                                    },
                                    contentDescription = if (showCustomBackgroundImagePreview) {
                                        stringResource(R.string.collapse_content)
                                    } else {
                                        stringResource(R.string.expand_content)
                                    },
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            AnimatedVisibility(visible = showCustomBackgroundImagePreview) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
                                ) {
                                    AsyncImage(
                                        model = customBackgroundPreviewUri,
                                        contentDescription = stringResource(R.string.custom_background_image_preview),
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 140.dp, max = 220.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.custom_background_image_transparency,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    val hasCustomBackground = customBackgroundImageUri.isNotBlank()
                    val clampedOpacity = customBackgroundImageOpacity.coerceIn(0f, 1f)
                    val transparencyPercent = (clampedOpacity * 100f).roundToInt()
                    Column(
                        modifier = highlightModifier
                            .fillMaxWidth()
                            .alpha(if (hasCustomBackground) 1f else 0.5f)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.custom_background_image_transparency),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ) {
                                Text(
                                    text = "$transparencyPercent%",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.custom_background_image_transparency_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = clampedOpacity,
                            onValueChange = { value ->
                                if (hasCustomBackground) {
                                    viewModel.setCustomBackgroundImageOpacity(value)
                                }
                            },
                            enabled = hasCustomBackground,
                            valueRange = 0f..1f
                        )
                    }
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.clear_custom_background_image,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(R.string.clear_custom_background_image),
                        supportingContent = stringResource(R.string.clear_custom_background_image_description),
                        enabled = customBackgroundImageUri.isNotBlank(),
                        onClick = {
                            if (customBackgroundImageUri.isNotBlank()) {
                                viewModel.clearCustomBackgroundImageUri(context)
                            }
                        }
                    )
                }
            }
            val accentPresets = remember {
                listOf(
                    Color(0xFF6750A4),
                    Color(0xFF386641),
                    Color(0xFF0061A4),
                    Color(0xFF8E24AA),
                    Color(0xFFEF6C00),
                    Color(0xFF00897B),
                    Color(0xFFD81B60),
                    Color(0xFF5C6BC0),
                    Color(0xFF43A047),
                    Color(0xFFFF7043),
                    Color(0xFF1DE9B6),
                    Color(0xFFFFC400),
                    Color(0xFF00B8D4),
                    Color(0xFFBA68C8)
                )
            }
            val selectedAccentArgb = customAccentColorHex.toColorOrNull()?.toArgb()
            SettingsSearchHighlight(
                targetKey = R.string.accent_color_presets,
                activeKey = highlightTarget,
                onHighlightComplete = { highlightTarget = null }
            ) { highlightModifier ->
                Text(
                    text = stringResource(R.string.accent_color_presets),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = highlightModifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .alpha(accentControlsAlpha)
                )
            }
            Text(
                text = stringResource(R.string.accent_color_presets_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .alpha(accentControlsAlpha)
            )
            val swatchSize = 40.dp
            val swatchSpacing = 12.dp
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .alpha(accentControlsAlpha)
            ) {
                val maxColumns = ((maxWidth + swatchSpacing) / (swatchSize + swatchSpacing))
                    .toInt()
                    .coerceAtLeast(4)
                    .coerceAtMost(accentPresets.size.coerceAtLeast(1))
                val gridColumns = (maxColumns downTo 1).firstOrNull {
                    accentPresets.isNotEmpty() && accentPresets.size % it == 0
                } ?: maxColumns
                val gridRows = (accentPresets.size + gridColumns - 1) / gridColumns
                val gridHeight = (swatchSize * gridRows) + (swatchSpacing * (gridRows - 1))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(gridHeight),
                    horizontalArrangement = Arrangement.spacedBy(swatchSpacing),
                    verticalArrangement = Arrangement.spacedBy(swatchSpacing),
                    userScrollEnabled = false
                ) {
                    items(accentPresets.size) { index ->
                        val preset = accentPresets[index]
                        val isSelected = selectedAccentArgb != null && preset.toArgb() == selectedAccentArgb
                        Box(
                            modifier = Modifier
                                .size(swatchSize)
                                .clip(RoundedCornerShape(14.dp))
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .background(preset, RoundedCornerShape(12.dp))
                                .clickable(enabled = canAdjustAccentColor) {
                                    viewModel.setCustomAccentColor(preset)
                                }
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(18.dp)
                                        .background(
                                            MaterialTheme.colorScheme.surface,
                                            CircleShape
                                        )
                                        .border(
                                            1.dp,
                                            MaterialTheme.colorScheme.primary,
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            SettingsSearchHighlight(
                targetKey = R.string.theme_preview_title,
                activeKey = highlightTarget,
                onHighlightComplete = { highlightTarget = null }
            ) { highlightModifier ->
                Text(
                    text = stringResource(R.string.theme_preview_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = highlightModifier.padding(horizontal = 16.dp)
                )
            }
            Text(
                text = stringResource(R.string.theme_preview_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            ThemePreview(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
            ExpressiveThemePreview(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 0.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            )
            Spacer(modifier = Modifier.height(16.dp))

            SettingsSearchHighlight(
                targetKey = R.string.theme_reset,
                activeKey = highlightTarget,
                onHighlightComplete = { highlightTarget = null }
            ) { highlightModifier ->
                FilledTonalButton(
                    onClick = { viewModel.resetThemeSettings() },
                    modifier = highlightModifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                ) {
                    Text(stringResource(R.string.theme_reset))
                }
            }

            GroupHeader(stringResource(R.string.language_settings))
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                SettingsSearchHighlight(
                    targetKey = R.string.app_language,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(R.string.app_language),
                        supportingContent = stringResource(selectedLanguageLabel),
                        onClick = { showLanguageDialog = true }
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun isSupportedBackgroundImageFile(path: Path, extensions: Set<String>): Boolean {
    val extension = path.fileName?.toString()
        ?.substringAfterLast('.', "")
        ?.lowercase()
        .orEmpty()
    return extension in extensions
}

private data class ThemePresetSwatch(val preset: ThemePreset, @StringRes val labelRes: Int, val colors: List<Color>)
private data class LanguageOption(val code: String, @StringRes val labelRes: Int)

@Composable
private fun ThemeSwatchChip(
    modifier: Modifier = Modifier,
    label: String,
    colors: List<Color>,
    isSelected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val swatchAlpha = if (enabled) 1f else 0.5f
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .alpha(swatchAlpha)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(14.dp)
                )
                .background(
                    brush = when {
                        colors.size >= 2 -> Brush.linearGradient(colors.take(2))
                        else -> Brush.linearGradient(colors.ifEmpty { listOf(MaterialTheme.colorScheme.primary) })
                    }
                )
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LanguageDialog(
    options: List<LanguageOption>,
    selectedCode: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = {
            Text(
                text = stringResource(R.string.language_dialog_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        text = {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(scrollState)
                    .padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(option.code) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = option.code == selectedCode,
                            onClick = { onSelect(option.code) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(option.labelRes),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    )
}

private fun hexToComposeColor(input: String): Color? {
    val normalized = input.trim().let { if (it.startsWith("#")) it else "#" + it }
    return runCatching { Color(AndroidColor.parseColor(normalized)) }.getOrNull()
}

@Composable
private fun ColorPickerDialog(
    @StringRes titleRes: Int,
    @StringRes previewLabelRes: Int,
    @StringRes resetLabelRes: Int,
    initialColor: Color,
    allowReset: Boolean,
    onReset: () -> Unit,
    onConfirm: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    var red by rememberSaveable(initialColor) { mutableStateOf((initialColor.red * 255).roundToInt()) }
    var green by rememberSaveable(initialColor) { mutableStateOf((initialColor.green * 255).roundToInt()) }
    var blue by rememberSaveable(initialColor) { mutableStateOf((initialColor.blue * 255).roundToInt()) }
    var hexInput by rememberSaveable(initialColor) { mutableStateOf(initialColor.toHexString().uppercase()) }

    fun rgbToColor(r: Int, g: Int, b: Int) = Color(
        red = r.coerceIn(0, 255) / 255f,
        green = g.coerceIn(0, 255) / 255f,
        blue = b.coerceIn(0, 255) / 255f
    )

    val previewColor = rgbToColor(red, green, blue)
    fun updateHexFromRgb(r: Int = red, g: Int = green, b: Int = blue) {
        hexInput = rgbToColor(r, g, b).toHexString().uppercase()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(previewLabelRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .background(previewColor)
                )
                TextField(
                    value = hexInput,
                    onValueChange = { value ->
                        val input = value.trim().uppercase().let {
                            if (it.startsWith("#")) it else "#" + it
                        }
                        hexInput = input
                        hexToComposeColor(input)?.let { color ->
                            red = (color.red * 255).roundToInt()
                            green = (color.green * 255).roundToInt()
                            blue = (color.blue * 255).roundToInt()
                        }
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                    shape = RoundedCornerShape(12.dp)
                )
                ColorChannelSlider(
                    label = stringResource(R.string.color_channel_red),
                    value = red,
                    trackColor = Color.Red,
                    onValueChange = {
                        red = it
                        updateHexFromRgb(it, green, blue)
                    }
                )
                ColorChannelSlider(
                    label = stringResource(R.string.color_channel_green),
                    value = green,
                    trackColor = Color.Green,
                    onValueChange = {
                        green = it
                        updateHexFromRgb(red, it, blue)
                    }
                )
                ColorChannelSlider(
                    label = stringResource(R.string.color_channel_blue),
                    value = blue,
                    trackColor = Color.Blue,
                    onValueChange = {
                        blue = it
                        updateHexFromRgb(red, green, it)
                    }
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        maxItemsInEachRow = 2
                    ) {
                        if (allowReset) {
                            OutlinedButton(
                                modifier = Modifier.defaultMinSize(
                                    minWidth = ButtonDefaults.MinWidth,
                                    minHeight = ButtonDefaults.MinHeight
                                ),
                                onClick = {
                                    onReset()
                                    onDismiss()
                                }
                            ) {
                                Text(
                                    text = stringResource(resetLabelRes),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                        TextButton(
                            modifier = Modifier.defaultMinSize(
                                minWidth = ButtonDefaults.MinWidth,
                                minHeight = ButtonDefaults.MinHeight
                            ),
                            onClick = onDismiss
                        ) {
                            Text(
                                text = stringResource(R.string.cancel),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        FilledTonalButton(
                            modifier = Modifier.defaultMinSize(
                                minWidth = ButtonDefaults.MinWidth,
                                minHeight = ButtonDefaults.MinHeight
                            ),
                            onClick = {
                                onConfirm(previewColor)
                                onDismiss()
                            }
                        ) {
                            Text(
                                text = stringResource(R.string.apply),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

@Composable
private fun ColorChannelSlider(
    label: String,
    value: Int,
    trackColor: Color,
    onValueChange: (Int) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value.toString(), style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = 0f..255f,
            colors = SliderDefaults.colors(
                activeTrackColor = trackColor,
                inactiveTrackColor = trackColor.copy(alpha = 0.3f),
                thumbColor = trackColor
            )
        )
    }
}

@Composable
private fun ThemePreview(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clip(shape),
        shape = shape,
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            RoundedCornerShape(8.dp)
                        )
                ) {
                    Text(
                        text = "UR",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.theme_preview_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpressiveThemePreview(modifier: Modifier = Modifier) {
    ExpressiveSettingsCard(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        )
                    )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    tonalElevation = 2.dp
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.Palette, contentDescription = null)
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.theme_preview_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp))
                        Text(
                            text = stringResource(R.string.theme_preview_title),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.secondary,
                    MaterialTheme.colorScheme.tertiary
                ).forEach { swatch ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = swatch,
                        tonalElevation = 1.dp,
                        modifier = Modifier.size(18.dp)
                    ) {}
                }
            }
        }
    }
}
