package app.urv.manager.domain.installer

import android.app.Application
import android.os.SystemClock
import app.urv.manager.util.PM
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

class RootInstaller(
    private val app: Application,
    private val pm: PM
) {
    @Volatile
    private var cachedHasRoot: Boolean? = null
    @Volatile
    private var lastRootCheck = 0L

    private suspend fun getShell() = with(CompletableDeferred<Shell>()) {
        Shell.getShell(::complete)

        await()
    }

    suspend fun execute(vararg commands: String) = execute(getShell(), *commands)

    private fun execute(shell: Shell, vararg commands: String): Shell.Result {
        val stdout = ArrayList<String>()
        val stderr = ArrayList<String>()
        return shell.newJob()
            .add(*commands)
            .to(stdout, stderr)
            .exec()
    }

    fun hasRootAccess(forceRefresh: Boolean = false): Boolean {
        if (!forceRefresh) {
            Shell.isAppGrantedRoot()?.let { granted ->
                cachedHasRoot = granted
                lastRootCheck = SystemClock.elapsedRealtime()
                return granted
            }

            cachedHasRoot?.let { cached ->
                if (cached) return true
                if (SystemClock.elapsedRealtime() - lastRootCheck < ROOT_CHECK_INTERVAL_MS) return false
            }
        }

        synchronized(this) {
            if (!forceRefresh) {
                Shell.isAppGrantedRoot()?.let { granted ->
                    cachedHasRoot = granted
                    lastRootCheck = SystemClock.elapsedRealtime()
                    return granted
                }

                cachedHasRoot?.let { cached ->
                    if (cached) return true
                    if (SystemClock.elapsedRealtime() - lastRootCheck < ROOT_CHECK_INTERVAL_MS) return false
                }
            }

            val probeResult = runCatching {
                Shell.Builder.create().build().use { shell ->
                    execute(shell, "id")
                }
            }.getOrNull()
            lastRootCheck = SystemClock.elapsedRealtime()

            val granted = probeResult?.hasRootUid() == true
            cachedHasRoot = granted

            return granted
        }
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

    suspend fun isAppMounted(packageName: String) = withContext(Dispatchers.IO) {
        pm.getPackageInfo(packageName)?.applicationInfo?.sourceDir?.let {
            execute("mount | grep \"$it\"").isSuccess
        } ?: false
    }

    suspend fun isPackageResolvableForMount(packageName: String): Boolean =
        resolveStockApkPathForMount(packageName) != null

    suspend fun mount(packageName: String) {
        if (isAppMounted(packageName)) return

        withContext(Dispatchers.IO) {
            val stockAPK = pm.getPackageInfo(packageName)?.applicationInfo?.sourceDir
                ?: throw Exception("Failed to load application info")
            val patchedAPK = resolvePatchedApkPath(packageName)

            execute(
                "chcon u:object_r:apk_data_file:s0 \"$patchedAPK\"; " +
                    "mount -o bind \"$patchedAPK\" \"$stockAPK\"; " +
                    "am force-stop \"$packageName\""
            ).assertSuccess("Failed to mount APK")
        }
    }

    suspend fun unmount(packageName: String) {
        if (!isAppMounted(packageName)) return

        withContext(Dispatchers.IO) {
            val stockAPK = pm.getPackageInfo(packageName)?.applicationInfo?.sourceDir
                ?: throw Exception("Failed to load application info")

            execute("umount -l \"$stockAPK\"").assertSuccess("Failed to unmount APK")
        }
    }

    suspend fun install(
        patchedAPK: File,
        stockAPKs: List<File>?,
        packageName: String,
        version: String,
        label: String
    ) = withContext(Dispatchers.IO) {
        require(patchedAPK.exists()) { "Patched APK does not exist" }
        val modulePath = "$modulesPath/$packageName-revanced"
        val apkPath = "$modulePath/$packageName.apk"
        val stagingDir = File(app.cacheDir, "root-mount-$packageName").apply {
            deleteRecursively()
            mkdirs()
        }

        try {
            unmount(packageName)
            stockAPKs?.let { stockFiles ->
                if (pm.getPackageInfo(packageName) != null) {
                    execute("pm uninstall -k --user 0 ${shellQuote(packageName)}")
                        .assertSuccess("Failed to remove the existing app before installing the stock APK")
                }
                installPackageFiles(stockFiles)
            }

            listOf("service.sh", "module.prop").forEach { fileName ->
                val content = app.assets.open("root/$fileName").use { input ->
                    String(input.readBytes())
                        .replace("\r\n", "\n")
                        .replace("\r", "\n")
                        .replace("__PKG_NAME__", packageName)
                        .replace("__VERSION__", version)
                        .replace("__LABEL__", label)
                }
                stagingDir.resolve(fileName).writeText(content)
            }

            execute(
                "mkdir -p ${shellQuote(serviceDirPath)} ${shellQuote(modulePath)}",
                "rm -f ${shellQuote("$serviceDirPath/urv-$packageName.sh")}",
                "cp ${shellQuote(stagingDir.resolve("service.sh").absolutePath)} ${shellQuote("$modulePath/service.sh")}",
                "cp ${shellQuote(stagingDir.resolve("module.prop").absolutePath)} ${shellQuote("$modulePath/module.prop")}",
                "cp ${shellQuote(patchedAPK.absolutePath)} ${shellQuote(apkPath)}",
                "chmod 644 ${shellQuote(apkPath)}",
                "chown system:system ${shellQuote(apkPath)}",
                "chcon u:object_r:apk_data_file:s0 ${shellQuote(apkPath)}",
                "chmod +x ${shellQuote("$modulePath/service.sh")}"
            ).assertSuccess("Failed to prepare rooted mount module")
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    suspend fun installPackageFiles(
        apkFiles: List<File>,
        onLog: (String) -> Unit = {},
        registerCancelCleanup: ((() -> Unit) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        require(apkFiles.isNotEmpty()) { "No stock APK files were provided" }
        require(apkFiles.all(File::exists)) { "A stock APK file is missing" }

        val installShell = Shell.Builder.create()
            .setFlags(Shell.FLAG_MOUNT_MASTER)
            .build()
        val closeInstallShell = {
            runCatching { installShell.close() }
            Unit
        }
        registerCancelCleanup?.invoke(closeInstallShell)

        try {
            val rootProbe = execute(installShell, "id")
            onLog("Root shell probe: ${rootProbe.render()}")
            if (!rootProbe.hasRootUid()) throw RootServiceException()

            val installerPackage = shellQuote(app.packageName)
            val createCommands = listOf(
                "pm install-create -r --install-location 0 -i $installerPackage",
                "pm install-create -r -i $installerPackage",
                "cmd package install-create -r --install-location 0 -i $installerPackage",
                "cmd package install-create -r -i $installerPackage",
                "pm install-create -r",
                "cmd package install-create -r"
            )
            var sessionId: String? = null
            var createFailure = "Failed to create root install session"
            createCommands.forEachIndexed { index, command ->
                if (sessionId != null) return@forEachIndexed
                kotlin.coroutines.coroutineContext.ensureActive()
                val result = execute(installShell, command)
                val output = result.combinedOutput()
                val parsed = parseSessionId(output)
                onLog("Root install-create attempt ${index + 1}: ${result.render()}, session=${parsed ?: "n/a"}")
                if (result.isSuccess && parsed != null) sessionId = parsed
                else if (output.isNotBlank()) createFailure = output
            }
            val session = sessionId ?: throw Exception(createFailure)

            val abandon = {
                Shell.cmd(
                    "pm install-abandon $session",
                    "cmd package install-abandon $session"
                ).exec()
                Unit
            }
            registerCancelCleanup?.invoke(abandon)

            var committed = false
            try {
                apkFiles.forEachIndexed { index, file ->
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val splitName = "$index.apk"
                    val writeCommands = listOf(
                        "pm install-write -S ${file.length()} $session ${shellQuote(splitName)} < ${shellQuote(file.absolutePath)}",
                        "cmd package install-write -S ${file.length()} $session ${shellQuote(splitName)} < ${shellQuote(file.absolutePath)}"
                    )
                    var written = false
                    var failure = "Failed to write stock APK ${file.name}"
                    writeCommands.forEachIndexed { attempt, command ->
                        if (written) return@forEachIndexed
                        kotlin.coroutines.coroutineContext.ensureActive()
                        val result = execute(installShell, command)
                        val output = result.combinedOutput()
                        onLog("Root install-write attempt ${attempt + 1}: ${result.render()}")
                        written = result.isSuccess && !output.contains("Failure", ignoreCase = true)
                        if (!written && output.isNotBlank()) failure = output
                    }
                    if (!written) throw Exception(failure)
                }

                val commitCommands = listOf(
                    "pm install-commit $session",
                    "cmd package install-commit $session"
                )
                var commitSucceeded = false
                var commitFailure = "Failed to install stock app"
                commitCommands.forEachIndexed { index, command ->
                    if (commitSucceeded) return@forEachIndexed
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val result = execute(installShell, command)
                    val output = result.combinedOutput()
                    onLog("Root install-commit attempt ${index + 1}: ${result.render()}")
                    commitSucceeded = result.isSuccess && !output.contains("Failure", ignoreCase = true)
                    if (!commitSucceeded && output.isNotBlank()) commitFailure = output
                }
                if (!commitSucceeded) throw Exception(commitFailure)
                onLog("Root package manager install committed successfully (session $session)")
                committed = true
            } finally {
                if (!committed) abandon()
            }
        } finally {
            closeInstallShell()
        }
    }
    suspend fun uninstall(packageName: String) = withContext(Dispatchers.IO) {
        if (isAppMounted(packageName)) unmount(packageName)
        execute(
            "rm -rf ${shellQuote("$modulesPath/$packageName-revanced")}",
            "rm -rf ${shellQuote("$revancedPath/$packageName")}",
            "rm -f ${shellQuote("$serviceDirPath/urv-$packageName.sh")}"
        ).assertSuccess("Failed to remove rooted mount files")
    }

    private fun parseSessionId(output: String): String? {
        val normalized = output.trim()
        if (normalized.matches(Regex("""^\d+$"""))) return normalized
        Regex("""\[(\d+)]""").find(normalized)?.groupValues?.getOrNull(1)?.let { return it }
        return Regex("""session(?:\s+id)?\s*[:=]?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    companion object {
        private const val TAG = "RootInstaller"
        const val modulesPath = "/data/adb/modules"
        private const val revancedPath = "/data/adb/revanced"
        private const val serviceDirPath = "/data/adb/service.d"

        private fun Shell.Result.assertSuccess(errorMessage: String) {
            if (!isSuccess) throw Exception(errorMessage)
        }

        private const val ROOT_CHECK_INTERVAL_MS = 1_000L
    }

    private suspend fun resolvePatchedApkPath(packageName: String): String {
        val moduleApk = "$modulesPath/$packageName-revanced/$packageName.apk"
        val revancedApk = "$revancedPath/$packageName/$packageName.apk"
        val result = execute(
            "if [ -f ${shellQuote(moduleApk)} ]; then echo ${shellQuote(moduleApk)}; " +
                "elif [ -f ${shellQuote(revancedApk)} ]; then echo ${shellQuote(revancedApk)}; fi"
        )
        return result.out.firstOrNull { it.startsWith("/") }
            ?: throw Exception("Patched APK not found for mount")
    }

    private suspend fun resolveStockApkPathForMount(packageName: String): String? = withContext(Dispatchers.IO) {
        val command = """
            stock_path_data="${'$'}(pm path "$packageName" 2>/dev/null | grep base | grep /data/app/ | head -n 1 | sed 's/package://g')"
            stock_path_fallback="${'$'}(pm path "$packageName" 2>/dev/null | grep base | head -n 1 | sed 's/package://g')"
            if [ -z "${'$'}stock_path_data" ] && [ -z "${'$'}stock_path_fallback" ]; then
              stock_path_cmd="${'$'}(cmd package path "$packageName" 2>/dev/null | grep base | head -n 1 | sed 's/package://g')"
            else
              stock_path_cmd=""
            fi
            stock_path="${'$'}{stock_path_data:-${'$'}{stock_path_fallback:-${'$'}stock_path_cmd}}"
            if [ -n "${'$'}stock_path" ] && [ -f "${'$'}stock_path" ]; then
              echo "${'$'}stock_path"
            fi
        """.trimIndent().replace("\n", "; ")
        val result = execute(command)
        result.out
            .asSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("/") }
    }
}

class RootServiceException : Exception("Root not available")

private fun Shell.Result.combinedOutput(): String =
    (out + err).joinToString("\n").trim()

private fun Shell.Result.render(): String {
    val output = combinedOutput().ifBlank { "n/a" }
    return "success=$isSuccess, code=$code, output=$output"
}

private fun Shell.Result.hasRootUid() = isSuccess && out.any { line ->
    line.contains("uid=0")
}
