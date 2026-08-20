package app.urv.manager.domain.installer

import android.app.Application
import android.os.SystemClock
import app.urv.manager.util.PM
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Low-level root shell and PackageInstaller-session primitives.
 * Root-mount workflows belong exclusively to RootMountTransactionCoordinator.
 */
class RootInstaller(
    private val app: Application,
    private val pm: PM
) {
    @Volatile private var cachedHasRoot: Boolean? = null
    @Volatile private var lastRootCheck = 0L
    @Volatile private var boundedShell: Shell? = null
    private val boundedShellGuard = Any()
    private val boundedShellMutex = Mutex()

    private suspend fun getShell(): Shell {
        Shell.getCachedShell()?.takeIf(Shell::isAlive)?.let { return it }
        return with(CompletableDeferred<Shell>()) {
            Shell.getShell(::complete)
            await()
        }
    }

    suspend fun execute(command: String): Shell.Result = withContext(Dispatchers.IO) {
        boundedShellMutex.withLock {
            var shell = getOrCreateBoundedShell()
            try {
                val first = execute(shell, command)
                if (first.code != SHELL_JOB_NOT_EXECUTED) return@withLock first

                discardBoundedShell(shell)
                shell = getOrCreateBoundedShell()
                execute(shell, command)
            } catch (failure: Throwable) {
                if (!shell.isAlive) discardBoundedShell(shell)
                throw failure
            }
        }
    }

    suspend fun executeSharedBounded(
        command: String,
        timeoutSeconds: Long,
        operation: String
    ): Shell.Result = withContext(Dispatchers.IO) {
        require(timeoutSeconds > 0) { "Root command timeout must be positive" }
        val shell = getShell()
        val first = executeWithTimeout(shell, command, timeoutSeconds, operation)
        if (first.code != SHELL_JOB_NOT_EXECUTED) return@withContext first

        runCatching { shell.close() }
        executeWithTimeout(getShell(), command, timeoutSeconds, "$operation retry")
    }

    suspend fun executeBounded(
        command: String,
        timeoutSeconds: Long,
        operation: String
    ): Shell.Result = withContext(Dispatchers.IO) {
        require(timeoutSeconds > 0) { "Root command timeout must be positive" }
        boundedShellMutex.withLock {
            var shell = getOrCreateBoundedShell()
            try {
                val first = executeWithTimeout(shell, command, timeoutSeconds, operation)
                if (first.code != SHELL_JOB_NOT_EXECUTED) return@withLock first

                discardBoundedShell(shell)
                shell = getOrCreateBoundedShell()
                executeWithTimeout(shell, command, timeoutSeconds, "$operation retry")
            } catch (failure: Throwable) {
                if (!shell.isAlive) discardBoundedShell(shell)
                throw failure
            }
        }
    }

    private fun getOrCreateBoundedShell(): Shell = synchronized(boundedShellGuard) {
        boundedShell?.takeIf(Shell::isAlive)?.let { return@synchronized it }
        boundedShell?.let { runCatching { it.close() } }
        Shell.Builder.create()
            .setFlags(Shell.FLAG_MOUNT_MASTER)
            .build()
            .also { boundedShell = it }
    }

    private fun discardBoundedShell(shell: Shell) {
        synchronized(boundedShellGuard) {
            if (boundedShell === shell) boundedShell = null
        }
        runCatching { shell.close() }
    }

    private fun execute(shell: Shell, command: String): Shell.Result {
        val stdout = ArrayList<String>()
        val stderr = ArrayList<String>()
        return shell.newJob().add(isolateShellJob(command)).to(stdout, stderr).exec()
    }

    fun hasRootAccess(forceRefresh: Boolean = false): Boolean {
        Shell.isAppGrantedRoot()?.let { granted ->
            if (granted || !forceRefresh) return rememberRoot(granted)
        }
        liveRootShellAvailable()?.let { return rememberRoot(it) }
        if (!forceRefresh) {
            cachedHasRoot?.let { cached ->
                if (cached || SystemClock.elapsedRealtime() - lastRootCheck < ROOT_CHECK_INTERVAL_MS) return cached
            }
        }
        synchronized(this) {
            Shell.isAppGrantedRoot()?.let { granted ->
                if (granted || !forceRefresh) return rememberRoot(granted)
            }
            liveRootShellAvailable()?.let { return rememberRoot(it) }
            if (!forceRefresh) {
                cachedHasRoot?.let { cached ->
                    if (cached || SystemClock.elapsedRealtime() - lastRootCheck < ROOT_CHECK_INTERVAL_MS) return cached
                }
            }
            if (forceRefresh) {
                boundedShell?.takeIf(Shell::isAlive)?.let { shell ->
                    if (!shell.isRoot) discardBoundedShell(shell)
                }
            }
            val shell = runCatching { getOrCreateBoundedShell() }.getOrNull()
            return rememberRoot(shell?.isRoot == true)
        }
    }

    private fun liveRootShellAvailable(): Boolean? {
        boundedShell?.takeIf(Shell::isAlive)?.let { if (it.isRoot) return true }
        Shell.getCachedShell()?.takeIf(Shell::isAlive)?.let { if (it.isRoot) return true }
        return null
    }

    private fun rememberRoot(granted: Boolean): Boolean {
        cachedHasRoot = granted
        lastRootCheck = SystemClock.elapsedRealtime()
        return granted
    }

    fun peekRootAccess(): Boolean? = Shell.isAppGrantedRoot() ?: cachedHasRoot
    fun currentRootGrant(): Boolean? = Shell.isAppGrantedRoot()

    fun isDeviceRooted() = System.getenv("PATH")?.split(":")?.any { path ->
        File(path, "su").canExecute()
    } ?: false

    suspend fun isAppInstalled(packageName: String): Boolean = withContext(Dispatchers.IO) {
        execute(
            "[ -f ${shellQuote("$revancedPath/$packageName/$packageName.apk")} ] || " +
                "[ -d ${shellQuote("$modulesPath/$packageName-revanced")} ]"
        ).isSuccess
    }

    suspend fun isAppMounted(packageName: String): Boolean = withContext(Dispatchers.IO) {
        pm.getPackageInfo(packageName)?.applicationInfo?.sourceDir?.let { target ->
            val sources = listOf(
                "$modulesPath/$packageName-revanced/$packageName.apk",
                "$revancedPath/$packageName/$packageName.apk",
                "$modulesPath/.$packageName-revanced.urv-rollback/$packageName.apk",
                "/data/adb/urv/transactions/$packageName/backup/module/$packageName.apk"
            )
            val inodeChecks = sources.joinToString(" ") { shellQuote(it) }
            val rootMatches = sources.joinToString(" || ") { source ->
                "root == ${awkQuote(source)} || mounted_source == ${awkQuote(source)} || " +
                    "root == ${awkQuote("$source (deleted)")} || mounted_source == ${awkQuote("$source (deleted)")} || " +
                    "root == ${awkQuote("$source\\040(deleted)")} || " +
                    "mounted_source == ${awkQuote("$source\\040(deleted)")}"
            }
            execute(
                "target_inode=\"${'$'}(stat -c '%d:%i' ${shellQuote(target)} 2>/dev/null)\" || exit 1; " +
                    "for source in $inodeChecks; do [ -f \"${'$'}source\" ] || continue; " +
                    "[ \"${'$'}(stat -c '%d:%i' \"${'$'}source\" 2>/dev/null)\" = \"${'$'}target_inode\" ] && exit 0; done; " +
                    "awk -v target=${shellQuote(target)} '${'$'}5 == target { separator=0; " +
                    "for (i=6; i<=NF; i++) if (${'$'}i == \"-\") { separator=i; break } " +
                    "root=${'$'}4; mounted_source=(separator ? ${'$'}(separator+2) : \"\"); " +
                    "if ($rootMatches) found=1 } END { exit !found }' /proc/self/mountinfo"
            ).isSuccess
        } ?: false
    }

    suspend fun isPackageResolvableForMount(packageName: String): Boolean =
        resolveStockApkPathForMount(packageName) != null

    suspend fun installSinglePackageFile(
        apkFile: File,
        userId: Int = 0,
        allowDowngrade: Boolean = false,
        onLog: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        require(apkFile.isFile) { "Stock APK file is missing" }
        require(userId >= 0) { "Invalid Android user" }

        // Reuse the coordinator's mount-master shell. Opening a second libsu shell for
        // PackageInstaller makes some root managers show another grant toast and adds
        // avoidable startup latency to every stock transition.
        val rootProbe = executeBounded(
            "id",
            ROOT_PROBE_TIMEOUT_SECONDS,
            "root shell probe"
        )
        onLog("Root shell probe: ${rootProbe.render()}")
        if (!rootProbe.hasRootUid()) throw RootServiceException()

        val downgradeFlag = if (allowDowngrade) " -d" else ""
        val installerPackage = shellQuote(app.packageName)
        val apkPath = shellQuote(apkFile.absolutePath)
        val commandPrefixes = listOf(
            "pm install -r$downgradeFlag --user $userId --install-location 0 -i $installerPackage",
            "pm install -r$downgradeFlag --user $userId -i $installerPackage",
            "cmd package install -r$downgradeFlag --user $userId --install-location 0 -i $installerPackage",
            "cmd package install -r$downgradeFlag --user $userId -i $installerPackage",
            "pm install -r$downgradeFlag --user $userId",
            "cmd package install -r$downgradeFlag --user $userId"
        )
        // The stock APK is about to be replaced by a bind-mounted patched payload.
        // Avoid producing install-time dexopt artifacts for bytes that will not be launched.
        // Older Android releases can reject this option, so retain the existing commands as fallback.
        val commands = commandPrefixes.map {
            "$it --dexopt-compiler-filter skip $apkPath"
        } + commandPrefixes.map { "$it $apkPath" }
        var failure = "Failed to install stock app"
        commands.forEachIndexed { index, command ->
            kotlin.coroutines.coroutineContext.ensureActive()
            val result = executeBounded(
                command,
                installTimeoutSeconds(apkFile.length()),
                "stock APK install"
            )
            val output = result.combinedOutput()
            onLog("Root direct install attempt ${index + 1}: ${result.render()}")
            if (result.isSuccess && !output.contains("Failure", ignoreCase = true)) {
                return@withContext
            }
            if (output.isNotBlank()) failure = output
        }
        throw Exception(failure)
    }

    suspend fun installPackageFiles(
        apkFiles: List<File>,
        userId: Int = 0,
        allowDowngrade: Boolean = false,
        onLog: (String) -> Unit = {},
        registerCancelCleanup: ((() -> Unit) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        require(apkFiles.isNotEmpty()) { "No stock APK files were provided" }
        require(apkFiles.all(File::exists)) { "A stock APK file is missing" }
        require(userId >= 0) { "Invalid Android user" }

        val installShell = Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER).build()
        val closeInstallShell = { runCatching { installShell.close() }; Unit }
        registerCancelCleanup?.invoke(closeInstallShell)
        try {
            val rootProbe = executeWithTimeout(
                installShell,
                "id",
                ROOT_PROBE_TIMEOUT_SECONDS,
                "root shell probe"
            )
            onLog("Root shell probe: ${rootProbe.render()}")
            if (!rootProbe.hasRootUid()) throw RootServiceException()

            val installerPackage = shellQuote(app.packageName)
            val downgradeFlag = if (allowDowngrade) " -d" else ""
            val createCommands = listOf(
                "pm" to "pm install-create -r$downgradeFlag --user $userId --install-location 0 -i $installerPackage",
                "pm" to "pm install-create -r$downgradeFlag --user $userId -i $installerPackage",
                "cmd package" to "cmd package install-create -r$downgradeFlag --user $userId --install-location 0 -i $installerPackage",
                "cmd package" to "cmd package install-create -r$downgradeFlag --user $userId -i $installerPackage",
                "pm" to "pm install-create -r$downgradeFlag --user $userId",
                "cmd package" to "cmd package install-create -r$downgradeFlag --user $userId"
            )
            var session: InstallerSession? = null
            var createFailure = "Failed to create root install session"
            createCommands.forEachIndexed { index, (backend, command) ->
                if (session != null) return@forEachIndexed
                kotlin.coroutines.coroutineContext.ensureActive()
                val result = executeWithTimeout(
                    installShell,
                    command,
                    SESSION_CONTROL_TIMEOUT_SECONDS,
                    "root install session creation"
                )
                val output = result.combinedOutput()
                val parsed = parseSessionId(output)
                onLog("Root install-create attempt ${index + 1}: ${result.render()}, session=${parsed ?: "n/a"}")
                if (result.isSuccess && parsed != null) session = InstallerSession(backend, parsed)
                else if (output.isNotBlank()) createFailure = output
            }
            val created = session ?: throw Exception(createFailure)
            val abandon = {
                // Cancellation cleanup may close installShell before this callback runs.
                var cleanupShell: Shell? = null
                try {
                    val shell = Shell.Builder.create()
                        .setFlags(Shell.FLAG_MOUNT_MASTER)
                        .build()
                    cleanupShell = shell
                    val result = executeWithTimeout(
                        shell,
                        "${created.backend} install-abandon ${created.id}",
                        SESSION_CONTROL_TIMEOUT_SECONDS,
                        "root install session abandon"
                    )
                    runCatching { onLog("Root install-abandon: ${result.render()}") }
                } catch (failure: Throwable) {
                    runCatching {
                        onLog(
                            "Root install-abandon failed: " +
                                (failure.message ?: failure.javaClass.simpleName)
                        )
                    }
                } finally {
                    runCatching { cleanupShell?.close() }
                }
                Unit
            }
            registerCancelCleanup?.invoke(abandon)

            var committed = false
            try {
                apkFiles.forEachIndexed { index, file ->
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val splitName = "$index.apk"
                    val command =
                        "${created.backend} install-write -S ${file.length()} ${created.id} " +
                            "${shellQuote(splitName)} < ${shellQuote(file.absolutePath)}"
                    val result = executeWithTimeout(
                        installShell,
                        command,
                        installTimeoutSeconds(file.length()),
                        "root install session write"
                    )
                    val output = result.combinedOutput()
                    onLog("Root install-write: ${result.render()}")
                    if (!result.isSuccess || output.contains("Failure", ignoreCase = true)) {
                        throw Exception(output.ifBlank { "Failed to write stock APK ${file.name}" })
                    }
                }

                kotlin.coroutines.coroutineContext.ensureActive()
                val totalSize = apkFiles.sumOf { it.length().coerceAtLeast(0L) }
                val commit = executeWithTimeout(
                    installShell,
                    "${created.backend} install-commit ${created.id}",
                    installTimeoutSeconds(totalSize),
                    "root install session commit"
                )
                val commitOutput = commit.combinedOutput()
                onLog("Root install-commit: ${commit.render()}")
                if (!commit.isSuccess || commitOutput.contains("Failure", ignoreCase = true)) {
                    throw Exception(commitOutput.ifBlank { "Failed to install stock app" })
                }
                committed = true
            } finally {
                if (!committed) abandon()
            }
        } finally {
            closeInstallShell()
        }
    }

    private fun parseSessionId(output: String): String? {
        val normalized = output.trim()
        if (normalized.matches(Regex("""^\d+$"""))) return normalized
        Regex("""\[(\d+)]""").find(normalized)?.groupValues?.getOrNull(1)?.let { return it }
        return Regex("""session(?:\s+id)?\s*[:=]?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(normalized)?.groupValues?.getOrNull(1)
    }

    private data class InstallerSession(val backend: String, val id: String)

    private fun executeWithTimeout(
        shell: Shell,
        command: String,
        timeoutSeconds: Long,
        operation: String
    ): Shell.Result {
        val stdout = ArrayList<String>()
        val stderr = ArrayList<String>()
        val future = shell.newJob()
            .add(isolateShellJob(command))
            .to(stdout, stderr)
            .enqueue()
        return try {
            future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (timeout: TimeoutException) {
            future.cancel(true)
            runCatching { shell.close() }
            throw IllegalStateException(
                "Root operation timed out during $operation after $timeoutSeconds seconds",
                timeout
            )
        }
    }

    private fun installTimeoutSeconds(fileSize: Long): Long =
        (DIRECT_INSTALL_BASE_TIMEOUT_SECONDS +
            fileSize.coerceAtLeast(0L) / BYTES_PER_TIMEOUT_SECOND)
            .coerceAtMost(DIRECT_INSTALL_MAX_TIMEOUT_SECONDS)

    // Magisk normally honors FLAG_MOUNT_MASTER, while KernelSU can accept it without
    // leaving the caller's mount namespace. Avoid an unnecessary nsenter on Magisk and
    // explicitly enter init's namespace only when the shell is still isolated. The long
    // mount option is accepted by Android Toybox and the root-manager BusyBox applets.
    // Keep the command in a child shell so a local exit cannot terminate libsu's shell.
    private fun isolateShellJob(command: String): String = """
        (
        current_mount_ns="${'$'}(readlink /proc/self/ns/mnt)" || exit 1
        init_mount_ns="${'$'}(readlink /proc/1/ns/mnt)" || exit 1
        if [ "${'$'}current_mount_ns" = "${'$'}init_mount_ns" ]; then
          /system/bin/sh -c ${shellQuote(command)}
        else
          nsenter --mount=/proc/1/ns/mnt -- /system/bin/sh -c ${shellQuote(command)}
        fi
        )
    """.trimIndent()

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun awkQuote(value: String): String = "\"" +
        value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private suspend fun resolveStockApkPathForMount(packageName: String): String? = withContext(Dispatchers.IO) {
        val sourceDir = pm.getPackageInfo(packageName)?.applicationInfo?.sourceDir ?: return@withContext null
        val result = execute(
            "pm path ${shellQuote(packageName)} 2>/dev/null | sed -n 's/^package://p' | " +
                "grep -Fx ${shellQuote(sourceDir)}"
        )
        sourceDir.takeIf { result.isSuccess }
    }

    companion object {
        const val modulesPath = "/data/adb/modules"
        private const val revancedPath = "/data/adb/revanced"
        private const val SHELL_JOB_NOT_EXECUTED = -1
        private const val ROOT_CHECK_INTERVAL_MS = 1_000L
        private const val ROOT_PROBE_TIMEOUT_SECONDS = 15L
        private const val SESSION_CONTROL_TIMEOUT_SECONDS = 30L
        private const val DIRECT_INSTALL_BASE_TIMEOUT_SECONDS = 60L
        private const val DIRECT_INSTALL_MAX_TIMEOUT_SECONDS = 240L
        private const val BYTES_PER_TIMEOUT_SECOND = 4L * 1024L * 1024L
    }
}

class RootServiceException : Exception("Root not available")

private fun Shell.Result.combinedOutput(): String = (out + err).joinToString("\n").trim()
private fun Shell.Result.render(): String =
    "success=$isSuccess, code=$code, output=${combinedOutput().ifBlank { "n/a" }}"
private fun Shell.Result.hasRootUid() = isSuccess && out.any { it.contains("uid=0") }
