package app.urv.manager.patcher.split

import android.content.res.Resources
import android.os.Build
import android.util.Log
import android.util.DisplayMetrics
import app.urv.manager.patcher.logger.LogLevel
import app.urv.manager.patcher.logger.Logger
import app.urv.manager.patcher.util.NativeLibStripper
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
    private const val MAX_FLATTEN_RETRIES = 2
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
        if (extension !in SUPPORTED_EXTENSIONS && extension != "zip" && extension != "apk") return false
        return splitApkEntryNames(file).isNotEmpty()
    }

    internal fun splitApkEntryNames(file: File): Set<String> {
        if (!file.exists()) return emptySet()
        val extension = file.extension.lowercase(Locale.ROOT)
        if (extension !in SUPPORTED_EXTENSIONS && extension != "zip" && extension != "apk") {
            return emptySet()
        }
        return runCatching {
            ZipFile(file).use { zip ->
                resolveSplitApkEntryNames(zip, extension)
            }
        }.getOrDefault(emptySet())
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
        return try {
            var preparationSource = source
            var mergedApk: File? = null
            var mergeEntries = 0
            var flattenPass = 0
            while (true) {
                coroutineContext.ensureActive()
                val passDir = workingDir.resolve("pass-$flattenPass")
                val modulesDir = passDir.resolve("modules").also { it.mkdirs() }
                val passOutput = workingDir.resolve(
                    "${source.nameWithoutExtension}-merged-${flattenPass + 1}.apk"
                )
                val sourceSize = preparationSource.length()
                logger.info(
                    "Preparing split APK bundle from ${preparationSource.name} " +
                        "(size=${sourceSize} bytes, pass=${flattenPass + 1})"
                )
                val entries = extractSplitEntries(preparationSource, modulesDir, onProgress)
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
                if (flattenPass == 0) {
                    logger.info(
                        "Included splits: ${mergeOrder.filterNot(skippedModules::contains).toLogList()}"
                    )
                    logger.info(
                        "Excluded splits: ${mergeOrder.filter(skippedModules::contains).toLogList()}"
                    )
                    onSubSteps?.invoke(buildSplitSubSteps(mergeOrder, skippedModules, stripNativeLibs))
                }
                coroutineContext.ensureActive()

                Merger.merge(
                    apkDir = modulesDir.toPath(),
                    outputApk = passOutput,
                    skipModules = skippedModules,
                    onProgress = onProgress,
                    sortApkEntries = sortMergedApkEntries
                )
                coroutineContext.ensureActive()

                val validation = validatePreparedApk(passOutput)
                if (validation.isUsable) {
                    mergedApk = passOutput
                    mergeEntries = entries.size
                    break
                }
                if (flattenPass >= MAX_FLATTEN_RETRIES || validation.embeddedSplitEntries.isEmpty()) {
                    throw IOException(
                        "Merged APK is missing required root files: ${validation.describe()}"
                    )
                }
                logger.warn(
                    "Merged APK still looks like a split container " +
                        "(${validation.describe()}); retrying flatten pass ${flattenPass + 2}."
                )
                preparationSource = passOutput
                flattenPass += 1
            }

            if (stripNativeLibs) {
                val finalApk = requireNotNull(mergedApk)
                onProgress?.invoke("Stripping native libraries")
                NativeLibStripper.strip(finalApk)
                coroutineContext.ensureActive()
            }

            onProgress?.invoke("Finalizing merged APK")
            coroutineContext.ensureActive()

            val finalApk = requireNotNull(mergedApk)
            logger.info(
                "Split APK merged to ${finalApk.absolutePath} " +
                        "(modules=${mergeEntries}, mergedSize=${finalApk.length()} bytes)"
            )
            PreparationResult(
                file = finalApk,
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

    private fun Collection<String>.toLogList(): String =
        if (isEmpty()) "None" else joinToString(", ")

    private data class MergeOrderInspection(
        val unusedAbiModules: Set<String>,
        val unusedLanguageModules: Set<String>,
        val unusedDensityModules: Set<String>
    )

    private fun inspectMergeOrder(mergeOrder: List<String>): MergeOrderInspection {
        val localeTokens = deviceLocaleTokens()
        val allowedDensityQualifiers = supportedDensityQualifiers(
            mergeOrder = mergeOrder,
            densityQualifier = deviceDensityQualifier()
        )
        return MergeOrderInspection(
            unusedAbiModules = unusedAbiModules(mergeOrder),
            unusedLanguageModules = unusedLanguageModules(mergeOrder, localeTokens),
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

    private fun resolveSplitApkEntryNames(
        zip: ZipFile,
        extension: String
    ): Set<String> {
        val candidates = zip.entries().asSequence()
            .filterNot { it.isDirectory }
            .filter { it.name.endsWith(".apk", ignoreCase = true) }
            .toList()
        if (candidates.isEmpty()) return emptySet()

        if (extension in SUPPORTED_EXTENSIONS) {
            return candidates.mapTo(LinkedHashSet()) { it.name }
        }
        if (extension == "zip") {
            return resolveZipApkEntryNames(candidates)
        }

        if (candidates.size < 2 || candidates.none { isLikelySplitApkEntryName(it.name) }) {
            return emptySet()
        }
        // Flatten retries re-feed merged APKs that already have a root manifest,
        // so keep verifying those containers instead of rejecting them early.
        if (!isVerifiedSplitCandidateSet(zip, candidates)) return emptySet()
        return candidates.mapTo(LinkedHashSet()) { it.name }
    }

    private fun hasRootManifest(zip: ZipFile): Boolean =
        zip.entries().asSequence().any { entry ->
            !entry.isDirectory && entry.name == "AndroidManifest.xml"
        }

    private fun hasRootResourcesTable(zip: ZipFile): Boolean =
        zip.entries().asSequence().any { entry ->
            !entry.isDirectory && entry.name == "resources.arsc"
        }

    private fun resolveZipApkEntryNames(
        candidates: List<java.util.zip.ZipEntry>
    ): Set<String> {
        if (candidates.size == 1) {
            return linkedSetOf(candidates.single().name)
        }

        val splitLike = candidates.filter { isLikelySplitApkEntryName(it.name) }
        if (splitLike.isEmpty()) return emptySet()

        val selected = LinkedHashSet<String>()
        selectProbableZipBaseEntry(candidates, splitLike)?.let { selected += it.name }
        splitLike.forEach { selected += it.name }
        return selected
    }

    private fun selectProbableZipBaseEntry(
        candidates: List<java.util.zip.ZipEntry>,
        splitLike: List<java.util.zip.ZipEntry>
    ): java.util.zip.ZipEntry? {
        candidates.firstOrNull { isExplicitBaseApkEntryName(it.name) }?.let { return it }
        val splitLikeNames = splitLike.mapTo(HashSet()) { it.name }
        return candidates
            .asSequence()
            .filterNot { it.name in splitLikeNames }
            .maxByOrNull { entry -> if (entry.size >= 0L) entry.size else Long.MIN_VALUE }
    }

    private fun isExplicitBaseApkEntryName(entryName: String): Boolean {
        val normalized = entryName.replace('\\', '/')
        val fileName = normalized.substringAfterLast('/').lowercase(Locale.ROOT)
        if (!fileName.endsWith(".apk")) return false
        val stem = fileName.removeSuffix(".apk")
        return fileName == "base.apk" ||
            stem == "base" ||
            stem == "main" ||
            stem == "master" ||
            stem.startsWith("base-") ||
            stem.endsWith("-main") ||
            stem.endsWith("-master")
    }

    private fun isVerifiedSplitCandidateSet(
        zip: ZipFile,
        candidates: List<java.util.zip.ZipEntry>
    ): Boolean = runCatching {
        val workingDir = Files.createTempDirectory("split-verify-").toFile()
        try {
            candidates.forEach { entry ->
                val destination = workingDir.resolve(entry.name.substringAfterLast('/'))
                zip.getInputStream(entry).use { input ->
                    Files.newOutputStream(destination.toPath()).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            val mergeOrder = runCatching {
                Merger.listMergeOrder(workingDir.toPath())
            }.getOrDefault(emptyList())
            mergeOrder.size >= 2 && mergeOrder.any(::isLikelySplitApkEntryName)
        } finally {
            workingDir.deleteRecursively()
        }
    }.getOrDefault(false)

    internal fun isLikelySplitApkEntryName(entryName: String): Boolean {
        val normalized = entryName.replace('\\', '/')
        val fileName = normalized.substringAfterLast('/')
        if (!fileName.endsWith(".apk", ignoreCase = true)) return false
        val lowerName = fileName.lowercase(Locale.ROOT)
        val stem = lowerName.removeSuffix(".apk")

        if (lowerName == "base.apk") return true
        if (lowerName.startsWith("split_config.") || lowerName.startsWith("config.")) return true
        if (stem.startsWith("split_")) return true
        if (stem == "main" || stem == "master") return true
        if (stem.startsWith("base-") || stem.endsWith("-main") || stem.endsWith("-master")) return true
        return false
    }

    private fun validatePreparedApk(file: File): PreparedApkValidation =
        runCatching {
            ZipFile(file).use { zip ->
                val embeddedSplitEntries = zip.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .map { it.name }
                    .filter { it.endsWith(".apk", ignoreCase = true) }
                    .filter { isExplicitBaseApkEntryName(it) || isLikelySplitApkEntryName(it) }
                    .toCollection(LinkedHashSet())
                PreparedApkValidation(
                    hasRootManifest = hasRootManifest(zip),
                    hasRootResources = hasRootResourcesTable(zip),
                    embeddedSplitEntries = embeddedSplitEntries
                )
            }
        }.getOrElse {
            PreparedApkValidation(
                hasRootManifest = false,
                hasRootResources = false,
                embeddedSplitEntries = linkedSetOf()
            )
        }

    private data class ExtractedModule(val name: String, val file: File)

    private data class PreparedApkValidation(
        val hasRootManifest: Boolean,
        val hasRootResources: Boolean,
        val embeddedSplitEntries: Set<String>
    ) {
        val isUsable: Boolean
            get() = hasRootManifest && hasRootResources && embeddedSplitEntries.isEmpty()

        fun describe(): String {
            val embedded = if (embeddedSplitEntries.isEmpty()) {
                "none"
            } else {
                embeddedSplitEntries.joinToString(",")
            }
            return "manifest=$hasRootManifest, resources=$hasRootResources, embeddedSplits=$embedded"
        }
    }

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

    private fun supportedAbiTokens(abiModules: List<String>): Set<String> =
        selectPrimaryAbi(Build.SUPPORTED_ABIS.toList(), abiModules)
            ?.let { primary ->
                buildAbiTokens(primary)
                    .map { it.lowercase(Locale.ROOT) }
                    .toSet()
            }
            ?: emptySet()

    private fun buildAbiTokens(abi: String): Set<String> {
        val normalized = abi.lowercase(Locale.ROOT)
        return setOf(
            normalized,
            normalized.replace('-', '_'),
            normalized.replace('_', '-')
        )
    }

    private fun selectPrimaryAbi(
        supportedAbis: List<String>,
        abiModules: List<String>
    ): String? =
        supportedAbis.firstOrNull { abi ->
            abi.isNotBlank() && abiModules.any {
                matchesAbiTokens(it, buildAbiTokens(abi))
            }
        }

    private fun normalizeModuleSelectionName(name: String): String =
        name.lowercase(Locale.ROOT).removeSuffix(".apk")

    private fun unusedAbiModules(moduleNames: List<String>): Set<String> {
        val abiModules = moduleNames.filter(::hasAbiQualifier)
        if (abiModules.size <= 1) return emptySet()
        val supportedTokens = supportedAbiTokens(abiModules)
        if (supportedTokens.isEmpty()) return emptySet()
        if (abiModules.none { matchesAbiTokens(it, supportedTokens) }) return emptySet()
        return abiModules.filter { shouldSkipModule(it, supportedTokens) }.toSet()
    }

    private fun hasAbiQualifier(moduleName: String): Boolean =
        matchesAbiTokens(
            moduleName = moduleName,
            abiTokens = KNOWN_ABIS.flatMap { buildAbiTokens(it) }.toSet()
        )

    private fun matchesAbiTokens(
        moduleName: String,
        abiTokens: Set<String>
    ): Boolean {
        val lower = moduleName.lowercase(Locale.ROOT)
        return abiTokens.any { token -> lower.contains(token.lowercase(Locale.ROOT)) }
    }

    private fun shouldSkipModule(
        moduleName: String,
        supportedTokens: Set<String>
    ): Boolean {
        if (!hasAbiQualifier(moduleName)) return false
        return !matchesAbiTokens(moduleName, supportedTokens)
    }

    private fun unusedLanguageModules(
        mergeOrder: List<String>,
        localeTokens: Set<String>
    ): Set<String> {
        val languageModules = mergeOrder.filter(::hasLanguageQualifier)
        if (languageModules.size <= 1) return emptySet()
        return languageModules.filter { shouldSkipLanguageModule(it, localeTokens) }.toSet()
    }

    private fun hasLanguageQualifier(moduleName: String): Boolean =
        splitConfigQualifiers(moduleName).any { qualifier ->
            parseLocaleQualifier(qualifier) != null
        }

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
        if (availableQualifiers.size == 1) return emptySet()
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
            val splitEntryNames = splitApkEntryNames(source)
            ZipFile(source).use { zip ->
                val apkEntries = zip.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .filter { it.name in splitEntryNames }
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
