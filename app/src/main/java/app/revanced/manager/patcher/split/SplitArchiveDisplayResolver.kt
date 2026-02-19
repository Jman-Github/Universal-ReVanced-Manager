package app.revanced.manager.patcher.split

import android.app.Application
import android.content.pm.PackageInfo
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.content.res.AssetManager
import android.os.Build
import app.revanced.manager.util.PM
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SplitArchiveDisplayResolver {
    private val densityQualifiers =
        setOf("ldpi", "mdpi", "tvdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
    private val knownAbis =
        setOf("armeabi", "armeabi-v7a", "arm64-v8a", "x86", "x86_64")

    suspend fun resolve(
        source: File,
        workspace: File,
        app: Application,
        pm: PM
    ): ResolvedSplitArchiveDisplay? = withContext(Dispatchers.IO) {
        if (!SplitApkPreparer.isSplitArchive(source)) return@withContext null
        workspace.mkdirs()

        val extractionDir = File(workspace, "display-${UUID.randomUUID()}")
        extractionDir.mkdirs()

        var packageInfo: PackageInfo? = null
        var iconDrawable: Drawable? = null
        var label: String? = null

        try {
            val extractedApks = extractRelevantApks(source, extractionDir)
            val baseApk = extractedApks.firstOrNull { isBaseApkName(it.name) }
                ?: extractedApks.firstOrNull()
                ?: return@withContext null

            packageInfo = pm.getPackageInfo(baseApk)
            if (packageInfo == null) return@withContext null

            val resourcesSession = createResourcesForApks(app, extractedApks) ?: return@withContext null
            val iconBitmapDrawable = try {
                label = resolveLabel(packageInfo, resourcesSession.resources, app)
                iconDrawable = resolveIcon(packageInfo, resourcesSession.resources, app)
                iconDrawable?.let { toBitmapDrawable(it, app.resources) }
            } finally {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    runCatching { resourcesSession.assetManager.close() }
                }
            }

            ResolvedSplitArchiveDisplay(
                packageInfo = packageInfo,
                label = label,
                icon = iconBitmapDrawable
            )
        } finally {
            extractionDir.deleteRecursively()
        }
    }

    private fun extractRelevantApks(source: File, extractionDir: File): List<File> {
        val locales = deviceLocaleTokens()
        val density = deviceDensityQualifier()
        val abiTokens = deviceAbiTokens()
        val output = mutableListOf<File>()

        ZipFile(source).use { zip ->
            val entries = zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .filter { it.name.lowercase(Locale.ROOT).endsWith(".apk") }
                .toList()
            if (entries.isEmpty()) return emptyList()

            val base = entries.firstOrNull { isBaseApkName(it.name) }
            val selected = LinkedHashSet<String>()
            base?.let { selected.add(it.name) }

            entries.forEach { entry ->
                if (entry.name in selected) return@forEach
                val qualifiers = splitConfigQualifiers(entry.name)
                if (qualifiers.isEmpty()) return@forEach
                if (qualifiers.any { qualifierMatches(it, locales, density, abiTokens) }) {
                    selected.add(entry.name)
                }
            }

            if (selected.isEmpty()) {
                selected += entries.first().name
            }

            selected.forEachIndexed { index, name ->
                val entry = zip.getEntry(name) ?: return@forEachIndexed
                val fileName = "${index}-${entry.name.substringAfterLast('/')}"
                val destination = File(extractionDir, fileName)
                zip.getInputStream(entry).use { input ->
                    Files.newOutputStream(destination.toPath()).use { outputStream ->
                        input.copyTo(outputStream)
                    }
                }
                output += destination
            }
        }

        return output
    }

    private data class ResourcesSession(
        val resources: Resources,
        val assetManager: AssetManager
    )

    private fun createResourcesForApks(app: Application, apks: List<File>): ResourcesSession? {
        if (apks.isEmpty()) return null
        val assetManager = AssetManager::class.java
            .getDeclaredConstructor()
            .newInstance()
        val addAssetPath = AssetManager::class.java
            .getMethod("addAssetPath", String::class.java)
        apks.forEach { apk ->
            val cookie = addAssetPath.invoke(assetManager, apk.absolutePath) as? Int ?: 0
            if (cookie == 0) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    runCatching { assetManager.close() }
                }
                return null
            }
        }
        return ResourcesSession(
            resources = Resources(assetManager, app.resources.displayMetrics, app.resources.configuration),
            assetManager = assetManager
        )
    }

    private fun resolveLabel(
        packageInfo: PackageInfo,
        resources: Resources,
        app: Application
    ): String {
        val appInfo = packageInfo.applicationInfo ?: return packageInfo.packageName
        appInfo.nonLocalizedLabel?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        if (appInfo.labelRes != 0) {
            runCatching { resources.getString(appInfo.labelRes) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return runCatching { appInfo.loadLabel(app.packageManager)?.toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: packageInfo.packageName
    }

    private fun resolveIcon(
        packageInfo: PackageInfo,
        resources: Resources,
        app: Application
    ): Drawable? {
        val appInfo = packageInfo.applicationInfo ?: return null
        val iconRes = appInfo.icon
        if (iconRes != 0) {
            val fromResources = runCatching {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    resources.getDrawable(iconRes, null)
                } else {
                    resources.getDrawable(iconRes)
                }
            }.getOrNull()
            if (fromResources != null) return fromResources
        }
        return runCatching { appInfo.loadIcon(app.packageManager) }.getOrNull()
    }

    private fun toBitmapDrawable(drawable: Drawable, resources: Resources): BitmapDrawable {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable
        }
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 1
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return BitmapDrawable(resources, bitmap)
    }

    private fun isBaseApkName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith("/base.apk") ||
            lower == "base.apk" ||
            lower.contains("base-master") ||
            lower.contains("base-main")
    }

    private fun splitConfigQualifiers(entryName: String): List<String> {
        val normalized = entryName.lowercase(Locale.ROOT)
            .substringAfterLast('/')
            .removeSuffix(".apk")
        val splitIndex = normalized.indexOf("split_config.")
        val configIndex = normalized.indexOf("config.")
        val start = when {
            splitIndex != -1 -> splitIndex + "split_config.".length
            configIndex != -1 -> configIndex + "config.".length
            else -> return emptyList()
        }
        return normalized.substring(start)
            .split('.')
            .filter { it.isNotBlank() }
    }

    private fun qualifierMatches(
        qualifier: String,
        localeTokens: Set<String>,
        densityQualifier: String?,
        abiTokens: Set<String>
    ): Boolean {
        val normalized = qualifier.lowercase(Locale.ROOT)
        if (normalized in densityQualifiers) return densityQualifier == normalized

        val parsedLocale = parseLocaleQualifier(normalized)
        if (parsedLocale != null) {
            return localeTokens.contains(parsedLocale)
        }

        val abiMatched = abiTokens.any { token ->
            normalized == token || normalized == token.replace('-', '_')
        }
        return abiMatched
    }

    private fun parseLocaleQualifier(raw: String): String? {
        val token = raw.replace('-', '_')
        val parts = token.split('_').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val language = parts[0]
        if (language.length !in 2..3 || !language.all { it.isLetter() }) return null
        val region = parts.getOrNull(1)
            ?.removePrefix("r")
            ?.takeIf { it.length in 2..3 && it.all { ch -> ch.isLetterOrDigit() } }
        return if (region == null) {
            language
        } else {
            "${language}_r${region.lowercase(Locale.ROOT)}"
        }
    }

    private fun deviceLocaleTokens(): Set<String> {
        val locales = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val list = Resources.getSystem().configuration.locales
            (0 until list.size()).map { list[it] }
        } else {
            listOf(Locale.getDefault())
        }

        return locales.flatMap { locale ->
            val language = locale.language.lowercase(Locale.ROOT)
            if (language.isBlank()) return@flatMap emptyList()
            val region = locale.country.lowercase(Locale.ROOT)
            if (region.isBlank()) {
                listOf(language)
            } else {
                listOf(language, "${language}_r$region")
            }
        }.toSet()
    }

    private fun deviceDensityQualifier(): String? {
        val density = Resources.getSystem().displayMetrics?.densityDpi ?: return null
        return when {
            density <= android.util.DisplayMetrics.DENSITY_LOW -> "ldpi"
            density <= android.util.DisplayMetrics.DENSITY_MEDIUM -> "mdpi"
            density <= android.util.DisplayMetrics.DENSITY_TV -> "tvdpi"
            density <= android.util.DisplayMetrics.DENSITY_HIGH -> "hdpi"
            density <= android.util.DisplayMetrics.DENSITY_XHIGH -> "xhdpi"
            density <= android.util.DisplayMetrics.DENSITY_XXHIGH -> "xxhdpi"
            else -> "xxxhdpi"
        }
    }

    private fun deviceAbiTokens(): Set<String> {
        val supported = Build.SUPPORTED_ABIS.flatMap { abi ->
            val lower = abi.lowercase(Locale.ROOT)
            listOf(lower, lower.replace('-', '_'), lower.replace('_', '-'))
        }.toMutableSet()
        knownAbis.forEach { abi ->
            val lower = abi.lowercase(Locale.ROOT)
            supported += lower
            supported += lower.replace('-', '_')
            supported += lower.replace('_', '-')
        }
        return supported
    }
}

data class ResolvedSplitArchiveDisplay(
    val packageInfo: PackageInfo,
    val label: String,
    val icon: Drawable?
)
