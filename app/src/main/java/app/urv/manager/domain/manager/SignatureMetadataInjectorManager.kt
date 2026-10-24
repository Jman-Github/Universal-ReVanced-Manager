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

