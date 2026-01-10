package app.revanced.manager.patcher.split

import android.os.Build
import android.util.Log
import app.revanced.manager.patcher.logger.LogLevel
import app.revanced.manager.patcher.logger.Logger
import app.revanced.manager.patcher.util.NativeLibStripper
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

object SplitApkPreparer {
    private val SUPPORTED_EXTENSIONS = setOf("apks", "apkm", "xapk")
    private const val SKIPPED_STEP_PREFIX = "[skipped]"
    private val KNOWN_ABIS = setOf("armeabi", "armeabi-v7a", "arm64-v8a", "x86", "x86_64")

    fun isSplitArchive(file: File?): Boolean {
        if (file == null || !file.exists()) return false
        val extension = file.extension.lowercase(Locale.ROOT)
        if (extension in SUPPORTED_EXTENSIONS) return true
        return hasEmbeddedApkEntries(file)
    }

    suspend fun prepareIfNeeded(
        source: File,
        workspace: File,
        logger: Logger = defaultLogger,
        stripNativeLibs: Boolean = false,
        onProgress: ((String) -> Unit)? = null,
        onSubSteps: ((List<String>) -> Unit)? = null
    ): PreparationResult {
        if (!isSplitArchive(source)) {
            return PreparationResult(source, merged = false)
        }

        workspace.mkdirs()
        val workingDir = File(workspace, "split-${System.currentTimeMillis()}")
        val modulesDir = workingDir.resolve("modules").also { it.mkdirs() }
        val mergedApk = workingDir.resolve("${source.nameWithoutExtension}-merged.apk")

        return try {
            val sourceSize = source.length()
            logger.info("Preparing split APK bundle from ${source.name} (size=${sourceSize} bytes)")
            val entries = extractSplitEntries(source, modulesDir, onProgress)
            logger.info("Found ${entries.size} split modules: ${entries.joinToString { it.name }}")
            logger.info("Module sizes: ${entries.joinToString { "${it.name}=${it.file.length()} bytes" }}")
            val mergeOrder = runCatching {
                Merger.listMergeOrder(modulesDir.toPath())
            }.getOrElse {
                entries.map { it.name }
            }
            val skippedModules = if (stripNativeLibs) {
                val supportedTokens = supportedAbiTokens()
                mergeOrder.filter { shouldSkipModule(it, supportedTokens) }.toSet()
            } else {
                emptySet()
            }
            onSubSteps?.invoke(buildSplitSubSteps(mergeOrder, skippedModules, stripNativeLibs))

            val module = Merger.merge(modulesDir.toPath(), skippedModules, onProgress)
            module.use {
                runInterruptible(Dispatchers.IO) {
                    onProgress?.invoke("Writing merged APK")
                    it.writeApk(mergedApk)
                }
            }

            if (stripNativeLibs) {
                onProgress?.invoke("Stripping native libraries")
                NativeLibStripper.strip(mergedApk)
            }

            onProgress?.invoke("Finalizing merged APK")
            persistMergedIfDownloaded(source, mergedApk, logger)

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

    private fun hasEmbeddedApkEntries(file: File): Boolean =
        runCatching {
            ZipFile(file).use { zip ->
                zip.entries().asSequence().any { entry ->
                    !entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)
                }
            }
        }.getOrDefault(false)

    private data class ExtractedModule(val name: String, val file: File)

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
        val (skipped, remaining) = moduleNames.partition {
            skippedLookup.contains(it.lowercase(Locale.ROOT))
        }
        (skipped + remaining).forEach { name ->
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
        Build.SUPPORTED_ABIS
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

    private fun shouldSkipModule(
        moduleName: String,
        supportedTokens: Set<String>
    ): Boolean {
        val lower = moduleName.lowercase(Locale.ROOT)
        val knownTokens = KNOWN_ABIS.flatMap { buildAbiTokens(it) }.toSet()
        if (knownTokens.none { lower.contains(it) }) return false
        return supportedTokens.none { lower.contains(it) }
    }

    private suspend fun extractSplitEntries(
        source: File,
        targetDir: File,
        onProgress: ((String) -> Unit)? = null
    ): List<ExtractedModule> =
        withContext(Dispatchers.IO) {
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
                    val destination = targetDir.resolve(entry.name.substringAfterLast('/'))
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
