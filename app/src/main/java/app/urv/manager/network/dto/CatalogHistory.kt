package app.urv.manager.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HistoricalRelease(
    val id: String,
    val name: String? = null,
    val tag: String? = null,
    val version: String? = null,
    @SerialName("is_prerelease") val isPrerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("release_url") val releaseUrl: String? = null,
    val availability: String? = null,
    @SerialName("bundle_ids") val bundleIds: List<String> = emptyList(),
    @SerialName("bundle_types") val bundleTypes: List<String> = emptyList(),
    val changelog: String? = null,
    @SerialName("release_notes") val releaseNotes: String? = null,
    @SerialName("metadata_document_url") val metadataDocumentUrl: String? = null,
)

@Serializable
data class HistoricalReleasePage(
    val generation: String,
    @SerialName("next_cursor") val nextCursor: String? = null,
    val data: List<HistoricalRelease> = emptyList(),
)

@Serializable
data class HistoricalReleasesData(
    @SerialName("historical_releases") val releases: HistoricalReleasePage,
)

@Serializable
data class HistoricalReleaseData(
    @SerialName("catalog_generation") val generation: String,
    @SerialName("historical_release") val release: HistoricalRelease? = null,
)

@Serializable
data class HistoricalBundlesData(
    @SerialName("catalog_generation") val generation: String,
    @SerialName("historical_bundles") val bundles: List<BundleNode> = emptyList(),
)

@Serializable
data class HistoricalDocumentData(
    @SerialName("catalog_generation") val generation: String,
    @SerialName("historical_release") val release: HistoricalDocumentRelease? = null,
)

@Serializable
data class HistoricalDocumentRelease(val document: HistoricalDocument)

@Serializable
data class HistoricalDocument(
    val encoding: String,
    val data: String,
    @SerialName("total_bytes") val totalBytes: Int,
    @SerialName("next_offset") val nextOffset: Int? = null,
)
