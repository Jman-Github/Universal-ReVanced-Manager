package app.urv.manager.patcher.runtime

import android.content.Context
import app.urv.manager.data.platform.Filesystem
import app.urv.manager.domain.manager.PreferencesManager
import app.urv.manager.domain.repository.PatchBundleRepository
import app.urv.manager.patcher.ProgressEvent
import app.urv.manager.patcher.aapt.Aapt
import app.urv.manager.patcher.aapt.AaptModern
import app.urv.manager.patcher.aapt.AaptSelector
import app.urv.manager.patcher.logger.Logger
import app.urv.manager.patcher.patch.PatchBundleType
import app.urv.manager.util.Options
import app.urv.manager.util.PatchSelection
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
    protected val aaptPrimaryPath = Aapt.binary(context)?.absolutePath
        ?: throw FileNotFoundException("Could not resolve aapt.")
    protected val aaptFallbackPath = AaptModern.binary(context)?.absolutePath
    protected val frameworkPath: String =
        context.cacheDir.resolve("framework").also { it.mkdirs() }.absolutePath

    protected suspend fun bundles() =
        patchBundlesRepo.bundlesByType(PatchBundleType.REVANCED).first()

    protected fun resolveAaptPath(
        inputFile: File,
        logger: Logger,
        relatedArchives: Collection<File> = emptyList()
    ): String =
        AaptSelector.select(
            aaptPrimaryPath,
            aaptFallbackPath,
            inputFile,
            logger,
            additionalArchives = relatedArchives
        )

    abstract suspend fun execute(
        inputFile: String,
        outputFile: String,
        packageName: String,
        selectedPatches: PatchSelection,
        options: Options,
        logger: Logger,
        onEvent: (ProgressEvent) -> Unit,
        stripNativeLibs: Boolean,
        skipUnneededSplits: Boolean,
    )

    open fun cancel() = Unit
}
