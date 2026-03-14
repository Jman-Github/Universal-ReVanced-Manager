package app.revanced.manager.util

import app.revanced.manager.data.room.options.Option
import app.revanced.manager.data.room.profile.PatchProfilePayload
import java.security.MessageDigest

private const val SAVED_APP_ENTRY_DELIMITER = "__bundle_"
private const val UNSPECIFIED_SAVED_APP_VERSION = "unspecified"

fun buildSavedAppVariantIdentity(
    appVersion: String,
    selectionPayload: PatchProfilePayload?,
    patchSelection: PatchSelection = emptyMap()
): String {
    val normalizedVersion = appVersion.ifBlank { UNSPECIFIED_SAVED_APP_VERSION }
    val canonicalBundles = selectionPayload?.bundles
        ?.takeIf { it.isNotEmpty() }
        ?.sortedBy { it.bundleUid }
        ?.joinToString(separator = ";") { bundle ->
            buildString {
                append("uid=")
                append(bundle.bundleUid)
                append("|version=")
                append(bundle.version.orEmpty())
                append("|patches=")
                append(bundle.patches.sorted().joinToString(separator = ","))
                append("|options=")
                append(canonicalizeBundleOptions(bundle.options))
            }
        }
        ?: patchSelection.toSortedMap().entries.joinToString(separator = ";") { (uid, patches) ->
            "uid=$uid|patches=${patches.sorted().joinToString(separator = ",")}"
        }.ifBlank { "none" }

    return "appVersion=$normalizedVersion;$canonicalBundles"
}

private fun canonicalizeBundleOptions(
    options: Map<String, Map<String, Option.SerializedValue>>
): String {
    if (options.isEmpty()) return "none"
    return options.toSortedMap().entries.joinToString(separator = "&") { (patchName, values) ->
        val canonicalValues = values.toSortedMap().entries.joinToString(separator = ",") { (key, value) ->
            "$key=${value.toJsonString()}"
        }
        "$patchName{$canonicalValues}"
    }
}

fun buildSavedAppEntryKey(packageName: String, variantIdentity: String): String {
    val canonical = variantIdentity.ifBlank { "none" }
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
