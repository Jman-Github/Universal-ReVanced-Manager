package app.urv.manager.domain.lsposed

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.annotation.StringRes
import androidx.core.content.pm.PackageInfoCompat
import app.universal.revanced.manager.R
import app.urv.manager.data.room.lsposed.LsposedModule
import app.urv.manager.data.room.lsposed.LsposedModuleDao
import app.urv.manager.domain.installer.RootInstaller
import app.urv.manager.network.api.ReVancedAPI
import app.urv.manager.network.api.successOrThrow
import app.urv.manager.network.dto.GitHubAsset
import app.urv.manager.network.dto.GitHubRelease
import app.urv.manager.network.service.HttpService
import app.urv.manager.network.utils.APIResponse
import app.urv.manager.util.PM
import io.ktor.client.request.url
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

enum class LsposedSourceKind {
    LOCAL_FILE,
    GITHUB_REPOSITORY,
    GITHUB_RELEASE,
    GITHUB_ASSET,
}

data class LsposedFrameworkState(
    val rootAvailable: Boolean,
    val installed: Boolean,
)

data class LsposedInstalledPackageState(
    val versionCode: Long,
    val signingFingerprint: String,
    val lastUpdateTime: Long,
)

data class LsposedReleaseAsset(
    val sourceReference: String,
    val sourceKind: LsposedSourceKind,
    val repositoryUrl: String,
    val releaseTag: String,
    val asset: GitHubAsset,
)

data class PendingLsposedModule(
    val file: File,
    val packageName: String,
    val displayName: String,
    val versionName: String,
    val versionCode: Long,
    val signingFingerprint: String,
    val sourceKind: LsposedSourceKind,
    val sourceReference: String,
    val releaseTag: String? = null,
    val assetName: String? = null,
    val assetDigest: String? = null,
    val checksumPublished: Boolean,
    val temporary: Boolean,
)

class LsposedSourceParseException(
    @StringRes val messageRes: Int,
) : IllegalArgumentException()

object LsposedSourceParser {
    data class Parsed(
        val kind: LsposedSourceKind,
        val repositoryUrl: String,
        val releaseTag: String? = null,
        val assetName: String? = null,
    )

    fun parse(raw: String): Parsed {
        val uri = runCatching { URI(raw.trim()) }
            .getOrElse { throw LsposedSourceParseException(R.string.lsposed_error_valid_github_url) }
        if (!uri.scheme.equals("https", true) || !uri.host.equals("github.com", true)) {
            throw LsposedSourceParseException(R.string.lsposed_error_github_only)
        }
        val parts = uri.rawPath
            .trim('/')
            .split('/')
            .filter(String::isNotBlank)
            .map(::decodePathSegment)
        if (parts.size < 2) {
            throw LsposedSourceParseException(R.string.lsposed_error_repository_or_release_url)
        }
        val repositoryUrl = "https://github.com/${parts[0]}/${parts[1].removeSuffix(".git")}"
        if (parts.size == 2) {
            return Parsed(LsposedSourceKind.GITHUB_REPOSITORY, repositoryUrl)
        }
        if (parts.getOrNull(2) != "releases") {
            throw LsposedSourceParseException(R.string.lsposed_error_release_urls_only)
        }
        return when {
            parts.getOrNull(3) == "tag" && parts.size == 5 ->
                Parsed(LsposedSourceKind.GITHUB_RELEASE, repositoryUrl, parts[4])
            parts.getOrNull(3) == "download" && parts.size == 6 ->
                Parsed(LsposedSourceKind.GITHUB_ASSET, repositoryUrl, parts[4], parts[5])
            else -> throw LsposedSourceParseException(R.string.lsposed_error_repository_release_asset_url)
        }
    }

    fun normalizeDigest(value: String?): String? {
        val parts = value?.trim()?.split(':', limit = 2) ?: return null
        if (parts.size == 2 && !parts[0].equals("sha256", ignoreCase = true)) return null
        return parts.last()
            .trim()
            .lowercase(Locale.ROOT)
            .takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    }

    private fun decodePathSegment(value: String): String =
        // URLDecoder treats '+' as form data, but it is a literal character in URL paths.
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
}

class LsposedRepository(
    private val app: Application,
    private val dao: LsposedModuleDao,
    private val api: ReVancedAPI,
    private val http: HttpService,
    private val pm: PM,
    private val rootInstaller: RootInstaller,
) {
    val modules: Flow<List<LsposedModule>> = dao.observeAll()

    suspend fun frameworkState(): LsposedFrameworkState = withContext(Dispatchers.IO) {
        val root = rootInstaller.hasRootAccess(forceRefresh = true)
        val installed = root && rootInstaller.execute(FRAMEWORK_RUNNING_CHECK).isSuccess
        LsposedFrameworkState(root, installed)
    }

    suspend fun openManager(): Boolean = withContext(Dispatchers.IO) {
        if (!rootInstaller.hasRootAccess(forceRefresh = true)) return@withContext false
        val moduleDir = findFrameworkModule()
        val officialLaunch =
            "am start -c ${shellQuote(OFFICIAL_MANAGER_CATEGORY)} " +
                shellQuote(OFFICIAL_MANAGER_COMPONENT)
        val command = moduleDir?.let {
            val actionScript = shellQuote("$it/action.sh")
            "if [ -f $actionScript ]; then sh $actionScript || $officialLaunch; " +
                "else $officialLaunch; fi"
        } ?: officialLaunch
        rootInstaller.execute(command).isSuccess
    }

    @Suppress("DEPRECATION")
    fun openModuleSettings(packageName: String): Boolean {
        val settingsQuery = Intent(Intent.ACTION_MAIN)
            .addCategory(MODULE_SETTINGS_CATEGORY)
            .setPackage(packageName)
        val settingsIntent = app.packageManager
            .queryIntentActivities(settingsQuery, PackageManager.MATCH_ALL)
            .asSequence()
            .mapNotNull { it.activityInfo }
            .firstOrNull { activity ->
                activity.packageName == packageName && activity.exported
            }
            ?.let { activity ->
                Intent(settingsQuery)
                    .setComponent(ComponentName(activity.packageName, activity.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        val directSettingsIntent = Intent(settingsQuery)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launcherIntent = app.packageManager
            .getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return listOfNotNull(settingsIntent, directSettingsIntent, launcherIntent).any { intent ->
            runCatching {
                app.startActivity(intent)
                true
            }.getOrDefault(false)
        }
    }

    private suspend fun findFrameworkModule(): String? {
        val result = rootInstaller.execute(FRAMEWORK_MODULE_DISCOVERY)
        if (!result.isSuccess) return null
        return result.out
            .asSequence()
            .map(String::trim)
            .firstOrNull { it.matches(FRAMEWORK_MODULE_PATH) }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun parseSource(raw: String): LsposedSourceParser.Parsed =
        try {
            LsposedSourceParser.parse(raw)
        } catch (error: LsposedSourceParseException) {
            throw IllegalArgumentException(app.getString(error.messageRes), error)
        }

    suspend fun resolveRemoteSource(raw: String): List<LsposedReleaseAsset> {
        val parsed = parseSource(raw)
        val release = when (parsed.kind) {
            LsposedSourceKind.GITHUB_REPOSITORY -> latestStableRelease(parsed.repositoryUrl)
            LsposedSourceKind.GITHUB_RELEASE,
            LsposedSourceKind.GITHUB_ASSET -> api.getRepositoryReleaseByTag(
                parsed.repositoryUrl,
                requireNotNull(parsed.releaseTag),
            ).successOrThrow(app.getString(R.string.lsposed_github_release, parsed.releaseTag))
            LsposedSourceKind.LOCAL_FILE -> null
        } ?: throw IllegalStateException(app.getString(R.string.lsposed_error_release_not_found))
        check(!release.draft) { app.getString(R.string.lsposed_error_draft_release) }
        val assets = release.assets.filter {
            it.name.endsWith(".apk", true) &&
                (parsed.assetName == null || it.name == parsed.assetName)
        }
        check(assets.isNotEmpty()) { app.getString(R.string.lsposed_error_no_apk_assets) }
        return assets.map {
            LsposedReleaseAsset(raw.trim(), parsed.kind, parsed.repositoryUrl, release.tagName, it)
        }
    }

    suspend fun prepareRemote(asset: LsposedReleaseAsset): PendingLsposedModule =
        withContext(Dispatchers.IO) {
            val tempDir = File(app.cacheDir, "lsposed_modules").apply { mkdirs() }
            val file = File(tempDir, asset.asset.name)
            try {
                http.downloadToFile(file, builder = { url(asset.asset.downloadUrl) })
                val digest = LsposedSourceParser.normalizeDigest(asset.asset.digest)
                if (digest != null) verifySha256(file, digest)
                inspectApk(
                    file = file,
                    sourceKind = asset.sourceKind,
                    sourceReference = asset.sourceReference,
                    releaseTag = asset.releaseTag,
                    assetName = asset.asset.name,
                    assetDigest = digest,
                    checksumPublished = digest != null,
                    temporary = true,
                )
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }

    suspend fun prepareLocal(uri: Uri): PendingLsposedModule = withContext(Dispatchers.IO) {
        val dir = File(app.cacheDir, "lsposed_modules").apply { mkdirs() }
        val incoming = File(dir, "selected-${System.currentTimeMillis()}.apk")
        try {
            app.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { app.getString(R.string.lsposed_error_open_apk) }
                incoming.outputStream().use(input::copyTo)
            }
            inspectApk(
                incoming,
                LsposedSourceKind.LOCAL_FILE,
                uri.toString(),
                checksumPublished = false,
                temporary = true,
            )
        } catch (error: Exception) {
            incoming.delete()
            throw error
        }
    }

    suspend fun persistLocalApk(
        pending: PendingLsposedModule,
    ): PendingLsposedModule = withContext(Dispatchers.IO) {
        if (pending.sourceKind != LsposedSourceKind.LOCAL_FILE || !pending.temporary) {
            return@withContext pending
        }
        val dir = File(app.filesDir, "lsposed_modules").apply { mkdirs() }
        val target = File(dir, "${pending.packageName}.apk")
        pending.file.copyTo(target, overwrite = true)
        pending.copy(file = target, temporary = false)
    }

    suspend fun prepareStored(module: LsposedModule): PendingLsposedModule {
        require(module.sourceKind == LsposedSourceKind.LOCAL_FILE.name) {
            app.getString(R.string.lsposed_error_local_reinstall_only)
        }
        val file = File(File(app.filesDir, "lsposed_modules"), "${module.packageName}.apk")
        check(file.exists()) { app.getString(R.string.lsposed_error_saved_apk_missing) }
        return inspectApk(
            file,
            LsposedSourceKind.LOCAL_FILE,
            module.sourceReference,
            checksumPublished = false,
            temporary = false,
        )
    }

    suspend fun checkForUpdate(module: LsposedModule): LsposedModule {
        require(module.sourceKind != LsposedSourceKind.LOCAL_FILE.name)
        val parsed = parseSource(module.sourceReference)
        val latestRelease = latestStableRelease(parsed.repositoryUrl)
        val candidates = updateAssets(module, parsed, latestRelease)
        val asset = candidates.firstOrNull { it.asset.name == module.assetName }
            ?: candidates.singleOrNull()
        val latestDigest = LsposedSourceParser.normalizeDigest(asset?.asset?.digest)
        val latestTag = latestRelease.tagName
        val sameRelease = latestTag == module.releaseTag
        val releaseIsNewer = !sameRelease && isReleaseNewer(module, parsed, latestRelease)
        val assetChanged = sameRelease && (
            asset == null ||
                (latestDigest != null && latestDigest != module.assetDigest)
        )
        val updated = module.copy(
            latestVersion = if (releaseIsNewer) latestTag else module.releaseTag,
            latestAssetDigest = if (releaseIsNewer || sameRelease) {
                latestDigest
            } else {
                module.assetDigest
            },
            lastUpdateCheck = System.currentTimeMillis(),
            updateAvailable = releaseIsNewer || assetChanged,
        )
        dao.upsert(updated)
        return updated
    }

    suspend fun recordInstalled(pending: PendingLsposedModule) {
        dao.upsert(
            LsposedModule(
                packageName = pending.packageName,
                displayName = pending.displayName,
                installedVersion = pending.versionName,
                installedVersionCode = pending.versionCode,
                sourceKind = pending.sourceKind.name,
                sourceReference = pending.sourceReference,
                releaseTag = pending.releaseTag,
                assetName = pending.assetName,
                assetDigest = pending.assetDigest,
                signingFingerprint = pending.signingFingerprint,
            )
        )
    }

    fun installedPackageState(packageName: String): LsposedInstalledPackageState? {
        val installed = pm.getPackageInfo(packageName) ?: return null
        val signature = runCatching { pm.getSignature(packageName) }.getOrNull() ?: return null
        return LsposedInstalledPackageState(
            versionCode = PackageInfoCompat.getLongVersionCode(installed),
            signingFingerprint = signingFingerprint(signature.toByteArray()),
            lastUpdateTime = installed.lastUpdateTime,
        )
    }

    fun installedPackageMatches(pending: PendingLsposedModule): Boolean {
        val installed = installedPackageState(pending.packageName) ?: return false
        return installed.versionCode == pending.versionCode &&
            installed.signingFingerprint == pending.signingFingerprint
    }

    fun installedPackageChangedSince(
        pending: PendingLsposedModule,
        previousState: LsposedInstalledPackageState?,
    ): Boolean {
        val installed = installedPackageState(pending.packageName) ?: return false
        if (installed.versionCode != pending.versionCode ||
            installed.signingFingerprint != pending.signingFingerprint
        ) return false
        return installed != previousState
    }

    suspend fun forget(packageName: String) = withContext(Dispatchers.IO) {
        val storedApk = File(File(app.filesDir, "lsposed_modules"), "$packageName.apk")
        check(!storedApk.exists() || storedApk.delete()) {
            app.getString(R.string.lsposed_error_delete_saved_apk)
        }
        dao.delete(packageName)
    }

    suspend fun clearStoredLocalModules() = withContext(Dispatchers.IO) {
        val directory = File(app.filesDir, "lsposed_modules")
        check(!directory.exists() || directory.deleteRecursively()) {
            app.getString(R.string.lsposed_error_delete_saved_apk)
        }
        dao.deleteBySourceKind(LsposedSourceKind.LOCAL_FILE.name)
    }

    fun deleteTemporary(pending: PendingLsposedModule) {
        if (pending.temporary) pending.file.delete()
    }

    suspend fun resolveUpdateAssets(
        module: LsposedModule,
    ): List<LsposedReleaseAsset> {
        val parsed = parseSource(module.sourceReference)
        val release = latestStableRelease(parsed.repositoryUrl)
        return updateAssets(module, parsed, release)
    }

    private fun updateAssets(
        module: LsposedModule,
        parsed: LsposedSourceParser.Parsed,
        release: GitHubRelease,
    ): List<LsposedReleaseAsset> {
        val assets = release.assets.filter { it.name.endsWith(".apk", true) }
        check(assets.isNotEmpty()) { app.getString(R.string.lsposed_error_latest_no_apk_assets) }
        return assets.map {
            LsposedReleaseAsset(
                sourceReference = module.sourceReference,
                sourceKind = LsposedSourceKind.valueOf(module.sourceKind),
                repositoryUrl = parsed.repositoryUrl,
                releaseTag = release.tagName,
                asset = it,
            )
        }
    }

    private suspend fun isReleaseNewer(
        module: LsposedModule,
        parsed: LsposedSourceParser.Parsed,
        latestRelease: GitHubRelease,
    ): Boolean {
        val installedTag = module.releaseTag ?: return true
        val installedReleaseResponse = api.getRepositoryReleaseByTag(parsed.repositoryUrl, installedTag)
        if (installedReleaseResponse is APIResponse.Error &&
            installedReleaseResponse.error.code == HttpStatusCode.NotFound
        ) return true
        val installedRelease = installedReleaseResponse
            .successOrThrow(app.getString(R.string.lsposed_github_release, installedTag))
        val latestTimestamp = releaseTimestamp(latestRelease)
        val installedTimestamp = releaseTimestamp(installedRelease)
        return when {
            latestTimestamp != null && installedTimestamp != null ->
                latestTimestamp > installedTimestamp
            parsed.kind == LsposedSourceKind.GITHUB_REPOSITORY -> true
            else -> false
        }
    }

    private fun releaseTimestamp(release: GitHubRelease): Instant? =
        (release.publishedAt ?: release.createdAt)?.let { timestamp ->
            runCatching { Instant.parse(timestamp) }.getOrNull()
        }

    private suspend fun latestStableRelease(repo: String): GitHubRelease =
        api.getRepositoryReleaseHistory(repo, prerelease = false, limit = 20)
            .successOrThrow(app.getString(R.string.lsposed_github_releases))
            .firstOrNull()
            ?: throw IllegalStateException(app.getString(R.string.lsposed_error_no_stable_release))

    private fun inspectApk(
        file: File,
        sourceKind: LsposedSourceKind,
        sourceReference: String,
        releaseTag: String? = null,
        assetName: String? = null,
        assetDigest: String? = null,
        checksumPublished: Boolean,
        temporary: Boolean,
    ): PendingLsposedModule {
        val info = pm.getPackageInfo(file, includeSigning = true)
            ?: throw IllegalArgumentException(app.getString(R.string.lsposed_error_invalid_apk))
        val xposed = info.applicationInfo?.metaData?.get("xposedmodule")
        require(xposed == true || xposed?.toString().equals("true", true)) {
            app.getString(R.string.lsposed_error_not_module)
        }
        val signature = pm.getSignature(info)
            ?: throw IllegalArgumentException(app.getString(R.string.lsposed_error_no_certificate))
        val fingerprint = signingFingerprint(signature.toByteArray())
        return PendingLsposedModule(
            file = file,
            packageName = info.packageName,
            displayName = with(pm) { info.label() },
            versionName = info.versionName ?: app.getString(R.string.lsposed_unknown_version),
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            signingFingerprint = fingerprint,
            sourceKind = sourceKind,
            sourceReference = sourceReference,
            releaseTag = releaseTag,
            assetName = assetName,
            assetDigest = assetDigest,
            checksumPublished = checksumPublished,
            temporary = temporary,
        )
    }

    private fun verifySha256(file: File, expected: String) {
        val actual = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        check(actual == expected) { app.getString(R.string.lsposed_error_checksum_failed) }
    }

    private fun signingFingerprint(certificate: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(certificate)
            .joinToString(":") { "%02X".format(it) }

    companion object {
        const val MODULE_SETTINGS_CATEGORY = "de.robv.android.xposed.category.MODULE_SETTINGS"
        private const val OFFICIAL_MANAGER_CATEGORY = "org.lsposed.manager.LAUNCH_MANAGER"
        private const val OFFICIAL_MANAGER_COMPONENT = "com.android.shell/.BugreportWarningActivity"
        private const val FRAMEWORK_RUNNING_CHECK = "pidof lspd >/dev/null 2>&1"
        private val FRAMEWORK_MODULE_PATH = Regex("^/data/adb/modules/[^/]+$")
        private val FRAMEWORK_MODULE_DISCOVERY = """
            pid="@(pidof lspd 2>/dev/null | awk '{print @1}')"
            if [ -n "@pid" ]; then
              {
                readlink "/proc/@pid/exe" 2>/dev/null
                sed -n 's#.*\(/data/adb/modules/[^/]*/[^ ]*\).*#\1#p' "/proc/@pid/maps" 2>/dev/null
              } | while IFS= read -r mapped_path; do
                case "@mapped_path" in
                  /data/adb/modules/*/*)
                    relative="@{mapped_path#/data/adb/modules/}"
                    module_dir="/data/adb/modules/@{relative%%/*}"
                    ;;
                  *) continue ;;
                esac
                if [ -f "@module_dir/module.prop" ] &&
                    [ ! -e "@module_dir/disable" ] &&
                    [ ! -e "@module_dir/remove" ] &&
                    { [ -f "@module_dir/action.sh" ] ||
                      [ -f "@module_dir/daemon.apk" ] ||
                      [ -f "@module_dir/manager.apk" ]; }; then
                    echo "@module_dir"
                    break
                fi
              done
            fi
        """.trimIndent().replace('@', '$')
    }
}

