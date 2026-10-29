package app.universal.patch.manager.domain.installer

import app.universal.patch.manager.patcher.split.SplitApkFiles
import app.universal.patch.manager.patcher.split.SplitApkPreparer
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** Keep extracted APKs alive until the installer has completed or been cancelled. */
internal suspend fun <T> withInstallApkFiles(
    source: File,
    cacheDirectory: File,
    install: suspend (List<File>) -> T
): T = withContext(Dispatchers.IO) {
    if (!SplitApkPreparer.isSplitArchive(source)) return@withContext install(listOf(source))
    val directory = Files.createTempDirectory(cacheDirectory.toPath(), "split-install-").toFile()
    try {
        val files = runInterruptible {
            SplitApkFiles.extract(source, directory, SplitApkPreparer.splitApkEntryNames(source))
                .sortedWith(compareBy<File> { !it.name.equals("base.apk", true) }.thenBy { it.name })
        }
        require(files.first().name.equals("base.apk", true)) { "Split package is missing base.apk." }
        install(files)
    } finally {
        directory.deleteRecursively()
    }
}
