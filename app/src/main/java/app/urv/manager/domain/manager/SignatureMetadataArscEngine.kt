package app.urv.manager.domain.manager

import com.reandroid.apk.APKLogger
import com.reandroid.archive.Archive
import com.reandroid.archive.ArchiveFile
import com.reandroid.archive.ByteInputSource
import com.reandroid.archive.InputSource
import com.reandroid.archive.WriteProgress
import com.reandroid.archive.ZipEntryMap
import com.reandroid.archive.block.ApkSignatureBlock
import com.reandroid.archive.block.SignatureId
import com.reandroid.archive.block.SignatureInfo
import com.reandroid.archive.writer.ApkFileWriter
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale

internal data class SignatureMetadataInjectorEngineResult(
    val injectedEntries: List<String>,
    val skippedEntries: List<String>,
    val removedSignatureEntryCount: Int
)

internal object SignatureMetadataInjectorArscEngine {
    fun execute(
        metadataArchive: File,
        targetApk: File,
        outputApk: File,
        mode: SignatureMetadataInjectionMode,
        onProgress: (SignatureMetadataInjectorProgress) -> Unit,
        onLog: (String) -> Unit,
        checkCancelled: () -> Unit = {}
    ): SignatureMetadataInjectorEngineResult {
        checkCancelled()
        onProgress(SignatureMetadataInjectorProgress(SignatureMetadataInjectorStage.LOADING))
        onLog("APKEditor / ARSCLib: loading the target APK without decoding resources or DEX")

        val metadataInspection = SignatureMetadataArchiveEngine.inspectMetadataArchive(
            metadataArchive,
            checkCancelled
        )
        val targetInspection = SignatureMetadataArchiveEngine.inspectApk(
            targetApk,
            checkCancelled
        )
        val metadataEntries = SignatureMetadataArchiveEngine.readMetadataEntries(
            metadataArchive,
            metadataInspection,
            checkCancelled
        )
        val existingNames = targetInspection.signatureEntries
            .mapTo(HashSet()) { it.uppercase(Locale.ROOT) }
        val injectedEntries = ArrayList<String>()
        val skippedEntries = ArrayList<String>()
        metadataEntries.keys.forEach { name ->
            if (
                mode == SignatureMetadataInjectionMode.ADD_ALONGSIDE &&
                !existingNames.add(name.uppercase(Locale.ROOT))
            ) {
                skippedEntries += name
            } else {
                injectedEntries += name
            }
        }
        val removedCount = if (
            mode == SignatureMetadataInjectionMode.REPLACE_EXISTING
        ) {
            targetInspection.signatureEntries.size
        } else {
            0
        }

        outputApk.parentFile?.mkdirs()
        outputApk.delete()
        try {
            ArchiveFile(targetApk).use { archive ->
                checkCancelled()
                val entries = archive.createZipEntryMap()
                onProgress(
                    SignatureMetadataInjectorProgress(
                        SignatureMetadataInjectorStage.INJECTING
                    )
                )
                if (mode == SignatureMetadataInjectionMode.REPLACE_EXISTING) {
                    targetInspection.signatureEntries.forEachIndexed { index, name ->
                        checkCancelled()
                        entries.remove(name)
                        onLog(
                            "APKEditor / ARSCLib: removed signature metadata " +
                                "${index + 1}/$removedCount: $name"
                        )
                    }
                }

                var nextSort = entries.listInputSources()
                    .maxOfOrNull(InputSource::getSort)
                    ?.plus(1)
                    ?: 0
                injectedEntries.forEachIndexed { index, name ->
                    checkCancelled()
                    val bytes = metadataEntries.getValue(name)
                    val inputSource = ByteInputSource(bytes, name).apply {
                        method = Archive.DEFLATED
                        sort = nextSort++
                    }
                    entries.add(inputSource)
                    onLog(
                        "APKEditor / ARSCLib: added signature metadata " +
                            "${index + 1}/${injectedEntries.size}: $name"
                    )
                }
                skippedEntries.forEach { name ->
                    onLog(
                        "APKEditor / ARSCLib: skipped existing metadata name: $name"
                    )
                }

                onProgress(
                    SignatureMetadataInjectorProgress(
                        SignatureMetadataInjectorStage.WRITING
                    )
                )
                onLog(
                    "APKEditor / ARSCLib: writing APK and removing the previous " +
                        "APK Signing Block"
                )
                SignatureMetadataArscArchiveWriter.write(
                    entries = entries,
                    outputApk = outputApk,
                    signingBlock = null,
                    onLog = onLog,
                    checkCancelled = checkCancelled
                )
            }
            checkCancelled()
            require(outputApk.isFile && outputApk.length() > 0L) {
                "APKEditor / ARSCLib did not produce an APK."
            }
            return SignatureMetadataInjectorEngineResult(
                injectedEntries = injectedEntries,
                skippedEntries = skippedEntries,
                removedSignatureEntryCount = removedCount
            )
        } catch (error: Throwable) {
            outputApk.delete()
            throw error
        }
    }
}

internal object SignatureMetadataArscSigningBlock {
    fun contentSnapshot(apk: File): List<String> =
        ArchiveFile(apk).use { archive ->
            archive.apkSignatureBlock?.let(::contentSnapshot).orEmpty()
        }

    fun extractToDirectory(
        apk: File,
        outputDirectory: File,
        maxEntrySize: Long,
        maxTotalSize: Long,
        checkCancelled: () -> Unit = {}
    ): List<File> {
        outputDirectory.deleteRecursively()
        try {
            require(outputDirectory.mkdirs() || outputDirectory.isDirectory) {
                "Unable to create the APK Signing Block workspace."
            }
            checkCancelled()
            val blockInspection = SignatureMetadataArchiveEngine
                .inspectApkSigningBlockRecords(
                    file = apk,
                    maxEntrySize = maxEntrySize,
                    maxTotalSize = maxTotalSize,
                    checkCancelled = checkCancelled
                )
                ?: return emptyList()

            return ArchiveFile(apk).use { archive ->
                checkCancelled()
                val signingBlock = requireNotNull(archive.apkSignatureBlock) {
                    "Unable to read the APK Signing Block metadata."
                }
                signingBlock.refresh()
                require(signingBlock.size() == blockInspection.entryCount) {
                    "APK Signing Block metadata entry count changed while extracting."
                }
                val outputFiles = ArrayList<File>(blockInspection.entryCount)
                var totalSize = 0L
                signingBlock.forEach { info ->
                    checkCancelled()
                    val entrySize = info.countBytes().toLong()
                    require(entrySize in 0..maxEntrySize) {
                        "APK Signing Block metadata entry is too large."
                    }
                    totalSize = Math.addExact(totalSize, entrySize)
                    require(totalSize <= maxTotalSize) {
                        "APK Signing Block metadata exceeds the supported total size limit."
                    }
                    val output = writeRawRecord(
                        info = info,
                        outputDirectory = outputDirectory,
                        maxBytes = entrySize,
                        checkCancelled = checkCancelled
                    )
                    checkCancelled()
                    require(output.length() == entrySize) {
                        "APK Signing Block metadata size changed while extracting."
                    }
                    outputFiles += output
                }
                require(totalSize == blockInspection.totalSize) {
                    "APK Signing Block metadata size does not match the APK."
                }
                outputFiles
            }
        } catch (error: Throwable) {
            outputDirectory.deleteRecursively()
            throw error
        }
    }

    private fun writeRawRecord(
        info: SignatureInfo,
        outputDirectory: File,
        maxBytes: Long,
        checkCancelled: () -> Unit
    ): File {
        val output = outputDirectory.resolve(
            "${info.index}_${info.id.toFileName()}"
        )
        output.outputStream().use { fileOutput ->
            val guardedOutput = CancellableBoundedOutputStream(
                delegate = fileOutput,
                maxBytes = maxBytes,
                checkCancelled = checkCancelled
            )
            val reportedSize = info.writeBytes(guardedOutput).toLong()
            guardedOutput.flush()
            require(reportedSize == guardedOutput.bytesWritten) {
                "APK Signing Block metadata size changed while extracting."
            }
        }
        return output
    }

    fun suppliedContentSnapshot(signaturesDirectory: File): List<String> =
        contentSnapshot(readSuppliedBlock(signaturesDirectory))

    fun restore(
        sourceApk: File,
        signaturesDirectory: File,
        outputApk: File,
        onLog: (String) -> Unit,
        checkCancelled: () -> Unit = {}
    ) {
        outputApk.parentFile?.mkdirs()
        outputApk.delete()
        try {
            checkCancelled()
            val signingBlock = readSuppliedBlock(signaturesDirectory)
            ArchiveFile(sourceApk).use { archive ->
                checkCancelled()
                SignatureMetadataArscArchiveWriter.write(
                    entries = archive.createZipEntryMap(),
                    outputApk = outputApk,
                    signingBlock = signingBlock,
                    onLog = onLog,
                    checkCancelled = checkCancelled
                )
            }
            checkCancelled()
            require(outputApk.isFile && outputApk.length() > 0L) {
                "APKEditor / ARSCLib did not apply the supplied APK Signing Block."
            }
        } catch (error: Throwable) {
            outputApk.delete()
            throw error
        }
    }

    private fun readSuppliedBlock(signaturesDirectory: File): ApkSignatureBlock {
        require(signaturesDirectory.isDirectory) {
            "The supplied APK Signing Block files are missing."
        }
        val signingBlock = ApkSignatureBlock().apply {
            scanSplitFiles(signaturesDirectory)
        }
        val ids = HashSet<Int>()
        signingBlock.forEach { info ->
            require(ids.add(info.idValue)) {
                "The supplied APK Signing Block contains duplicate signature records."
            }
        }
        require(contentSnapshot(signingBlock).isNotEmpty()) {
            "The supplied APK Signing Block contains no usable signature records."
        }
        return signingBlock
    }

    private fun contentSnapshot(signingBlock: ApkSignatureBlock): List<String> =
        signingBlock.asSequence()
            .filterNot { it.id == SignatureId.PADDING }
            .map { info ->
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(info.bytes)
                    .joinToString("") { "%02x".format(it) }
                "${info.idValue}:$digest"
            }
            .sorted()
            .toList()
}

private class CancellableBoundedOutputStream(
    private val delegate: OutputStream,
    private val maxBytes: Long,
    private val checkCancelled: () -> Unit
) : OutputStream() {
    var bytesWritten: Long = 0L
        private set

    override fun write(value: Int) {
        checkCancelled()
        val nextSize = checkedNextSize(1)
        delegate.write(value)
        bytesWritten = nextSize
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > buffer.size - length) {
            throw IndexOutOfBoundsException()
        }
        var currentOffset = offset
        var remaining = length
        while (remaining > 0) {
            checkCancelled()
            val count = minOf(DEFAULT_BUFFER_SIZE, remaining)
            val nextSize = checkedNextSize(count)
            delegate.write(buffer, currentOffset, count)
            bytesWritten = nextSize
            currentOffset += count
            remaining -= count
        }
    }

    override fun flush() {
        checkCancelled()
        delegate.flush()
    }

    private fun checkedNextSize(count: Int): Long {
        val nextSize = Math.addExact(bytesWritten, count.toLong())
        require(nextSize <= maxBytes) {
            "APK Signing Block metadata entry is too large."
        }
        return nextSize
    }
}

private object SignatureMetadataArscArchiveWriter {
    fun write(
        entries: ZipEntryMap,
        outputApk: File,
        signingBlock: ApkSignatureBlock?,
        onLog: (String) -> Unit,
        checkCancelled: () -> Unit
    ) {
        val writer = ApkFileWriter(outputApk, entries.toArray())
        try {
            writer.setArchiveInfo(entries.archiveInfo)
            writer.setAPKLogger(ArscLogForwarder(onLog))
            writer.setWriteProgress(
                WriteProgress { _, _, _ -> checkCancelled() }
            )
            if (signingBlock != null) {
                writer.setApkSignatureBlock(signingBlock)
            }
            checkCancelled()
            writer.write()
            checkCancelled()
        } finally {
            runCatching { writer.close() }
        }
    }
}

private class ArscLogForwarder(
    private val onLog: (String) -> Unit
) : APKLogger {
    override fun logMessage(message: String?) {
        forward(message)
    }

    override fun logVerbose(message: String?) {
        forward(message)
    }

    override fun logError(message: String?, error: Throwable?) {
        val detail = error?.message?.takeIf(String::isNotBlank)
        forward(
            listOfNotNull(message?.takeIf(String::isNotBlank), detail)
                .joinToString(": ")
        )
    }

    private fun forward(message: String?) {
        message
            ?.lineSequence()
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.forEach { onLog("APKEditor / ARSCLib: $it") }
    }
}
