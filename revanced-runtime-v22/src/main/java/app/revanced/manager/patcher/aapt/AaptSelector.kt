package app.revanced.manager.patcher.aapt

import app.revanced.manager.patcher.logger.Logger
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

object AaptSelector {
    private val genderQualifiers = listOf("-feminine", "-masculine", "-neuter")
    private val genderTokens = listOf("feminine", "masculine", "neuter", "grammaticalgender", "grammatical-gender")
    private val manifestTokens = listOf(
        "uri-relative-filter-group",
        "android:allow",
        "intentMatchingFlags",
        "queryadvancedpattern",
        "querypattern",
        "queryprefix",
        "querysuffix",
        "fragmentadvancedpattern",
        "fragmentpattern",
        "fragmentprefix",
        "fragmentsuffix"
    )
    private val normalizedManifestTokens = manifestTokens.map(::normalizeForTokenScan)
    private val targetSdkRegex = Regex("targetSdkVersion:'(\\d+)'", RegexOption.IGNORE_CASE)

    fun select(
        primary: String,
        fallback: String?,
        apk: File,
        logger: Logger? = null,
        additionalArchives: Collection<File> = emptyList(),
        preferPrimary: Boolean = false
    ): String {
        if (fallback.isNullOrBlank() || primary == fallback) {
            return primary
        }
        if (preferPrimary) {
            logger?.info("AAPT2: primary binary forced by override")
            return primary
        }

        val archives = linkedSetOf(apk).apply { addAll(additionalArchives.filter { it.exists() }) }.toList()
        if (shouldPreferModernByTargetSdk(fallback, apk, logger)) {
            logger?.info("AAPT2: using fallback binary due to targetSdk >= 35")
            return fallback
        }

        val useFallback = when {
            containsGenderedQualifiers(archives) -> {
                logger?.info("AAPT2: using fallback binary due to gendered resource qualifiers")
                true
            }
            containsModernManifestFeatures(archives) -> {
                logger?.info("AAPT2: using fallback binary due to API 35 manifest features")
                true
            }
            dumpShowsGenderedQualifiers(fallback, apk, logger) -> {
                logger?.info("AAPT2: using fallback binary due to qualifier probe")
                true
            }
            dumpShowsModernManifestFeatures(fallback, apk, logger) -> {
                logger?.info("AAPT2: using fallback binary due to manifest probe")
                true
            }
            else -> false
        }

        if (useFallback) {
            return fallback
        }

        return primary
    }

    private fun containsGenderedQualifiers(archives: Collection<File>): Boolean {
        return archives.any { archive ->
            runCatching {
                var found = false
                ZipFile(archive).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val name = entries.nextElement().name
                        if (!name.startsWith("res/")) continue
                        val folder = name.substringAfter("res/").substringBefore('/')
                        if (!folder.startsWith("values-")) continue
                        val lower = folder.lowercase(Locale.ROOT)
                        if (genderQualifiers.any { lower.contains(it) }) {
                            found = true
                            break
                        }
                    }
                }
                found
            }.getOrDefault(false)
        }
    }

    private fun containsModernManifestFeatures(archives: Collection<File>): Boolean {
        return archives.any { archive ->
            runCatching {
                var found = false
                ZipFile(archive).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val name = entry.name
                        if (!name.endsWith("AndroidManifest.xml")) continue
                        val normalizedText = zip.getInputStream(entry).use { input ->
                            normalizeForTokenScan(String(input.readBytes(), StandardCharsets.ISO_8859_1))
                        }
                        if (normalizedManifestTokens.any { token -> normalizedText.contains(token) }) {
                            found = true
                            break
                        }
                    }
                }
                found
            }.getOrDefault(false)
        }
    }

    private fun dumpShowsGenderedQualifiers(aaptPath: String, apk: File, logger: Logger?): Boolean {
        return runCatching {
            val process = ProcessBuilder(
                aaptPath,
                "dump",
                "configurations",
                apk.absolutePath
            )
                .redirectErrorStream(true)
                .start()

            val completed = process.waitFor(20, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                logger?.warn("AAPT2 selector probe timed out")
                return@runCatching false
            }

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val lower = output.lowercase(Locale.ROOT)
            genderTokens.any { token -> lower.contains(token) }
        }.onFailure {
            logger?.warn("AAPT2 selector probe failed: ${it.message}")
        }.getOrDefault(false)
    }

    private fun dumpShowsModernManifestFeatures(aaptPath: String, apk: File, logger: Logger?): Boolean {
        return runCatching {
            val process = ProcessBuilder(
                aaptPath,
                "dump",
                "xmltree",
                apk.absolutePath,
                "AndroidManifest.xml"
            )
                .redirectErrorStream(true)
                .start()

            val completed = process.waitFor(20, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                logger?.warn("AAPT2 manifest probe timed out")
                return@runCatching false
            }

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val normalizedOutput = normalizeForTokenScan(output)
            normalizedManifestTokens.any { token -> normalizedOutput.contains(token) }
        }.onFailure {
            logger?.warn("AAPT2 manifest probe failed: ${it.message}")
        }.getOrDefault(false)
    }

    private fun shouldPreferModernByTargetSdk(aaptPath: String, apk: File, logger: Logger?): Boolean {
        val targetSdk = dumpTargetSdk(aaptPath, apk, logger) ?: return false
        return targetSdk >= 35
    }

    private fun dumpTargetSdk(aaptPath: String, apk: File, logger: Logger?): Int? {
        return runCatching {
            val process = ProcessBuilder(
                aaptPath,
                "dump",
                "badging",
                apk.absolutePath
            )
                .redirectErrorStream(true)
                .start()

            val completed = process.waitFor(20, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                logger?.warn("AAPT2 targetSdk probe timed out")
                return@runCatching null
            }

            val output = process.inputStream.bufferedReader().use { it.readText() }
            targetSdkRegex.find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }.onFailure {
            logger?.warn("AAPT2 targetSdk probe failed: ${it.message}")
        }.getOrNull()
    }

    private fun normalizeForTokenScan(value: String): String = buildString(value.length) {
        value.lowercase(Locale.ROOT).forEach { ch ->
            if ((ch in 'a'..'z') || (ch in '0'..'9')) {
                append(ch)
            }
        }
    }
}
