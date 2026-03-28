package app.urv.manager.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.revanced.manager.R
import app.urv.manager.domain.bundles.RemotePatchBundle
import app.urv.manager.domain.manager.PreferencesManager
import app.urv.manager.domain.repository.PatchBundleRepository
import app.urv.manager.util.uiSafe
import kotlinx.coroutines.launch

class DeveloperOptionsViewModel(
    val prefs: PreferencesManager,
    private val app: Application,
    private val patchBundleRepository: PatchBundleRepository
) : ViewModel() {
    fun redownloadBundles() = viewModelScope.launch {
        uiSafe(app, R.string.patches_download_fail, RemotePatchBundle.updateFailMsg) {
            patchBundleRepository.redownloadRemoteBundles()
        }
    }

    fun resetBundles() = viewModelScope.launch {
        patchBundleRepository.reset()
    }
}
