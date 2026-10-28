package app.universal.patch.manager.domain.manager

import java.io.File

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

