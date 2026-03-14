package app.revanced.manager.patcher.split

import android.content.pm.PackageInfo
import java.io.File
import java.util.LinkedHashSet
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object InstalledSplitArchiveBuilder {
    fun collectApkFiles(packageInfo: PackageInfo): List<File> {
        val appInfo = packageInfo.applicationInfo
            ?: throw IllegalStateException("ApplicationInfo missing for package: ${packageInfo.packageName}")
        val basePath = appInfo.sourceDir
            ?: throw IllegalStateException("sourceDir missing for package: ${packageInfo.packageName}")

        val baseApk = File(basePath)
        if (!baseApk.exists()) {
            throw IllegalStateException("Base APK not found for package: ${packageInfo.packageName}")
        }

        val splitApks = appInfo.splitSourceDirs
            ?.map(::File)
            ?.map { splitApk ->
                if (!splitApk.exists()) {
                    throw IllegalStateException(
                        "Installed split APK missing for package: ${packageInfo.packageName} (${splitApk.absolutePath})"
                    )
                }
                splitApk
            }
            ?.sortedBy { it.name }
            .orEmpty()

        return buildList {
            add(baseApk)
            addAll(splitApks)
        }
    }

    fun buildArchive(apkFiles: List<File>, output: File) {
        output.parentFile?.mkdirs()
        val usedNames = LinkedHashSet<String>()
        var writtenEntries = 0
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            apkFiles.forEachIndexed { index, apk ->
                if (!apk.exists()) {
                    throw IllegalStateException(
                        "Installed split APK disappeared while building archive: ${apk.absolutePath}"
                    )
                }
                val entryName = uniqueEntryName(apk.name, index, usedNames)
                zip.putNextEntry(ZipEntry(entryName).apply { time = apk.lastModified() })
                apk.inputStream().buffered().use { input -> input.copyTo(zip) }
                zip.closeEntry()
                writtenEntries++
            }
        }
        if (writtenEntries == 0) {
            throw IllegalStateException("Failed to build installed split archive: no APK entries written.")
        }
    }

    private fun uniqueEntryName(originalName: String, index: Int, usedNames: MutableSet<String>): String {
        val normalized = if (originalName.endsWith(".apk", ignoreCase = true)) originalName else "$originalName.apk"
        if (usedNames.add(normalized)) return normalized

        val dot = normalized.lastIndexOf('.')
        val base = if (dot >= 0) normalized.substring(0, dot) else normalized
        val ext = if (dot >= 0) normalized.substring(dot) else ".apk"
        var counter = 1
        while (true) {
            val candidate = "${base}_${index}_$counter$ext"
            if (usedNames.add(candidate)) return candidate
            counter++
        }
    }
}
