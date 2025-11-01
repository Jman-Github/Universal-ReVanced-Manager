package app.revanced.manager.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.revanced.manager.data.room.profile.PatchProfilePayload
import app.revanced.manager.domain.bundles.PatchBundleSource
import app.revanced.manager.domain.bundles.PatchBundleSource.Extensions.asRemoteOrNull
import app.revanced.manager.domain.bundles.RemotePatchBundle
import app.revanced.manager.domain.repository.DuplicatePatchProfileNameException
import app.revanced.manager.domain.repository.PatchBundleRepository
import app.revanced.manager.domain.repository.PatchProfile
import app.revanced.manager.domain.repository.PatchProfileRepository
import app.revanced.manager.domain.repository.remapLocalBundles
import app.revanced.manager.domain.repository.toConfiguration
import app.revanced.manager.util.mutableStateSetOf
import app.revanced.manager.util.tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    enum class ChangeUidResult {
        SUCCESS,
        PROFILE_OR_BUNDLE_NOT_FOUND,
        TARGET_NOT_FOUND
    }

    enum class RenameResult {
        SUCCESS,
        DUPLICATE_NAME,
        FAILED
    }

    val selectedProfiles = mutableStateSetOf<Int>()

    val profiles = combine(
        patchProfileRepository.profilesFlow(),
        patchBundleRepository.bundleInfoFlow,
        patchBundleRepository.sources
    ) { profiles, bundleInfoMap, sources ->
        val sourceMap = sources.associateBy { it.uid }
        val endpointToSource = sources.mapNotNull { source ->
            (source as? RemotePatchBundle)?.endpoint?.let { endpoint -> endpoint to source }
        }.toMap()
        val signatureMap = bundleInfoMap.mapValues { (_, info) ->
            info.patches.map { it.name.trim().lowercase() }.toSet()
        }
        val availableIds = profiles.map { it.uid }.toSet()
        selectedProfiles.retainAll(availableIds)
        profiles.map { profile ->
            val remappedPayload = profile.payload.remapLocalBundles(sources, signatureMap)
            val workingPayload = if (remappedPayload === profile.payload) {
                profile.payload
            } else {
                viewModelScope.launch(Dispatchers.Default) {
                    patchProfileRepository.updateProfile(
                        profile.uid,
                        profile.packageName,
                        profile.appVersion,
                        profile.name,
                        remappedPayload
                    )
                }
                remappedPayload
            }
            PatchProfileListItem(
                id = profile.uid,
                name = profile.name,
                packageName = profile.packageName,
                appVersion = profile.appVersion,
                bundleCount = workingPayload.bundles.size,
                bundleNames = workingPayload.bundles.map { bundle ->
                    val resolvedSource = sourceMap[bundle.bundleUid]
                        ?: bundle.sourceEndpoint?.let { endpointToSource[it] }
                    resolvedSource?.displayTitle
                        ?: bundle.displayName
                        ?: bundle.sourceName
                        ?: bundle.bundleUid.toString()
                },
                createdAt = profile.createdAt,
                bundleDetails = workingPayload.bundles.map { bundle ->
                    val resolvedSource = sourceMap[bundle.bundleUid]
                        ?: bundle.sourceEndpoint?.let { endpointToSource[it] }
                    val resolvedName = resolvedSource?.displayTitle ?: bundle.displayName ?: bundle.sourceName
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
        val sourcesList = patchBundleRepository.sources.first()
        val bundleInfoSnapshot = patchBundleRepository.bundleInfoFlow.first()
        val signatureMap = bundleInfoSnapshot.mapValues { (_, info) ->
            info.patches.map { it.name.trim().lowercase() }.toSet()
        }
        val remappedPayload = profile.payload.remapLocalBundles(sourcesList, signatureMap)
        val workingProfile = if (remappedPayload === profile.payload) profile else profile.copy(payload = remappedPayload)
        val scopedBundles = patchBundleRepository
            .scopedBundleInfoFlow(workingProfile.packageName, workingProfile.appVersion)
            .first()
            .associateBy { it.uid }
        val sources = sourcesList.associateBy { it.uid }
        val configuration = workingProfile.toConfiguration(scopedBundles, sources)
        val availableBundles = workingProfile.payload.bundles.size - configuration.missingBundles.size
        val universalPatchNames = bundleInfoSnapshot
            .values
            .flatMap { it.patches }
            .filter { it.compatiblePackages == null }
            .mapTo(mutableSetOf()) { it.name.lowercase() }
        val containsUniversalPatches = workingProfile.payload.bundles.any { bundle ->
            val info = scopedBundles[bundle.bundleUid]
            bundle.patches.any { patchName ->
                val normalized = patchName.lowercase()
                val matchesScoped = info?.patches?.any { it.name.equals(patchName, true) && it.compatiblePackages == null } == true
                matchesScoped || universalPatchNames.contains(normalized)
            }
        }
        return PatchProfileLaunchData(
            profile = workingProfile,
            missingBundles = configuration.missingBundles,
            changedBundles = configuration.changedBundles,
            availableBundleCount = availableBundles,
            containsUniversalPatches = containsUniversalPatches
        )
    }

    suspend fun deleteProfile(profileId: Int) {
        selectedProfiles.remove(profileId)
        patchProfileRepository.deleteProfile(profileId)
    }

    suspend fun renameProfile(profileId: Int, name: String): RenameResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return RenameResult.FAILED
        return withContext(Dispatchers.Default) {
            val profile = patchProfileRepository.getProfile(profileId)
                ?: return@withContext RenameResult.FAILED
            try {
                val updated = patchProfileRepository.updateProfile(
                    uid = profileId,
                    packageName = profile.packageName,
                    appVersion = profile.appVersion,
                    name = trimmed,
                    payload = profile.payload
                )
                if (updated != null) RenameResult.SUCCESS else RenameResult.FAILED
            } catch (duplicate: DuplicatePatchProfileNameException) {
                RenameResult.DUPLICATE_NAME
            } catch (t: Exception) {
                Log.e(tag, "Failed to rename patch profile", t)
                RenameResult.FAILED
            }
        }
    }

    suspend fun changeLocalBundleUid(
        profileId: Int,
        currentUid: Int,
        newUid: Int
    ): ChangeUidResult = withContext(Dispatchers.Default) {
        val profile = patchProfileRepository.getProfile(profileId)
            ?: return@withContext ChangeUidResult.PROFILE_OR_BUNDLE_NOT_FOUND
        val sourcesList = patchBundleRepository.sources.first()
        val remappedPayload = profile.payload.remapLocalBundles(sourcesList)
        val bundles = remappedPayload.bundles.toMutableList()
        val bundleIndex = bundles.indexOfFirst { it.bundleUid == currentUid }
        if (bundleIndex == -1) return@withContext ChangeUidResult.PROFILE_OR_BUNDLE_NOT_FOUND

        val targetSource = sourcesList.firstOrNull { it.uid == newUid && it.asRemoteOrNull == null }
            ?: return@withContext ChangeUidResult.TARGET_NOT_FOUND

        val updatedBundle = bundles[bundleIndex].copy(
            bundleUid = targetSource.uid,
            displayName = targetSource.displayTitle,
            sourceName = targetSource.patchBundle?.manifestAttributes?.name ?: targetSource.name,
            sourceEndpoint = null
        )
        bundles[bundleIndex] = updatedBundle
        val updatedPayload = remappedPayload.copy(bundles = bundles.toList())

        patchProfileRepository.updateProfile(
            uid = profileId,
            packageName = profile.packageName,
            appVersion = profile.appVersion,
            name = profile.name,
            payload = updatedPayload
        )
        ChangeUidResult.SUCCESS
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
    val availableBundleCount: Int,
    val containsUniversalPatches: Boolean
)

