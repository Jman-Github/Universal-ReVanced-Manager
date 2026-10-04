package app.urv.manager.domain.manager

import app.urv.manager.patcher.split.SplitApkInspector
import app.urv.manager.patcher.split.SplitApkPreparer
import app.urv.manager.util.PM
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

enum class SignatureMetadataSourceType {
    METADATA_ZIP,
    APK,
    SPLIT_APK_CONTAINER;

    val usesAutomaticSignatureCloning: Boolean
        get() = this != METADATA_ZIP
}

enum class SignatureMetadataTargetType {
    APK,
    SPLIT_APK_CONTAINER
}

data class SignatureMetadataTargetInfo(
    val targetType: SignatureMetadataTargetType,
    val apkInfo: SignatureMetadataApkInfo,
    val apkEntryCount: Int
)

data class SignatureMetadataSourceInfo(
    val sourceType: SignatureMetadataSourceType,
    val entryNames: List<String>,
    val totalSize: Long,
    val containsManifest: Boolean,
    val signingBlockEntryCount: Int,
    val donorApkInfo: SignatureMetadataApkInfo? = null,
    val apkEntryCount: Int = 0
)

data class SignatureMetadataApkInfo(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val existingSignatureEntries: List<String>,
    val hasApkSigningBlock: Boolean,
    val entryCount: Int
)

data class SignatureMetadataApkMetadata(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val isSplitApk: Boolean
)

fun interface ApkArchiveMetadataReader {
    fun read(file: File): SignatureMetadataApkMetadata?
}

class AndroidApkArchiveMetadataReader(
    private val pm: PM
) : ApkArchiveMetadataReader {
    override fun read(file: File): SignatureMetadataApkMetadata? {
        val packageInfo = pm.getPackageInfo(file, includeSigning = false) ?: return null
        return SignatureMetadataApkMetadata(
            packageName = packageInfo.packageName,
            versionName = packageInfo.versionName,
            versionCode = pm.getVersionCode(packageInfo),
            isSplitApk = packageInfo.splitNames?.isNotEmpty() == true ||
                packageInfo.applicationInfo?.splitSourceDirs?.isNotEmpty() == true
        )
    }
}

internal class SignatureMetadataInputAnalyzer(
    private val archiveMetadataReader: ApkArchiveMetadataReader,
    private val maxArchiveEntries: Int = SignatureMetadataArchiveEngine.MAX_ENTRY_COUNT
) {
    init {
        require(maxArchiveEntries >= 0) { "Invalid archive entry limit." }
    }

    suspend fun analyzeSignatureSource(file: File): SignatureMetadataSourceInfo {
        val parent = file.absoluteFile.parentFile
            ?: throw IOException("Signature source directory is unavailable.")
        val analysisWorkspace = parent.resolve(
            ".signature-source-analysis-${UUID.randomUUID()}"
        )
        val operationContext = coroutineContext
        return try {
            prepareSignatureSource(
                file = file,
                workspace = analysisWorkspace,
                checkCancelled = { operationContext.ensureActive() }
            ).sourceInfo
        } finally {
            analysisWorkspace.deleteRecursively()
        }
    }

    suspend fun prepareSignatureSource(
        file: File,
        workspace: File,
        checkCancelled: () -> Unit
    ): PreparedSignatureMetadataSource = withContext(Dispatchers.IO) {
        checkCancelled()
        require(file.isFile && file.length() > 0L) {
            "Signature source is missing or empty."
        }
        val extension = file.extension.lowercase(Locale.ROOT)
        require(extension in SUPPORTED_SIGNATURE_SOURCE_EXTENSIONS) {
            "Select an APK, split APK container, or metadata ZIP."
        }
        workspace.deleteRecursively()
        workspace.mkdirs()

        if (extension == "zip" && splitApkEntryNames(file, checkCancelled).isEmpty()) {
            val inspection = SignatureMetadataArchiveEngine.inspectMetadataArchive(
                file,
                checkCancelled
            )
            return@withContext PreparedSignatureMetadataSource(
                metadataArchive = file,
                metadataInspection = inspection,
                sourceInfo = metadataSourceInfo(inspection)
            )
        }

        if (extension == "apk") {
            try {
                return@withContext prepareDonorApkSource(
                    donorApk = file,
                    sourceType = SignatureMetadataSourceType.APK,
                    apkEntryCount = 1,
                    workspace = workspace,
                    checkCancelled = checkCancelled
                )
            } catch (error: CancellationException) {
                throw error
            } catch (directApkError: Exception) {
                if (splitApkEntryNames(file, checkCancelled).isEmpty()) {
                    throw directApkError
                }
            }
        }

        val apkEntries = splitApkEntryNames(file, checkCancelled)
        require(apkEntries.isNotEmpty()) {
            "The selected container does not contain any APK files."
        }
        val extracted = SplitApkInspector.extractRepresentativeApk(
            source = file,
            workspace = workspace,
            maxExtractedBytes = SignatureMetadataArchiveEngine.MAX_ENTRY_SIZE,
            maxArchiveEntries = maxArchiveEntries,
            validateEntryName = SignatureMetadataArchiveEngine::requireSafeEntryName,
            validateEntry = { entry ->
                if (entry.size >= 0L && entry.compressedSize >= 0L) {
                    SignatureMetadataArchiveEngine.validateExpansionBounds(
                        size = entry.size,
                        compressedSize = entry.compressedSize,
                        directory = entry.isDirectory
                    )
                }
            },
            checkCancelled = checkCancelled
        ) ?: throw IOException("Unable to select a representative APK from the container.")
        prepareDonorApkSource(
            donorApk = extracted.file,
            sourceType = SignatureMetadataSourceType.SPLIT_APK_CONTAINER,
            apkEntryCount = apkEntries.size,
            workspace = workspace,
            checkCancelled = checkCancelled
        )
    }

    private fun splitApkEntryNames(
        file: File,
        checkCancelled: () -> Unit
    ): Set<String> = SplitApkPreparer.splitApkEntryNames(
        file = file,
        maxArchiveEntries = maxArchiveEntries,
        checkCancelled = checkCancelled
    )

    private fun prepareDonorApkSource(
        donorApk: File,
        sourceType: SignatureMetadataSourceType,
        apkEntryCount: Int,
        workspace: File,
        checkCancelled: () -> Unit
    ): PreparedSignatureMetadataSource {
        checkCancelled()
        val preparedMetadata = prepareDonorMetadataArchive(
            donorApk = donorApk,
            workspace = workspace,
            checkCancelled = checkCancelled
        )
        val apkInfo = buildApkInfo(
            donorApk,
            preparedMetadata.apkInspection,
            checkCancelled,
            allowSplitApk = true
        )
        return PreparedSignatureMetadataSource(
            metadataArchive = preparedMetadata.metadataArchive,
            metadataInspection = preparedMetadata.metadataInspection,
            sourceInfo = metadataSourceInfo(
                inspection = preparedMetadata.metadataInspection,
                sourceType = sourceType,
                donorApkInfo = apkInfo,
                apkEntryCount = apkEntryCount
            )
        )
    }

    internal suspend fun prepareSplitDonorModuleSources(
        file: File,
        workspace: File,
        checkCancelled: () -> Unit
    ): List<PreparedSplitSignatureMetadataSource> = withContext(Dispatchers.IO) {
        checkCancelled()
        workspace.deleteRecursively()
        workspace.mkdirs()
        val extractedModules = SplitApkPreparer.extractEntriesForProcessing(
            source = file,
            targetDir = workspace.resolve("extracted")
        )
        extractedModules.mapIndexed { index, module ->
            checkCancelled()
            val preparedMetadata = prepareDonorMetadataArchive(
                donorApk = module.file,
                workspace = workspace.resolve(
                    "metadata/" + index.toString().padStart(4, '0')
                ),
                checkCancelled = checkCancelled
            )
            PreparedSplitSignatureMetadataSource(
                archiveName = module.archiveName,
                moduleName = module.name,
                metadataArchive = preparedMetadata.metadataArchive,
                manifestIdentity = readSplitModuleManifestIdentity(
                    file = module.file,
                    checkCancelled = checkCancelled
                )
            )
        }
    }

    internal fun readSplitModuleManifestIdentity(
        file: File,
        checkCancelled: () -> Unit
    ): SplitApkManifestIdentity? {
        checkCancelled()
        return try {
            ZipFile(file).use { zip ->
                val manifestEntry = zip.getEntry("AndroidManifest.xml")
                    ?: return null
                zip.getInputStream(manifestEntry).use { input ->
                    checkCancelled()
                    val manifest = AndroidManifestBlock.load(input)
                    checkCancelled()
                    SplitApkManifestIdentity(
                        splitName = manifest.split
                            ?.trim()
                            ?.takeIf(String::isNotEmpty)
                            ?.lowercase(Locale.ROOT),
                        packageName = manifest.packageName
                            ?.trim()
                            ?.takeIf(String::isNotEmpty),
                        versionCode = manifest.versionCode,
                        versionName = manifest.versionName
                            ?.trim()
                            ?.takeIf(String::isNotEmpty)
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    internal fun readValidatedSplitTargetManifestIdentities(
        modules: List<SplitApkPreparer.ExtractedModule>,
        containerInfo: SignatureMetadataApkInfo,
        checkCancelled: () -> Unit
    ): Map<String, SplitApkManifestIdentity> {
        val identities = modules.associateTo(LinkedHashMap()) { module ->
            checkCancelled()
            val identity = readSplitModuleManifestIdentity(module.file, checkCancelled)
                ?: throw IOException(
                    "Target split has an unreadable Android manifest: " + module.archiveName
                )
            module.archiveName to identity
        }
        validateSplitTargetManifestIdentities(
            modules = modules,
            identities = identities,
            containerInfo = containerInfo
        )
        return identities
    }

    internal fun validateSplitTargetManifestIdentities(
        modules: List<SplitApkPreparer.ExtractedModule>,
        identities: Map<String, SplitApkManifestIdentity>,
        containerInfo: SignatureMetadataApkInfo
    ) {
        require(identities.keys == modules.mapTo(LinkedHashSet()) { it.archiveName }) {
            "Not every target split has a readable Android manifest."
        }
        val splitNames = HashSet<String?>()
        modules.forEach { module ->
            val identity = identities.getValue(module.archiveName)
            requireSplitModuleIdentityMatchesContainer(
                moduleName = module.archiveName,
                identity = identity,
                containerInfo = containerInfo
            )
            require(splitNames.add(identity.splitName)) {
                val displayName = identity.splitName ?: "base"
                "Target split container contains duplicate split identity: $displayName"
            }
        }
        require(null in splitNames) {
            "Target split container does not contain a base APK."
        }
    }

    private fun prepareDonorMetadataArchive(
        donorApk: File,
        workspace: File,
        checkCancelled: () -> Unit
    ): PreparedDonorMetadataArchive {
        checkCancelled()
        val apkInspection = SignatureMetadataArchiveEngine.inspectApk(
            donorApk,
            checkCancelled
        )
        val legacyEntries = SignatureMetadataArchiveEngine.readApkSignatureEntries(
            donorApk,
            apkInspection.signatureEntries,
            checkCancelled
        )
        val legacyEntrySize = legacyEntries.values.sumOf { it.size.toLong() }
        val remainingSignatureSize =
            SignatureMetadataArchiveEngine.MAX_TOTAL_SIGNATURE_SIZE - legacyEntrySize
        val signingBlockDirectory = workspace.resolve("donor-signing-block")
        val signingBlockFiles = SignatureMetadataArscSigningBlock.extractToDirectory(
            apk = donorApk,
            outputDirectory = signingBlockDirectory,
            maxEntrySize = SignatureMetadataArchiveEngine.MAX_SIGNATURE_ENTRY_SIZE,
            maxTotalSize = remainingSignatureSize,
            checkCancelled = checkCancelled
        )
        require(legacyEntries.isNotEmpty() || signingBlockFiles.isNotEmpty()) {
            "The selected APK contains no transferable signature metadata."
        }

        val normalizedArchive = workspace.resolve("normalized-signature-metadata.zip")
        writeNormalizedMetadataArchive(
            output = normalizedArchive,
            legacyEntries = legacyEntries,
            signingBlockFiles = signingBlockFiles,
            checkCancelled = checkCancelled
        )
        val metadataInspection = SignatureMetadataArchiveEngine.inspectMetadataArchive(
            normalizedArchive,
            checkCancelled
        )
        return PreparedDonorMetadataArchive(
            metadataArchive = normalizedArchive,
            metadataInspection = metadataInspection,
            apkInspection = apkInspection
        )
    }

    private fun writeNormalizedMetadataArchive(
        output: File,
        legacyEntries: Map<String, ByteArray>,
        signingBlockFiles: List<File>,
        checkCancelled: () -> Unit
    ) {
        output.parentFile?.mkdirs()
        output.delete()
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            legacyEntries.forEach { (name, bytes) ->
                checkCancelled()
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            signingBlockFiles.forEach { file ->
                checkCancelled()
                zip.putNextEntry(ZipEntry("APK Signing Block/${file.name}"))
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        zip.write(buffer, 0, count)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun metadataSourceInfo(
        inspection: SignatureMetadataArchiveInspection,
        sourceType: SignatureMetadataSourceType = SignatureMetadataSourceType.METADATA_ZIP,
        donorApkInfo: SignatureMetadataApkInfo? = null,
        apkEntryCount: Int = 0
    ): SignatureMetadataSourceInfo = SignatureMetadataSourceInfo(
        sourceType = sourceType,
        entryNames = inspection.entries.map(SignatureMetadataSourceEntry::targetName),
        totalSize = (inspection.entries + inspection.signingBlockEntries)
            .sumOf(SignatureMetadataSourceEntry::size),
        containsManifest = inspection.entries.any {
            it.targetName.equals("META-INF/MANIFEST.MF", ignoreCase = true)
        },
        signingBlockEntryCount = inspection.signingBlockEntries.size,
        donorApkInfo = donorApkInfo,
        apkEntryCount = apkEntryCount
    )

    suspend fun analyzeApk(file: File): SignatureMetadataApkInfo = withContext(Dispatchers.IO) {
        analyzeApkBlocking(
            file = file,
            checkCancelled = { coroutineContext.ensureActive() }
        )
    }

    suspend fun analyzeTarget(
        file: File,
        allowStandaloneSplitApk: Boolean = false
    ): SignatureMetadataTargetInfo {
        val parent = file.absoluteFile.parentFile
            ?: throw IOException("Target input directory is unavailable.")
        val analysisWorkspace = parent.resolve(
            ".signature-target-analysis-${UUID.randomUUID()}"
        )
        val operationContext = coroutineContext
        return try {
            analyzeTargetBlocking(
                file = file,
                workspace = analysisWorkspace,
                checkCancelled = { operationContext.ensureActive() },
                allowStandaloneSplitApk = allowStandaloneSplitApk
            )
        } finally {
            analysisWorkspace.deleteRecursively()
        }
    }

    private suspend fun analyzeTargetBlocking(
        file: File,
        workspace: File,
        checkCancelled: () -> Unit,
        allowStandaloneSplitApk: Boolean
    ): SignatureMetadataTargetInfo = withContext(Dispatchers.IO) {
        checkCancelled()
        require(file.isFile && file.length() > 0L) {
            "Target APK or split APK container is missing or empty."
        }
        val extension = file.extension.lowercase(Locale.ROOT)
        require(extension in SUPPORTED_SIGNATURE_SOURCE_EXTENSIONS) {
            "Select an APK or split APK container."
        }

        if (extension == "apk") {
            try {
                return@withContext SignatureMetadataTargetInfo(
                    targetType = SignatureMetadataTargetType.APK,
                    apkInfo = analyzeApkBlocking(
                        file = file,
                        checkCancelled = checkCancelled,
                        allowSplitApk = allowStandaloneSplitApk
                    ),
                    apkEntryCount = 1
                )
            } catch (error: CancellationException) {
                throw error
            } catch (directApkError: Exception) {
                if (splitApkEntryNames(file, checkCancelled).isEmpty()) {
                    throw directApkError
                }
            }
        }

        val apkEntries = splitApkEntryNames(file, checkCancelled)
        require(apkEntries.isNotEmpty()) {
            "The selected target container does not contain any APK files."
        }
        workspace.deleteRecursively()
        workspace.mkdirs()
        val extracted = SplitApkInspector.extractRepresentativeApk(
            source = file,
            workspace = workspace,
            maxExtractedBytes = SignatureMetadataArchiveEngine.MAX_ENTRY_SIZE,
            maxArchiveEntries = maxArchiveEntries,
            validateEntryName = SignatureMetadataArchiveEngine::requireSafeEntryName,
            validateEntry = { entry ->
                if (entry.size >= 0L && entry.compressedSize >= 0L) {
                    SignatureMetadataArchiveEngine.validateExpansionBounds(
                        size = entry.size,
                        compressedSize = entry.compressedSize,
                        directory = entry.isDirectory
                    )
                }
            },
            checkCancelled = checkCancelled
        ) ?: throw IOException(
            "Unable to select a representative APK from the target container."
        )
        try {
            val inspection = SignatureMetadataArchiveEngine.inspectApk(
                extracted.file,
                checkCancelled
            )
            SignatureMetadataTargetInfo(
                targetType = SignatureMetadataTargetType.SPLIT_APK_CONTAINER,
                apkInfo = buildApkInfo(
                    file = extracted.file,
                    inspection = inspection,
                    checkCancelled = checkCancelled,
                    allowSplitApk = true
                ),
                apkEntryCount = apkEntries.size
            )
        } finally {
            extracted.cleanup()
        }
    }

    private fun analyzeApkBlocking(
        file: File,
        checkCancelled: () -> Unit,
        allowSplitApk: Boolean = false
    ): SignatureMetadataApkInfo {
        val inspection = SignatureMetadataArchiveEngine.inspectApk(file, checkCancelled)
        return buildApkInfo(
            file = file,
            inspection = inspection,
            checkCancelled = checkCancelled,
            allowSplitApk = allowSplitApk
        )
    }

    internal fun buildSplitModuleInfoFromContainer(
        file: File,
        containerInfo: SignatureMetadataApkInfo,
        manifestIdentity: SplitApkManifestIdentity,
        checkCancelled: () -> Unit
    ): SignatureMetadataApkInfo {
        requireSplitModuleIdentityMatchesContainer(
            moduleName = file.name,
            identity = manifestIdentity,
            containerInfo = containerInfo
        )
        val inspection = SignatureMetadataArchiveEngine.inspectApk(file, checkCancelled)
        return containerInfo.copy(
            existingSignatureEntries = inspection.signatureEntries,
            hasApkSigningBlock = inspection.hasApkSigningBlock,
            entryCount = inspection.entries.size
        )
    }

    internal fun buildApkInfo(
        file: File,
        inspection: SignatureMetadataApkInspection,
        checkCancelled: () -> Unit,
        allowSplitApk: Boolean = false
    ): SignatureMetadataApkInfo {
        checkCancelled()
        val metadata = archiveMetadataReader.read(file)
            ?: throw IOException("The APK has an unreadable Android manifest.")
        require(allowSplitApk || !metadata.isSplitApk) {
            "Split APK files are not supported as the target APK."
        }
        return SignatureMetadataApkInfo(
            packageName = metadata.packageName,
            versionName = metadata.versionName,
            versionCode = metadata.versionCode,
            existingSignatureEntries = inspection.signatureEntries,
            hasApkSigningBlock = inspection.hasApkSigningBlock,
            entryCount = inspection.entries.size
        )
    }

    private companion object {
        val SUPPORTED_SIGNATURE_SOURCE_EXTENSIONS = setOf(
            "apk",
            "apks",
            "xapk",
            "apkm",
            "zip"
        )
    }
}

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

private fun requireSplitModuleIdentityMatchesContainer(
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

private data class PreparedDonorMetadataArchive(
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

