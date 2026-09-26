package app.urv.manager.domain.installer.root

import app.urv.manager.domain.installer.RootInstaller
import kotlinx.coroutines.CancellationException
import java.io.File

class RootPackageInstaller(
    private val rootInstaller: RootInstaller
) : RootPackageInstallation {
    override suspend fun replace(
        apks: List<File>,
        userId: Int,
        allowDowngrade: Boolean
    ): RootPackageReplaceResult = try {
        if (apks.size == 1) {
            rootInstaller.installSinglePackageFile(
                apks.single(),
                userId = userId,
                allowDowngrade = allowDowngrade
            )
        } else {
            rootInstaller.installPackageFiles(apks, userId = userId, allowDowngrade = allowDowngrade)
        }
        RootPackageReplaceResult.Success
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        if (allowDowngrade && failure.isVersionDowngradeRejection()) {
            RootPackageReplaceResult.DowngradeRejected(failure)
        } else {
            RootPackageReplaceResult.Failure(failure)
        }
    }

    override suspend fun uninstallKeepData(packageName: String, userId: Int) {
        val result = runBounded(
            "pm uninstall -k --user $userId ${shellQuote(packageName)}",
            PACKAGE_MANAGER_TIMEOUT_SECONDS,
            "keep-data uninstall"
        ).requireSuccess("Keep-data uninstall")
        check(result.output.contains("Success", ignoreCase = true) &&
            !result.output.contains("Failure", ignoreCase = true)
        ) { "Keep-data uninstall was not acknowledged: ${result.output.ifBlank { "no output" }}" }
    }

    override suspend fun restoreSystemRegistration(packageName: String, userId: Int): Boolean {
        val result = runBounded(
            "cmd package install-existing --user $userId ${shellQuote(packageName)}",
            PACKAGE_MANAGER_TIMEOUT_SECONDS,
            "system package registration restore"
        )
        return result.isSuccess && !result.output.contains("Failure", ignoreCase = true)
    }

    override suspend fun replaceRootBackup(
        path: String,
        expectedSha256: String,
        userId: Int
    ): Result<Unit> = runCatching {
        val actualHash = runBounded(
            "sha256sum ${shellQuote(path)} 2>/dev/null | awk '{print ${'$'}1}'",
            HASH_TIMEOUT_SECONDS,
            "rollback APK verification"
        ).requireSuccess("Verify rollback stock APK")
            .stdout.firstOrNull()?.trim()
        check(actualHash == expectedSha256) { "Rollback stock APK hash mismatch" }
        installRootPath(path, userId, allowDowngrade = true)
    }

    private suspend fun installRootPath(path: String, userId: Int, allowDowngrade: Boolean) {
        val downgrade = if (allowDowngrade) " -d" else ""
        val createAttempts = listOf(
            "pm install-create -r$downgrade --user $userId",
            "cmd package install-create -r$downgrade --user $userId"
        )
        var session: InstallerSession? = null
        var failure = "Failed to create rollback install session"
        for (command in createAttempts) {
            val result = runBounded(
                command,
                SESSION_CONTROL_TIMEOUT_SECONDS,
                "rollback install session creation"
            )
            val parsed = parseSessionId(result.output)
            if (result.isSuccess && parsed != null) {
                session = InstallerSession(command.substringBefore(" install-create"), parsed)
                break
            }
            if (result.output.isNotBlank()) failure = result.output
        }
        val created = session ?: error(failure)
        var committed = false
        try {
            val size = runBounded(
                "stat -c %s ${shellQuote(path)}",
                FILE_METADATA_TIMEOUT_SECONDS,
                "rollback APK size read"
            ).requireSuccess("Read rollback APK size")
                .stdout.firstOrNull()?.trim()?.toLongOrNull()
                ?: error("Invalid rollback APK size")
            val installTimeout = installTimeoutSeconds(size)
            val write = runBounded(
                "${created.backend} install-write -S $size ${created.id} 0.apk < ${shellQuote(path)}",
                installTimeout,
                "rollback install session write"
            )
            write.requireSuccess("Write rollback install session")
            check(!write.output.contains("Failure", ignoreCase = true)) { write.output }
            val commit = runBounded(
                "${created.backend} install-commit ${created.id}",
                installTimeout,
                "rollback install session commit"
            ).requireSuccess("Commit rollback install session")
            check(!commit.output.contains("Failure", ignoreCase = true)) { commit.output }
            committed = true
        } finally {
            if (!committed) {
                runCatching {
                    runBounded(
                        "${created.backend} install-abandon ${created.id}",
                        SESSION_CONTROL_TIMEOUT_SECONDS,
                        "rollback install session abandon"
                    )
                }
            }
        }
    }

    private suspend fun runBounded(
        command: String,
        timeoutSeconds: Long,
        operation: String
    ): RootCommandResult {
        val result = rootInstaller.executeBounded(command, timeoutSeconds, operation)
        return RootCommandResult(result.code, result.out.toList(), result.err.toList())
    }

    private fun installTimeoutSeconds(fileSize: Long): Long =
        (INSTALL_BASE_TIMEOUT_SECONDS + fileSize.coerceAtLeast(0L) / BYTES_PER_TIMEOUT_SECOND)
            .coerceAtMost(INSTALL_MAX_TIMEOUT_SECONDS)

    private fun parseSessionId(output: String): String? {
        val normalized = output.trim()
        if (normalized.matches(Regex("""^\d+$"""))) return normalized
        Regex("""\[(\d+)]""").find(normalized)?.groupValues?.getOrNull(1)?.let { return it }
        return Regex("""session(?:\s+id)?\s*[:=]?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(normalized)?.groupValues?.getOrNull(1)
    }

    private fun Throwable.isVersionDowngradeRejection(): Boolean =
        generateSequence(this) { it.cause }
            .mapNotNull { it.message }
            .any { it.contains("INSTALL_FAILED_VERSION_DOWNGRADE", ignoreCase = true) }

    private data class InstallerSession(val backend: String, val id: String)

    private companion object {
        const val FILE_METADATA_TIMEOUT_SECONDS = 15L
        const val SESSION_CONTROL_TIMEOUT_SECONDS = 30L
        const val PACKAGE_MANAGER_TIMEOUT_SECONDS = 120L
        const val HASH_TIMEOUT_SECONDS = 120L
        const val INSTALL_BASE_TIMEOUT_SECONDS = 60L
        const val INSTALL_MAX_TIMEOUT_SECONDS = 240L
        const val BYTES_PER_TIMEOUT_SECOND = 4L * 1024L * 1024L
    }
}
