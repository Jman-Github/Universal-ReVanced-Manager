package app.urv.manager.util

import java.net.URI
import java.net.URLDecoder

/** Labels imports before the downloaded bundle's manifest is available. */
fun bundleImportLabel(endpoint: String, name: String? = null): String {
    val uri = runCatching { URI(endpoint) }.getOrNull()
    val query = uri?.rawQuery.orEmpty().split('&').mapNotNull { part ->
        val key = part.substringBefore('=')
        val value = runCatching {
            URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
        }.getOrNull() ?: return@mapNotNull null
        key to value
    }.toMap()
    val source = query["source_url"]?.let { runCatching { URI(it) }.getOrNull() }
    val filename = uri?.path.orEmpty().substringAfterLast('/')
        .replace(Regex("(?i)[.](json|rvp|mpp)$"), "")
    val channel = when (query["channel"]) {
        "any" -> "latest"
        "prerelease" -> "pre-release"
        "stable" -> "release"
        else -> when {
            filename.contains("latest", ignoreCase = true) -> "latest"
            filename.contains("pre-release", ignoreCase = true) ||
                filename.contains("prerelease", ignoreCase = true) ||
                Regex("(?i)(^|[-_])dev([-_]|$)").containsMatchIn(filename) -> "pre-release"
            filename.contains("release", ignoreCase = true) ||
                Regex("(?i)(^|[-_])stable([-_]|$)").containsMatchIn(filename) -> "release"
            else -> null
        }
    }
    val rawName = name?.trim()?.takeIf { it.isNotEmpty() }
        ?: source?.path?.trim('/')?.takeIf { it.isNotEmpty() }
        ?: filename.takeIf { it.isNotEmpty() && !it.equals("bundle", true) }
        ?: uri?.host
        ?: endpoint
    val label = rawName
        .replace(Regex("(?i)[ _-]+patches?[ _-]+bundles?$"), "")
        .replace(Regex("(?i)[ _-]+bundles?$"), "")
    val channelSuffix = Regex("(?i)[ _(-]+(latest|pre-release|prerelease|release|dev|stable)\\)?$")
    val namedChannel = channelSuffix.find(label)?.groupValues?.get(1)?.lowercase()
        ?.let { channelName ->
            when (channelName) {
                "dev", "prerelease" -> "pre-release"
                "stable" -> "release"
                else -> channelName
            }
        }
    val identity = label.replace(channelSuffix, "").ifBlank { rawName }
    return listOfNotNull(identity, namedChannel ?: channel, "patch bundle").joinToString(" ")
}
