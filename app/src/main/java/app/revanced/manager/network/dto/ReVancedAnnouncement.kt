package app.revanced.manager.network.dto

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ReVancedAnnouncement(
    val id: Long,
    val author: String,
    val title: String,
    val content: String,
    val tags: List<String>,
    val attachments: List<String> = emptyList(),
    @SerialName("created_at")
    val createdAt: Instant,
    @SerialName("archived_at")
    val archivedAt: Instant? = null,
    val level: Int
)
