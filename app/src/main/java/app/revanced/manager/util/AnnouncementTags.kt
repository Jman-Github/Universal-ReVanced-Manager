package app.revanced.manager.util

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

fun announcementTagDisplayName(rawTag: String): String {
    val decoded = runCatching {
        URLDecoder.decode(rawTag, StandardCharsets.UTF_8.name())
    }.getOrDefault(rawTag)

    return decoded.replace(Regex("\\s+"), " ").trim()
}

fun announcementTagKey(rawTag: String): String {
    val normalized = announcementTagDisplayName(rawTag)
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{Nd}]+"), "")

    return normalized.ifBlank { rawTag.trim().lowercase() }
}

fun Iterable<String>.distinctAnnouncementTags(): List<String> {
    val seen = linkedSetOf<String>()
    val result = mutableListOf<String>()

    for (rawTag in this) {
        val display = announcementTagDisplayName(rawTag)
        val key = announcementTagKey(display)
        if (key.isEmpty() || !seen.add(key)) continue
        result += display
    }

    return result
}
