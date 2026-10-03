/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.urv.manager.domain.manager

import com.android.apksig.DefaultApkSignerEngine
import com.android.apksig.KeyConfig
import com.android.apksig.apk.ApkSigningBlockNotFoundException
import com.android.apksig.apk.ApkUtils
import com.android.apksig.internal.zip.CentralDirectoryRecord
import com.android.apksig.internal.zip.LocalFileRecord
import com.android.apksig.util.DataSource
import com.android.apksig.util.DataSources
import java.io.File
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.PrivateKey
import java.security.cert.X509Certificate
import com.android.apksig.ApkSigner as AndroidApkSigner

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/pull/1088
// https://github.com/MorpheApp/morphe-patcher/blob/6757813eda350102574ccf1f0b7a4cddd869f0ba/src/main/kotlin/app/morphe/patcher/apk/ApkSigner.kt
/** Signs aligned patched APKs in place, repairing ZIP alignment when a runtime disturbed it. */
internal class InPlaceApkSigner(
    alias: String,
    privateKey: PrivateKey,
    certificates: List<X509Certificate>
) {
    private val keyConfig = KeyConfig.Jca(privateKey)
    private val signerConfig =
        DefaultApkSignerEngine.SignerConfig.Builder(alias, keyConfig, certificates).build()
    private val aligningSignerConfig =
        AndroidApkSigner.SignerConfig.Builder(alias, keyConfig, certificates).build()

    fun sign(apkFile: File, minSdkVersion: Int) {
        require(minSdkVersion >= 24) { "In-place v2 signing requires Android 7 or newer." }
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("APK signing cancelled")
        }
        val alignmentSdk = RandomAccessFile(apkFile, "rw").use { file ->
            val apk = DataSources.asDataSource(file)
            val lowestSdkVersion = ApkUtils.getMinSdkVersionFromBinaryAndroidManifest(
                ApkUtils.getAndroidManifest(apk)
            ).coerceAtLeast(minSdkVersion)
            val sections = ApkUtils.findZipSections(apk)
            val contentsEnd = try {
                ApkUtils.findApkSigningBlock(apk, sections).startOffset
            } catch (_: ApkSigningBlockNotFoundException) {
                sections.zipCentralDirectoryOffset
            }
            val centralDirectory = apk.getByteBuffer(
                sections.zipCentralDirectoryOffset,
                sections.zipCentralDirectorySizeBytes.toInt()
            )
            val endOfCentralDirectory = sections.zipEndOfCentralDirectory
            // ReVanced can still align libraries to 4 KB, and ABI stripping rewrites ZIP headers.
            // The old apksig signing path repaired these; retain that behavior where it is needed.
            if (needsAlignment(apk, centralDirectory.duplicate(), contentsEnd)) {
                return@use lowestSdkVersion
            }

            DefaultApkSignerEngine.Builder(listOf(signerConfig), lowestSdkVersion)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(false)
                .build().use { engine ->
                    val request = engine.outputZipSections2(
                        apk.slice(0, contentsEnd),
                        DataSources.asDataSource(centralDirectory.duplicate()),
                        DataSources.asDataSource(endOfCentralDirectory.duplicate())
                    )
                    val padding = ByteBuffer.wrap(ByteArray(request.paddingSizeBeforeApkSigningBlock))
                    val block = ByteBuffer.wrap(request.apkSigningBlock)
                    request.done()

                    val centralDirectoryOffset = contentsEnd + padding.remaining() + block.remaining()
                    ApkUtils.setZipEocdCentralDirectoryOffset(endOfCentralDirectory, centralDirectoryOffset)
                    if (Thread.currentThread().isInterrupted) {
                        throw InterruptedIOException("APK signing cancelled")
                    }

                    var position = contentsEnd
                    for (buffer in arrayOf(padding, block, centralDirectory, endOfCentralDirectory)) {
                        while (buffer.hasRemaining()) position += file.channel.write(buffer, position)
                    }
                    file.setLength(position)
                    engine.outputDone()
                }
            null
        }
        alignmentSdk?.let { signWithAlignment(apkFile, it) }
    }

    private fun needsAlignment(apk: DataSource, centralDirectory: ByteBuffer, contentsEnd: Long): Boolean {
        centralDirectory.order(ByteOrder.LITTLE_ENDIAN)
        while (centralDirectory.hasRemaining()) {
            val record = CentralDirectoryRecord.getRecord(centralDirectory)
            // Validate compressed headers too, so malformed ZIPs enter the existing repair path.
            val localRecord = LocalFileRecord.getRecord(apk, record, contentsEnd)
            if (record.compressionMethod.toInt() != 0 || record.name.endsWith("/")) continue
            val offset = localRecord.startOffsetInArchive + localRecord.dataStartOffsetInRecord
            val alignment = if (record.name.endsWith(".so")) LIBRARY_ALIGNMENT else DEFAULT_ALIGNMENT
            if (offset % alignment != 0L) return true
        }
        return false
    }

    private fun signWithAlignment(apkFile: File, minSdkVersion: Int) {
        val aligned = File.createTempFile("signing-aligned-", ".apk", apkFile.absoluteFile.parentFile)
        try {
            AndroidApkSigner.Builder(listOf(aligningSignerConfig))
                .setInputApk(apkFile)
                .setOutputApk(aligned)
                .setMinSdkVersion(minSdkVersion)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(false)
                .setV4SigningEnabled(false)
                .setAlignmentPreserved(false)
                .setLibraryPageAlignmentBytes(LIBRARY_ALIGNMENT)
                .build().sign()
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedIOException("APK signing cancelled")
            }
            Files.move(aligned.toPath(), apkFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            aligned.delete()
        }
    }

    private companion object {
        const val LIBRARY_ALIGNMENT = 16 * 1024
        const val DEFAULT_ALIGNMENT = 4
    }
}
