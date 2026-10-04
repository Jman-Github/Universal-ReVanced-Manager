package app.urv.manager.domain.installer.root

import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import app.urv.manager.util.PM
import com.android.apksig.ApkVerifier
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

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
        val splitPaths = paths.filterNot { it == basePath }.sorted()
        // Do not parse the mounted base path as an archive fallback. It may be the patched payload,
        // while recovery needs the package identity registered by PackageManager.
        val info = pm.getPackageInfo(packageName, flags)
        val apkPaths = listOf(basePath) + splitPaths
        val hashes = runQuery(
            "sha256sum ${apkPaths.joinToString(" ", transform = ::shellQuote)} 2>/dev/null",
            HASH_TIMEOUT_SECONDS
        ).requireSuccess("Hash installed APK set").stdout.map { it.substringBefore(' ').trim() }
        check(hashes.size == apkPaths.size && hashes.all { it.matches(Regex("[0-9a-f]{64}")) }) {
            "Installed APK set could not be fully verified"
        }
        val baseHash = hashes.first()
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
            splitSha256 = splitPaths.zip(hashes.drop(1)).toMap(),
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
        val manifest = ZipFile(file).use { zip ->
            val entry = requireNotNull(zip.getEntry("AndroidManifest.xml")) { "APK manifest is missing" }
            zip.getInputStream(entry).use(AndroidManifestBlock::load)
        }
        val splitName = manifest.split?.takeIf(String::isNotBlank)
        val manifestVersionCode = requireNotNull(manifest.versionCode) { "APK version code is missing" }
        val versionCodeMajor = manifest.manifestElement
            ?.searchAttribute("http://schemas.android.com/apk/res/android", "versionCodeMajor")?.data ?: 0
        val info = pm.getPackageInfo(file, includeSigning = true)
        require(info != null || splitName != null) { "Invalid base APK: ${file.name}" }
        val signer = info?.let { pm.getSignature(it) }?.toByteArray()?.sha256() ?: run {
            val verification = ApkVerifier.Builder(file).build().verify()
            require(verification.isVerified) { "Stock split signature could not be verified" }
            requireNotNull(verification.signerCertificates.singleOrNull()) {
                "Stock split must have one verified signer"
            }.encoded.sha256()
        }
        val splitRequiredValue = info?.applicationInfo?.metaData?.get("com.android.vending.splits.required")
        val splitRequired = splitRequiredValue == true ||
            splitRequiredValue?.toString()?.equals("true", ignoreCase = true) == true
        return RootArtifactState(
            path = file.absolutePath,
            packageName = info?.packageName ?: requireNotNull(manifest.packageName),
            versionName = (info?.versionName ?: manifest.versionName)?.takeIf(String::isNotBlank),
            versionCode = info?.let(PackageInfoCompat::getLongVersionCode)
                ?: ((versionCodeMajor.toLong() shl 32) or (manifestVersionCode.toLong() and 0xffffffffL)),
            signerSha256 = signer,
            sha256 = file.inputStream().use { input -> input.sha256() },
            topology = if (splitName == null && !splitRequired && info?.splitNames.isNullOrEmpty() &&
                info?.applicationInfo?.splitSourceDirs.isNullOrEmpty()
            ) {
                "SINGLE"
            } else {
                "SPLIT"
            },
            splitName = splitName
        )
    }

    override suspend fun waitForStable(
        expected: RootPackageState,
        consecutiveReads: Int
    ): RootPackageState = awaitStableRootPackageState(expected, consecutiveReads) {
        read(expected.packageName, expected.userId)
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
        return withTimeoutOrNull(timeoutMs) {
            while (runningPids(packageName).isNotEmpty()) delay(200)
            true
        } ?: false
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
