package app.revanced.manager.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExternalBundleSnapshot(
    val ownerName: String = "",
    val ownerAvatarUrl: String? = null,
    val repoName: String = "",
    val repoDescription: String? = null,
    val sourceUrl: String = "",
    val repoStars: Int = 0,
    val repoPushedAt: String? = null,
    val isRepoArchived: Boolean = false,
    val bundleId: Int = 0,
    val bundleType: String = "",
    val createdAt: String = "",
    val description: String? = null,
    val version: String = "",
    val downloadUrl: String? = null,
    val signatureDownloadUrl: String? = null,
    val isPrerelease: Boolean = false,
    val isBundleV3: Boolean = false,
    val patchCount: Int = 0,
    val patches: List<ExternalBundlePatch> = emptyList(),
)

@Serializable
data class ExternalBundlePatch(
    val name: String? = null,
    val description: String? = null,
    val compatiblePackages: List<ExternalBundlePackage> = emptyList(),
)

@Serializable
data class ExternalBundlePackage(
    val name: String = "",
    val versions: List<String?> = emptyList(),
)
