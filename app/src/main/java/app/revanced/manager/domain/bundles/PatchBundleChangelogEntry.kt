package app.revanced.manager.domain.bundles

import app.revanced.manager.network.dto.ReVancedAsset
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable

@Serializable
data class PatchBundleChangelogEntry(
    val version: String,
    val description: String,
    val publishedAtMillis: Long? = null,
    val pageUrl: String? = null
) {
    companion object {
        fun fromAsset(asset: ReVancedAsset): PatchBundleChangelogEntry {
            val publishedAt = runCatching {
                asset.createdAt.toInstant(TimeZone.UTC).toEpochMilliseconds()
            }.getOrNull()

            return PatchBundleChangelogEntry(
                version = asset.version,
                description = asset.description,
                publishedAtMillis = publishedAt,
                pageUrl = asset.pageUrl
            )
        }
    }
}
