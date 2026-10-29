package app.universal.patch.manager.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

@Serializable
data class GraphqlRequest(
    val query: String,
    val variables: JsonObject? = null,
)

@Serializable
data class GraphqlResponse<T>(
    val data: T? = null,
    val errors: List<GraphqlError>? = null,
)

@Serializable
data class GraphqlError(
    val message: String? = null,
)

@Serializable
data class BundlesQueryData(
    val bundle: List<BundleNode> = emptyList(),
    @SerialName("refresh_jobs")
    val refreshJobs: List<RefreshJobNode> = emptyList(),
    @SerialName("catalog_generation") val catalogGeneration: String? = null,
)

@Serializable
data class BundleNode(
    val id: Int,
    @SerialName("bundle_type")
    val bundleType: String? = null,
    @SerialName("icon_url") val iconUrl: String? = null,
    @SerialName("owner_avatar_url") val ownerAvatarUrl: String? = null,
    @SerialName("patch_count") val patchCount: Int? = null,
    @SerialName("metadata_status") val metadataStatus: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    val description: String? = null,
    @SerialName("download_url")
    val downloadUrl: String? = null,
    @SerialName("signature_download_url")
    val signatureDownloadUrl: String? = null,
    @SerialName("file_hash")
    val fileHash: String? = null,
    @SerialName("need_patches_update")
    val needPatchesUpdate: Boolean = false,
    @SerialName("patcher_failure_fingerprint")
    val patcherFailureFingerprint: String? = null,
    @SerialName("is_prerelease")
    val isPrerelease: Boolean = false,
    val version: String? = null,
    val source: SourceNode? = null,
    @SerialName("patches_aggregate")
    val patchesAggregate: PatchesAggregate? = null,
    val patches: List<PatchNode>? = null,
    @Transient
    val patchMetadataVerified: Boolean = true,
    @SerialName("catalog_id") val catalogId: String? = null,
)

@Serializable
data class RefreshJobNode(
    @SerialName("started_at")
    val startedAt: String? = null,
    @SerialName("completed_at")
    val completedAt: String? = null,
    val status: String? = null,
)

@Serializable
data class SourceNode(
    val url: String? = null,
    @SerialName("source_metadatum")
    val sourceMetadata: SourceMetadata? = null,
)

@Serializable
data class SourceMetadata(
    @SerialName("owner_name")
    val ownerName: String? = null,
    @SerialName("owner_avatar_url")
    val ownerAvatarUrl: String? = null,
    @SerialName("bundle_icon_url") val bundleIconUrl: String? = null,
    @SerialName("repo_name")
    val repoName: String? = null,
    @SerialName("repo_description")
    val repoDescription: String? = null,
    @SerialName("repo_stars")
    val repoStars: Int? = null,
    @SerialName("repo_pushed_at")
    val repoPushedAt: String? = null,
    @SerialName("is_repo_archived")
    val isRepoArchived: Boolean? = null,
)

@Serializable
data class PatchesAggregate(
    val aggregate: AggregateCount? = null,
)

@Serializable
data class AggregateCount(
    val count: Int? = null,
)

@Serializable
data class PatchNode(
    val id: Int = 0,
    val name: String? = null,
    val description: String? = null,
    @SerialName("patch_packages")
    val patchPackages: List<PatchPackageNode> = emptyList(),
)

@Serializable
data class PatchPackageNode(
    @SerialName("package")
    val pkg: PackageNode? = null,
)

@Serializable
data class PackageNode(
    @SerialName("app_name") val appName: String? = null,
    @SerialName("icon_url") val iconUrl: String? = null,
    val name: String? = null,
    val version: String? = null,
)

@Serializable
data class PatchBundlesQueryData(val patches: List<PatchBundleNode> = emptyList())

@Serializable
data class PatchBundleNode(val bundle: BundleNode)

@Serializable
data class AppsQueryData(val packages: List<PackageNode> = emptyList())

@Serializable
data class SourcesQueryData(val source: List<SourceNode> = emptyList())

@Serializable
data class PatchesQueryData(val patch: List<PatchNode> = emptyList())

@Serializable
data class PatchLinksQueryData(val links: List<PatchPackageNode> = emptyList())

@Serializable
data class BundleIdsQueryData(val patch: List<BundleIdNode> = emptyList())

@Serializable
data class BundleIdNode(@SerialName("bundle_fk") val bundleId: Int)
