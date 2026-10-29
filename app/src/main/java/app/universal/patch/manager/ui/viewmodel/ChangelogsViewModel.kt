package app.universal.patch.manager.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.patch.manager.R
import app.universal.patch.manager.network.api.UpmAPI
import app.universal.patch.manager.network.dto.ReVancedAsset
import app.universal.patch.manager.network.utils.getOrThrow
import app.universal.patch.manager.util.uiSafe
import kotlinx.coroutines.launch

class ChangelogsViewModel(
    private val api: UpmAPI,
    private val app: Application,
) : ViewModel() {
    var releaseInfo: ReVancedAsset? by mutableStateOf(null)
        private set

    init {
        viewModelScope.launch {
            uiSafe(app, R.string.changelog_download_fail, "Failed to download changelog") {
                releaseInfo = api.getLatestAppInfo().getOrThrow()
            }
        }
    }
}
