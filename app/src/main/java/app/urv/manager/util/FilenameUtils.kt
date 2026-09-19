package app.urv.manager.util

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Utility helpers for working with filenames.
 */
object FilenameUtils {
    private val logTimestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun logTimestamp(): String = LocalDateTime.now().format(logTimestampFormatter)

    fun timestampedLogFileName(prefix: String): String =
        "${sanitize(prefix).ifBlank { "log" }}-log-${logTimestamp()}.txt"

    /**
     * Sanitize a string so it can safely be used as part of a filename.
     */
    fun sanitize(segment: String): String {
        if (segment.isEmpty()) return ""
        val raw = buildString(segment.length) {
            segment.forEach { char ->
                val sanitized = when {
                    char in '0'..'9' || char in 'a'..'z' || char in 'A'..'Z' -> char
                    char == '-' || char == '_' || char == '.' -> char
                    char.isWhitespace() -> '_'
                    char == '\'' || char == '"' || char == '`' -> null
                    else -> '_'
                }
                sanitized?.let { append(it) }
            }
        }

        return raw
            .replace(Regex("[_]{2,}"), "_")
            .replace(Regex("[-]{2,}"), "-")
            .trim('_', '-')
    }
}
