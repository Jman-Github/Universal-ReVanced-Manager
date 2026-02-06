package app.revanced.manager.patcher.aapt

import app.revanced.manager.patcher.logger.Logger
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

object AaptSelector {
    private val genderQualifiers = listOf("-feminine", "-masculine", "-neuter")
    private val genderTokens = listOf("feminine", "masculine", "neuter", "grammaticalgender", "grammatical-gender")

    fun select(primary: String, fallback: String?, apk: File, logger: Logger? = null): String {
        if (fallback.isNullOrBlank() || primary == fallback) {
            return primary
        }

        val useFallback = containsGenderedQualifiers(apk) || dumpShowsGenderedQualifiers(fallback, apk, logger)
        if (useFallback) {
            logger?.info("AAPT2: using fallback binary due to gendered resource qualifiers")
            return fallback
        }

        return primary
    }

    private fun containsGenderedQualifiers(apk: File): Boolean {
        return runCatching {
            var found = false
            ZipFile(apk).use { zip ->
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
}
