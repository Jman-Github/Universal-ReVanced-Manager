package app.urv.manager.util

import android.content.Context
import app.universal.revanced.manager.R
import app.urv.manager.patcher.split.SplitApkFiles
import app.urv.manager.patcher.split.SplitApkPreparer
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

private const val APK_LIB_PREFIX = "lib/"
private val ABI_LABEL_ORDER = listOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86_64", "x86")

// Code adapted from Morphe, see third-party/NOTICE for more information
// https://github.com/MorpheApp/morphe-manager/commit/e193dd1d4ef75c0328a8c4eb70b8a952a34c8b95
private data class ApkAbiFileStamp(val path: String, val size: Long, val lastModified: Long)

private val apkAbiCache = ConcurrentHashMap<String, Pair<ApkAbiFileStamp, Set<String>>>()

private fun File.abiFileStampOrNull(): ApkAbiFileStamp? =
    if (isFile) ApkAbiFileStamp(absolutePath, length(), lastModified()) else null

// Cache the ABI names rather than the translated label, so language changes still take effect.
internal fun File.savedApkAbisOrNull(): Set<String>? =
    absoluteFile.parentFile?.let { savedApkAbisOrNull(it) }

internal fun File.savedApkAbisOrNull(cacheDirectory: File): Set<String>? {
    val stamp = abiFileStampOrNull() ?: run {
        apkAbiCache.remove(absolutePath)
        return null
    }
    apkAbiCache[stamp.path]?.takeIf { it.first == stamp }?.let { return it.second }

    return runCatching {
        if (SplitApkPreparer.isSplitArchive(this)) {
            val directory = Files.createTempDirectory(cacheDirectory.toPath(), "split-abis-").toFile()
            try {
                val found = linkedSetOf<String>()
                // Read each component's ZIP directory without merging or inflating its contents.
                // Release components as we go to avoid another full split set in the cache.
                SplitApkPreparer.splitApkEntryNames(this).forEach { name ->
                    val component = SplitApkFiles.extract(this, directory, setOf(name)).single()
                    try {
                        found += component.apkAbis()
                    } finally {
                        component.delete()
                    }
                }
                found
            } finally {
                directory.deleteRecursively()
            }
        } else apkAbis()
    }.onSuccess { abis ->
        // A file replaced while scanning must be read again on the next request.
        if (abiFileStampOrNull() == stamp) apkAbiCache[stamp.path] = stamp to abis
    }.getOrNull()
}

private fun File.apkAbis(): Set<String> = ZipFile(this).use { zip ->
    val found = linkedSetOf<String>()
    val entries = zip.entries()
    while (entries.hasMoreElements()) {
        val name = entries.nextElement().name
        if (!name.startsWith(APK_LIB_PREFIX)) continue
        val abi = name.removePrefix(APK_LIB_PREFIX).substringBefore('/', missingDelimiterValue = "")
        if (abi.isNotBlank()) found += abi
    }
    found
}

fun File.savedApkAbiLabel(context: Context): String? {
    val abis = savedApkAbisOrNull(context.cacheDir) ?: return null
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
