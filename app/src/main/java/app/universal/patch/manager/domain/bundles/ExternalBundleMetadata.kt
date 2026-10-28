package app.universal.patch.manager.domain.bundles

import app.universal.patch.manager.network.dto.ExternalBundleSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ExternalBundleMetadata(
    val bundleId: Int,
    val downloadUrl: String,
    val signatureDownloadUrl: String? = null,
    val version: String,
    val createdAt: String? = null,
    val description: String? = null,
    val ownerName: String? = null,
    val repoName: String? = null,
    val isPrerelease: Boolean? = null,
    val iconUrl: String? = null,
    val ownerAvatarUrl: String? = null,
    val sourceUrl: String? = null,
    val apiHost: String? = null,
    val fileHash: String? = null
)

/** Use identical persisted metadata for discovery imports and migrated API entries. */
internal fun ExternalBundleSnapshot.toExternalBundleMetadata(
    prerelease: Boolean? = isPrerelease,
    fallbackDownloadUrl: String? = null
) = ExternalBundleMetadata(
    bundleId = bundleId,
    downloadUrl = requireNotNull(downloadUrl?.trim()?.takeIf(String::isNotBlank)
        ?: fallbackDownloadUrl?.trim()?.takeIf(String::isNotBlank)) { "External bundle downloadUrl cannot be null or blank" },
    signatureDownloadUrl = signatureDownloadUrl,
    version = version.ifBlank { "unknown" },
    createdAt = createdAt.takeUnless(String::isBlank),
    description = description,
    ownerName = ownerName.takeIf(String::isNotBlank),
    repoName = repoName.takeIf(String::isNotBlank),
    isPrerelease = prerelease,
    iconUrl = iconUrl?.takeIf(String::isNotBlank),
    ownerAvatarUrl = ownerAvatarUrl?.takeIf(String::isNotBlank),
    sourceUrl = sourceUrl,
    apiHost = apiHost,
    fileHash = fileHash
)

object ExternalBundleMetadataStore {
    private const val FILE_NAME = "external_bundle.json"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun read(directory: File): ExternalBundleMetadata? {
        val file = directory.resolve(FILE_NAME)
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<ExternalBundleMetadata>(file.readText()) }.getOrNull()
    }

    fun write(directory: File, metadata: ExternalBundleMetadata) {
        val file = directory.resolve(FILE_NAME)
        file.writeText(json.encodeToString(metadata))
    }

    fun clear(directory: File) {
        runCatching { directory.resolve(FILE_NAME).delete() }
    }
}
