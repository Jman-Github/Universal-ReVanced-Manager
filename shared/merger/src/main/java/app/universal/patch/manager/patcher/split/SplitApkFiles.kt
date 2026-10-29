package app.universal.patch.manager.patcher.split

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** File operations for separate APK components. Never merges their resources or code. */
object SplitApkFiles {
    private const val OUTPUT_MARKER = "META-INF/upm-splits"

    /** Identify host-produced archives even when saved under a legacy .apk filename. */
    fun isOutputArchive(zip: ZipFile): Boolean =
        zip.getEntry("AndroidManifest.xml") == null &&
            zip.getEntry("base.apk")?.isDirectory == false &&
            zip.getEntry(OUTPUT_MARKER)?.let { marker ->
                marker.size == 1L && zip.getInputStream(marker).use { it.read() == '1'.code }
            } == true

    private const val MAX_ENTRY_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024

    fun extract(
        source: File,
        directory: File,
        entryNames: Set<String>,
        onProgress: ((copied: Long, total: Long) -> Unit)? = null
    ): List<File> {
        require(directory.mkdirs() || directory.isDirectory)
        val usedNames = mutableSetOf<String>()
        var total = 0L
        return ZipFile(source).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory && it.name in entryNames }.toList()
            require(entries.size == entryNames.size && entries.isNotEmpty()) { "Missing split APK entries." }
            val expectedTotal = entries.fold(0L) { size, entry ->
                require(entry.size in 0..MAX_ENTRY_BYTES) { "Invalid split APK size." }
                (size + entry.size).also { require(it <= MAX_TOTAL_BYTES) { "Split APK archive is too large." } }
            }
            var lastReportedBytes = 0L
            var lastReportedAt = System.nanoTime()
            onProgress?.invoke(0L, expectedTotal)
            val extracted = entries.map { entry ->
                require(!entry.name.startsWith('/') && '\\' !in entry.name &&
                    !Regex("^[A-Za-z]:").containsMatchIn(entry.name) &&
                    entry.name.split('/').none { it == ".." } && '\u0000' !in entry.name
                ) { "Unsafe split APK path." }
                val name = entry.name.substringAfterLast('/')
                require(name.endsWith(".apk", true) && usedNames.add(name.lowercase(Locale.ROOT))) {
                    "Duplicate or invalid split APK name: $name"
                }
                require(entry.size in 0..MAX_ENTRY_BYTES) { "Invalid split APK size." }
                val target = directory.resolve(name)
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        while (true) {
                            check(!Thread.currentThread().isInterrupted) { "Split extraction interrupted." }
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            total += count
                            require(copied <= MAX_ENTRY_BYTES && total <= MAX_TOTAL_BYTES) { "Split APK archive is too large." }
                            output.write(buffer, 0, count)
                            if (onProgress != null) {
                                val now = System.nanoTime()
                                if (total - lastReportedBytes >= 1024 * 1024 || now - lastReportedAt >= 100_000_000L) {
                                    onProgress(total, expectedTotal)
                                    lastReportedBytes = total
                                    lastReportedAt = now
                                }
                            }
                        }
                        require(copied == entry.size) { "Truncated split APK." }
                    }
                }
                target
            }
            onProgress?.invoke(total, expectedTotal)
            extracted
        }
    }

    fun pack(apks: List<File>, output: File) = pack(apks, output, null)

    fun pack(apks: List<File>, output: File, onProgress: ((copied: Long, total: Long) -> Unit)?) {
        require(apks.isNotEmpty() && apks.all { it.isFile && it.extension.equals("apk", true) })
        require(apks.map { it.name.lowercase(Locale.ROOT) }.distinct().size == apks.size) { "Duplicate split APK names." }
        require(apks.any { it.name.equals("base.apk", true) }) { "Split output is missing base.apk." }
        val total = apks.sumOf { it.length() }
        var copied = 0L
        var lastReportedBytes = 0L
        var lastReportedAt = System.nanoTime()
        onProgress?.invoke(0L, total)
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            zip.setLevel(0) // APKs are already compressed; avoid recompressing a large split set.
            zip.putNextEntry(ZipEntry(OUTPUT_MARKER))
            zip.write('1'.code)
            zip.closeEntry()
            apks.sortedWith(compareBy<File> { !it.name.equals("base.apk", true) }.thenBy { it.name }).forEach { apk ->
                check(!Thread.currentThread().isInterrupted) { "Split packaging interrupted." }
                // Components are already compressed and aligned APKs. Keep their bytes unchanged.
                zip.putNextEntry(ZipEntry(apk.name))
                apk.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        check(!Thread.currentThread().isInterrupted) { "Split packaging interrupted." }
                        val count = input.read(buffer)
                        if (count < 0) break
                        zip.write(buffer, 0, count)
                        copied += count
                        if (onProgress != null) {
                            val now = System.nanoTime()
                            if (copied - lastReportedBytes >= 1024 * 1024 || now - lastReportedAt >= 100_000_000L) {
                                onProgress(copied, total)
                                lastReportedBytes = copied
                                lastReportedAt = now
                            }
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        onProgress?.invoke(copied, total)
    }

    /** Keep a readable base APK alive for PackageManager labels/icons and signature checks. */
    @Synchronized
    fun baseApk(source: File, cacheDirectory: File, entryNames: Set<String>? = null): File {
        require(cacheDirectory.mkdirs() || cacheDirectory.isDirectory)
        val key = MessageDigest.getInstance("SHA-256").digest(source.absolutePath.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val target = cacheDirectory.resolve("$key.apk")
        val marker = cacheDirectory.resolve("$key.version")
        ZipFile(source).use { zip ->
            val entries = zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .filter { entryNames == null || it.name in entryNames }
                .filter { it.name.endsWith(".apk", ignoreCase = true) }
                .toList()
            val base = entries.firstOrNull {
                it.name.substringAfterLast('/').equals("base.apk", true)
            } ?: run {
                // Downloaded archives can name the base after the app. Inspect only the
                // recognized components' manifests; resource APKs are not candidates.
                val names = entries.mapTo(linkedSetOf()) { it.name }
                val baseName = SplitManifestInspector.archiveOrder(source, names).first()
                entries.single { it.name.substringAfterLast('/') == baseName }
            }
            val identity = "${source.length()}:${source.lastModified()}:${base.crc}:${base.size}"
            if (target.isFile && marker.isFile && marker.readText() == identity) return target
            require(base.size in 0..MAX_ENTRY_BYTES)
            val staged = File.createTempFile("split-base-", ".apk", cacheDirectory)
            try {
                zip.getInputStream(base).use { input ->
                    staged.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        while (true) {
                            check(!Thread.currentThread().isInterrupted) { "Base extraction interrupted." }
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            require(copied <= MAX_ENTRY_BYTES && copied <= base.size) { "Invalid base APK size." }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                require(staged.length() == base.size)
                Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                marker.writeText(identity)
            } finally {
                staged.delete()
            }
        }
        return target
    }
}
