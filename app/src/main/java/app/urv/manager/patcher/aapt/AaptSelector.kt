package app.urv.manager.patcher.aapt

import app.urv.manager.patcher.logger.Logger
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object AaptSelector {
    fun select(
        modern: String,
        legacy: String?,
        apk: File,
        logger: Logger? = null,
        additionalArchives: Collection<File> = emptyList()
    ): String {
        if (legacy.isNullOrBlank() || modern == legacy) {
            logger?.info(MODERN_LOG)
            return modern
        }

        val targetSdk = resolveTargetSdk(apk, listOf(modern, legacy), logger)
        if (targetSdk != null && targetSdk < MODERN_MIN_TARGET_SDK) {
            logger?.info(LEGACY_LOG)
            logger?.info("AAPT2 selector: target SDK $targetSdk uses legacy aapt2")
            return legacy
        }

        val archives = linkedSetOf(apk).apply { addAll(additionalArchives.filter { it.exists() }) }.toList()
        val legacyArchive = archives.firstOrNull { archive ->
            val modernProbe = probe(modern, archive, logger)
            modernProbe == ProbeResult.Failed && probe(legacy, archive, logger) == ProbeResult.Succeeded
        }

        if (legacyArchive != null) {
            logger?.info(LEGACY_LOG)
            return legacy
        }

        logger?.info(MODERN_LOG)
        return modern
    }

    private enum class ProbeResult {
        Succeeded,
        Failed
    }

    private fun probe(aaptPath: String, apk: File, logger: Logger?): ProbeResult =
        runCatching {
            val process = ProcessBuilder(
                aaptPath,
                "dump",
                "configurations",
                apk.absolutePath
            )
                .redirectErrorStream(true)
                .start()
            val outputDrain = thread(start = true, name = "aapt2-selector-probe") {
                process.inputStream.bufferedReader().use { it.readText() }
            }

            val completed = process.waitFor(20, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                outputDrain.join(1_000)
                logger?.warn("AAPT2 selector probe timed out")
                return@runCatching ProbeResult.Failed
            }
            outputDrain.join(1_000)

            if (process.exitValue() == 0) ProbeResult.Succeeded else ProbeResult.Failed
        }.onFailure {
            logger?.warn("AAPT2 selector probe failed: ${it.message}")
        }.getOrDefault(ProbeResult.Failed)

    private fun resolveTargetSdk(apk: File, aaptPaths: List<String>, logger: Logger?): Int? {
        aaptPaths.forEach { aaptPath ->
            val targetSdk = dumpBadging(aaptPath, apk, logger)
                ?.let { output -> targetSdkRegex.find(output)?.groupValues?.getOrNull(1)?.toIntOrNull() }
                ?: dumpXmlTree(aaptPath, apk, logger)
                    ?.let { output ->
                        targetSdkXmlHexRegex.find(output)?.groupValues?.getOrNull(1)?.toIntOrNull(16)
                            ?: targetSdkXmlDecRegex.find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    }

            if (targetSdk != null) return targetSdk
        }

        logger?.warn("AAPT2 selector could not resolve APK target SDK")
        return null
    }

    private fun dumpBadging(aaptPath: String, apk: File, logger: Logger?): String? =
        runAapt(aaptPath, apk, logger, "dump", "badging", apk.absolutePath)

    private fun dumpXmlTree(aaptPath: String, apk: File, logger: Logger?): String? =
        runAapt(aaptPath, apk, logger, "dump", "xmltree", apk.absolutePath, "AndroidManifest.xml")

    private fun runAapt(aaptPath: String, apk: File, logger: Logger?, vararg args: String): String? =
        runCatching {
            val process = ProcessBuilder(listOf(aaptPath) + args)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val outputDrain = thread(start = true, name = "aapt2-selector-target-sdk") {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> output.appendLine(line) }
                }
            }

            val completed = process.waitFor(20, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                outputDrain.join(1_000)
                logger?.warn("AAPT2 selector target SDK probe timed out for ${apk.name}")
                return@runCatching null
            }
            outputDrain.join(1_000)

            output.takeIf { process.exitValue() == 0 }?.toString()
        }.onFailure {
            logger?.warn("AAPT2 selector target SDK probe failed: ${it.message}")
        }.getOrNull()

    private const val MODERN_MIN_TARGET_SDK = 35
    private const val MODERN_LOG = "AAPT2: Modern"
    private const val LEGACY_LOG = "AAPT2: Legacy"
    private val targetSdkRegex = Regex("targetSdkVersion:'(\\d+)'")
    private val targetSdkXmlHexRegex = Regex(
        "targetSdkVersion\\(0x[0-9a-f]+\\)=\\(type 0x10\\)0x([0-9a-f]+)",
        RegexOption.IGNORE_CASE
    )
    private val targetSdkXmlDecRegex = Regex(
        "targetSdkVersion\\(0x[0-9a-f]+\\)=\\(type 0x10\\)(\\d+)",
        RegexOption.IGNORE_CASE
    )
}
