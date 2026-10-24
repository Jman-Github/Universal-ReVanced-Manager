package app.urv.manager.network.api

import app.urv.manager.network.dto.BundleNode
import kotlinx.serialization.json.*

/** Search metadata locally so every keystroke can reuse the same catalog page cache. */
internal fun CatalogFilters.matchesBundle(bundle: BundleNode): Boolean {
    if (sourceUrl.isNotBlank() && bundle.source?.url != sourceUrl) return false
    if (version.isNotBlank() && bundle.version != version) return false
    if (bundleType.isNotBlank() && bundle.bundleType != bundleType) return false
    if (release == "release" && bundle.isPrerelease) return false
    if (release == "prerelease" && !bundle.isPrerelease) return false
    val text = query.trim()
    val metadata = bundle.source?.sourceMetadata
    return text.isBlank() || listOfNotNull(bundle.version, bundle.bundleType, bundle.source?.url,
        metadata?.ownerName, metadata?.repoName, metadata?.repoDescription)
        .any { it.contains(text, ignoreCase = true) }
}

/** An exact package shortcut and explicit bundle IDs keep scans inside their shard scope. */
internal fun scopedPackagePatchWhere(
    bundleIds: List<Int>,
    packageNames: List<String>,
    appVersion: String?
) = buildJsonObject {
    put("bundle_fk", buildJsonObject { put("_in", JsonArray(bundleIds.map(::JsonPrimitive))) })
    put("metadata_status", buildJsonObject { put("_eq", "verified") })
    put("bundle", buildJsonObject {
        put("metadata_status", buildJsonObject { put("_eq", "verified") })
    })
    // package_name already constrains a single app without hydrating package relationships.
    if (packageNames.size != 1 || !appVersion.isNullOrBlank()) {
        put("patch_packages", buildJsonObject {
            put("package", buildJsonObject {
                put("name", buildJsonObject {
                    if (packageNames.size == 1) put("_eq", packageNames.single())
                    else put("_in", JsonArray(packageNames.map(::JsonPrimitive)))
                })
                if (!appVersion.isNullOrBlank()) put("_or", buildJsonArray {
                    add(buildJsonObject { put("version", buildJsonObject { put("_eq", appVersion) }) })
                    add(buildJsonObject { put("version", buildJsonObject { put("_is_null", true) }) })
                })
            })
        })
    }
}
