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
    private val DENSITY_QUALIFIERS =
        setOf("ldpi", "mdpi", "tvdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")

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
                val mergeOrder = runCatching {
                    Merger.listMergeOrder(modulesDir.toPath())
                }.getOrElse {
                    entries.map { it.name }
                }
                coroutineContext.ensureActive()
                val supportedTokens = supportedAbiTokens()
                val skippedModules = buildSet {
                    if (stripNativeLibs) {
                        addAll(mergeOrder.filter { shouldSkipModule(it, supportedTokens) })
                    }
                    if (skipUnneededSplits) {
                        val localeTokens = deviceLocaleTokens()
                        val densityQualifier = deviceDensityQualifier()
                        addAll(
                            mergeOrder.filter {
                                shouldSkipModuleForDevice(
                                    moduleName = it,
                                    localeTokens = localeTokens,
                                    densityQualifier = densityQualifier
                                )
                            }
                        )
                    }
                }
                if (flattenPass == 0) {
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
            persistMergedIfDownloaded(source, finalApk, logger)

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

        if (extension == "apk" && hasRootManifest(zip)) return emptySet()
        if (candidates.size < 2 || candidates.none { isLikelySplitApkEntryName(it.name) }) {
            return emptySet()
        }
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
        val usedFileNames = HashSet<String>()
        try {
            candidates.forEach { entry ->
                val destination = workingDir.resolve(
                    uniqueExtractedFileName(entry.name, usedFileNames)
                )
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
        moduleNames: List<String>,
        skippedModules: Set<String>,
        stripNativeLibs: Boolean
    ): List<String> {
        val steps = mutableListOf<String>()
        steps.add("Extracting split APKs")
        val skippedLookup = skippedModules
            .map { it.lowercase(Locale.ROOT) }
            .toSet()
        moduleNames.forEach { name ->
            val label = "Merging $name"
            val entry = if (skippedLookup.contains(name.lowercase(Locale.ROOT))) {
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
        selectPrimaryAbi(Build.SUPPORTED_ABIS.toList())
            ?.let { primary ->
                buildAbiTokens(primary)
                    .map { it.lowercase(Locale.ROOT) }
                    .toSet()
            }
            ?: Build.SUPPORTED_ABIS
                .flatMap { abi -> buildAbiTokens(abi) }
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

    private fun selectPrimaryAbi(supportedAbis: List<String>): String? =
        supportedAbis.firstOrNull { it.isNotBlank() }

    private fun shouldSkipModule(
        moduleName: String,
        supportedTokens: Set<String>
    ): Boolean {
        val lower = moduleName.lowercase(Locale.ROOT)
        val knownTokens = KNOWN_ABIS.flatMap { buildAbiTokens(it) }.toSet()
        if (knownTokens.none { lower.contains(it) }) return false
        return supportedTokens.none { lower.contains(it) }
    }

    private fun shouldSkipModuleForDevice(
        moduleName: String,
        localeTokens: Set<String>,
        densityQualifier: String?
    ): Boolean {
        val qualifiers = splitConfigQualifiers(moduleName)
        if (qualifiers.isEmpty()) return false
        if (isAbiSplit(moduleName)) return false

        for (qualifier in qualifiers) {
            if (isDensityQualifier(qualifier)) {
                val deviceDensity = densityQualifier ?: continue
                if (qualifier != deviceDensity) return true
                continue
            }
            val localeQualifier = parseLocaleQualifier(qualifier) ?: continue
            if (!matchesLocaleQualifier(localeQualifier, localeTokens)) {
                return true
            }
        }
        return false
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

    private data class LocaleQualifier(val language: String, val region: String?)

    private fun parseLocaleQualifier(rawToken: String): LocaleQualifier? {
        val token = rawToken.replace('-', '_')
        val parts = token.split('_').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val language = parts[0]
        if (language.length !in 2..3 || !language.all { it.isLetter() }) return null
        val region = parts.getOrNull(1)
            ?.removePrefix("r")
            ?.takeIf { it.length in 2..3 && it.all { ch -> ch.isLetterOrDigit() } }
        return LocaleQualifier(language.lowercase(Locale.ROOT), region?.lowercase(Locale.ROOT))
    }

    private fun matchesLocaleQualifier(
        qualifier: LocaleQualifier,
        localeTokens: Set<String>
    ): Boolean {
        val language = qualifier.language
        val region = qualifier.region
        return if (region == null) {
            localeTokens.contains(language)
        } else {
            localeTokens.contains("${language}_r$region") ||
                localeTokens.contains("${language}_$region") ||
                localeTokens.contains("${language}-$region")
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

    private suspend fun extractSplitEntries(
        source: File,
        targetDir: File,
        onProgress: ((String) -> Unit)? = null
    ): List<ExtractedModule> =
        withContext(Dispatchers.IO) {
            val extracted = mutableListOf<ExtractedModule>()
            val usedFileNames = HashSet<String>()
            coroutineContext.ensureActive()
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
                    coroutineContext.ensureActive()
                    val destination = targetDir.resolve(
                        uniqueExtractedFileName(entry.name, usedFileNames)
                    )
                    destination.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        Files.newOutputStream(destination.toPath()).use { output ->
                            input.copyTo(output)
                        }
                    }
                    extracted += ExtractedModule(destination.name, destination)
                    coroutineContext.ensureActive()
                }
            }
            extracted
        }

    private fun uniqueExtractedFileName(
        entryName: String,
        usedFileNames: MutableSet<String>
    ): String {
        val original = entryName.substringAfterLast('/').ifBlank { "split.apk" }
        if (usedFileNames.add(original)) return original

        val dotIndex = original.lastIndexOf('.')
        val stem = if (dotIndex > 0) original.substring(0, dotIndex) else original
        val extension = if (dotIndex > 0) original.substring(dotIndex) else ""
        val suffix = entryName.replace('\\', '/').hashCode().toUInt().toString(16)
        var candidate = "$stem-$suffix$extension"
        var collisionIndex = 1
        while (!usedFileNames.add(candidate)) {
            candidate = "$stem-$suffix-$collisionIndex$extension"
            collisionIndex += 1
        }
        return candidate
    }

    data class PreparationResult(
        val file: File,
        val merged: Boolean,
        val cleanup: () -> Unit = {}
    )

    private fun persistMergedIfDownloaded(source: File, merged: File, logger: Logger) {
        // Only persist back to the downloads cache when the original input lives in our downloaded-apps dir.
        val downloadsRoot = source.parentFile?.parentFile
        val isDownloadedApp = downloadsRoot?.name?.startsWith("app_downloaded-apps") == true
        if (!isDownloadedApp) return

        runCatching {
            merged.copyTo(source, overwrite = true)
            logger.info("Persisted merged split APK back to downloads cache: ${source.absolutePath}")
        }.onFailure { error ->
            logger.warn("Failed to persist merged split APK to downloads cache: ${error.message}")
        }
    }

    private object defaultLogger : Logger() {
        override fun log(level: LogLevel, message: String) {
            Log.d("SplitApkPreparer", "[${level.name}] $message")
        }
    }
}
