package app.universal.patch.manager.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.patch.manager.network.api.UpmAPI
import app.universal.patch.manager.network.dto.ReVancedGitRepository
import app.universal.patch.manager.network.utils.getOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContributorViewModel(private val reVancedAPI: UpmAPI) : ViewModel() {
    var repositories: List<ReVancedGitRepository>? by mutableStateOf(null)
    	private set

    init {
        viewModelScope.launch {
            repositories = withContext(Dispatchers.IO) {
                reVancedAPI.getContributors().getOrNull()
            }
        }
    }
}
