package app.revanced.manager.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.revanced.manager.data.room.profile.PatchProfilePayload
import app.revanced.manager.domain.bundles.PatchBundleSource
import app.revanced.manager.domain.bundles.PatchBundleSource.Extensions.asRemoteOrNull
import app.revanced.manager.domain.bundles.RemotePatchBundle
import app.revanced.manager.domain.repository.PatchBundleRepository
import app.revanced.manager.domain.repository.PatchProfile
import app.revanced.manager.domain.repository.PatchProfileRepository
import app.revanced.manager.domain.repository.toConfiguration
import app.revanced.manager.util.mutableStateSetOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PatchProfileListItem(
    val id: Int,
    val name: String,
    val packageName: String,
    val appVersion: String?,
    val bundleCount: Int,
    val bundleNames: List<String>,
    val createdAt: Long,
    val bundleDetails: List<BundleDetail>
)

enum class BundleSourceType {
    Remote,
    Local
}

data class BundleDetail(
    val uid: Int,
    val displayName: String?,
    val patchCount: Int,
    val patches: List<String>,
    val isAvailable: Boolean,
    val type: BundleSourceType
)

class PatchProfilesViewModel(
    private val patchProfileRepository: PatchProfileRepository,
    private val patchBundleRepository: PatchBundleRepository
) : ViewModel() {
    enum class Event {
        DELETE_SELECTED,
        CANCEL
    }

    val selectedProfiles = mutableStateSetOf<Int>()

    val profiles = combine(
        patchProfileRepository.profilesFlow(),
        patchBundleRepository.bundleInfoFlow,
        patchBundleRepository.sources
    ) { profiles, _, sources ->
        val sourceMap = sources.associateBy { it.uid }
        val endpointToSource = sources.mapNotNull { source ->
            (source as? RemotePatchBundle)?.endpoint?.let { endpoint -> endpoint to source }
        }.toMap()
        val availableIds = profiles.map { it.uid }.toSet()
        selectedProfiles.retainAll(availableIds)
        profiles.map { profile ->
            PatchProfileListItem(
                id = profile.uid,
                name = profile.name,
                packageName = profile.packageName,
                appVersion = profile.appVersion,
                bundleCount = profile.payload.bundles.size,
                bundleNames = profile.payload.bundles.map { bundle ->
                    val resolvedSource = sourceMap[bundle.bundleUid]
                        ?: bundle.sourceEndpoint?.let { endpointToSource[it] }
                    resolvedSource?.displayTitle
                        ?: bundle.displayName
                        ?: bundle.bundleUid.toString()
                },
                createdAt = profile.createdAt,
                bundleDetails = profile.payload.bundles.map { bundle ->
                    val resolvedSource = sourceMap[bundle.bundleUid]
                        ?: bundle.sourceEndpoint?.let { endpointToSource[it] }
                    val resolvedName = resolvedSource?.displayTitle ?: bundle.displayName
                    val type = resolvedSource.determineType(bundle)
                    BundleDetail(
                        uid = bundle.bundleUid,
                        displayName = resolvedName,
                        patchCount = bundle.patches.size,
                        patches = bundle.patches,
                        isAvailable = resolvedSource != null,
                        type = type
                    )
                }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun resolveProfile(profileId: Int): PatchProfileLaunchData? {
        val profile = patchProfileRepository.getProfile(profileId) ?: return null
        val scopedBundles = patchBundleRepository
            .scopedBundleInfoFlow(profile.packageName, profile.appVersion)
            .first()
            .associateBy { it.uid }
        val sources = patchBundleRepository.sources.first().associateBy { it.uid }
        val configuration = profile.toConfiguration(scopedBundles, sources)
        val availableBundles = profile.payload.bundles.size - configuration.missingBundles.size
        return PatchProfileLaunchData(
            profile = profile,
            missingBundles = configuration.missingBundles,
            changedBundles = configuration.changedBundles,
            availableBundleCount = availableBundles
        )
    }

    suspend fun deleteProfile(profileId: Int) {
        selectedProfiles.remove(profileId)
        patchProfileRepository.deleteProfile(profileId)
    }

    fun toggleSelection(profileId: Int) {
        setSelection(profileId, profileId !in selectedProfiles)
    }

    fun handleEvent(event: Event) {
        when (event) {
            Event.CANCEL -> selectedProfiles.clear()
            Event.DELETE_SELECTED -> viewModelScope.launch(Dispatchers.Default) {
                val ids = selectedProfiles.toList()
                if (ids.isEmpty()) return@launch
                patchProfileRepository.deleteProfiles(ids)
                selectedProfiles.clear()
            }
        }
    }

    fun setSelection(profileId: Int, shouldSelect: Boolean) {
        if (shouldSelect) {
            selectedProfiles.add(profileId)
        } else {
            selectedProfiles.remove(profileId)
        }
    }

}

private fun PatchBundleSource?.determineType(bundle: PatchProfilePayload.Bundle): BundleSourceType {
    return when {
        this?.asRemoteOrNull != null -> BundleSourceType.Remote
        this != null -> BundleSourceType.Local
        bundle.sourceEndpoint != null -> BundleSourceType.Remote
        else -> BundleSourceType.Local
    }
}

data class PatchProfileLaunchData(
    val profile: PatchProfile,
    val missingBundles: Set<Int>,
    val changedBundles: Set<Int>,
    val availableBundleCount: Int
)
