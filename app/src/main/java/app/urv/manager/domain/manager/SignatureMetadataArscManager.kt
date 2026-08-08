package app.urv.manager.domain.manager

import android.app.Application
import android.os.Build
import app.urv.manager.patcher.runtime.MemoryLimitConfig
import app.urv.manager.patcher.split.SplitApkPreparer
import app.urv.manager.patcher.split.SplitMergeProcessRuntime
import com.android.apksig.ApkVerifier
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

enum class SignatureMetadataInjectionMode {
    REPLACE_EXISTING,
    ADD_ALONGSIDE
}

enum class SignatureMetadataSigningMode {
    DONT_SIGN,
    SIGN,
    APPLY_SUPPLIED_SIGNATURE
}

enum class SignatureMetadataExecutionMode {
    REMOTE_PROCESS,
    IN_PROCESS
}

enum class SignatureMetadataSplitOutputMode {
    MERGED_APK,
    SPLIT_APK_CONTAINER
}

enum class SignatureMetadataOutputType {
    APK,
    SPLIT_APK_CONTAINER
}

enum class SignatureMetadataInjectorStage {
    ANALYZING,
    PREPARING_TARGET,
    LOADING,
    INJECTING,
    WRITING,
    SIGNING,
    VALIDATING,
    COMPLETE
}

data class SignatureMetadataInjectorProgress(
    val stage: SignatureMetadataInjectorStage
)

data class SignatureMetadataInjectorResult(
    val outputFile: File,
    val packageName: String,
    val injectedEntries: List<String>,
    val skippedEntries: List<String>,
    val removedSignatureEntryCount: Int,
    val executionMode: SignatureMetadataExecutionMode,
    val signingMode: SignatureMetadataSigningMode,
    val suppliedSigningBlockApplied: Boolean,
    val sourceType: SignatureMetadataSourceType,
    val outputType: SignatureMetadataOutputType = SignatureMetadataOutputType.APK,
    val processedApkCount: Int = 1
)

private sealed interface SignatureBlockExpectation {
    data object Absent : SignatureBlockExpectation
    data object Present : SignatureBlockExpectation
    data class Exact(val snapshot: List<String>) : SignatureBlockExpectation
}

class SignatureMetadataInjectorManager(
    private val archiveMetadataReader: ApkArchiveMetadataReader,
    private val app: Application,
    private val keystoreManager: KeystoreManager,
    private val preferencesManager: PreferencesManager
) {
    private val analyzer = SignatureMetadataInputAnalyzer(archiveMetadataReader)
    private val processRuntime = SignatureMetadataInjectorProcessRuntime(app)
    private val splitMergeRuntime = SplitMergeProcessRuntime(app)

    private fun configuredProcessMemoryLimitMb(): Int =
        MemoryLimitConfig.resolveMemoryLimitMb(
            app,
            preferencesManager.processMemoryLimit.getBlocking()
        )

    suspend fun analyzeSignatureSource(file: File): SignatureMetadataSourceInfo =
        analyzer.analyzeSignatureSource(file)

    suspend fun analyzeTarget(file: File): SignatureMetadataTargetInfo =
        analyzer.analyzeTarget(file)

    fun cancelActiveExecution() {
        processRuntime.cancelActiveExecution()
        splitMergeRuntime.cancelActiveExecution()
    }

    suspend fun inject(
        signatureSource: File,
        targetApk: File,
        outputApk: File,
        mode: SignatureMetadataInjectionMode,
        signingMode: SignatureMetadataSigningMode?,
        splitOutputMode: SignatureMetadataSplitOutputMode =
            SignatureMetadataSplitOutputMode.MERGED_APK,
        includedSplitModules: Set<String>? = null,
        onProgress: (SignatureMetadataInjectorProgress) -> Unit = {},
        onLog: (String) -> Unit = {}
    ): SignatureMetadataInjectorResult = injectInternal(
        signatureSource = signatureSource,
        targetApk = targetApk,
        outputApk = outputApk,
        mode = mode,
        signingMode = signingMode,
        splitOutputMode = splitOutputMode,
        includedSplitModules = includedSplitModules,
        onProgress = onProgress,
        onLog = onLog,
        trustedSplitTargetInfo = null
    )

    private suspend fun injectInternal(
        signatureSource: File,
        targetApk: File,
        outputApk: File,
        mode: SignatureMetadataInjectionMode,
        signingMode: SignatureMetadataSigningMode?,
        splitOutputMode: SignatureMetadataSplitOutputMode,
        includedSplitModules: Set<String>?,
        onProgress: (SignatureMetadataInjectorProgress) -> Unit,
        onLog: (String) -> Unit,
        trustedSplitTargetInfo: SignatureMetadataApkInfo?
    ): SignatureMetadataInjectorResult = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        requireDistinctOutputPath(signatureSource, targetApk, outputApk)
        onProgress(SignatureMetadataInjectorProgress(SignatureMetadataInjectorStage.ANALYZING))
        onLog("Analyzing signature source and target APK")

        val outputDirectory = outputApk.absoluteFile.parentFile
            ?: throw IOException("Output directory is unavailable.")
        outputDirectory.mkdirs()
        val operationWorkspace = outputDirectory.resolve(
            ".signature-metadata-${UUID.randomUUID()}"
        )
        val partialExtension = outputApk.extension.ifBlank { "apk" }
        val partialOutput = outputDirectory.resolve(
            ".${outputApk.nameWithoutExtension}.${UUID.randomUUID()}.partial.$partialExtension"
        )
        val postProcessedOutput = outputDirectory.resolve(
            ".${outputApk.nameWithoutExtension}.${UUID.randomUUID()}.finalizing.apk"
        )
        val suppliedSignaturesDirectory = operationWorkspace.resolve("supplied-signatures")
        operationWorkspace.mkdirs()
        partialOutput.delete()
        postProcessedOutput.delete()

        try {
            val preparedSource = analyzer.prepareSignatureSource(
                file = signatureSource,
                workspace = operationWorkspace.resolve("signature-source"),
                checkCancelled = { coroutineContext.ensureActive() }
            )
            val metadataArchive = preparedSource.metadataArchive
            val metadataInspection = preparedSource.metadataInspection
            val sourceInfo = preparedSource.sourceInfo
            val effectiveSigningMode = if (
                sourceInfo.sourceType.usesAutomaticSignatureCloning
            ) {
                SignatureMetadataSigningMode.APPLY_SUPPLIED_SIGNATURE
            } else {
                requireNotNull(signingMode) {
                    "Select how the metadata ZIP output should be signed."
                }
            }
            onLog(
                when (sourceInfo.sourceType) {
                    SignatureMetadataSourceType.METADATA_ZIP ->
                        "Signature source: metadata ZIP"
                    SignatureMetadataSourceType.APK ->
                        "Signature source: donor APK; signature cloning is automatic"
                    SignatureMetadataSourceType.SPLIT_APK_CONTAINER ->
                        "Signature source: split APK container " +
                            "(${sourceInfo.apkEntryCount} APKs)"
                }
            )
            val executionMode = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
                SignatureMetadataExecutionMode.REMOTE_PROCESS
            } else {
                SignatureMetadataExecutionMode.IN_PROCESS
            }
            val selectedTargetInfo = trustedSplitTargetInfo?.let { targetInfo ->
                SignatureMetadataTargetInfo(
                    targetType = SignatureMetadataTargetType.APK,
                    apkInfo = targetInfo,
                    apkEntryCount = 1
                )
            } ?: analyzer.analyzeTarget(targetApk)
            if (
                selectedTargetInfo.targetType ==
                SignatureMetadataTargetType.SPLIT_APK_CONTAINER &&
                splitOutputMode == SignatureMetadataSplitOutputMode.MERGED_APK
            ) {
                onLog("Validating every target split before merging")
                val validationModules = SplitApkPreparer.extractEntriesForProcessing(
                    source = targetApk,
                    targetDir = operationWorkspace.resolve("target-validation")
                )
                require(validationModules.size == selectedTargetInfo.apkEntryCount) {
                    "The target split container changed while it was being prepared."
                }
                analyzer.readValidatedSplitTargetManifestIdentities(
                    modules = validationModules,
                    containerInfo = selectedTargetInfo.apkInfo,
                    checkCancelled = { coroutineContext.ensureActive() }
                )
            }
            if (
                selectedTargetInfo.targetType ==
                SignatureMetadataTargetType.SPLIT_APK_CONTAINER &&
                splitOutputMode == SignatureMetadataSplitOutputMode.SPLIT_APK_CONTAINER
            ) {
                return@withContext injectSplitContainer(
                    signatureSource = signatureSource,
                    metadataArchive = metadataArchive,
                    targetContainer = targetApk,
                    outputContainer = outputApk,
                    temporaryOutput = partialOutput,
                    workspace = operationWorkspace.resolve("split-output"),
                    mode = mode,
                    signingMode = effectiveSigningMode,
                    sourceInfo = sourceInfo,
                    expectedTargetInfo = selectedTargetInfo.apkInfo,
                    onProgress = onProgress,
                    onLog = onLog
                )
            }
            if (sourceInfo.sourceType == SignatureMetadataSourceType.SPLIT_APK_CONTAINER) {
                onLog(
                    "Merged target output: using the donor container's representative " +
                        "base APK signature metadata"
                )
            }
            val preparedTargetApk = if (
                selectedTargetInfo.targetType ==
                SignatureMetadataTargetType.SPLIT_APK_CONTAINER
            ) {
                onProgress(
                    SignatureMetadataInjectorProgress(
                        SignatureMetadataInjectorStage.PREPARING_TARGET
                    )
                )
                onLog(
                    "Target: split APK container " +
                        "(${selectedTargetInfo.apkEntryCount} APKs)"
                )
                if (selectedTargetInfo.apkEntryCount == 1) {
                    onLog("Extracting the container's APK without running a split merge")
                    val extracted = SplitApkPreparer.extractEntriesForProcessing(
                        source = targetApk,
                        targetDir = operationWorkspace.resolve("target-single-apk")
                    )
                    require(extracted.size == 1) {
                        "The target container no longer contains exactly one APK."
                    }
                    extracted.single().file
                } else {
                    onLog(
                        if (includedSplitModules == null) {
                            "Merging all target splits into one APK with APKEditor"
                        } else {
                            "Merging ${includedSplitModules.size} selected target splits " +
                                "into one APK with APKEditor"
                        }
                    )
                    when (executionMode) {
                        SignatureMetadataExecutionMode.REMOTE_PROCESS ->
                            splitMergeRuntime.execute(
                                inputFile = targetApk,
                                workspace = operationWorkspace.resolve("target-split-merge"),
                                stripNativeLibs = false,
                                skipUnneededSplits = false,
                                includedModules = includedSplitModules,
                                memoryLimitMb = configuredProcessMemoryLimitMb(),
                                onProgress = { message ->
                                    onLog("[split merge] $message")
                                },
                                onSubSteps = { steps ->
                                    steps.lastOrNull()?.let { step ->
                                        onLog("[split merge] $step")
                                    }
                                },
                                onLog = { message ->
                                    onLog("[split merge] $message")
                                }
                            )
                        SignatureMetadataExecutionMode.IN_PROCESS ->
                            SplitApkPreparer.prepareIfNeeded(
                                source = targetApk,
                                workspace = operationWorkspace.resolve("target-split-merge"),
                                stripNativeLibs = false,
                                skipUnneededSplits = false,
                                includedModules = includedSplitModules,
                                onProgress = { message ->
                                    onLog("[split merge] $message")
                                },
                                onLog = { message ->
                                    onLog("[split merge] $message")
                                }
                            ).file
                    }
                }
            } else {
                onLog(
                    if (trustedSplitTargetInfo == null) {
                        "Target: standalone APK"
                    } else {
                        "Target: split APK module"
                    }
                )
                targetApk
            }
            val targetInspection =
                SignatureMetadataArchiveEngine.inspectApk(preparedTargetApk) {
                    coroutineContext.ensureActive()
                }
            val targetInfo = trustedSplitTargetInfo?.copy(
                existingSignatureEntries = targetInspection.signatureEntries,
                hasApkSigningBlock = targetInspection.hasApkSigningBlock,
                entryCount = targetInspection.entries.size
            ) ?: analyzer.buildApkInfo(
                file = preparedTargetApk,
                inspection = targetInspection,
                checkCancelled = { coroutineContext.ensureActive() }
            )
            require(
                targetInfo.packageName == selectedTargetInfo.apkInfo.packageName &&
                    targetInfo.versionCode == selectedTargetInfo.apkInfo.versionCode &&
                    targetInfo.versionName == selectedTargetInfo.apkInfo.versionName
            ) {
                "The merged target APK identity differs from the selected split APK set."
            }
            val metadataEntries = SignatureMetadataArchiveEngine.readMetadataEntries(
                metadataArchive,
                metadataInspection
            ) { coroutineContext.ensureActive() }
            val targetSignatureEntries =
                SignatureMetadataArchiveEngine.readApkSignatureEntries(
                    preparedTargetApk,
                    targetInspection.signatureEntries
                ) { coroutineContext.ensureActive() }
            val expectedSignatureEntries = expectedSignatureEntries(
                mode,
                targetSignatureEntries,
                metadataEntries
            )
            val targetPayload = contentSnapshot(
                preparedTargetApk,
                checkCancelled = { coroutineContext.ensureActive() }
            )
            val targetPayloadMethods = compressionSnapshot(targetInspection)
            val targetPayloadOrder = payloadEntryOrder(targetInspection)
            onLog("Archive engine: APKEditor / ARSCLib")
            onLog(
                if (executionMode == SignatureMetadataExecutionMode.REMOTE_PROCESS) {
                    "Runtime mode: isolated app_process"
                } else {
                    "Runtime mode: in-process fallback for Android 10/11 or older"
                }
            )

            val suppliedSigningBlockSnapshot = if (
                effectiveSigningMode == SignatureMetadataSigningMode.APPLY_SUPPLIED_SIGNATURE &&
                metadataInspection.signingBlockEntries.isNotEmpty()
            ) {
                onLog(
                    "Apply supplied signature: loading APK Signing Block records " +
                        "from the signature source"
                )
                val extractedFiles =
                    SignatureMetadataArchiveEngine.extractApkSigningBlockEntries(
                        file = metadataArchive,
                        inspection = metadataInspection,
                        outputDirectory = suppliedSignaturesDirectory,
                        checkCancelled = { coroutineContext.ensureActive() }
                    )
                require(extractedFiles.isNotEmpty()) {
                    "The signature source contains no usable APK Signing Block records."
                }
                SignatureMetadataArscSigningBlock.suppliedContentSnapshot(
                    suppliedSignaturesDirectory
                )
            } else {
                null
            }

            val engineResult = when (executionMode) {
                SignatureMetadataExecutionMode.REMOTE_PROCESS ->
                    processRuntime.execute(
                        metadataArchive = metadataArchive,
                        targetApk = preparedTargetApk,
                        outputApk = partialOutput,
                        workspace = operationWorkspace,
                        mode = mode,
                        memoryLimitMb = configuredProcessMemoryLimitMb(),
                        onProgress = onProgress,
                        onLog = onLog
                    )
                SignatureMetadataExecutionMode.IN_PROCESS -> {
                    val operationContext = coroutineContext
                    runInterruptible {
                        SignatureMetadataInjectorArscEngine.execute(
                            metadataArchive = metadataArchive,
                            targetApk = preparedTargetApk,
                            outputApk = partialOutput,
                            mode = mode,
                            onProgress = onProgress,
                            onLog = onLog,
                            checkCancelled = {
                                operationContext.ensureActive()
                                if (Thread.currentThread().isInterrupted) {
                                    throw CancellationException(
                                        "Signature metadata injection cancelled."
                                    )
                                }
                            }
                        )
                    }
                }
            }

            coroutineContext.ensureActive()
            if (effectiveSigningMode == SignatureMetadataSigningMode.DONT_SIGN) {
                onProgress(
                    SignatureMetadataInjectorProgress(
                        SignatureMetadataInjectorStage.VALIDATING
                    )
                )
                onLog("Validating APKEditor / ARSCLib output")
            } else {
                onLog("Checking APKEditor / ARSCLib output before signing")
            }
            validateArchiveOutput(
                outputApk = partialOutput,
                targetInfo = targetInfo,
                targetPayload = targetPayload,
                targetPayloadMethods = targetPayloadMethods,
                targetPayloadOrder = targetPayloadOrder,
                expectedSignatureEntries = expectedSignatureEntries,
                signingBlockExpectation = SignatureBlockExpectation.Absent,
                validatePackageIdentity = trustedSplitTargetInfo == null
            )

            var suppliedSigningBlockApplied = false
            val finalCandidate = when (effectiveSigningMode) {
                SignatureMetadataSigningMode.DONT_SIGN -> {
                    onLog("Signing option: don't sign")
                    partialOutput
                }
                SignatureMetadataSigningMode.SIGN -> {
                    onProgress(
                        SignatureMetadataInjectorProgress(
                            SignatureMetadataInjectorStage.SIGNING
                        )
                    )
                    onLog(
                        "Signing with the manager certificate using APK Signature " +
                            "Scheme v2/v3; injected legacy metadata will be preserved"
                    )
                    keystoreManager.signPreservingSignatureMetadata(
                        partialOutput,
                        postProcessedOutput
                    )
                    postProcessedOutput
                }
                SignatureMetadataSigningMode.APPLY_SUPPLIED_SIGNATURE -> {
                    onProgress(
                        SignatureMetadataInjectorProgress(
                            SignatureMetadataInjectorStage.SIGNING
                        )
                    )
                    if (suppliedSigningBlockSnapshot == null) {
                        onLog(
                            "Apply supplied signature: the source contains only legacy metadata; " +
                                "the output will remain unsigned"
                        )
                        partialOutput
                    } else {
                        onLog(
                            "Apply supplied signature: restoring the APK Signing Block " +
                                "records from the signature source with APKEditor / ARSCLib"
                        )
                        SignatureMetadataArscSigningBlock.restore(
                            sourceApk = partialOutput,
                            signaturesDirectory = suppliedSignaturesDirectory,
                            outputApk = postProcessedOutput,
                            onLog = onLog,
                            checkCancelled = { coroutineContext.ensureActive() }
                        )
                        suppliedSigningBlockApplied = true
                        postProcessedOutput
                    }
                }
            }

            if (finalCandidate != partialOutput) {
                coroutineContext.ensureActive()
                onProgress(
                    SignatureMetadataInjectorProgress(
                        SignatureMetadataInjectorStage.VALIDATING
                    )
                )
                onLog("Validating final APK and selected signing behavior")
                val signingBlockExpectation = when (effectiveSigningMode) {
                    SignatureMetadataSigningMode.DONT_SIGN ->
                        SignatureBlockExpectation.Absent
                    SignatureMetadataSigningMode.SIGN ->
                        SignatureBlockExpectation.Present
                    SignatureMetadataSigningMode.APPLY_SUPPLIED_SIGNATURE ->
                        SignatureBlockExpectation.Exact(
                            checkNotNull(suppliedSigningBlockSnapshot)
                        )
                }
                validateArchiveOutput(
                    outputApk = finalCandidate,
                    targetInfo = targetInfo,
                    targetPayload = targetPayload,
                    targetPayloadMethods = targetPayloadMethods,
                    targetPayloadOrder = targetPayloadOrder,
                    expectedSignatureEntries = expectedSignatureEntries,
                    signingBlockExpectation = signingBlockExpectation,
                    validatePackageIdentity = trustedSplitTargetInfo == null
                )
                validateSigningMode(finalCandidate, effectiveSigningMode)
            }

            promote(finalCandidate, outputApk)
            if (finalCandidate != partialOutput) partialOutput.delete()
            onProgress(SignatureMetadataInjectorProgress(SignatureMetadataInjectorStage.COMPLETE))
            onLog("Completed: ${outputApk.name}")
            SignatureMetadataInjectorResult(
                outputFile = outputApk,
                packageName = targetInfo.packageName,
                injectedEntries = engineResult.injectedEntries,
                skippedEntries = engineResult.skippedEntries,
                removedSignatureEntryCount = engineResult.removedSignatureEntryCount,
                executionMode = executionMode,
                signingMode = effectiveSigningMode,
                suppliedSigningBlockApplied = suppliedSigningBlockApplied,
                sourceType = sourceInfo.sourceType
            )
        } catch (error: Throwable) {
            partialOutput.delete()
            postProcessedOutput.delete()
            throw error
        } finally {
            operationWorkspace.deleteRecursively()
        }
    }

    private suspend fun injectSplitContainer(
        signatureSource: File,
        metadataArchive: File,
        targetContainer: File,
        outputContainer: File,
        temporaryOutput: File,
        workspace: File,
        mode: SignatureMetadataInjectionMode,
        signingMode: SignatureMetadataSigningMode,
        sourceInfo: SignatureMetadataSourceInfo,
        expectedTargetInfo: SignatureMetadataApkInfo,
        onProgress: (SignatureMetadataInjectorProgress) -> Unit,
        onLog: (String) -> Unit
    ): SignatureMetadataInjectorResult {
        val operationContext = coroutineContext
        operationContext.ensureActive()
        onProgress(
            SignatureMetadataInjectorProgress(
                SignatureMetadataInjectorStage.PREPARING_TARGET
            )
        )
        onLog("Target: preserving the split APK container and processing every split")
        workspace.mkdirs()
        val extracted = SplitApkPreparer.extractEntriesForProcessing(
            source = targetContainer,
            targetDir = workspace.resolve("extracted")
        )
        require(extracted.isNotEmpty()) {
            "The target split container contains no processable APK entries."
        }
        val targetManifestIdentities = analyzer.readValidatedSplitTargetManifestIdentities(
            modules = extracted,
            containerInfo = expectedTargetInfo,
            checkCancelled = { operationContext.ensureActive() }
        )
        val perModuleSignatureSources = if (
            sourceInfo.sourceType == SignatureMetadataSourceType.SPLIT_APK_CONTAINER
        ) {
            onLog("Preparing signature metadata from every donor split")
            val donorSources = analyzer.prepareSplitDonorModuleSources(
                file = signatureSource,
                workspace = workspace.resolve("donor-source"),
                checkCancelled = { operationContext.ensureActive() }
            )
            matchSplitDonorMetadataSources(
                donorSources = donorSources,
                targetModules = extracted,
                targetManifestIdentities = targetManifestIdentities
            ).also { matches ->
                matches.forEach { (targetName, donorSource) ->
                    onLog(
                        "Matched donor split " + donorSource.archiveName +
                            " to target " + targetName
                    )
                }
            }
        } else {
            emptyMap()
        }

        val replacements = LinkedHashMap<String, File>()
        val injectedEntries = LinkedHashSet<String>()
        val skippedEntries = LinkedHashSet<String>()
        var removedSignatureEntryCount = 0
        var suppliedSigningBlockApplied = false
        var executionMode: SignatureMetadataExecutionMode? = null

        extracted.forEachIndexed { index, module ->
            coroutineContext.ensureActive()
            onLog(
                "Processing split ${index + 1}/${extracted.size}: ${module.archiveName}"
            )
            val moduleTargetInfo = analyzer.buildSplitModuleInfoFromContainer(
                file = module.file,
                containerInfo = expectedTargetInfo,
                manifestIdentity = targetManifestIdentities.getValue(module.archiveName),
                checkCancelled = { operationContext.ensureActive() }
            )
            val moduleOutput = workspace.resolve(
                "processed/${index.toString().padStart(4, '0')}-${module.name}"
            )
            moduleOutput.parentFile?.mkdirs()
            val moduleSignatureSource = if (
                sourceInfo.sourceType == SignatureMetadataSourceType.SPLIT_APK_CONTAINER
            ) {
                requireNotNull(perModuleSignatureSources[module.archiveName]) {
                    "No prepared donor metadata matches target split: " +
                        module.archiveName
                }.metadataArchive
            } else {
                metadataArchive
            }
            val result = injectInternal(
                signatureSource = moduleSignatureSource,
                targetApk = module.file,
                outputApk = moduleOutput,
                mode = mode,
                signingMode = signingMode,
                splitOutputMode = SignatureMetadataSplitOutputMode.MERGED_APK,
                includedSplitModules = null,
                onProgress = { progress ->
                    if (progress.stage != SignatureMetadataInjectorStage.COMPLETE) {
                        onProgress(progress)
                    }
                },
                onLog = { message ->
                    onLog("[${module.name}] $message")
                },
                trustedSplitTargetInfo = moduleTargetInfo
            )
            replacements[module.archiveName] = result.outputFile
            injectedEntries += result.injectedEntries
            skippedEntries += result.skippedEntries
            removedSignatureEntryCount = Math.addExact(
                removedSignatureEntryCount,
                result.removedSignatureEntryCount
            )
            suppliedSigningBlockApplied =
                suppliedSigningBlockApplied || result.suppliedSigningBlockApplied
            executionMode = executionMode ?: result.executionMode
        }

        onProgress(
            SignatureMetadataInjectorProgress(
                SignatureMetadataInjectorStage.WRITING
            )
        )
        onLog("Rebuilding the split APK container with processed APK entries")
        rebuildSplitContainer(
            source = targetContainer,
            replacements = replacements,
            output = temporaryOutput,
            checkCancelled = { operationContext.ensureActive() }
        )
        validateSplitContainerOutput(
            source = targetContainer,
            output = temporaryOutput,
            replacements = replacements,
            checkCancelled = { operationContext.ensureActive() }
        )
        promote(temporaryOutput, outputContainer)
        onProgress(
            SignatureMetadataInjectorProgress(
                SignatureMetadataInjectorStage.COMPLETE
            )
        )
        onLog("Completed split APK container: ${outputContainer.name}")
        return SignatureMetadataInjectorResult(
            outputFile = outputContainer,
            packageName = expectedTargetInfo.packageName,
            injectedEntries = injectedEntries.toList(),
            skippedEntries = skippedEntries.toList(),
            removedSignatureEntryCount = removedSignatureEntryCount,
            executionMode = checkNotNull(executionMode),
            signingMode = signingMode,
            suppliedSigningBlockApplied = suppliedSigningBlockApplied,
            sourceType = sourceInfo.sourceType,
            outputType = SignatureMetadataOutputType.SPLIT_APK_CONTAINER,
            processedApkCount = extracted.size
        )
    }

    private fun rebuildSplitContainer(
        source: File,
        replacements: Map<String, File>,
        output: File,
        checkCancelled: () -> Unit
    ) {
        output.parentFile?.mkdirs()
        output.delete()
        ZipFile(source).use { inputZip ->
            ZipOutputStream(output.outputStream().buffered()).use { outputZip ->
                var totalUncompressedBytes = 0L
                val entries = inputZip.entries()
                while (entries.hasMoreElements()) {
                    checkCancelled()
                    val entry = entries.nextElement()
                    val replacement = replacements[entry.name]
                    val expectedSize = when {
                        entry.isDirectory -> 0L
                        replacement != null -> replacement.length()
                        else -> entry.size
                    }
                    require(expectedSize >= 0L) {
                        "Split container entry has an unknown size: ${entry.name}"
                    }
                    val remainingBytes =
                        SignatureMetadataArchiveEngine.MAX_TOTAL_UNCOMPRESSED_SIZE -
                            totalUncompressedBytes
                    require(expectedSize <= remainingBytes) {
                        "Split container exceeds the supported uncompressed size limit."
                    }
                    val outputEntry = ZipEntry(entry.name).apply {
                        time = entry.time
                        comment = entry.comment
                        extra = entry.extra
                        method = entry.method
                        if (method == ZipEntry.STORED) {
                            if (replacement != null) {
                                size = replacement.length()
                                compressedSize = replacement.length()
                                crc = crc32(replacement, checkCancelled)
                            } else {
                                size = entry.size
                                compressedSize = entry.compressedSize
                                crc = entry.crc
                            }
                        }
                    }
                    outputZip.putNextEntry(outputEntry)
                    if (!entry.isDirectory) {
                        val copied = if (replacement != null) {
                            replacement.inputStream().use { input ->
                                input.copyWithCancellation(
                                    output = outputZip,
                                    maxBytes = remainingBytes,
                                    checkCancelled = checkCancelled
                                )
                            }
                        } else {
                            inputZip.getInputStream(entry).use { input ->
                                input.copyWithCancellation(
                                    output = outputZip,
                                    maxBytes = remainingBytes,
                                    checkCancelled = checkCancelled
                                )
                            }
                        }
                        require(copied == expectedSize) {
                            "Split container entry size changed while rebuilding: ${entry.name}"
                        }
                        totalUncompressedBytes = Math.addExact(
                            totalUncompressedBytes,
                            copied
                        )
                    }
                    outputZip.closeEntry()
                }
            }
        }
    }

    private fun validateSplitContainerOutput(
        source: File,
        output: File,
        replacements: Map<String, File>,
        checkCancelled: () -> Unit
    ) {
        val expectedApkEntries = SplitApkPreparer.splitApkEntryNames(source)
        val actualApkEntries = SplitApkPreparer.splitApkEntryNames(output)
        require(actualApkEntries == expectedApkEntries) {
            "The rebuilt split container has unexpected or missing APK entries."
        }
        require(replacements.keys == expectedApkEntries) {
            "Not every target split APK was processed."
        }

        ZipFile(source).use { sourceZip ->
            ZipFile(output).use { outputZip ->
                val entries = sourceZip.entries()
                while (entries.hasMoreElements()) {
                    checkCancelled()
                    val sourceEntry = entries.nextElement()
                    if (sourceEntry.isDirectory) continue
                    val outputEntry = outputZip.getEntry(sourceEntry.name)
                        ?: throw IOException(
                            "Missing rebuilt container entry: ${sourceEntry.name}"
                        )
                    val expectedDigest = replacements[sourceEntry.name]?.let { file ->
                        file.inputStream().use { digestStream(it, checkCancelled) }
                    } ?: sourceZip.getInputStream(sourceEntry).use {
                        digestStream(it, checkCancelled)
                    }
                    val actualDigest = outputZip.getInputStream(outputEntry).use {
                        digestStream(it, checkCancelled)
                    }
                    require(expectedDigest.contentEquals(actualDigest)) {
                        "Split container entry changed unexpectedly: ${sourceEntry.name}"
                    }
                }
            }
        }
    }

    private fun crc32(file: File, checkCancelled: () -> Unit): Long {
        val crc = CRC32()
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                crc.update(buffer, 0, count)
            }
        }
        return crc.value
    }

    private fun java.io.InputStream.copyWithCancellation(
        output: java.io.OutputStream,
        maxBytes: Long,
        checkCancelled: () -> Unit
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            checkCancelled()
            val count = read(buffer)
            if (count < 0) break
            copied = Math.addExact(copied, count.toLong())
            require(copied <= maxBytes) {
                "Split container exceeds the supported uncompressed size limit."
            }
            output.write(buffer, 0, count)
        }
        return copied
    }

    private fun digestStream(
        input: java.io.InputStream,
        checkCancelled: () -> Unit
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest()
    }

    private suspend fun validateArchiveOutput(
        outputApk: File,
        targetInfo: SignatureMetadataApkInfo,
        targetPayload: Map<String, String>,
        targetPayloadMethods: Map<String, Int>,
        targetPayloadOrder: List<String>,
        expectedSignatureEntries: Map<String, ByteArray>,
        signingBlockExpectation: SignatureBlockExpectation,
        validatePackageIdentity: Boolean
    ) {
        val operationContext = coroutineContext
        operationContext.ensureActive()
        // Configuration splits cannot be parsed as standalone packages. Their
        // manifest remains covered by the byte-for-byte payload check below.
        if (validatePackageIdentity) {
            val outputInfo = archiveMetadataReader.read(outputApk)
                ?: throw IOException("The output APK has an unreadable Android manifest.")
            require(outputInfo.packageName == targetInfo.packageName) {
                "The output APK package name changed unexpectedly."
            }
            require(
                outputInfo.versionCode == targetInfo.versionCode &&
                    outputInfo.versionName == targetInfo.versionName
            ) {
                "The output APK version changed unexpectedly."
            }
        }

        val outputInspection = SignatureMetadataArchiveEngine.inspectApk(outputApk) {
            operationContext.ensureActive()
        }
        require(
            contentSnapshot(
                outputApk,
                checkCancelled = { operationContext.ensureActive() }
            ) == targetPayload
        ) {
            "A non-signature APK entry changed while injecting metadata."
        }
        require(compressionSnapshot(outputInspection) == targetPayloadMethods) {
            "A non-signature APK entry changed compression method."
        }
        require(payloadEntryOrder(outputInspection) == targetPayloadOrder) {
            "The non-signature APK entry order changed."
        }
        SignatureMetadataArchiveEngine.requireAlignedStoredEntries(outputApk)

        val expectedNames = expectedSignatureEntries.keys
            .map { it.uppercase(Locale.ROOT) }
            .toSet()
        val actualNames = outputInspection.signatureEntries
            .map { it.uppercase(Locale.ROOT) }
            .toSet()
        require(actualNames == expectedNames) {
            "The output APK contains unexpected or missing signature metadata."
        }
        SignatureMetadataArchiveEngine.requireEntriesMatch(
            outputApk,
            expectedSignatureEntries,
            outputInspection.signatureEntries
        ) { operationContext.ensureActive() }

        when (signingBlockExpectation) {
            SignatureBlockExpectation.Absent ->
                require(!outputInspection.hasApkSigningBlock) {
                    "The unsigned APK unexpectedly contains an APK Signing Block."
                }
            SignatureBlockExpectation.Present ->
                require(outputInspection.hasApkSigningBlock) {
                    "The signed APK has no APK Signing Block."
                }
            is SignatureBlockExpectation.Exact -> {
                require(outputInspection.hasApkSigningBlock) {
                    "The output APK has no supplied APK Signing Block."
                }
                val actualBlock = SignatureMetadataArscSigningBlock.contentSnapshot(outputApk)
                require(actualBlock == signingBlockExpectation.snapshot) {
                    "The applied APK signing data differs from the signature source."
                }
            }
        }
    }

    private fun validateSigningMode(
        outputApk: File,
        signingMode: SignatureMetadataSigningMode
    ) {
        if (signingMode != SignatureMetadataSigningMode.SIGN) return
        val verification = ApkVerifier.Builder(outputApk)
            .setMinCheckedPlatformVersion(Build.VERSION_CODES.N)
            .build()
            .verify()
        require(
            verification.isVerified &&
                (verification.isVerifiedUsingV2Scheme || verification.isVerifiedUsingV3Scheme)
        ) {
            "The manager signature could not be verified."
        }
    }

    private fun promote(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }
}

internal fun matchSplitDonorMetadataSources(
    donorSources: List<PreparedSplitSignatureMetadataSource>,
    targetModules: List<SplitApkPreparer.ExtractedModule>,
    targetManifestIdentities: Map<String, SplitApkManifestIdentity> = emptyMap()
): Map<String, PreparedSplitSignatureMetadataSource> {
    require(donorSources.isNotEmpty()) {
        "The donor split container contains no signature metadata sources."
    }
    val remainingDonors = donorSources.toMutableList()
    val matches = LinkedHashMap<String, PreparedSplitSignatureMetadataSource>()

    targetModules.forEach { target ->
        val targetManifestIdentity = targetManifestIdentities[target.archiveName]
        val candidates = if (targetManifestIdentity != null) {
            remainingDonors.filter { donor ->
                donor.manifestIdentity?.splitName == targetManifestIdentity.splitName
            }
        } else {
            val normalizedTargetArchive = normalizeSplitArchiveName(target.archiveName)
            val normalizedTargetModule = normalizeSplitModuleName(target.name)
            var filenameCandidates = remainingDonors.filter { donor ->
                normalizeSplitArchiveName(donor.archiveName) == normalizedTargetArchive
            }
            if (filenameCandidates.isEmpty()) {
                filenameCandidates = remainingDonors.filter { donor ->
                    normalizeSplitModuleName(donor.moduleName) == normalizedTargetModule
                }
            }
            if (
                filenameCandidates.isEmpty() &&
                SplitApkPreparer.isExplicitBaseApkEntryName(target.archiveName)
            ) {
                filenameCandidates = remainingDonors.filter { donor ->
                    SplitApkPreparer.isExplicitBaseApkEntryName(donor.archiveName)
                }
            }
            filenameCandidates
        }

        require(candidates.size == 1) {
            if (candidates.isEmpty()) {
                "No donor split matches target split: " + target.archiveName
            } else {
                "Multiple donor splits match target split: " + target.archiveName
            }
        }
        val matched = candidates.single()
        remainingDonors.remove(matched)
        matches[target.archiveName] = matched
    }
    return matches
}

private fun normalizeSplitArchiveName(name: String): String =
    name.replace('\\', '/').lowercase(Locale.ROOT)

private fun normalizeSplitModuleName(name: String): String =
    normalizeSplitArchiveName(name).substringAfterLast('/')

private fun requireDistinctOutputPath(
    signatureSource: File,
    targetApk: File,
    outputApk: File
) {
    val output = outputApk.canonicalFile
    require(output != signatureSource.canonicalFile) {
        "Output APK must be different from the signature source."
    }
    require(output != targetApk.canonicalFile) {
        "Output APK must be different from the target APK."
    }
}

private fun expectedSignatureEntries(
    mode: SignatureMetadataInjectionMode,
    targetEntries: Map<String, ByteArray>,
    metadataEntries: Map<String, ByteArray>
): Map<String, ByteArray> {
    if (mode == SignatureMetadataInjectionMode.REPLACE_EXISTING) {
        return LinkedHashMap(metadataEntries)
    }
    val expected = LinkedHashMap(targetEntries)
    val names = expected.keys.mapTo(HashSet()) { it.uppercase(Locale.ROOT) }
    metadataEntries.forEach { (name, bytes) ->
        if (names.add(name.uppercase(Locale.ROOT))) expected[name] = bytes
    }
    return expected
}

private fun contentSnapshot(
    apk: File,
    checkCancelled: () -> Unit = {}
): Map<String, String> {
    val output = LinkedHashMap<String, String>()
    ZipFile(apk).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            checkCancelled()
            val entry = entries.nextElement()
            if (
                entry.isDirectory ||
                SignatureMetadataArchiveEngine.isSignatureMetadataEntry(entry.name)
            ) {
                continue
            }
            val digest = MessageDigest.getInstance("SHA-256")
            zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            output[entry.name] = digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
    return output
}

private fun payloadEntryOrder(
    inspection: SignatureMetadataApkInspection
): List<String> = inspection.entries
    .asSequence()
    .filterNot(SignatureMetadataEntryInfo::directory)
    .map(SignatureMetadataEntryInfo::name)
    .filterNot(SignatureMetadataArchiveEngine::isSignatureMetadataEntry)
    .toList()

private fun compressionSnapshot(
    inspection: SignatureMetadataApkInspection
): Map<String, Int> = inspection.entries
    .asSequence()
    .filterNot(SignatureMetadataEntryInfo::directory)
    .filterNot { SignatureMetadataArchiveEngine.isSignatureMetadataEntry(it.name) }
    .associate { it.name to it.method }
