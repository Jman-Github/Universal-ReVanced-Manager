package app.revanced.manager.util

import java.security.MessageDigest

private const val SAVED_APP_ENTRY_DELIMITER = "__bundle_"

fun buildSavedAppEntryKey(packageName: String, bundleUids: Set<Int>): String {
    val canonical = if (bundleUids.isEmpty()) {
        "none"
    } else {
        bundleUids.toList().sorted().joinToString(separator = ",")
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray())
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
        .take(12)
    return "$packageName$SAVED_APP_ENTRY_DELIMITER$digest"
}

fun savedAppBasePackage(entryKey: String): String =
    entryKey.substringBefore(SAVED_APP_ENTRY_DELIMITER)

fun isSavedAppEntryForPackage(entryKey: String, packageName: String): Boolean =
    entryKey == packageName || savedAppBasePackage(entryKey) == packageName
