package app.revanced.manager.ui.screen.settings

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewModelScope
import app.universal.revanced.manager.R
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.ui.component.AppTopBar
import app.revanced.manager.ui.component.ColumnWithScrollbar
import app.revanced.manager.ui.component.GroupHeader
import app.revanced.manager.ui.component.haptics.HapticRadioButton
import app.revanced.manager.ui.component.settings.BooleanItem
import app.revanced.manager.ui.component.settings.SettingsListItem
import app.revanced.manager.ui.theme.Theme
import app.revanced.manager.ui.viewmodel.GeneralSettingsViewModel
import app.revanced.manager.util.toColorOrNull
import app.revanced.manager.util.toHexString
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsScreen(
    onBackClick: () -> Unit,
    viewModel: GeneralSettingsViewModel = koinViewModel()
) {
    val prefs = viewModel.prefs
    val coroutineScope = viewModel.viewModelScope
    var showThemePicker by rememberSaveable { mutableStateOf(false) }
    var showAccentPicker by rememberSaveable { mutableStateOf(false) }

    if (showThemePicker) {
        ThemePicker(
            onDismiss = { showThemePicker = false },
            onConfirm = { viewModel.setTheme(it) }
        )
    }

    val customAccentColorHex by prefs.customAccentColor.getAsState()
    if (showAccentPicker) {
        val currentAccent = customAccentColorHex.toColorOrNull()
        AccentColorPickerDialog(
            initialColor = currentAccent ?: MaterialTheme.colorScheme.primary,
            allowReset = currentAccent != null,
            onReset = {
                viewModel.setCustomAccentColor(null)
            },
            onConfirm = { color -> viewModel.setCustomAccentColor(color) },
            onDismiss = { showAccentPicker = false }
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

            val theme by prefs.theme.getAsState()
            SettingsListItem(
                modifier = Modifier.clickable { showThemePicker = true },
                headlineContent = stringResource(R.string.theme),
                supportingContent = stringResource(R.string.theme_description),
                trailingContent = {
                    FilledTonalButton(
                        onClick = {
                            showThemePicker = true
                        }
                    ) {
                        Text(stringResource(theme.displayName))
                    }
                }
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BooleanItem(
                    preference = prefs.dynamicColor,
                    coroutineScope = coroutineScope,
                    headline = R.string.dynamic_color,
                    description = R.string.dynamic_color_description
                )
            }
            SettingsListItem(
                modifier = Modifier.clickable { showAccentPicker = true },
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
                }
            )
            AnimatedVisibility(theme != Theme.LIGHT) {
                BooleanItem(
                    preference = prefs.pureBlackTheme,
                    coroutineScope = coroutineScope,
                    headline = R.string.pure_black_theme,
                    description = R.string.pure_black_theme_description
                )
            }
        }
    }
}

@Composable
private fun ThemePicker(
    onDismiss: () -> Unit,
    onConfirm: (Theme) -> Unit,
    prefs: PreferencesManager = koinInject()
) {
    var selectedTheme by rememberSaveable { mutableStateOf(prefs.theme.getBlocking()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme)) },
        text = {
            Column {
                Theme.entries.forEach {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedTheme = it },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HapticRadioButton(
                            selected = selectedTheme == it,
                            onClick = { selectedTheme = it })
                        Text(stringResource(it.displayName))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(selectedTheme)
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.apply))
            }
        }
    )
}

@Composable
private fun AccentColorPickerDialog(
    initialColor: Color,
    allowReset: Boolean,
    onReset: () -> Unit,
    onConfirm: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    var red by rememberSaveable(initialColor) { mutableStateOf((initialColor.red * 255).roundToInt()) }
    var green by rememberSaveable(initialColor) { mutableStateOf((initialColor.green * 255).roundToInt()) }
    var blue by rememberSaveable(initialColor) { mutableStateOf((initialColor.blue * 255).roundToInt()) }

    val previewColor = Color(
        red = red.coerceIn(0, 255) / 255f,
        green = green.coerceIn(0, 255) / 255f,
        blue = blue.coerceIn(0, 255) / 255f
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.accent_color_picker_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.accent_color_preview),
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
                Text(
                    text = previewColor.toHexString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ColorChannelSlider(
                    label = stringResource(R.string.color_channel_red),
                    value = red,
                    trackColor = Color.Red,
                    onValueChange = { red = it }
                )
                ColorChannelSlider(
                    label = stringResource(R.string.color_channel_green),
                    value = green,
                    trackColor = Color.Green,
                    onValueChange = { green = it }
                )
                ColorChannelSlider(
                    label = stringResource(R.string.color_channel_blue),
                    value = blue,
                    trackColor = Color.Blue,
                    onValueChange = { blue = it }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(previewColor)
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (allowReset) {
                    TextButton(
                        onClick = {
                            onReset()
                            onDismiss()
                        }
                    ) {
                        Text(stringResource(R.string.accent_color_reset))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
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
