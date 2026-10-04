package app.urv.manager.domain.installer.root

import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import app.urv.manager.util.PM
import kotlinx.coroutines.delay
import java.io.File
import java.security.MessageDigest

class AndroidPackageStateReader(
    private val pm: PM,
    private val shell: RootShellGateway
) : PackageStateReader {
    override suspend fun installedUserIds(packageName: String): Set<Int> {
        val result = runQuery(
            "set -eu; users=\"\$(pm list users 2>/dev/null | " +
                "sed -n 's/.*UserInfo{\\([0-9][0-9]*\\):.*/\\1/p')\"; " +
                "[ -n \"\$users\" ]; for user in \$users; do " +
                "packages=\"\$(pm list packages --user \"\$user\" ${shellQuote(packageName)} 2>/dev/null)\"; " +
                "if printf '%s\\n' \"\$packages\" | " +
                "grep -Fx ${shellQuote("package:$packageName")} >/dev/null; then " +
                "printf '%s\\n' \"\$user\"; fi; done"
        )
        result.requireSuccess("Inspect Android users for target package")
        return result.stdout.mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

    @Suppress("DEPRECATION")
    override suspend fun read(packageName: String, userId: Int): RootPackageState {
        val currentUserId = android.os.Process.myUid() / PER_USER_RANGE
        require(userId == currentUserId) {
            "Cross-user root mount package inspection is unsupported"
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val paths = runQuery(
            "pm path --user $userId ${shellQuote(packageName)} 2>/dev/null || " +
                "cmd package path --user $userId ${shellQuote(packageName)} 2>/dev/null"
        ).stdout.mapNotNull { line ->
            line.substringAfter("package:", "").trim().takeIf(String::isNotEmpty)
        }
        val installedForUser = paths.isNotEmpty() && runQuery(
            "pm list packages --user $userId ${shellQuote(packageName)} 2>/dev/null | grep -Fx " +
                shellQuote("package:$packageName")
        ).isSuccess
        if (!installedForUser) {
            return RootPackageState(packageName, userId, installed = false)
        }
        val basePath = paths.firstOrNull { it.substringAfterLast('/').startsWith("base") }
            ?: paths.first()
        val splitPaths = paths.filterNot { it == basePath }
        // Do not parse the mounted base path as an archive fallback. It may be the patched payload,
        // while recovery needs the package identity registered by PackageManager.
        val info = pm.getPackageInfo(packageName, flags)
        val baseHash = runQuery(
            "sha256sum ${shellQuote(basePath)} 2>/dev/null | awk '{print ${'$'}1}'",
            HASH_TIMEOUT_SECONDS
        ).stdout.firstOrNull()?.trim()?.takeIf { it.length == 64 }
        val disabledForUser = runQuery(
            "pm list packages -d --user $userId ${shellQuote(packageName)} 2>/dev/null | " +
                "grep -Fx ${shellQuote("package:$packageName")}"
        ).isSuccess
        val launcherForUser = runQuery(
            "cmd package resolve-activity --brief --user $userId " +
                "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
                shellQuote(packageName)
        )
        val launcher = launcherForUser.isSuccess && launcherForUser.stdout.any { line ->
            line.contains('/') && !line.contains("No activity", ignoreCase = true)
        }
        return RootPackageState(
            packageName = packageName,
            userId = userId,
            installed = true,
            versionName = info?.versionName,
            versionCode = info?.let { PackageInfoCompat.getLongVersionCode(it) },
            signerSha256 = info?.let { pm.getSignature(it) }?.toByteArray()?.sha256(),
            basePath = basePath,
            splitPaths = splitPaths,
            baseSha256 = baseHash,
            enabled = !disabledForUser,
            launcherResolvable = launcher,
            systemApp = info?.let { pm.isSystemApp(it) } ?: isSystemPackage(packageName),
            sharedUserId = info?.sharedUserId
        )
    }

    private suspend fun isSystemPackage(packageName: String): Boolean {
        val result = runQuery(
            "dumpsys package ${shellQuote(packageName)} 2>/dev/null | " +
                "grep -m 1 -E '^[[:space:]]*(pkgFlags|flags)='"
        )
        return result.isSuccess && result.stdout.any { line ->
            SYSTEM_PACKAGE_FLAG.containsMatchIn(line)
        }
    }

    override fun inspect(file: File): RootArtifactState {
        require(file.isFile) { "APK is missing: ${file.name}" }
        val info = pm.getPackageInfo(file, includeSigning = true)
            ?: throw IllegalArgumentException("Invalid APK: ${file.name}")
        val splitRequiredValue = info.applicationInfo?.metaData?.get("com.android.vending.splits.required")
        val splitRequired = splitRequiredValue == true ||
            splitRequiredValue?.toString()?.equals("true", ignoreCase = true) == true
        return RootArtifactState(
            path = file.absolutePath,
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            signerSha256 = pm.getSignature(info)?.toByteArray()?.sha256(),
            sha256 = file.inputStream().use { input -> input.sha256() },
            topology = if (!splitRequired && info.splitNames.isNullOrEmpty() &&
                info.applicationInfo?.splitSourceDirs.isNullOrEmpty()
            ) {
                "SINGLE"
            } else {
                "SPLIT"
            }
        )
    }

    override suspend fun waitForStable(
        expected: RootPackageState,
        consecutiveReads: Int
    ): RootPackageState {
        require(consecutiveReads > 0) { "At least one stable PackageManager read is required" }
        var stable = 0
        var previous: RootPackageState? = null
        repeat(60) {
            val current = read(expected.packageName, expected.userId)
            val matches = current.installed &&
                current.packageName == expected.packageName &&
                (expected.versionName == null || current.versionName == expected.versionName) &&
                (expected.versionCode == null || current.versionCode == expected.versionCode) &&
                (expected.signerSha256 == null || current.signerSha256 == expected.signerSha256) &&
                (expected.baseSha256 == null || current.baseSha256 == expected.baseSha256) &&
                current.basePath != null && current.splitPaths == expected.splitPaths &&
                current.enabled == expected.enabled &&
                current.launcherResolvable == expected.launcherResolvable
            if (matches && current == previous) stable++ else stable = if (matches) 1 else 0
            if (stable >= consecutiveReads) return current
            previous = current
            delay(500)
        }
        throw IllegalStateException("PackageManager did not reach a stable verified state")
    }

    override suspend fun runningPids(packageName: String): List<Int> {
        val packageUid = pm.getApplicationInfo(packageName)?.uid ?: -1
        val packageAppId = if (packageUid >= 0) packageUid % PER_USER_RANGE else -1
        val result = runQuery(
            "if ps -A -o PID,UID,NAME >/dev/null 2>&1; then " +
                "ps -A -o PID,UID,NAME 2>/dev/null | " +
                "awk -v pkg=${shellQuote(packageName)} -v app_id=$packageAppId " +
                "-v per_user=$PER_USER_RANGE " +
                "'((app_id >= 0) && (${'$'}2 % per_user) == app_id) || " +
                "${'$'}3 == pkg || index(${'$'}3, pkg \":\") == 1 { print ${'$'}1 }'; " +
                "else ps -A -o PID,NAME 2>/dev/null | awk -v pkg=${shellQuote(packageName)} " +
                "'${'$'}2 == pkg || index(${'$'}2, pkg \":\") == 1 { print ${'$'}1 }'; fi"
        )
        result.requireSuccess("Inspect target package processes")
        return result.stdout.mapNotNull { it.trim().toIntOrNull() }.distinct()
    }

    override suspend fun waitUntilStopped(packageName: String, timeoutMs: Long): Boolean {
        val attempts = (timeoutMs / 200).coerceAtLeast(1).toInt()
        repeat(attempts) {
            if (runningPids(packageName).isEmpty()) return true
            delay(200)
        }
        return false
    }

    private suspend fun runQuery(
        command: String,
        timeoutSeconds: Long = QUERY_TIMEOUT_SECONDS
    ): RootCommandResult = shell.runIsolatedBounded(
        command,
        timeoutSeconds,
        "Android package state query"
    )

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).toHex()

    private fun java.io.InputStream.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val PER_USER_RANGE = 100_000
        const val QUERY_TIMEOUT_SECONDS = 30L
        const val HASH_TIMEOUT_SECONDS = 60L
        val SYSTEM_PACKAGE_FLAG = Regex("\\b(SYSTEM|UPDATED_SYSTEM_APP)\\b")
    }
}
