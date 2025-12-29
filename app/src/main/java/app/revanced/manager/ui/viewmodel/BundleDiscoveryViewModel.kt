package app.revanced.manager.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.revanced.manager.R
import app.revanced.manager.domain.repository.PatchBundleRepository
import app.revanced.manager.network.api.ExternalBundlesApi
import app.revanced.manager.network.dto.ExternalBundleSnapshot
import app.revanced.manager.network.utils.getOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BundleDiscoveryViewModel(
    private val api: ExternalBundlesApi,
    private val patchBundleRepository: PatchBundleRepository,
    private val app: Application,
) : ViewModel() {
    var bundles: List<ExternalBundleSnapshot>? by mutableStateOf(null)
        private set

    var isLoading: Boolean by mutableStateOf(false)
        private set

    var errorMessage: String? by mutableStateOf(null)
        private set

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            val snapshot = withContext(Dispatchers.IO) {
                api.getSnapshot().getOrNull()
            }
            bundles = snapshot
            if (snapshot == null) {
                errorMessage = app.getString(R.string.patch_bundle_discovery_error)
            }
            isLoading = false
        }
    }

    fun importBundle(bundleId: Int, autoUpdate: Boolean, searchUpdate: Boolean) {
        viewModelScope.launch {
            val url = bundleEndpoint(bundleId)
            patchBundleRepository.createRemote(url, searchUpdate, autoUpdate)
        }
    }

    fun bundleEndpoint(bundleId: Int): String =
        "https://revanced-external-bundles.brosssh.com/bundles/id?id=$bundleId"
}
