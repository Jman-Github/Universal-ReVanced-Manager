package app.revanced.manager.patcher.split

import android.content.res.Resources
import android.os.Build
import android.util.Log
import android.util.DisplayMetrics
import app.revanced.manager.patcher.logger.LogLevel
import app.revanced.manager.patcher.logger.Logger
import app.revanced.manager.patcher.util.NativeLibStripper
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

object SplitApkPreparer {
    private val SUPPORTED_EXTENSIONS = setOf("apks", "apkm", "xapk")
    private const val SKIPPED_STEP_PREFIX = "[skipped]"
    private val KNOWN_ABIS = setOf("armeabi", "armeabi-v7a", "arm64-v8a", "x86", "x86_64")
    private val DENSITY_DPI_VALUES = linkedMapOf(
        "ldpi" to DisplayMetrics.DENSITY_LOW,
        "mdpi" to DisplayMetrics.DENSITY_MEDIUM,
        "tvdpi" to DisplayMetrics.DENSITY_TV,
        "hdpi" to DisplayMetrics.DENSITY_HIGH,
        "xhdpi" to DisplayMetrics.DENSITY_XHIGH,
        "xxhdpi" to DisplayMetrics.DENSITY_XXHIGH,
        "xxxhdpi" to DisplayMetrics.DENSITY_XXXHIGH
    )
    private val DENSITY_QUALIFIERS = DENSITY_DPI_VALUES.keys

    fun isSplitArchive(file: File?): Boolean {
        if (file == null || !file.exists()) return false
        val extension = file.extension.lowercase(Locale.ROOT)
        if (extension in SUPPORTED_EXTENSIONS) return true
        if (extension == "zip") return hasEmbeddedApkEntries(file)
        // Some downloader plugins save split containers using a .apk filename.
        // Consider those split only when they do not look like a normal APK container.
        if (extension == "apk") return looksLikeMislabeledSplitArchive(file)
        return false
    }

    suspend fun prepareIfNeeded(
        source: File,
        workspace: File,
        logger: Logger = defaultLogger,
        stripNativeLibs: Boolean = false,
        skipUnneededSplits: Boolean = false,
        includedModules: Set<String>? = null,
        onProgress: ((String) -> Unit)? = null,
        onSubSteps: ((List<String>) -> Unit)? = null,
        sortMergedApkEntries: Boolean = false
    ): PreparationResult {
        if (!isSplitArchive(source)) {
            return PreparationResult(source, merged = false)
        }

        workspace.mkdirs()
        val workingDir = File(workspace, "split-${System.currentTimeMillis()}")
        val modulesDir = workingDir.resolve("modules").also { it.mkdirs() }
        val mergedApk = workingDir.resolve("${source.nameWithoutExtension}-merged.apk")

        return try {
            coroutineContext.ensureActive()
            val sourceSize = source.length()
            logger.info("Preparing split APK bundle from ${source.name} (size=${sourceSize} bytes)")
            val entries = extractSplitEntries(source, modulesDir, onProgress)
            coroutineContext.ensureActive()
            logger.info("Found ${entries.size} split modules: ${entries.joinToString { it.name }}")
            logger.info("Module sizes: ${entries.joinToString { "${it.name}=${it.file.length()} bytes" }}")
            val mergeOrder = Merger.listMergeOrder(modulesDir.toPath())
            coroutineContext.ensureActive()
            val inspection = inspectMergeOrder(mergeOrder)
            val skippedModules = includedModules
                ?.map(::normalizeModuleSelectionName)
                ?.toSet()
                ?.let { selectedLookup ->
                    mergeOrder.filterNot {
                        selectedLookup.contains(normalizeModuleSelectionName(it))
                    }.toSet()
                }
                ?: buildSet {
                    if (stripNativeLibs) {
                        addAll(inspection.unusedAbiModules)
                    }
                    if (skipUnneededSplits) {
                        addAll(inspection.unusedLanguageModules)
                        addAll(inspection.unusedDensityModules)
                    }
                }
            onSubSteps?.invoke(buildSplitSubSteps(mergeOrder, skippedModules, stripNativeLibs))
            coroutineContext.ensureActive()

            Merger.merge(
                apkDir = modulesDir.toPath(),
                outputApk = mergedApk,
                skipModules = skippedModules,
                onProgress = onProgress,
                sortApkEntries = sortMergedApkEntries
            )
            coroutineContext.ensureActive()

            if (stripNativeLibs) {
                onProgress?.invoke("Stripping native libraries")
                NativeLibStripper.strip(mergedApk)
                coroutineContext.ensureActive()
            }

            onProgress?.invoke("Finalizing merged APK")
            coroutineContext.ensureActive()

            logger.info(
                "Split APK merged to ${mergedApk.absolutePath} " +
                        "(modules=${entries.size}, mergedSize=${mergedApk.length()} bytes)"
            )
            PreparationResult(
                file = mergedApk,
                merged = true
            ) {
                workingDir.deleteRecursively()
            }
        } catch (error: Throwable) {
            workingDir.deleteRecursively()
            throw error
        }
    }

    suspend fun inspect(source: File): SplitArchiveInspection {
        require(isSplitArchive(source)) { "Source is not a supported split archive." }

        val workingDir = withContext(Dispatchers.IO) {
            Files.createTempDirectory("split-inspect-").toFile()
        }
        val modulesDir = workingDir.resolve("modules").also { it.mkdirs() }

        return try {
            extractSplitEntries(source, modulesDir)
            coroutineContext.ensureActive()

            val mergeOrder = withContext(Dispatchers.IO) {
                Merger.listMergeOrder(modulesDir.toPath())
            }
            val inspection = inspectMergeOrder(mergeOrder)
            val modules = mergeOrder.map { moduleName ->
                SplitArchiveModule(
                    name = moduleName,
                    kind = classifyModule(moduleName),
                    detail = moduleDetail(moduleName)
                )
            }
            SplitArchiveInspection(
                modules = modules,
                baseModuleName = mergeOrder.firstOrNull(),
                recommendedModules = buildRecommendedModules(modules, inspection),
                languageTrimmedModules = mergeOrder.toSet() - inspection.unusedLanguageModules,
                densityTrimmedModules = mergeOrder.toSet() - inspection.unusedDensityModules,
                abiTrimmedModules = mergeOrder.toSet() - inspection.unusedAbiModules,
                hasUnusedAbiModules = inspection.unusedAbiModules.isNotEmpty()
            )
        } finally {
            workingDir.deleteRecursively()
        }
    }

    private data class MergeOrderInspection(
        val unusedAbiModules: Set<String>,
        val unusedLanguageModules: Set<String>,
        val unusedDensityModules: Set<String>
    )

    private fun inspectMergeOrder(mergeOrder: List<String>): MergeOrderInspection {
        val supportedTokens = supportedAbiTokens()
        val localeTokens = deviceLocaleTokens()
        val allowedDensityQualifiers = supportedDensityQualifiers(
            mergeOrder = mergeOrder,
            densityQualifier = deviceDensityQualifier()
        )
        return MergeOrderInspection(
            unusedAbiModules = mergeOrder.filter { shouldSkipModule(it, supportedTokens) }.toSet(),
            unusedLanguageModules = mergeOrder.filter { shouldSkipLanguageModule(it, localeTokens) }.toSet(),
            unusedDensityModules = mergeOrder.filter {
                shouldSkipDensityModule(it, allowedDensityQualifiers)
            }.toSet()
        )
    }

    private fun buildRecommendedModules(
        modules: List<SplitArchiveModule>,
        inspection: MergeOrderInspection
    ): Set<String> = modules.asSequence()
        .filter { module ->
            when (module.kind) {
                SplitArchiveModuleKind.BASE -> true
                SplitArchiveModuleKind.ABI -> module.name !in inspection.unusedAbiModules
                SplitArchiveModuleKind.DENSITY -> module.name !in inspection.unusedDensityModules
                SplitArchiveModuleKind.LANGUAGE -> module.name !in inspection.unusedLanguageModules
                SplitArchiveModuleKind.FEATURE,
                SplitArchiveModuleKind.OTHER -> true
            }
        }
        .map { module -> module.name }
        .toSet()

    private fun hasEmbeddedApkEntries(file: File): Boolean =
        runCatching {
            ZipFile(file).use { zip ->
                zip.entries().asSequence().any { entry ->
                    !entry.isDirectory &&
                        isLikelySplitApkEntry(entry.name)
                }
            }
        }.getOrDefault(false)

    private fun looksLikeMislabeledSplitArchive(file: File): Boolean =
        runCatching {
            ZipFile(file).use { zip ->
                val hasRootManifest = zip.entries().asSequence().any { entry ->
                    !entry.isDirectory && entry.name == "AndroidManifest.xml"
                }
                !hasRootManifest && zip.entries().asSequence().any { entry ->
                    !entry.isDirectory && isLikelySplitApkEntry(entry.name)
                }
            }
        }.getOrDefault(false)

    private fun isLikelySplitApkEntry(entryName: String): Boolean {
        val normalized = entryName.replace('\\', '/')
        val fileName = normalized.substringAfterLast('/')
        if (!fileName.endsWith(".apk", ignoreCase = true)) return false
        val lowerName = fileName.lowercase(Locale.ROOT)

        if (lowerName == "base.apk") return true
        if (lowerName.startsWith("split_config.") || lowerName.startsWith("config.")) return true

        // Support zip containers whose APK modules are placed in root.
        return !normalized.contains('/')
    }

    private data class ExtractedModule(val name: String, val file: File)

    private fun buildSplitSubSteps(
        mergeOrder: List<String>,
        skippedModules: Set<String>,
        stripNativeLibs: Boolean
    ): List<String> {
        val steps = mutableListOf<String>()
        steps.add("Extracting split APKs")
        val skippedLookup = skippedModules
            .map(::normalizeModuleSelectionName)
            .toSet()
        mergeOrder.forEach { name ->
            val label = "Merging $name"
            val entry = if (skippedLookup.contains(normalizeModuleSelectionName(name))) {
                "$SKIPPED_STEP_PREFIX$label"
            } else {
                label
            }
            steps.add(entry)
        }
        steps.add("Writing merged APK")
        if (stripNativeLibs) {
            steps.add("Stripping native libraries")
        }
        steps.add("Finalizing merged APK")
        return steps
    }

    private fun supportedAbiTokens(): Set<String> =
        Build.SUPPORTED_ABIS
            .asSequence()
            .filter { it.isNotBlank() }
            .flatMap { abi -> buildAbiTokens(abi).asSequence() }
            .map { it.lowercase(Locale.ROOT) }
            .toSet()

    private fun buildAbiTokens(abi: String): Set<String> {
        val normalized = abi.lowercase(Locale.ROOT)
        return setOf(
            normalized,
            normalized.replace('-', '_'),
            normalized.replace('_', '-')
        )
    }

    private fun normalizeModuleSelectionName(name: String): String =
        name.lowercase(Locale.ROOT).removeSuffix(".apk")

    private fun shouldSkipModule(
        moduleName: String,
        supportedTokens: Set<String>
    ): Boolean {
        if (supportedTokens.isEmpty()) return false
        val lower = moduleName.lowercase(Locale.ROOT)
        val knownTokens = KNOWN_ABIS.flatMap { buildAbiTokens(it) }.toSet()
        if (knownTokens.none { lower.contains(it) }) return false
        return supportedTokens.none { lower.contains(it) }
    }

    private fun shouldSkipModuleForDevice(
        moduleName: String,
        localeTokens: Set<String>,
        allowedDensityQualifiers: Set<String>
    ): Boolean =
        shouldSkipLanguageModule(moduleName, localeTokens) ||
            shouldSkipDensityModule(moduleName, allowedDensityQualifiers)

    private fun shouldSkipLanguageModule(
        moduleName: String,
        localeTokens: Set<String>
    ): Boolean {
        val qualifiers = splitConfigQualifiers(moduleName)
        if (qualifiers.isEmpty() || isAbiSplit(moduleName)) return false
        return qualifiers.any { qualifier ->
            parseLocaleQualifier(qualifier)?.let { localeQualifier ->
                !matchesLocaleQualifier(localeQualifier, localeTokens)
            } ?: false
        }
    }

    private fun shouldSkipDensityModule(
        moduleName: String,
        allowedDensityQualifiers: Set<String>
    ): Boolean {
        val qualifiers = splitConfigQualifiers(moduleName)
        if (qualifiers.isEmpty() || isAbiSplit(moduleName)) return false
        if (allowedDensityQualifiers.isEmpty()) return false
        return qualifiers.any { qualifier ->
            isDensityQualifier(qualifier) && qualifier !in allowedDensityQualifiers
        }
    }

    private fun supportedDensityQualifiers(
        mergeOrder: List<String>,
        densityQualifier: String?
    ): Set<String> {
        if (densityQualifier == null) return emptySet()
        val availableQualifiers = mergeOrder
            .flatMap(::splitConfigQualifiers)
            .filter(::isDensityQualifier)
            .toSet()
        if (availableQualifiers.isEmpty()) return emptySet()
        if (densityQualifier in availableQualifiers) return setOf(densityQualifier)

        val targetDensity = DENSITY_DPI_VALUES[densityQualifier] ?: return availableQualifiers
        val availableDensityValues = availableQualifiers.mapNotNull { qualifier ->
            DENSITY_DPI_VALUES[qualifier]?.let { densityValue ->
                qualifier to densityValue
            }
        }
        if (availableDensityValues.isEmpty()) return availableQualifiers

        val closestDistance = availableDensityValues.minOf { (_, densityValue) ->
            kotlin.math.abs(densityValue - targetDensity)
        }
        return availableDensityValues
            .filter { (_, densityValue) ->
                kotlin.math.abs(densityValue - targetDensity) == closestDistance
            }
            .mapTo(linkedSetOf()) { (qualifier, _) -> qualifier }
    }

    private fun isAbiSplit(moduleName: String): Boolean {
        val lower = moduleName.lowercase(Locale.ROOT)
        val knownTokens = KNOWN_ABIS.flatMap { buildAbiTokens(it) }.toSet()
        return knownTokens.any { lower.contains(it) }
    }

    private fun splitConfigQualifiers(moduleName: String): List<String> {
        val normalized = moduleName.lowercase(Locale.ROOT).removeSuffix(".apk")
        val splitIndex = normalized.indexOf("split_config.")
        val configIndex = normalized.indexOf("config.")
        val startIndex = when {
            splitIndex != -1 -> splitIndex + "split_config.".length
            configIndex != -1 -> configIndex + "config.".length
            else -> return emptyList()
        }
        val tail = normalized.substring(startIndex)
        return tail.split('.').filter { it.isNotBlank() }
    }

    private fun isDensityQualifier(token: String): Boolean = token in DENSITY_QUALIFIERS

    private data class LocaleQualifier(
        val language: String,
        val script: String? = null,
        val region: String? = null
    )

    private fun parseLocaleQualifier(rawToken: String): LocaleQualifier? {
        val parts = when {
            rawToken.startsWith("b+", ignoreCase = true) ->
                rawToken.removePrefix("b+").removePrefix("B+").split('+')
            else -> rawToken.replace('-', '_').split('_')
        }.filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val language = parts.first().lowercase(Locale.ROOT)
        if (language.length !in 2..3 || !language.all { it.isLetter() }) return null

        var script: String? = null
        var region: String? = null
        parts.drop(1).forEach { rawPart ->
            val part = rawPart.lowercase(Locale.ROOT)
            val normalizedRegion = part.removePrefix("r")
            when {
                script == null && part.length == 4 && part.all { it.isLetter() } -> script = part
                region == null &&
                    normalizedRegion.length in 2..3 &&
                    normalizedRegion.all { it.isLetterOrDigit() } -> {
                    region = normalizedRegion
                }
            }
        }

        return LocaleQualifier(language = language, script = script, region = region)
    }

    private fun matchesLocaleQualifier(
        qualifier: LocaleQualifier,
        localeTokens: Set<String>
    ): Boolean {
        val language = qualifier.language
        val script = qualifier.script
        val region = qualifier.region
        return when {
            script == null && region == null -> {
                localeTokens.contains(language)
            }
            script != null && region == null -> {
                localeTokens.contains("${language}_$script") ||
                    localeTokens.contains("${language}-$script")
            }
            script == null && region != null -> {
                localeTokens.contains("${language}_r$region") ||
                    localeTokens.contains("${language}_$region") ||
                    localeTokens.contains("${language}-$region")
            }
            else -> {
                localeTokens.contains("${language}_${script}_$region") ||
                    localeTokens.contains("${language}_${script}-r$region") ||
                    localeTokens.contains("${language}-${script}-$region")
            }
        }
    }

    private fun deviceLocaleTokens(): Set<String> {
        val locales = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val list = Resources.getSystem().configuration.locales
            (0 until list.size()).map { index -> list[index] }
        } else {
            listOf(Locale.getDefault())
        }

        return locales.flatMap { locale ->
            buildLocaleTokens(locale)
        }.map { it.lowercase(Locale.ROOT) }.toSet()
    }

    private fun buildLocaleTokens(locale: Locale): Set<String> {
        val tokens = LinkedHashSet<String>()
        val language = locale.language.lowercase(Locale.ROOT)
        if (language.isBlank()) return tokens
        tokens.add(language)
        val region = locale.country.lowercase(Locale.ROOT)
        if (region.isNotBlank()) {
            tokens.add("${language}_r$region")
            tokens.add("${language}_$region")
            tokens.add("${language}-$region")
        }
        val script = locale.script.lowercase(Locale.ROOT)
        if (script.isNotBlank()) {
            tokens.add("${language}_$script")
            tokens.add("${language}-$script")
            if (region.isNotBlank()) {
                tokens.add("${language}_${script}_$region")
                tokens.add("${language}_${script}-r$region")
                tokens.add("${language}-${script}-$region")
            }
        }
        return tokens
    }

    private fun deviceDensityQualifier(): String? {
        val density = Resources.getSystem().displayMetrics?.densityDpi ?: return null
        return when {
            density <= DisplayMetrics.DENSITY_LOW -> "ldpi"
            density <= DisplayMetrics.DENSITY_MEDIUM -> "mdpi"
            density <= DisplayMetrics.DENSITY_TV -> "tvdpi"
            density <= DisplayMetrics.DENSITY_HIGH -> "hdpi"
            density <= DisplayMetrics.DENSITY_XHIGH -> "xhdpi"
            density <= DisplayMetrics.DENSITY_XXHIGH -> "xxhdpi"
            else -> "xxxhdpi"
        }
    }

    private fun classifyModule(moduleName: String): SplitArchiveModuleKind {
        val lower = moduleName.lowercase(Locale.ROOT)
        if (isBaseModuleName(moduleName)) return SplitArchiveModuleKind.BASE
        if (isAbiSplit(moduleName)) return SplitArchiveModuleKind.ABI
        val qualifiers = splitConfigQualifiers(moduleName)
        return when {
            qualifiers.any(::isDensityQualifier) -> SplitArchiveModuleKind.DENSITY
            qualifiers.any { parseLocaleQualifier(it) != null } -> SplitArchiveModuleKind.LANGUAGE
            lower.contains("feature") -> SplitArchiveModuleKind.FEATURE
            qualifiers.isNotEmpty() -> SplitArchiveModuleKind.FEATURE
            else -> SplitArchiveModuleKind.OTHER
        }
    }

    private fun moduleDetail(moduleName: String): String? {
        if (isAbiSplit(moduleName)) {
            return KNOWN_ABIS.firstOrNull { abi ->
                buildAbiTokens(abi).any { token -> moduleName.lowercase(Locale.ROOT).contains(token) }
            }
        }
        val qualifiers = splitConfigQualifiers(moduleName)
        return qualifiers.firstOrNull(::isDensityQualifier)
            ?: qualifiers.firstNotNullOfOrNull { qualifier ->
                parseLocaleQualifier(qualifier)?.let { locale ->
                    locale.region?.let { region -> "${locale.language.uppercase(Locale.ROOT)}-$region" }
                        ?: locale.language.uppercase(Locale.ROOT)
                }
            }
    }

    private fun isBaseModuleName(moduleName: String): Boolean {
        val lower = moduleName.lowercase(Locale.ROOT)
        return lower == "base.apk" || lower.startsWith("base-")
    }

    private suspend fun extractSplitEntries(
        source: File,
        targetDir: File,
        onProgress: ((String) -> Unit)? = null
    ): List<ExtractedModule> =
        runInterruptible(Dispatchers.IO) {
            val extracted = mutableListOf<ExtractedModule>()
            ZipFile(source).use { zip ->
                val apkEntries = zip.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .filter { it.name.endsWith(".apk", ignoreCase = true) }
                    .toList()

                if (apkEntries.isEmpty()) {
                    throw IOException("Split archive does not contain any APK entries.")
                }

                onProgress?.invoke("Extracting split APKs")
                apkEntries.forEach { entry ->
                    val entryName = entry.name.substringAfterLast('/')
                    val destination = targetDir.resolve(entryName)
                    destination.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        Files.newOutputStream(destination.toPath()).use { output ->
                            input.copyTo(output)
                        }
                    }
                    extracted += ExtractedModule(destination.name, destination)
                }
            }
            extracted
        }

    data class PreparationResult(
        val file: File,
        val merged: Boolean,
        val cleanup: () -> Unit = {}
    )

    data class SplitArchiveInspection(
        val modules: List<SplitArchiveModule>,
        val baseModuleName: String?,
        val recommendedModules: Set<String>,
        val languageTrimmedModules: Set<String>,
        val densityTrimmedModules: Set<String>,
        val abiTrimmedModules: Set<String>,
        val hasUnusedAbiModules: Boolean
    )

    data class SplitArchiveModule(
        val name: String,
        val kind: SplitArchiveModuleKind,
        val detail: String? = null
    )

    enum class SplitArchiveModuleKind {
        BASE,
        LANGUAGE,
        DENSITY,
        ABI,
        FEATURE,
        OTHER
    }

    private object defaultLogger : Logger() {
        override fun log(level: LogLevel, message: String) {
            Log.d("SplitApkPreparer", "[${level.name}] $message")
        }
    }
}
