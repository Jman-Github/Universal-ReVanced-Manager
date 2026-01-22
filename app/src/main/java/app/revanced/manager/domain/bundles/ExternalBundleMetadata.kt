package app.revanced.manager.domain.bundles

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
    val isPrerelease: Boolean? = null
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
}
