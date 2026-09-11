package app.urv.manager.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitLabRelease(
    @SerialName("tag_name")
    val tagName: String,
    val name: String? = null,
    val description: String? = null,
    @SerialName("released_at")
    val releasedAt: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("upcoming_release")
    val upcomingRelease: Boolean = false,
    val assets: GitLabReleaseAssets = GitLabReleaseAssets()
)

@Serializable
data class GitLabReleaseAssets(
    val links: List<GitLabReleaseLink> = emptyList()
)

@Serializable
data class GitLabReleaseLink(
    val name: String,
    val url: String,
    @SerialName("direct_asset_url")
    val directAssetUrl: String? = null
)
