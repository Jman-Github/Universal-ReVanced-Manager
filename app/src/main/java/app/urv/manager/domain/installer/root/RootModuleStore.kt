package app.urv.manager.domain.installer.root

import android.app.Application
import java.io.File
import java.util.Base64

class RootModuleStore(
    private val app: Application,
    private val shell: RootShellGateway
) : RootModuleStorage {
    override suspend fun ensureRollbackSpace(
        packageName: String,
        stockPaths: List<String>,
        incomingBytes: Long
    ) {
        require(incomingBytes >= 0) { "Invalid incoming payload size" }
        val stockSizeCommands = stockPaths.joinToString("; ") { path ->
            "size=\"${'$'}(stat -c %s ${shellQuote(path)})\"; " +
                "required_bytes=${'$'}((required_bytes + size))"
        }
        val stockSizeClause = stockSizeCommands.takeIf(String::isNotEmpty)?.plus("; ").orEmpty()
        runModuleCommand(
            "set -eu; required_bytes=$incomingBytes; $stockSizeClause" +
                "if [ -d ${shellQuote(RootPaths.module(packageName))} ]; then " +
                "module_kb=\"${'$'}(du -sk ${shellQuote(RootPaths.module(packageName))} | awk '{print ${'$'}1}')\"; " +
                "required_bytes=${'$'}((required_bytes + module_kb * 1024)); fi; " +
                "available_kb=\"${'$'}(df -Pk ${shellQuote(RootPaths.ROOT)} | awk 'END {print ${'$'}4}')\"; " +
                "required_kb=${'$'}(((required_bytes + 1023) / 1024 + 16384)); " +
                "if [ \"${'$'}available_kb\" -lt \"${'$'}required_kb\" ]; then " +
                "echo \"Insufficient rollback space: need ${'$'}required_kb KiB, have ${'$'}available_kb KiB\" >&2; " +
                "exit 1; fi"
        ).requireSuccess("Verify rollback and staging space")
    }

    override suspend fun snapshot(packageName: String, preferredPayload: RootBackupArtifact?): String? {
        val backupRoot = RootPaths.backup(packageName)
        val moduleBackup = "$backupRoot/module"
        val moduleNext = "$backupRoot/module.next"
        val modulePrevious = "$backupRoot/module.previous"
        val module = RootPaths.module(packageName)
        val legacyApk = "${RootPaths.legacyPackage(packageName)}/$packageName.apk"
        val preferredCopy = preferredPayload?.path?.let { payloadPath ->
            "cp -p ${shellQuote(payloadPath)} ${shellQuote("$moduleNext/$packageName.apk")}; "
        }.orEmpty()
        val result = runModuleCommand(
            "set -eu; mkdir -p ${shellQuote(backupRoot)}; chmod 700 ${shellQuote(backupRoot)}; " +
                "rm -rf ${shellQuote(moduleNext)}; mkdir -p ${shellQuote(moduleNext)}; chmod 700 ${shellQuote(moduleNext)}; " +
                "if [ -d ${shellQuote(module)} ]; then cp -a ${shellQuote("$module/.")} ${shellQuote(moduleNext)}; fi; " +
                "if [ ! -f ${shellQuote("$moduleNext/$packageName.apk")} ] && [ -f ${shellQuote(legacyApk)} ]; then " +
                "cp -p ${shellQuote(legacyApk)} ${shellQuote("$moduleNext/$packageName.apk")}; fi; " +
                preferredCopy +
                "if [ -f ${shellQuote("$moduleNext/$packageName.apk")} ]; then " +
                "sha256sum ${shellQuote("$moduleNext/$packageName.apk")}; fi; " +
                "rm -rf ${shellQuote(modulePrevious)}; " +
                "if [ -d ${shellQuote(moduleBackup)} ]; then mv ${shellQuote(moduleBackup)} ${shellQuote(modulePrevious)}; fi; " +
                "mv ${shellQuote(moduleNext)} ${shellQuote(moduleBackup)}; " +
                "sync -f ${shellQuote(backupRoot)} 2>/dev/null || sync"
        ).requireSuccess("Snapshot previous root module")
        return result.stdout.lastOrNull()
            ?.substringBefore(' ')
            ?.trim()
            ?.takeIf { it.length == 64 }
    }

    override suspend fun readLegacyPayload(packageName: String): RootBackupArtifact? {
        val module = RootPaths.module(packageName)
        val moduleApk = RootPaths.moduleApk(packageName)
        val legacyApk = "${RootPaths.legacyPackage(packageName)}/$packageName.apk"
        val moduleIdentity =
            "grep -Fx ${shellQuote("id=$packageName-revanced")} ${shellQuote("$module/module.prop")} >/dev/null 2>&1"
        val result = runModuleCommand(
            "set -eu; [ ! -f ${shellQuote("$module/state.env")} ]; " +
                "if [ -f ${shellQuote(moduleApk)} ] && $moduleIdentity; then candidate=${shellQuote(moduleApk)}; " +
                "elif [ -f ${shellQuote(legacyApk)} ] && " +
                "{ $moduleIdentity || [ -f ${shellQuote(RootPaths.legacyService(packageName))} ]; }; then " +
                "candidate=${shellQuote(legacyApk)}; else exit 1; fi; " +
                "printf '%s\\n' \"${'$'}candidate\"; sha256sum \"${'$'}candidate\" | awk '{print ${'$'}1}'"
        )
        if (!result.isSuccess) return null
        val path = result.stdout.getOrNull(0)?.trim()?.takeIf { it == moduleApk || it == legacyApk }
            ?: return null
        val hash = result.stdout.getOrNull(1)?.trim()?.takeIf { it.matches(SHA256) } ?: return null
        return RootBackupArtifact(path, hash)
    }

    override suspend fun readCommittedState(packageName: String): RootCommittedState? {
        val module = RootPaths.module(packageName)
        val statePath = "$module/state.env"
        val patchedPath = RootPaths.moduleApk(packageName)
        val stockShadowPath = RootPaths.moduleStockApk(packageName)
        val result = runModuleCommand(
            "set -eu; [ -d ${shellQuote(module)} ] && [ ! -L ${shellQuote(module)} ]; " +
                "[ -f ${shellQuote(statePath)} ] && [ ! -L ${shellQuote(statePath)} ]; " +
                "[ \"${'$'}(stat -c %u ${shellQuote(statePath)})\" = 0 ]; " +
                "if [ -f ${shellQuote("$module/disable")} ]; then printf '0\\n'; else printf '1\\n'; fi; " +
                "base64 ${shellQuote(statePath)} | tr -d '\\n'; printf '\\n'; " +
                "sha256sum ${shellQuote(patchedPath)} | awk '{print ${'$'}1}'; " +
                "if [ -f ${shellQuote(stockShadowPath)} ]; then " +
                "sha256sum ${shellQuote(stockShadowPath)} | awk '{print ${'$'}1}'; " +
                "else printf -- '-\\n'; fi"
        )
        if (!result.isSuccess) return null
        val active = when (result.stdout.getOrNull(0)?.trim()) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        val values = decodeStateEnv(result.stdout.getOrNull(1)?.trim().orEmpty()) ?: return null
        if (values["URV_STATE_VERSION"] != "1" || values["URV_PACKAGE"] != packageName) return null
        if (values["URV_PATCHED_PATH"] != patchedPath) return null

        val patchedSha256 = values["URV_PATCHED_SHA256"]?.takeIf { it.matches(SHA256) }
            ?: return null
        if (result.stdout.getOrNull(2)?.trim() != patchedSha256) return null
        val preserveStock = values["URV_PRESERVE_STOCK"] == "1"
        val storedStockShadowPath = values["URV_STOCK_SHADOW_PATH"].orEmpty()
        val stockShadowSha256 = values["URV_STOCK_SHADOW_SHA256"]
            ?.takeIf { it.matches(SHA256) }
        if (preserveStock) {
            if (storedStockShadowPath != stockShadowPath || stockShadowSha256 == null) return null
            if (result.stdout.getOrNull(3)?.trim() != stockShadowSha256) return null
        }

        return RootCommittedState(
            transactionId = values["URV_TRANSACTION_ID"]?.takeIf(String::isNotBlank) ?: return null,
            packageName = packageName,
            userId = values["URV_USER_ID"]?.toIntOrNull()?.takeIf { it >= 0 } ?: return null,
            versionName = values["URV_VERSION_NAME"]?.takeIf(String::isNotBlank) ?: return null,
            versionCode = values["URV_VERSION_CODE"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return null,
            signerSha256 = values["URV_SIGNER_SHA256"]?.takeIf { it.matches(SHA256) } ?: return null,
            stockPath = values["URV_STOCK_PATH"]?.takeIf(String::isNotBlank) ?: return null,
            stockSha256 = values["URV_STOCK_SHA256"]?.takeIf { it.matches(SHA256) } ?: return null,
            patchedPath = patchedPath,
            patchedSha256 = patchedSha256,
            stockShadowPath = storedStockShadowPath.takeIf(String::isNotBlank),
            stockShadowSha256 = stockShadowSha256,
            preserveStockAcrossBoot = preserveStock,
            topology = values["URV_TOPOLOGY"].orEmpty(),
            enabled = values["URV_ENABLED"] == "1",
            launcherResolvable = values["URV_LAUNCHER_RESOLVABLE"] == "1",
            active = active,
            status = if (active) "MOUNTED" else "STOCK",
            committedAtEpochMs = System.currentTimeMillis()
        )
    }

    override suspend fun snapshotStock(packageName: String, paths: List<String>): List<RootBackupArtifact> {
        require(paths.isNotEmpty()) { "No installed stock paths are available to snapshot" }
        val backupRoot = RootPaths.backup(packageName)
        val destination = "$backupRoot/package"
        val next = "$backupRoot/package.next"
        val previous = "$backupRoot/package.previous"
        val copied = paths.mapIndexed { index, path -> "$destination/$index.apk" }
        val commands = paths.mapIndexed { index, path ->
            "cp -p ${shellQuote(path)} ${shellQuote("$next/$index.apk")}; " +
                "sha256sum ${shellQuote("$next/$index.apk")}"
        }.joinToString("; ")
        runModuleCommand(
            "set -eu; mkdir -p ${shellQuote(backupRoot)}; chmod 700 ${shellQuote(backupRoot)}; " +
                "rm -rf ${shellQuote(next)}; mkdir -p ${shellQuote(next)}; chmod 700 ${shellQuote(next)}; " +
                "$commands; chmod 600 ${shellQuote(next)}/*.apk; " +
                "sync -f ${shellQuote(next)} 2>/dev/null || sync; " +
                "rm -rf ${shellQuote(previous)}; " +
                "if [ -d ${shellQuote(destination)} ]; then mv ${shellQuote(destination)} ${shellQuote(previous)}; fi; " +
                "mv ${shellQuote(next)} ${shellQuote(destination)}; " +
                "sync -f ${shellQuote(backupRoot)} 2>/dev/null || sync"
        ).requireSuccess("Snapshot raw stock APK")
        val hashes = resultHashes(packageName, copied)
        return copied.mapIndexed { index, path ->
            RootBackupArtifact(path, hashes.getOrElse(index) { error("Missing stock backup hash") })
        }
    }

    override suspend fun commitSnapshot(packageName: String) {
        val backupRoot = RootPaths.backup(packageName)
        runModuleCommand(
            "set -eu; rm -rf ${shellQuote("$backupRoot/module.previous")} " +
                "${shellQuote("$backupRoot/module.next")} ${shellQuote("$backupRoot/package.previous")} " +
                "${shellQuote("$backupRoot/package.next")} ${shellQuote(RootPaths.rollbackModule(packageName))} " +
                "${shellQuote(RootPaths.legacyPackage(packageName))}; " +
                "rm -f ${shellQuote(RootPaths.legacyService(packageName))}; " +
                "sync"
        ).requireSuccess("Finalize verified rollback snapshot")
    }

    override suspend fun stageAndActivate(
        transactionId: String,
        packageName: String,
        label: String,
        patchedApk: File,
        compatible: RootPackageState,
        patchedHash: String
    ): String {
        val local = File(app.cacheDir, "root-module-$transactionId").apply {
            deleteRecursively()
            check(mkdirs()) { "Failed to create local module staging directory" }
        }
        try {
            writeAsset("root/post-fs-data.sh", local.resolve("post-fs-data.sh"), packageName, label, compatible)
            writeAsset("root/service.sh", local.resolve("service.sh"), packageName, label, compatible)
            writeAsset("root/module.prop", local.resolve("module.prop"), packageName, label, compatible)
            local.resolve("state.env").writeText(buildStateEnv(transactionId, packageName, compatible, patchedHash))

            val stage = RootPaths.stagingModule(packageName, transactionId)
            val active = RootPaths.module(packageName)
            val rollback = RootPaths.rollbackModule(packageName)
            val apkName = "$packageName.apk"
            val stockApkName = "$packageName-stock.apk"
            val stockPath = requireNotNull(compatible.basePath)
            val stockHash = requireNotNull(compatible.baseSha256)
            runModuleCommand(
                "set -eu; rm -rf ${shellQuote(stage)} ${shellQuote(rollback)}; " +
                    "mkdir -p ${shellQuote(stage)}; chmod 700 ${shellQuote(stage)}; " +
                    "cp ${shellQuote(local.resolve("post-fs-data.sh").absolutePath)} ${shellQuote("$stage/post-fs-data.sh")}; " +
                    "cp ${shellQuote(local.resolve("service.sh").absolutePath)} ${shellQuote("$stage/service.sh")}; " +
                    "cp ${shellQuote(local.resolve("module.prop").absolutePath)} ${shellQuote("$stage/module.prop")}; " +
                    "cp ${shellQuote(local.resolve("state.env").absolutePath)} ${shellQuote("$stage/state.env")}; " +
                    "cp ${shellQuote(patchedApk.absolutePath)} ${shellQuote("$stage/$apkName")}; " +
                    "cp ${shellQuote(stockPath)} ${shellQuote("$stage/$stockApkName")}; " +
                    "chmod 755 ${shellQuote("$stage/post-fs-data.sh")} ${shellQuote("$stage/service.sh")}; " +
                    "chmod 600 ${shellQuote("$stage/state.env")}; chmod 644 ${shellQuote("$stage/module.prop")} " +
                    "${shellQuote("$stage/$apkName")} ${shellQuote("$stage/$stockApkName")}; " +
                    "chown -R 0:0 ${shellQuote(stage)}; " +
                    "chcon u:object_r:apk_data_file:s0 ${shellQuote("$stage/$apkName")} ${shellQuote("$stage/$stockApkName")}; " +
                    "test \"${'$'}(sha256sum ${shellQuote("$stage/$apkName")} | awk '{print ${'$'}1}')\" = ${shellQuote(patchedHash)}; " +
                    "test \"${'$'}(sha256sum ${shellQuote("$stage/$stockApkName")} | awk '{print ${'$'}1}')\" = ${shellQuote(stockHash)}; " +
                    "sync -f ${shellQuote("$stage/$apkName")} 2>/dev/null || sync; " +
                    "sync -f ${shellQuote("$stage/$stockApkName")} 2>/dev/null || sync; " +
                    "sync -f ${shellQuote(stage)} 2>/dev/null || sync; " +
                    "if [ -d ${shellQuote(active)} ]; then mv ${shellQuote(active)} ${shellQuote(rollback)}; fi; " +
                    "mv ${shellQuote(stage)} ${shellQuote(active)}; " +
                    "sync -f ${shellQuote(RootPaths.MODULES)} 2>/dev/null || sync"
            ).requireSuccess("Atomically activate root module")
            return RootPaths.moduleApk(packageName)
        } finally {
            local.deleteRecursively()
        }
    }

    override suspend fun updateState(state: RootCommittedState) {
        val module = RootPaths.module(state.packageName)
        val statePath = "$module/state.env"
        val tempPath = "$statePath.tmp-${state.transactionId}"
        require(state.patchedPath == RootPaths.moduleApk(state.packageName)) {
            "Committed payload path does not belong to the package module"
        }
        if (state.preserveStockAcrossBoot) {
            require(state.stockShadowPath == RootPaths.moduleStockApk(state.packageName)) {
                "Committed stock shadow does not belong to the package module"
            }
            require(state.stockShadowSha256 == state.stockSha256) {
                "Committed stock shadow identity changed"
            }
        }
        val encoded = Base64.getEncoder().encodeToString(buildStateEnv(state).toByteArray())
        val shadowValidation = if (state.preserveStockAcrossBoot) {
            "test \"${'$'}(sha256sum ${shellQuote(requireNotNull(state.stockShadowPath))} | awk '{print ${'$'}1}')\" = " +
                "${shellQuote(requireNotNull(state.stockShadowSha256))}; "
        } else {
            ""
        }
        runModuleCommand(
            "set -eu; [ -d ${shellQuote(module)} ]; " +
                "test \"${'$'}(sha256sum ${shellQuote(state.patchedPath)} | awk '{print ${'$'}1}')\" = " +
                "${shellQuote(state.patchedSha256)}; " + shadowValidation +
                "printf %s ${shellQuote(encoded)} | base64 -d > ${shellQuote(tempPath)}; " +
                "chmod 600 ${shellQuote(tempPath)}; chown 0:0 ${shellQuote(tempPath)}; " +
                "sync -f ${shellQuote(tempPath)} 2>/dev/null || sync; " +
                "mv -f ${shellQuote(tempPath)} ${shellQuote(statePath)}; " +
                "sync -f ${shellQuote(module)} 2>/dev/null || sync"
        ).requireSuccess("Atomically update committed root module state")
    }

    override suspend fun restorePrevious(packageName: String): Boolean {
        val active = RootPaths.module(packageName)
        val rollback = RootPaths.rollbackModule(packageName)
        val backup = "${RootPaths.backup(packageName)}/module"
        val restoreNext = "${RootPaths.MODULES}/.$packageName-revanced.urv-restore"
        val backupPayload = "$backup/$packageName.apk"
        val rollbackPayload = "$rollback/$packageName.apk"
        val result = runModuleCommand(
            "set -eu; rm -rf ${shellQuote(restoreNext)}; " +
                "if [ -f ${shellQuote(backupPayload)} ]; then " +
                "mkdir -p ${shellQuote(restoreNext)}; cp -a ${shellQuote("$backup/.")} ${shellQuote(restoreNext)}; " +
                "elif [ -f ${shellQuote(rollbackPayload)} ]; then mv ${shellQuote(rollback)} ${shellQuote(restoreNext)}; " +
                "else rm -rf ${shellQuote(active)} ${shellQuote(rollback)}; " +
                "sync -f ${shellQuote(RootPaths.MODULES)} 2>/dev/null || sync; exit 1; fi; " +
                "rm -rf ${shellQuote(active)} ${shellQuote(rollback)}; " +
                "mv ${shellQuote(restoreNext)} ${shellQuote(active)}; " +
                "sync -f ${shellQuote(RootPaths.MODULES)} 2>/dev/null || sync"
        )
        return result.isSuccess
    }

    override suspend fun enable(packageName: String) {
        val module = RootPaths.module(packageName)
        val local = File(app.cacheDir, "root-runtime-$packageName-${System.nanoTime()}").apply {
            check(mkdirs()) { "Failed to create root runtime staging directory" }
        }
        try {
            val postFsSource = local.resolve("post-fs-data.sh")
            val serviceSource = local.resolve("service.sh")
            copyAsset("root/post-fs-data.sh", postFsSource)
            copyAsset("root/service.sh", serviceSource)
            val postFsTarget = "$module/post-fs-data.sh"
            val serviceTarget = "$module/service.sh"
            val postFsNext = "$postFsTarget.urv-next"
            val serviceNext = "$serviceTarget.urv-next"
            runModuleCommand(
                "set -eu; [ -d ${shellQuote(module)} ] && [ ! -L ${shellQuote(module)} ]; " +
                    "rm -f ${shellQuote(postFsNext)} ${shellQuote(serviceNext)}; " +
                    "if [ ! -f ${shellQuote(postFsTarget)} ] || [ -L ${shellQuote(postFsTarget)} ] || " +
                    "[ \"${'$'}(sha256sum ${shellQuote(postFsSource.absolutePath)} | awk '{print ${'$'}1}')\" != " +
                    "\"${'$'}(sha256sum ${shellQuote(postFsTarget)} | awk '{print ${'$'}1}')\" ]; then " +
                    "cp ${shellQuote(postFsSource.absolutePath)} ${shellQuote(postFsNext)}; " +
                    "chmod 755 ${shellQuote(postFsNext)}; chown 0:0 ${shellQuote(postFsNext)}; " +
                    "sync -f ${shellQuote(postFsNext)} 2>/dev/null || sync; " +
                    "mv -f ${shellQuote(postFsNext)} ${shellQuote(postFsTarget)}; fi; " +
                    "if [ ! -f ${shellQuote(serviceTarget)} ] || [ -L ${shellQuote(serviceTarget)} ] || " +
                    "[ \"${'$'}(sha256sum ${shellQuote(serviceSource.absolutePath)} | awk '{print ${'$'}1}')\" != " +
                    "\"${'$'}(sha256sum ${shellQuote(serviceTarget)} | awk '{print ${'$'}1}')\" ]; then " +
                    "cp ${shellQuote(serviceSource.absolutePath)} ${shellQuote(serviceNext)}; " +
                    "chmod 755 ${shellQuote(serviceNext)}; chown 0:0 ${shellQuote(serviceNext)}; " +
                    "sync -f ${shellQuote(serviceNext)} 2>/dev/null || sync; " +
                    "mv -f ${shellQuote(serviceNext)} ${shellQuote(serviceTarget)}; fi; " +
                    "rm -f ${shellQuote("$module/disable")}; " +
                    "sync -f ${shellQuote(module)} 2>/dev/null || sync"
            ).requireSuccess("Refresh and enable root mount module")
        } finally {
            local.deleteRecursively()
        }
    }

    override suspend fun disable(packageName: String) {
        val module = RootPaths.module(packageName)
        runModuleCommand(
            "set -eu; if [ -d ${shellQuote(module)} ]; then : > ${shellQuote("$module/disable")}; fi; " +
                "rm -f ${shellQuote(RootPaths.legacyService(packageName))}; " +
                "rm -rf ${shellQuote(RootPaths.legacyPackage(packageName))}; " +
                "sync"
        ).requireSuccess("Disable root mount module")
    }

    override suspend fun removeActive(packageName: String) {
        runModuleCommand(
            "set -eu; rm -rf ${shellQuote(RootPaths.module(packageName))} " +
                "${shellQuote(RootPaths.rollbackModule(packageName))} " +
                "${shellQuote(RootPaths.legacyPackage(packageName))}; " +
                "rm -f ${shellQuote(RootPaths.legacyService(packageName))}; " +
                "sync"
        ).requireSuccess("Remove root mount module")
    }

    override suspend fun purgeBackups(packageName: String) {
        val backupRoot = RootPaths.backup(packageName)
        runModuleCommand(
            "set -eu; rm -rf ${shellQuote(backupRoot)}; " +
                "sync -f ${shellQuote(RootPaths.transaction(packageName))} 2>/dev/null || sync"
        ).requireSuccess("Remove committed root mount backups")
    }

    private fun copyAsset(asset: String, destination: File) {
        val content = app.assets.open(asset).bufferedReader().use { it.readText() }
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        destination.writeText(content)
    }

    private fun writeAsset(
        asset: String,
        destination: File,
        packageName: String,
        label: String,
        compatible: RootPackageState
    ) {
        val safeVersion = compatible.versionName.orEmpty().replace('\n', ' ').replace('\r', ' ')
        val safeLabel = label.replace('\n', ' ').replace('\r', ' ')
        val content = app.assets.open(asset).bufferedReader().use { it.readText() }
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("__PKG_NAME__", packageName)
            .replace("__VERSION__", safeVersion)
            .replace("__VERSION_CODE__", compatible.versionCode?.toString().orEmpty())
            .replace("__LABEL__", safeLabel)
        destination.writeText(content)
    }

    private fun decodeStateEnv(encoded: String): Map<String, String>? = runCatching {
        val content = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        buildMap {
            content.lineSequence()
                .filter(String::isNotBlank)
                .forEach { line ->
                    val separator = line.indexOf('=')
                    require(separator > 0) { "Invalid root module state entry" }
                    val key = line.substring(0, separator)
                    val rawValue = line.substring(separator + 1)
                    require(key.matches(STATE_KEY)) { "Invalid root module state key" }
                    require(key !in this) { "Duplicate root module state key" }
                    require(rawValue.length >= 2 && rawValue.first() == '\'' && rawValue.last() == '\'') {
                        "Invalid root module state value"
                    }
                    put(key, rawValue.substring(1, rawValue.lastIndex).replace("'\\''", "'"))
                }
        }
    }.getOrNull()

    private fun buildStateEnv(
        transactionId: String,
        packageName: String,
        compatible: RootPackageState,
        patchedHash: String
    ): String = buildStateEnv(
        transactionId = transactionId,
        packageName = packageName,
        userId = compatible.userId,
        versionName = compatible.versionName.orEmpty(),
        versionCode = compatible.versionCode?.toString().orEmpty(),
        signerSha256 = compatible.signerSha256.orEmpty(),
        stockPath = requireNotNull(compatible.basePath),
        stockSha256 = requireNotNull(compatible.baseSha256),
        patchedPath = RootPaths.moduleApk(packageName),
        patchedSha256 = patchedHash,
        stockShadowPath = RootPaths.moduleStockApk(packageName),
        stockShadowSha256 = requireNotNull(compatible.baseSha256),
        preserveStockAcrossBoot = true,
        topology = compatible.topology,
        enabled = compatible.enabled,
        launcherResolvable = compatible.launcherResolvable
    )

    private fun buildStateEnv(state: RootCommittedState): String = buildStateEnv(
        transactionId = state.transactionId,
        packageName = state.packageName,
        userId = state.userId,
        versionName = state.versionName.orEmpty(),
        versionCode = state.versionCode.toString(),
        signerSha256 = state.signerSha256.orEmpty(),
        stockPath = state.stockPath,
        stockSha256 = state.stockSha256,
        patchedPath = state.patchedPath,
        patchedSha256 = state.patchedSha256,
        stockShadowPath = state.stockShadowPath.orEmpty(),
        stockShadowSha256 = state.stockShadowSha256.orEmpty(),
        preserveStockAcrossBoot = state.preserveStockAcrossBoot,
        topology = state.topology,
        enabled = state.enabled,
        launcherResolvable = state.launcherResolvable
    )

    private fun buildStateEnv(
        transactionId: String,
        packageName: String,
        userId: Int,
        versionName: String,
        versionCode: String,
        signerSha256: String,
        stockPath: String,
        stockSha256: String,
        patchedPath: String,
        patchedSha256: String,
        stockShadowPath: String,
        stockShadowSha256: String,
        preserveStockAcrossBoot: Boolean,
        topology: String,
        enabled: Boolean,
        launcherResolvable: Boolean
    ): String = buildString {
        appendLine("URV_STATE_VERSION='1'")
        appendLine("URV_TRANSACTION_ID=${envQuote(transactionId)}")
        appendLine("URV_PACKAGE=${envQuote(packageName)}")
        appendLine("URV_USER_ID=${envQuote(userId.toString())}")
        appendLine("URV_VERSION_NAME=${envQuote(versionName)}")
        appendLine("URV_VERSION_CODE=${envQuote(versionCode)}")
        appendLine("URV_SIGNER_SHA256=${envQuote(signerSha256)}")
        appendLine("URV_STOCK_PATH=${envQuote(stockPath)}")
        appendLine("URV_STOCK_SHA256=${envQuote(stockSha256)}")
        appendLine("URV_PATCHED_PATH=${envQuote(patchedPath)}")
        appendLine("URV_PATCHED_SHA256=${envQuote(patchedSha256)}")
        appendLine("URV_STOCK_SHADOW_PATH=${envQuote(stockShadowPath)}")
        appendLine("URV_STOCK_SHADOW_SHA256=${envQuote(stockShadowSha256)}")
        appendLine("URV_PRESERVE_STOCK=${envQuote(if (preserveStockAcrossBoot) "1" else "0")}")
        appendLine("URV_TOPOLOGY=${envQuote(topology)}")
        appendLine("URV_ENABLED=${envQuote(if (enabled) "1" else "0")}")
        appendLine("URV_LAUNCHER_RESOLVABLE=${envQuote(if (launcherResolvable) "1" else "0")}")
    }

    private fun envQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private suspend fun runModuleCommand(command: String): RootCommandResult =
        shell.runIsolatedBounded(
            command,
            MODULE_TIMEOUT_SECONDS,
            "root module storage"
        )

    private suspend fun resultHashes(packageName: String, paths: List<String>): List<String> {
        if (paths.isEmpty()) return emptyList()
        val result = runModuleCommand(paths.joinToString("; ") { path -> "sha256sum ${shellQuote(path)}" })
            .requireSuccess("Verify stock rollback snapshot for $packageName")
        return result.stdout.mapNotNull { line ->
            line.substringBefore(' ').trim().takeIf { it.length == 64 }
        }
    }

    private companion object {
        const val MODULE_TIMEOUT_SECONDS = 300L
        val SHA256 = Regex("[0-9a-f]{64}")
        val STATE_KEY = Regex("URV_[A-Z0-9_]+")
    }
}
