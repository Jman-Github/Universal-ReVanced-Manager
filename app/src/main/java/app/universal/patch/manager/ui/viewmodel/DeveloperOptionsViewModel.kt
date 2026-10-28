package app.universal.patch.manager.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.patch.manager.R
import app.universal.patch.manager.domain.bundles.RemotePatchBundle
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import app.universal.patch.manager.util.toast
import app.universal.patch.manager.util.uiSafe
import kotlinx.coroutines.launch

class DeveloperOptionsViewModel(
    val prefs: PreferencesManager,
    private val app: Application,
    private val patchBundleRepository: PatchBundleRepository
) : ViewModel() {
    fun redownloadBundles() = viewModelScope.launch {
        uiSafe(app, R.string.patches_download_fail, RemotePatchBundle.updateFailMsg) {
            app.toast(app.getString(R.string.patches_force_download_started))
            val downloaded = patchBundleRepository.redownloadRemoteBundles()
            if (!downloaded) {
                app.toast(app.getString(R.string.patches_force_download_none))
            }
        }
    }

    fun resetBundles() = viewModelScope.launch {
        patchBundleRepository.reset()
    }
}
