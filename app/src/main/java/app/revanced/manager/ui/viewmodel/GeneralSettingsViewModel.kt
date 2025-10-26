package app.revanced.manager.ui.viewmodel

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.ui.theme.Theme
import app.revanced.manager.util.resetListItemColorsCached
import app.revanced.manager.util.toHexString
import kotlinx.coroutines.launch

class GeneralSettingsViewModel(
    val prefs: PreferencesManager
) : ViewModel() {
    fun setTheme(theme: Theme) = viewModelScope.launch {
        prefs.theme.update(theme)
        resetListItemColorsCached()
    }

    fun setCustomAccentColor(color: Color?) = viewModelScope.launch {
        val value = color?.toHexString().orEmpty()
        prefs.customAccentColor.update(value)
        resetListItemColorsCached()
    }
}
