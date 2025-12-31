package app.revanced.manager.ui.screen.settings

import app.revanced.manager.ui.model.navigation.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsSearchTarget(
    val destination: Settings.Destination,
    val targetId: Int
)

object SettingsSearchState {
    private val mutableTarget = MutableStateFlow<SettingsSearchTarget?>(null)
    val target: StateFlow<SettingsSearchTarget?> = mutableTarget.asStateFlow()

    fun setTarget(target: SettingsSearchTarget) {
        mutableTarget.value = target
    }

    fun clear() {
        mutableTarget.value = null
    }
}
