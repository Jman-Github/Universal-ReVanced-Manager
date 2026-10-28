package app.universal.patch.manager.domain.manager

import com.android.tools.build.apkzlib.zip.CompressionMethod
import com.android.tools.build.apkzlib.zip.StoredEntryType
import com.android.tools.build.apkzlib.zip.ZFile
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class SignatureMetadataSourceEntry(
    val sourceName: String,
    val targetName: String,
    val size: Long
)

internal data class SignatureMetadataArchiveInspection(
    val entries: List<SignatureMetadataSourceEntry>,
    val signingBlockEntries: List<SignatureMetadataSourceEntry>
)

internal data class PreparedSignatureMetadataSource(
    val metadataArchive: File,
    val metadataInspection: SignatureMetadataArchiveInspection,
    val sourceInfo: SignatureMetadataSourceInfo
)

internal data class PreparedSplitSignatureMetadataSource(
    val archiveName: String,
    val moduleName: String,
    val metadataArchive: File,
    val manifestIdentity: SplitApkManifestIdentity? = null
)

internal data class SplitApkManifestIdentity(
    val splitName: String?,
    val packageName: String? = null,
    val versionCode: Int? = null,
    val versionName: String? = null
)

internal fun requireSplitModuleIdentityMatchesContainer(
    moduleName: String,
    identity: SplitApkManifestIdentity,
    containerInfo: SignatureMetadataApkInfo
) {
    require(identity.packageName == containerInfo.packageName) {
        "Target split package does not match the base APK: $moduleName"
    }
    identity.versionCode?.let { versionCode ->
        val unsignedVersionCode = versionCode.toLong() and 0xffffffffL
        val expectedVersionCode = containerInfo.versionCode and 0xffffffffL
        require(unsignedVersionCode == expectedVersionCode) {
            "Target split version code does not match the base APK: $moduleName"
        }
    }
    identity.versionName?.let { versionName ->
        require(versionName == containerInfo.versionName) {
            "Target split version name does not match the base APK: $moduleName"
        }
    }
}

internal data class PreparedDonorMetadataArchive(
    val metadataArchive: File,
    val metadataInspection: SignatureMetadataArchiveInspection,
    val apkInspection: SignatureMetadataApkInspection
)

internal data class SignatureMetadataApkInspection(
    val entries: List<SignatureMetadataEntryInfo>,
    val signatureEntries: List<String>,
    val hasApkSigningBlock: Boolean
)

internal data class SignatureMetadataEntryInfo(
    val name: String,
    val size: Long,
    val compressedSize: Long,
    val method: Int,
    val directory: Boolean
)

internal data class SignatureMetadataSigningBlockInspection(
    val entryCount: Int,
    val totalSize: Long
)

internal object SignatureMetadataArchiveEngine {
    internal const val MAX_ENTRY_COUNT = 100_000
    internal const val MAX_ENTRY_SIZE = 2L * 1024L * 1024L * 1024L
    internal const val MAX_TOTAL_UNCOMPRESSED_SIZE = 4L * 1024L * 1024L * 1024L
    internal const val MAX_SIGNATURE_ENTRY_SIZE = 16L * 1024L * 1024L
    internal const val MAX_TOTAL_SIGNATURE_SIZE = 64L * 1024L * 1024L
    private const val MAX_COMPRESSION_RATIO = 1_000L
    private const val RATIO_CHECK_MIN_SIZE = 64L * 1024L * 1024L
    private const val MAX_EOCD_SIZE = 65_557L
    private const val APK_SIGNING_BLOCK_CONTAINER_SIZE = 32L
    private const val APK_SIGNING_BLOCK_ENTRY_SUFFIX = ".signature.info.bin"
    private val signingBlockMagic = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)

    fun inspectMetadataArchive(
        file: File,
        checkCancelled: () -> Unit = {}
    ): SignatureMetadataArchiveInspection {
        require(file.isFile && file.length() > 0L) {
            "Signature metadata ZIP is missing or empty."
        }
        val metadataEntries = mutableListOf<SignatureMetadataSourceEntry>()
        val signingBlockEntries = mutableListOf<SignatureMetadataSourceEntry>()
        val exactNames = HashSet<String>()
        val targetNames = HashSet<String>()
        var entryCount = 0
        var totalMetadataSize = 0L

        try {
            ZipFile(file).use { zip ->
                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) {
                    checkCancelled()
                    val entry = enumeration.nextElement()
                    entryCount++
                    require(entryCount <= MAX_ENTRY_COUNT) {
                        "Metadata ZIP contains too many entries."
                    }
                    requireSafeEntryName(entry.name)
                    require(exactNames.add(entry.name)) {
                        "Duplicate ZIP entry: ${entry.name}"
                    }
                    validateExpansionBounds(entry.size, entry.compressedSize, entry.isDirectory)
                    if (entry.isDirectory) continue

                    val legacyTargetName = signatureMetadataTargetName(entry.name)
                    val signingBlockTargetName = apkSigningBlockTargetName(entry.name)
                    val targetName = legacyTargetName ?: signingBlockTargetName ?: continue
                    val targetKey = if (legacyTargetName != null) {
                        "LEGACY:$targetName"
                    } else {
                        "SIGNING-BLOCK:$targetName"
                    }
                    require(targetNames.add(targetKey.uppercase(Locale.ROOT))) {
                        "Duplicate signature metadata entry: $targetName"
                    }
                    require(entry.size in 0..MAX_SIGNATURE_ENTRY_SIZE) {
                        "Signature metadata entry is too large: ${entry.name}"
                    }
                    val remaining = MAX_TOTAL_SIGNATURE_SIZE - totalMetadataSize
                    require(entry.size <= remaining) {
                        "Signature metadata exceeds the supported total size limit."
                    }
                    var actualSize = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    zip.getInputStream(entry).use { input ->
                        while (true) {
                            checkCancelled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            actualSize = Math.addExact(actualSize, count.toLong())
                            require(actualSize <= remaining) {
                                "Signature metadata exceeds the supported total size limit."
                            }
                        }
                    }
                    require(actualSize == entry.size) {
                        "ZIP entry size metadata does not match its contents: ${entry.name}"
                    }
                    totalMetadataSize = Math.addExact(totalMetadataSize, actualSize)
                    val sourceEntry = SignatureMetadataSourceEntry(
                        sourceName = entry.name,
                        targetName = targetName,
                        size = actualSize
                    )
                    if (legacyTargetName != null) {
                        metadataEntries += sourceEntry
                    } else {
                        signingBlockEntries += sourceEntry
                    }
                }
            }
        } catch (error: ArithmeticException) {
            throw IOException("Metadata ZIP size calculation overflowed.", error)
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: IOException) {
            throw IOException("Signature metadata is not a readable ZIP archive.", error)
        }

        require(metadataEntries.isNotEmpty() || signingBlockEntries.isNotEmpty()) {
            "Metadata ZIP contains no supported signature metadata files."
        }
        return SignatureMetadataArchiveInspection(
            entries = metadataEntries,
            signingBlockEntries = signingBlockEntries
        )
    }

    fun inspectApk(
        file: File,
        checkCancelled: () -> Unit = {}
    ): SignatureMetadataApkInspection {
        require(file.isFile && file.length() > 0L) { "APK file is missing or empty." }
        val entries = mutableListOf<SignatureMetadataEntryInfo>()
        val exactNames = HashSet<String>()
        val criticalNames = HashSet<String>()
        var actualTotalSize = 0L

        try {
            ZipFile(file).use { zip ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) {
                    checkCancelled()
                    val entry = enumeration.nextElement()
                    val name = entry.name
                    requireSafeEntryName(name)
                    require(exactNames.add(name)) { "Duplicate ZIP entry: $name" }
                    if (isSignatureMetadataEntry(name)) {
                        require(criticalNames.add(name.uppercase(Locale.ROOT))) {
                            "Duplicate signature metadata entry: $name"
                        }
                    }
                    validateExpansionBounds(entry.size, entry.compressedSize, entry.isDirectory)
                    if (!entry.isDirectory) {
                        var actualEntrySize = 0L
                        zip.getInputStream(entry).use { input ->
                            while (true) {
                                checkCancelled()
                                val count = input.read(buffer)
                                if (count < 0) break
                                actualEntrySize = Math.addExact(actualEntrySize, count.toLong())
                                actualTotalSize = Math.addExact(actualTotalSize, count.toLong())
                                require(actualEntrySize <= MAX_ENTRY_SIZE) {
                                    "APK entry exceeds the supported size limit: $name"
                                }
                                require(actualTotalSize <= MAX_TOTAL_UNCOMPRESSED_SIZE) {
                                    "APK uncompressed size exceeds the supported limit."
                                }
                            }
                        }
                        require(actualEntrySize == entry.size) {
                            "APK entry size metadata does not match its contents: $name"
                        }
                        validateExpansionBounds(actualEntrySize, entry.compressedSize, false)
                    }
                    entries += SignatureMetadataEntryInfo(
                        name = name,
                        size = entry.size,
                        compressedSize = entry.compressedSize,
                        method = entry.method,
                        directory = entry.isDirectory
                    )
                    require(entries.size <= MAX_ENTRY_COUNT) { "APK contains too many ZIP entries." }
                }
            }
        } catch (error: ArithmeticException) {
            throw IOException("APK size calculation overflowed.", error)
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: IOException) {
            throw IOException("APK is not a readable ZIP archive.", error)
        }

        require(entries.any { it.name == "AndroidManifest.xml" && !it.directory }) {
            "AndroidManifest.xml is missing."
        }
        return SignatureMetadataApkInspection(
            entries = entries,
            signatureEntries = entries.map(SignatureMetadataEntryInfo::name)
                .filter(::isSignatureMetadataEntry),
            hasApkSigningBlock = hasApkSigningBlock(file)
        )
    }

    fun readApkSignatureEntries(
        apk: File,
        names: List<String>,
        checkCancelled: () -> Unit = {}
    ): Map<String, ByteArray> {
        val output = linkedMapOf<String, ByteArray>()
        var totalSize = 0L
        ZipFile(apk).use { zip ->
            names.forEach { name ->
                checkCancelled()
                val entry = zip.getEntry(name)
                    ?: throw IOException("Missing signature metadata entry: $name")
                require(entry.size in 0..MAX_SIGNATURE_ENTRY_SIZE) {
                    "Existing signature metadata entry is too large: $name"
                }
                require(entry.size <= MAX_TOTAL_SIGNATURE_SIZE - totalSize) {
                    "Existing signature metadata exceeds the supported size limit."
                }
                val bytes = zip.getInputStream(entry).use { input ->
                    input.readBytesLimited(
                        MAX_TOTAL_SIGNATURE_SIZE - totalSize,
                        name,
                        checkCancelled
                    )
                }
                require(bytes.size.toLong() == entry.size) {
                    "Signature metadata size changed while reading: $name"
                }
                totalSize = Math.addExact(totalSize, bytes.size.toLong())
                output[name] = bytes
            }
        }
        return output
    }

    fun readMetadataEntries(
        file: File,
        inspection: SignatureMetadataArchiveInspection,
        checkCancelled: () -> Unit = {}
    ): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        var totalSize = 0L
        ZipFile(file).use { zip ->
            inspection.entries.forEach { metadataEntry ->
                checkCancelled()
                val entry = zip.getEntry(metadataEntry.sourceName)
                    ?: throw IOException("Missing ZIP entry: ${metadataEntry.sourceName}")
                val remaining = MAX_TOTAL_SIGNATURE_SIZE - totalSize
                require(metadataEntry.size <= remaining) {
                    "Signature metadata exceeds the supported total size limit."
                }
                val bytes = zip.getInputStream(entry).use { input ->
                    input.readBytesLimited(
                        minOf(MAX_SIGNATURE_ENTRY_SIZE, remaining),
                        metadataEntry.sourceName,
                        checkCancelled
                    )
                }
                require(bytes.size.toLong() == metadataEntry.size) {
                    "ZIP entry changed while reading: ${metadataEntry.sourceName}"
                }
                totalSize = Math.addExact(totalSize, bytes.size.toLong())
                result[metadataEntry.targetName] = bytes
            }
        }
        return result
    }

    fun extractApkSigningBlockEntries(
        file: File,
        inspection: SignatureMetadataArchiveInspection,
        outputDirectory: File,
        checkCancelled: () -> Unit = {}
    ): List<File> {
        outputDirectory.deleteRecursively()
        outputDirectory.mkdirs()
        val result = ArrayList<File>(inspection.signingBlockEntries.size)
        var totalSize = 0L
        ZipFile(file).use { zip ->
            inspection.signingBlockEntries.forEach { metadataEntry ->
                checkCancelled()
                val entry = zip.getEntry(metadataEntry.sourceName)
                    ?: throw IOException("Missing ZIP entry: ${metadataEntry.sourceName}")
                val remaining = MAX_TOTAL_SIGNATURE_SIZE - totalSize
                val bytes = zip.getInputStream(entry).use { input ->
                    input.readBytesLimited(
                        minOf(MAX_SIGNATURE_ENTRY_SIZE, remaining),
                        metadataEntry.sourceName,
                        checkCancelled
                    )
                }
                require(bytes.size.toLong() == metadataEntry.size) {
                    "ZIP entry changed while reading: ${metadataEntry.sourceName}"
                }
                totalSize = Math.addExact(totalSize, bytes.size.toLong())
                val output = outputDirectory.resolve(metadataEntry.targetName)
                output.writeBytes(bytes)
                result += output
            }
        }
        return result
    }

    fun requireEntriesMatch(
        file: File,
        expectedEntries: Map<String, ByteArray>,
        actualEntryNames: List<String>,
        checkCancelled: () -> Unit
    ) {
        val actualNames = actualEntryNames.associateBy { it.uppercase(Locale.ROOT) }
        ZipFile(file).use { zip ->
            expectedEntries.forEach { (expectedName, expectedBytes) ->
                checkCancelled()
                val actualName = actualNames[expectedName.uppercase(Locale.ROOT)]
                    ?: throw IOException("Missing injected metadata entry: $expectedName")
                val entry = zip.getEntry(actualName)
                    ?: throw IOException("Missing ZIP entry: $actualName")
                val actualBytes = zip.getInputStream(entry).use { input ->
                    input.readBytesLimited(MAX_SIGNATURE_ENTRY_SIZE, actualName, checkCancelled)
                }
                require(actualBytes.contentEquals(expectedBytes)) {
                    "Injected metadata entry changed: $expectedName"
                }
            }
        }
    }

    fun requireAlignedStoredEntries(file: File) {
        ZFile.openReadOnly(file).use { zip ->
            zip.entries().forEach { entry ->
                if (entry.type != StoredEntryType.FILE) return@forEach
                val header = entry.centralDirectoryHeader
                if (header.compressionInfoWithWait.method != CompressionMethod.STORE) return@forEach
                val alignment = if (header.name.endsWith(".so", ignoreCase = true)) 4096L else 4L
                val dataOffset = header.offset + entry.localHeaderSize
                require(dataOffset % alignment == 0L) {
                    "Stored APK entry is not aligned to $alignment bytes: ${header.name}"
                }
            }
        }
    }

    fun hasApkSigningBlock(file: File): Boolean =
        locateApkSigningBlock(file) != null

    fun inspectApkSigningBlockRecords(
        file: File,
        maxEntrySize: Long,
        maxTotalSize: Long,
        checkCancelled: () -> Unit = {}
    ): SignatureMetadataSigningBlockInspection? {
        val location = locateApkSigningBlock(file) ?: return null
        val expectedTotalSize =
            location.totalSize - APK_SIGNING_BLOCK_CONTAINER_SIZE
        require(expectedTotalSize in 0..maxTotalSize) {
            "APK Signing Block metadata exceeds the supported total size limit."
        }

        RandomAccessFile(file, "r").use { input ->
            val recordsStart = Math.addExact(location.offset, 8L)
            val recordsEnd = Math.subtractExact(
                Math.addExact(location.offset, location.totalSize),
                24L
            )
            var cursor = recordsStart
            var entryCount = 0
            var totalSize = 0L
            val lengthBytes = ByteArray(8)
            while (cursor < recordsEnd) {
                checkCancelled()
                val remaining = recordsEnd - cursor
                require(remaining >= 12L) {
                    "APK Signing Block contains a truncated metadata record."
                }
                input.seek(cursor)
                input.readFully(lengthBytes)
                val payloadSize = littleEndianLong(lengthBytes, 0)
                require(payloadSize >= 4L) {
                    "APK Signing Block contains an invalid metadata record."
                }
                val entrySize = Math.addExact(payloadSize, 8L)
                require(entrySize <= maxEntrySize) {
                    "APK Signing Block metadata entry is too large."
                }
                require(entrySize <= remaining) {
                    "APK Signing Block contains a truncated metadata record."
                }
                totalSize = Math.addExact(totalSize, entrySize)
                require(totalSize <= maxTotalSize) {
                    "APK Signing Block metadata exceeds the supported total size limit."
                }
                entryCount = Math.addExact(entryCount, 1)
                require(entryCount <= MAX_ENTRY_COUNT) {
                    "APK Signing Block contains too many metadata entries."
                }
                cursor = Math.addExact(cursor, entrySize)
            }
            require(cursor == recordsEnd && totalSize == expectedTotalSize) {
                "APK Signing Block metadata size does not match the APK."
            }
            return SignatureMetadataSigningBlockInspection(
                entryCount = entryCount,
                totalSize = totalSize
            )
        }
    }

    private fun locateApkSigningBlock(file: File): ApkSigningBlockLocation? {
        val eocd = locateEocd(file) ?: return null
        val centralDirectoryOffset = eocd.centralDirectoryOffset
        if (centralDirectoryOffset < 24L || centralDirectoryOffset > eocd.offset) return null
        RandomAccessFile(file, "r").use { input ->
            input.seek(centralDirectoryOffset - 24L)
            val footer = ByteArray(24)
            input.readFully(footer)
            if (!footer.copyOfRange(8, 24).contentEquals(signingBlockMagic)) return null
            val size = littleEndianLong(footer, 0)
            if (size < 24L || size > centralDirectoryOffset - 8L) return null
            val blockOffset = centralDirectoryOffset - size - 8L
            if (blockOffset < 0L) return null
            input.seek(blockOffset)
            val headerSize = ByteArray(8)
            input.readFully(headerSize)
            if (littleEndianLong(headerSize, 0) != size) return null
            return ApkSigningBlockLocation(
                offset = blockOffset,
                totalSize = size + 8L
            )
        }
    }

    private fun locateEocd(file: File): ZipEocdLocation? {
        if (file.length() < 22L) return null
        RandomAccessFile(file, "r").use { input ->
            val tailSize = minOf(file.length(), MAX_EOCD_SIZE).toInt()
            val tail = ByteArray(tailSize)
            val tailOffset = file.length() - tailSize
            input.seek(tailOffset)
            input.readFully(tail)
            val eocdIndex = findEocd(tail) ?: return null
            val centralDirectoryOffset = littleEndianUInt32(tail, eocdIndex + 16)
            val absoluteEocdOffset = tailOffset + eocdIndex
            if (centralDirectoryOffset > absoluteEocdOffset) return null
            return ZipEocdLocation(
                offset = absoluteEocdOffset,
                centralDirectoryOffset = centralDirectoryOffset
            )
        }
    }

    private data class ApkSigningBlockLocation(
        val offset: Long,
        val totalSize: Long
    )

    private data class ZipEocdLocation(
        val offset: Long,
        val centralDirectoryOffset: Long
    )

    internal fun validateExpansionBounds(size: Long, compressedSize: Long, directory: Boolean) {
        if (directory) return
        require(size >= 0L && compressedSize >= 0L) {
            "ZIP archive contains unknown entry sizes."
        }
        require(size <= MAX_ENTRY_SIZE) { "ZIP entry exceeds the supported size limit." }
        if (size >= RATIO_CHECK_MIN_SIZE && compressedSize > 0L) {
            require(size / compressedSize <= MAX_COMPRESSION_RATIO) {
                "ZIP archive contains a suspiciously compressed entry."
            }
        }
    }

    internal fun isSignatureMetadataEntry(name: String): Boolean {
        val normalized = name.uppercase(Locale.ROOT)
        if (!normalized.startsWith("META-INF/")) return false
        val leaf = normalized.removePrefix("META-INF/")
        return !leaf.contains('/') && isSignatureMetadataLeaf(leaf)
    }

    internal fun signatureMetadataTargetName(sourceName: String): String? {
        val leaf = when {
            '/' !in sourceName -> sourceName
            sourceName.startsWith("META-INF/", ignoreCase = true) &&
                sourceName.count { it == '/' } == 1 -> sourceName.substringAfter('/')
            else -> return null
        }
        if (!isSignatureMetadataLeaf(leaf.uppercase(Locale.ROOT))) return null
        return "META-INF/$leaf"
    }

    internal fun apkSigningBlockTargetName(sourceName: String): String? {
        val leaf = sourceName.substringAfterLast('/')
        return leaf.takeIf {
            it.endsWith(APK_SIGNING_BLOCK_ENTRY_SUFFIX, ignoreCase = true)
        }
    }

    private fun isSignatureMetadataLeaf(leaf: String): Boolean {
        return leaf == "MANIFEST.MF" ||
            leaf.startsWith("SIG-") ||
            leaf.endsWith(".SF") ||
            leaf.endsWith(".RSA") ||
            leaf.endsWith(".DSA") ||
            leaf.endsWith(".EC")
    }

    internal fun requireSafeEntryName(name: String) {
        require(name.isNotBlank() && '\u0000' !in name) { "ZIP archive contains an invalid path." }
        require(!name.startsWith('/') && !name.startsWith('\\')) {
            "ZIP archive contains an absolute path."
        }
        require(!Regex("^[A-Za-z]:").containsMatchIn(name)) {
            "ZIP archive contains an absolute path."
        }
        require('\\' !in name) { "ZIP archive contains an unsafe path separator." }
        require(name.split('/').none { it == ".." }) {
            "ZIP archive contains a path traversal entry."
        }
    }

    private fun findEocd(bytes: ByteArray): Int? {
        for (index in bytes.size - 22 downTo 0) {
            if (
                bytes[index] == 0x50.toByte() &&
                bytes[index + 1] == 0x4b.toByte() &&
                bytes[index + 2] == 0x05.toByte() &&
                bytes[index + 3] == 0x06.toByte()
            ) {
                val commentLength = littleEndianUInt16(bytes, index + 20)
                if (index + 22 + commentLength == bytes.size) return index
            }
        }
        return null
    }

    private fun littleEndianUInt16(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun littleEndianUInt32(bytes: ByteArray, offset: Int): Long {
        return ByteBuffer.wrap(bytes, offset, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
            .toLong() and 0xffffffffL
    }

    private fun littleEndianLong(bytes: ByteArray, offset: Int): Long {
        return ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long
    }
}

private fun java.io.InputStream.readBytesLimited(
    limit: Long,
    entryName: String,
    checkCancelled: () -> Unit
): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        checkCancelled()
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= limit) { "Signature metadata entry is too large: $entryName" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

