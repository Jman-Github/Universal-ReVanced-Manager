package app.revanced.manager.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitHubRelease(
    @SerialName("tag_name")
    val tagName: String,
    val body: String? = null,
    val name: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at")
    val publishedAt: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url")
    val downloadUrl: String,
    @SerialName("content_type")
    val contentType: String? = null,
)

@Serializable
data class GitHubContributor(
    val login: String,
    @SerialName("avatar_url")
    val avatarUrl: String,
)
