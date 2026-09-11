package app.urv.manager.util

import android.content.Context
import app.universal.revanced.manager.R
import java.io.File
import java.util.zip.ZipFile

private const val APK_LIB_PREFIX = "lib/"
private val ABI_LABEL_ORDER = listOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86_64", "x86")

fun File.savedApkAbiLabel(context: Context): String? {
    if (!isFile) return null

    val abis = runCatching {
        ZipFile(this).use { zip ->
            val found = linkedSetOf<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement().name
                if (!name.startsWith(APK_LIB_PREFIX)) continue
                val relativeName = name.removePrefix(APK_LIB_PREFIX)
                val abi = relativeName.substringBefore('/', missingDelimiterValue = "")
                if (abi.isNotBlank()) {
                    found += abi
                }
            }
            found
        }
    }.getOrNull() ?: return null

    if (abis.isEmpty()) {
        return context.getString(R.string.saved_app_abi_none)
    }

    val orderedAbis = ABI_LABEL_ORDER.filterTo(mutableListOf()) { it in abis }
    orderedAbis += abis.filterNot { it in orderedAbis }.sorted()

    return if (orderedAbis.size > 1) {
        context.getString(R.string.saved_app_abi_universal)
    } else {
        when (orderedAbis.single()) {
            "arm64-v8a" -> context.getString(R.string.saved_app_abi_arm64)
            "armeabi-v7a" -> context.getString(R.string.saved_app_abi_armv7)
            "armeabi" -> context.getString(R.string.saved_app_abi_arm)
            "x86" -> context.getString(R.string.saved_app_abi_x86)
            "x86_64" -> context.getString(R.string.saved_app_abi_x86_64)
            else -> orderedAbis.single()
        }
    }
}
