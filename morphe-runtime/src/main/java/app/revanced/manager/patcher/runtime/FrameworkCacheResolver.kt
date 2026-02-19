package app.revanced.manager.patcher.runtime

import app.revanced.manager.patcher.logger.Logger
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

object FrameworkCacheResolver {
    private val targetSdkRegex = Regex("targetSdkVersion:'(\\d+)'")
    private val targetSdkXmlHexRegex = Regex(
        "targetSdkVersion\\(0x[0-9a-f]+\\)=\\(type 0x10\\)0x([0-9a-f]+)",
        RegexOption.IGNORE_CASE
    )
    private val targetSdkXmlDecRegex = Regex(
        "targetSdkVersion\\(0x[0-9a-f]+\\)=\\(type 0x10\\)(\\d+)",
        RegexOption.IGNORE_CASE
    )

    fun resolve(
        baseFrameworkDir: String,
        runtimeTag: String,
        apkFile: File,
        aaptPath: String,
        logger: Logger? = null
    ): String {
        val targetSdk = resolveTargetSdk(apkFile, aaptPath, logger) ?: 0
        val aaptHashPart = sha256(File(aaptPath))?.take(12) ?: "nohash"
        val aaptVersionPart = runAaptVersion(aaptPath)?.let(::sha256Text)?.take(8) ?: "noversion"
        val key = buildString {
            append("framework_")
            append(sanitize(runtimeTag))
            append("_sdk")
            append(targetSdk)
            append("_")
            append(aaptHashPart)
            append("_")
            append(aaptVersionPart)
        }

        val resolvedDir = File(baseFrameworkDir).resolve(key).also { it.mkdirs() }
        logger?.info("Framework cache key: $key")
        return resolvedDir.absolutePath
    }

    private fun resolveTargetSdk(apkFile: File, aaptPath: String, logger: Logger?): Int? {
        val fromBadging = runAapt(
            aaptPath,
            "dump",
            "badging",
            apkFile.absolutePath,
            logger = logger
        )
            ?.let { output ->
                targetSdkRegex.find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
        if (fromBadging != null) return fromBadging

        return runAapt(
            aaptPath,
            "dump",
            "xmltree",
            apkFile.absolutePath,
            "AndroidManifest.xml",
            logger = logger
        )
            ?.let { output ->
                targetSdkXmlHexRegex.find(output)?.groupValues?.getOrNull(1)?.toIntOrNull(16)
                    ?: targetSdkXmlDecRegex.find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
    }

    private fun runAapt(aaptPath: String, vararg args: String, logger: Logger?): String? =
        runCatching {
            val process = ProcessBuilder(listOf(aaptPath) + args)
                .redirectErrorStream(true)
                .start()

            val completed = process.waitFor(20, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                logger?.warn("Framework cache probe timed out for command: ${args.joinToString(" ")}")
                return@runCatching null
            }

            process.inputStream.bufferedReader().use { it.readText() }
        }.onFailure {
            logger?.warn("Framework cache probe failed: ${it.message}")
        }.getOrNull()

    private fun runAaptVersion(aaptPath: String): String? =
        runCatching {
            val process = ProcessBuilder(aaptPath, "version")
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(10, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return@runCatching null
            }
            process.inputStream.bufferedReader().use { it.readText().trim() }.takeIf { it.isNotBlank() }
        }.getOrNull()

    private fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
    }.getOrNull()

    private fun sha256Text(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

    private fun sanitize(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9._-]"), "_")
}
