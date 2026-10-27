package app.urv.manager.domain.bundles

import app.urv.manager.network.dto.ReVancedAsset
import java.net.URI
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Reseam's patches.json publishing index and official single-release API response. */
// https://reseam.app/docs/patches/publishing/
// https://git.reseam.app/reseam/manager/src/commit/3cab4b1fad/composeApp/src/jvmCommonMain/kotlin/app/reseam/manager/data/ReleaseIndex.kt
internal object ReseamBundleIndex {
    data class Release(val asset: ReVancedAsset, val publicKey: String?)

    fun parseAll(json: JsonObject, endpoint: String): List<Release>? {
        if (json["bundle"] !is JsonObject) return null
        val releases = json["releases"] as? JsonArray
            ?: return listOfNotNull(parse(json, endpoint, null))
        require(releases.isNotEmpty()) { "Reseam index has no releases." }
        return releases.map { release ->
            require(release is JsonObject) { "Invalid Reseam release." }
            requireNotNull(parse(JsonObject(json + ("releases" to JsonArray(listOf(release)))), endpoint, null))
        }
    }

    fun parse(json: JsonObject, endpoint: String, prerelease: Boolean?): Release? {
        val publisher = json["bundle"] as? JsonObject ?: return null
        val releases = (json["releases"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?: listOfNotNull(json["release"] as? JsonObject)
        require(releases.isNotEmpty()) { "Reseam index has no releases." }
        val release = releases.firstOrNull {
            prerelease == null || ((it["prerelease"] as? JsonPrimitive)?.booleanOrNull ?: false) == prerelease
        } ?: error("Reseam index has no release in the selected channel.")
        fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
        val downloadUrl = URI(endpoint).resolve(requireNotNull(release.text("download_url"))).toString()
        require(URI(downloadUrl).scheme in setOf("https", "http")) { "Invalid Reseam download URL." }
        val key = publisher.text("public_key")?.also {
            require(it.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid Reseam publisher key." }
        }
        return Release(
            ReVancedAsset(
                downloadUrl = downloadUrl,
                // The index does not publish dates. A fixed value keeps update checks deterministic.
                createdAt = LocalDateTime(1970, 1, 1, 0, 0),
                pageUrl = endpoint,
                description = publisher.text("name").orEmpty(),
                version = requireNotNull(release.text("version")).also {
                    require(it.isNotBlank()) { "Missing Reseam release version." }
                }
            ),
            key
        )
    }
}
