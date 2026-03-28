package app.revanced.manager.patcher.util

import app.revanced.manager.patcher.logger.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.LinkedHashMap
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object MislabeledImageResourceSanitizer {
    data class Result(
        val file: File,
        val cleanup: () -> Unit = {}
    )

    fun sanitizeApkFile(apkFile: File, workingDir: File, logger: Logger? = null): Result {
        if (!apkFile.exists() || !apkFile.extension.equals("apk", ignoreCase = true)) {
            return Result(apkFile)
        }

        return runCatching {
            ZipFile(apkFile).use { zip ->
                val planned = planRenames(zip)
                if (planned.rewrites.isEmpty()) {
                    if (planned.collisions.isNotEmpty()) {
                        logger?.warn(
                            "Skipped ${planned.collisions.size} mislabeled image resource rename(s) " +
                                "due to entry name collisions: " +
                                planned.collisions.joinToString { "${it.first} -> ${it.second}" }
                        )
                    }
                    return Result(apkFile)
                }

                workingDir.mkdirs()
                val sanitized = Files.createTempFile(
                    workingDir.toPath(),
                    "${apkFile.nameWithoutExtension}-resource-sanitized-",
                    ".apk"
                ).toFile()

                try {
                    rewriteApk(zip, sanitized, planned.rewrites)
                } catch (error: Throwable) {
                    sanitized.delete()
                    throw error
                }

                logger?.warn(
                    "Sanitized ${planned.rewrites.size} mislabeled image resource(s) before patching: " +
                        planned.rewrites.entries.joinToString { "${it.key} -> ${it.value}" }
                )
                if (planned.collisions.isNotEmpty()) {
                    logger?.warn(
                        "Skipped ${planned.collisions.size} additional mislabeled image resource rename(s) " +
                            "due to entry name collisions: " +
                            planned.collisions.joinToString { "${it.first} -> ${it.second}" }
                    )
                }

                Result(sanitized) { sanitized.delete() }
            }
        }.getOrElse { error ->
            logger?.warn(
                "Failed to sanitize mislabeled image resources in ${apkFile.name}: " +
                    (error.message ?: error::class.java.simpleName)
            )
            Result(apkFile)
        }
    }

    fun sanitizeDecodedResources(resourcesDir: File, logger: Logger? = null) {
        if (!resourcesDir.exists() || !resourcesDir.isDirectory) {
            return
        }

        val resourceRoot = resourcesDir.parentFile ?: return
        val rewrites = LinkedHashMap<String, String>()
        val collisions = mutableListOf<Pair<String, String>>()
        val unsupportedFormats = mutableListOf<Pair<String, ImageType>>()
        val invalidImages = mutableListOf<String>()

        resourcesDir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val relativeName = file.relativeTo(resourceRoot)
                    .path
                    .replace(File.separatorChar, '/')
                if (!isSanitizableResourceEntry(relativeName)) return@forEach
                val declaredType = declaredImageType(relativeName) ?: return@forEach
                val actualType = file.inputStream().buffered().use(::detectImageType)
                when {
                    actualType == null -> invalidImages += relativeName
                    declaredType == actualType -> Unit
                    !actualType.resourceSafe -> unsupportedFormats += relativeName to actualType
                    else -> {
                        val renamedRelativeName = renamedEntryName(relativeName, actualType)
                        if (renamedRelativeName == relativeName) return@forEach
                        val renamedFile = resourceRoot.resolve(
                            renamedRelativeName.replace('/', File.separatorChar)
                        )
                        if (renamedFile.exists()) {
                            collisions += relativeName to renamedRelativeName
                            return@forEach
                        }
                        renamedFile.parentFile?.mkdirs()
                        Files.move(file.toPath(), renamedFile.toPath())
                        rewrites[relativeName] = renamedRelativeName
                    }
                }
            }

        logDirectoryOutcome(
            resourcesDir = resourcesDir,
            rewrites = rewrites,
            collisions = collisions,
            unsupportedFormats = unsupportedFormats,
            invalidImages = invalidImages,
            logger = logger
        )

    }

    private fun planRenames(zip: ZipFile): RenamePlan {
        val entries = zip.entries().asSequence()
            .filterNot { it.isDirectory }
            .toList()
        val reservedNames = entries.mapTo(HashSet()) { it.name }
        val rewrites = LinkedHashMap<String, String>()
        val collisions = mutableListOf<Pair<String, String>>()

        entries.forEach { entry ->
            if (!isSanitizableResourceEntry(entry.name)) return@forEach
            val declaredType = declaredImageType(entry.name) ?: return@forEach
            val actualType = zip.getInputStream(entry).use(::detectImageType) ?: return@forEach
            if (declaredType == actualType) return@forEach
            if (!actualType.resourceSafe) return@forEach

            val renamed = renamedEntryName(entry.name, actualType)
            if (renamed == entry.name) return@forEach
            if (!reservedNames.add(renamed)) {
                collisions += entry.name to renamed
                return@forEach
            }
            rewrites[entry.name] = renamed
        }

        return RenamePlan(rewrites, collisions)
    }

    private fun rewriteApk(
        zip: ZipFile,
        output: File,
        rewrites: Map<String, String>
    ) {
        ZipOutputStream(FileOutputStream(output).buffered()).use { outputZip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val renamed = rewrites[entry.name] ?: entry.name
                val outputEntry = cloneEntry(entry, renamed)
                outputZip.putNextEntry(outputEntry)
                if (!entry.isDirectory) {
                    zip.getInputStream(entry).use { input -> input.copyTo(outputZip) }
                }
                outputZip.closeEntry()
            }
        }
    }

    private fun isSanitizableResourceEntry(name: String): Boolean {
        val normalized = name.replace('\\', '/')
        if (!normalized.startsWith("res/")) return false
        val dirName = normalized.substringAfter("res/").substringBefore('/')
        if (!dirName.startsWith("drawable") && !dirName.startsWith("mipmap")) return false
        val lower = normalized.lowercase(Locale.ROOT)
        if (lower.endsWith(".9.png")) return false
        return declaredImageType(lower) != null
    }

    private fun declaredImageType(name: String): ImageType? {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".png") -> ImageType.PNG
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> ImageType.JPEG
            lower.endsWith(".webp") -> ImageType.WEBP
            lower.endsWith(".avif") -> ImageType.AVIF
            lower.endsWith(".gif") -> ImageType.GIF
            lower.endsWith(".bmp") -> ImageType.BMP
            lower.endsWith(".heic") || lower.endsWith(".heif") -> ImageType.HEIF
            else -> null
        }
    }

    private fun detectImageType(input: InputStream): ImageType? {
        val header = ByteArray(32)
        val count = input.read(header)
        if (count >= 8 &&
            header[0] == 0x89.toByte() &&
            header[1] == 0x50.toByte() &&
            header[2] == 0x4E.toByte() &&
            header[3] == 0x47.toByte() &&
            header[4] == 0x0D.toByte() &&
            header[5] == 0x0A.toByte() &&
            header[6] == 0x1A.toByte() &&
            header[7] == 0x0A.toByte()
        ) {
            return ImageType.PNG
        }
        if (count >= 3 &&
            header[0] == 0xFF.toByte() &&
            header[1] == 0xD8.toByte() &&
            header[2] == 0xFF.toByte()
        ) {
            return ImageType.JPEG
        }
        if (count >= 12 &&
            header[0] == 'R'.code.toByte() &&
            header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() &&
            header[3] == 'F'.code.toByte() &&
            header[8] == 'W'.code.toByte() &&
            header[9] == 'E'.code.toByte() &&
            header[10] == 'B'.code.toByte() &&
            header[11] == 'P'.code.toByte()
        ) {
            return ImageType.WEBP
        }
        if (count >= 6) {
            val signature = String(header, 0, 6, Charsets.US_ASCII)
            if (signature == "GIF87a" || signature == "GIF89a") {
                return ImageType.GIF
            }
        }
        if (count >= 2 &&
            header[0] == 'B'.code.toByte() &&
            header[1] == 'M'.code.toByte()
        ) {
            return ImageType.BMP
        }
        detectIsoBmffImageType(header, count)?.let { return it }
        return null
    }

    private fun detectIsoBmffImageType(header: ByteArray, count: Int): ImageType? {
        if (count < 16) return null
        if (header[4] != 'f'.code.toByte() ||
            header[5] != 't'.code.toByte() ||
            header[6] != 'y'.code.toByte() ||
            header[7] != 'p'.code.toByte()
        ) {
            return null
        }

        val brands = buildList {
            fourCcAt(header, 8, count)?.let(::add)
            var offset = 16
            while (offset + 4 <= count) {
                fourCcAt(header, offset, count)?.let(::add)
                offset += 4
            }
        }
        if (brands.any { it == "avif" || it == "avis" }) {
            return ImageType.AVIF
        }
        if (brands.any { it == "heic" || it == "heix" || it == "hevc" || it == "hevx" }) {
            return ImageType.HEIF
        }
        return null
    }

    private fun fourCcAt(bytes: ByteArray, offset: Int, count: Int): String? {
        if (offset + 4 > count) return null
        return String(bytes, offset, 4, Charsets.US_ASCII)
    }

    private fun renamedEntryName(name: String, type: ImageType): String {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".png") -> name.dropLast(4) + type.extension
            lower.endsWith(".jpg") -> name.dropLast(4) + type.extension
            lower.endsWith(".jpeg") -> name.dropLast(5) + type.extension
            lower.endsWith(".webp") -> name.dropLast(5) + type.extension
            lower.endsWith(".avif") -> name.dropLast(5) + type.extension
            lower.endsWith(".gif") -> name.dropLast(4) + type.extension
            lower.endsWith(".bmp") -> name.dropLast(4) + type.extension
            lower.endsWith(".heic") || lower.endsWith(".heif") -> name.dropLast(5) + type.extension
            else -> name
        }
    }

    private fun logDirectoryOutcome(
        resourcesDir: File,
        rewrites: Map<String, String>,
        collisions: List<Pair<String, String>>,
        unsupportedFormats: List<Pair<String, ImageType>>,
        invalidImages: List<String>,
        logger: Logger?
    ) {
        if (rewrites.isNotEmpty()) {
            logger?.warn(
                "Sanitized ${rewrites.size} decoded image resource(s) before resource compile in " +
                    "${resourcesDir.absolutePath}: " +
                    rewrites.entries.joinToString { "${it.key} -> ${it.value}" }
            )
        }
        if (collisions.isNotEmpty()) {
            logger?.warn(
                "Skipped ${collisions.size} decoded image resource rename(s) due to collisions: " +
                    collisions.joinToString { "${it.first} -> ${it.second}" }
            )
        }
        if (unsupportedFormats.isNotEmpty()) {
            logger?.warn(
                "Detected ${unsupportedFormats.size} decoded image resource(s) with unsupported " +
                    "actual formats that were left unchanged: " +
                    unsupportedFormats.joinToString { "${it.first} -> ${it.second.extension}" }
            )
        }
        if (invalidImages.isNotEmpty()) {
            logger?.warn(
                "Detected ${invalidImages.size} decoded image resource(s) with unrecognized signatures: " +
                    invalidImages.joinToString()
            )
        }
    }

    private fun cloneEntry(entry: ZipEntry, name: String): ZipEntry {
        val clone = ZipEntry(name)
        clone.time = entry.time
        clone.comment = entry.comment
        entry.extra?.let { clone.extra = it.copyOf() }
        when (entry.method) {
            ZipEntry.STORED -> {
                clone.method = ZipEntry.STORED
                if (entry.size >= 0) clone.size = entry.size
                if (entry.compressedSize >= 0) clone.compressedSize = entry.compressedSize
                clone.crc = entry.crc
            }

            ZipEntry.DEFLATED -> clone.method = ZipEntry.DEFLATED
            else -> if (entry.method != -1) clone.method = entry.method
        }
        return clone
    }

    private data class RenamePlan(
        val rewrites: Map<String, String>,
        val collisions: List<Pair<String, String>>
    )

    private enum class ImageType(val extension: String, val resourceSafe: Boolean) {
        PNG(".png", true),
        JPEG(".jpg", true),
        WEBP(".webp", true),
        AVIF(".avif", true),
        GIF(".gif", false),
        BMP(".bmp", false),
        HEIF(".heic", false)
    }
}
