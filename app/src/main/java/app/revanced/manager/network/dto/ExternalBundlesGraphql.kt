package app.revanced.manager.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
)

@Serializable
data class RefreshJobsQueryData(
    @SerialName("refresh_jobs")
    val refreshJobs: List<RefreshJobNode> = emptyList(),
)

@Serializable
data class BundleNode(
    val id: Int,
    @SerialName("bundle_type")
    val bundleType: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    val description: String? = null,
    @SerialName("download_url")
    val downloadUrl: String? = null,
    @SerialName("signature_download_url")
    val signatureDownloadUrl: String? = null,
    @SerialName("is_prerelease")
    val isPrerelease: Boolean = false,
    val version: String? = null,
    val source: SourceNode? = null,
    @SerialName("patches_aggregate")
    val patchesAggregate: PatchesAggregate? = null,
    val patches: List<PatchNode>? = null,
)

@Serializable
data class RefreshJobNode(
    @SerialName("started_at")
    val startedAt: String? = null,
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
    val name: String? = null,
    val version: String? = null,
)
