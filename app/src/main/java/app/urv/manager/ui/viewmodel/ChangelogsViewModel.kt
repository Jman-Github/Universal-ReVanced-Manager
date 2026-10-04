package app.urv.manager.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.revanced.manager.R
import app.urv.manager.network.api.ReVancedAPI
import app.urv.manager.network.dto.ReVancedAsset
import app.urv.manager.network.utils.getOrThrow
import app.urv.manager.util.uiSafe
import kotlinx.coroutines.launch

class ChangelogsViewModel(
    private val api: ReVancedAPI,
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
