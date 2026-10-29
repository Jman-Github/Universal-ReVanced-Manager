package app.universal.patch.manager.patcher.runtime

import app.universal.patch.manager.util.managerStorageContext

import android.content.Context
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import app.universal.patch.manager.patcher.ProgressEvent
import app.universal.patch.manager.patcher.aapt.Aapt
import app.universal.patch.manager.patcher.aapt.AaptModern
import app.universal.patch.manager.patcher.aapt.AaptSelector
import app.universal.patch.manager.patcher.logger.Logger
import app.universal.patch.manager.patcher.patch.PatchBundleType
import app.universal.patch.manager.util.Options
import app.universal.patch.manager.util.PatchSelection
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileNotFoundException

sealed class Runtime(context: Context) : KoinComponent {
    private val fs: Filesystem by inject()
    private val patchBundlesRepo: PatchBundleRepository by inject()
    protected val prefs: PreferencesManager by inject()

    protected val cacheDir: String = fs.tempDir.absolutePath
    protected val aaptModernPath = AaptModern.binary(context)?.absolutePath
        ?: throw FileNotFoundException("Could not resolve modern aapt.")
    protected val aaptLegacyPath = Aapt.legacyBinary(context)?.absolutePath
    protected val frameworkPath: String =
        context.managerStorageContext.cacheDir.resolve("framework").also { it.mkdirs() }.absolutePath

    protected suspend fun bundles(
        type: PatchBundleType = PatchBundleType.REVANCED,
        enabledOnly: Boolean = false
    ): Map<Int, app.universal.patch.manager.patcher.patch.PatchBundle> {
        val bundles = patchBundlesRepo.bundlesByType(type).first()
        if (!enabledOnly) return bundles
        val enabledIds = patchBundlesRepo.enabledBundlesInfoFlow.first().keys
        return bundles.filterKeys { it in enabledIds }
    }

    protected fun resolveAaptPath(
        inputFile: File,
        logger: Logger,
        relatedArchives: Collection<File> = emptyList()
    ): String =
        AaptSelector.select(
            legacy = aaptLegacyPath,
            modern = aaptModernPath,
            apk = inputFile,
            logger = logger,
            additionalArchives = relatedArchives
        )

    protected fun resolveAaptFallbackPath(selected: String): String? =
        AaptSelector.alternate(
            selected = selected,
            modern = aaptModernPath,
            legacy = aaptLegacyPath
        )

    abstract suspend fun execute(
        inputFile: String,
        outputFile: String,
        packageName: String,
        selectedPatches: PatchSelection,
        options: Options,
        logger: Logger,
        onEvent: (ProgressEvent) -> Unit,
        onMemoryUsage: (usedMb: Long, maxMb: Long) -> Unit,
        stripNativeLibs: Boolean,
        skipUnneededSplits: Boolean,
    )

    open fun cancel() = Unit
}
