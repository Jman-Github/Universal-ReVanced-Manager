package app.revanced.manager.domain.installer

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.annotation.StringRes
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.domain.manager.InstallerPreferenceTokens
import app.universal.revanced.manager.R
import java.io.File
import java.io.IOException
import java.util.UUID

class InstallerManager(
    private val app: Application,
    private val prefs: PreferencesManager,
    private val rootInstaller: RootInstaller
) {
    private val packageManager: PackageManager = app.packageManager
    private val authority = InstallerFileProvider.authority(app)
    private val shareDir: File = File(app.cacheDir, SHARE_DIR).apply { mkdirs() }
    private val dummyUri: Uri = InstallerFileProvider.buildUri(app, "dummy.apk")
    private val defaultInstallerComponent: ComponentName? by lazy { resolveDefaultInstallerComponent() }
    private val defaultInstallerPackage: String? get() = defaultInstallerComponent?.packageName

    fun listEntries(target: InstallTarget, includeNone: Boolean): List<Entry> {
        val entries = mutableListOf<Entry>()
        val defaultIcon = loadInstallerIcon(defaultInstallerPackage)

        entries += Entry(
            token = Token.Internal,
            label = app.getString(R.string.installer_internal_name),
            description = app.getString(R.string.installer_internal_description),
            availability = Availability(true),
            icon = defaultIcon
        )

        val activityEntries = queryInstallerActivities()
            .filter(::isInstallerCandidate)
            .distinctBy { it.activityInfo.packageName }
            .mapNotNull { info ->
                val component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
                if (isDefaultComponent(component)) return@mapNotNull null
                val label = info.loadLabel(packageManager)?.toString()
                    ?: info.activityInfo.packageName
                if (isExcludedDuplicate(component.packageName, label)) return@mapNotNull null
                val description = component.packageName
                Entry(
                    token = Token.Component(component),
                    label = label,
                    description = description,
                    availability = availabilityFor(Token.Component(component), target),
                    icon = loadInstallerIcon(component)
                )
            }
            .sortedBy { it.label.lowercase() }

        entries += activityEntries

        if (includeNone) {
            entries += Entry(
                token = Token.None,
                label = app.getString(R.string.installer_option_none),
                description = app.getString(R.string.installer_none_description),
                availability = Availability(true),
                icon = null
            )
        }

        return entries
    }

    fun parseToken(value: String?): Token = when (value) {
        InstallerPreferenceTokens.ROOT,
        InstallerPreferenceTokens.SYSTEM -> Token.Internal
        InstallerPreferenceTokens.NONE -> Token.None
        InstallerPreferenceTokens.INTERNAL, null, "" -> Token.Internal
        else -> ComponentName.unflattenFromString(value)?.let { component ->
            if (isDefaultComponent(component)) Token.Internal else Token.Component(component)
        } ?: Token.Internal
    }

    fun tokenToPreference(token: Token): String = when (token) {
        Token.Internal -> InstallerPreferenceTokens.INTERNAL
        Token.Root -> InstallerPreferenceTokens.INTERNAL
        Token.None -> InstallerPreferenceTokens.NONE
        is Token.Component -> token.componentName.flattenToString()
    }

    fun getPrimaryToken(): Token = parseToken(prefs.installerPrimary.getBlocking())

    fun getFallbackToken(): Token = parseToken(prefs.installerFallback.getBlocking())

    suspend fun updatePrimaryToken(token: Token) {
        prefs.installerPrimary.update(tokenToPreference(token))
    }

    suspend fun updateFallbackToken(token: Token) {
        prefs.installerFallback.update(tokenToPreference(token))
    }

    fun resolvePlan(
        target: InstallTarget,
        sourceFile: File,
        expectedPackage: String,
        sourceLabel: String?
    ): InstallPlan {
        val sequence = buildSequence(target)
        sequence.forEach { token ->
            createPlan(token, target, sourceFile, expectedPackage, sourceLabel)?.let { return it }
        }

        // Should never happen, fallback to internal install.
        return InstallPlan.Internal(target)
    }

    fun cleanup(plan: InstallPlan.External) {
        runCatching {
            app.revokeUriPermission(plan.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        plan.sharedFile.delete()
    }

    private fun createPlan(
        token: Token,
        target: InstallTarget,
        sourceFile: File,
        expectedPackage: String,
        sourceLabel: String?
    ): InstallPlan? {
        return when (token) {
            Token.Internal -> InstallPlan.Internal(target)
            Token.None -> null
            Token.Root -> if (availabilityFor(Token.Root, target).available) {
                InstallPlan.Root(target)
            } else null

            is Token.Component -> {
                if (!availabilityFor(token, target).available) {
                    null
                } else {
                    val shared = copyToShareDir(sourceFile)
                    val uri = InstallerFileProvider.buildUri(app, shared)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, APK_MIME)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        clipData = ClipData.newRawUri("APK", uri)
                        putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                        putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, app.packageName)
                        component = token.componentName
                    }
                    app.grantUriPermission(
                        token.componentName.packageName,
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    InstallPlan.External(
                        target = target,
                        intent = intent,
                        sharedFile = shared,
                        uri = uri,
                        expectedPackage = expectedPackage,
                        installerLabel = resolveLabel(token.componentName),
                        sourceLabel = sourceLabel,
                        token = token
                    )
                }
            }
        }
    }

    private fun resolveLabel(componentName: ComponentName): String =
        runCatching {
            val activityInfo: ActivityInfo = packageManager.getActivityInfo(componentName, 0)
            activityInfo.loadLabel(packageManager)?.toString() ?: componentName.packageName
        }.getOrDefault(componentName.packageName)

    private fun copyToShareDir(source: File): File {
        val target = File(shareDir, "${UUID.randomUUID()}.apk")
        try {
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (error: IOException) {
            target.delete()
            throw error
        }
        return target
    }

    private fun buildSequence(target: InstallTarget): List<Token> {
        val tokens = mutableListOf<Token>()
        val primary = parseToken(prefs.installerPrimary.getBlocking())
        val fallback = parseToken(prefs.installerFallback.getBlocking())

        fun add(token: Token) {
            if (token == Token.None) return
            if (token in tokens) return
            if (!availabilityFor(token, target).available) return
            tokens += token
        }

        add(primary)
        add(fallback)

        if (Token.Internal !in tokens) add(Token.Internal)

        return tokens
    }

    private fun availabilityFor(token: Token, target: InstallTarget): Availability = when (token) {
        Token.Internal -> Availability(true)
        Token.None -> Availability(true)

        Token.Root -> if (!target.supportsRoot) {
            Availability(false, R.string.installer_status_not_supported)
        } else if (!rootInstaller.hasRootAccess()) {
            Availability(false, R.string.installer_status_requires_root)
        } else Availability(true)

        is Token.Component -> {
            if (isComponentAvailable(token.componentName)) Availability(true)
            else Availability(false, R.string.installer_status_not_supported)
        }
    }

    private fun isComponentAvailable(componentName: ComponentName): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(dummyUri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            component = componentName
        }
        return intent.resolveActivity(packageManager) != null
    }

    private fun queryInstallerActivities() =
        packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(dummyUri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            PackageManager.MATCH_DEFAULT_ONLY
        )

    private fun resolveDefaultInstallerComponent(): ComponentName? {
        val resolveInfo = packageManager.resolveActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(dummyUri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            PackageManager.MATCH_DEFAULT_ONLY
        ) ?: return null
        val activityInfo = resolveInfo.activityInfo ?: return null
        if (!isInstallerCandidate(resolveInfo)) return null
        return ComponentName(activityInfo.packageName, activityInfo.name)
    }

    private fun isDefaultComponent(componentName: ComponentName): Boolean =
        defaultInstallerPackage == componentName.packageName

    private fun loadInstallerIcon(componentName: ComponentName): Drawable? =
        loadInstallerIcon(componentName.packageName)

    private fun loadInstallerIcon(packageName: String?): Drawable? =
        packageName?.let { runCatching { packageManager.getApplicationIcon(it) }.getOrNull() }

    private fun isExcludedDuplicate(packageName: String, label: String): Boolean =
        packageName == AOSP_INSTALLER_PACKAGE &&
            label.equals(AOSP_INSTALLER_LABEL, ignoreCase = true)

    private fun isInstallerCandidate(info: ResolveInfo): Boolean {
        if (!info.activityInfo.exported) return false
        val requestedPermissions = runCatching {
            packageManager.getPackageInfo(
                info.activityInfo.packageName,
                PackageManager.GET_PERMISSIONS
            ).requestedPermissions
        }.getOrNull() ?: return false

        return requestedPermissions.any {
            it == Manifest.permission.REQUEST_INSTALL_PACKAGES ||
                it == Manifest.permission.INSTALL_PACKAGES
        }
    }

    data class Entry(
        val token: Token,
        val label: String,
        val description: String?,
        val availability: Availability,
        val icon: Drawable?
    )

    data class Availability(
        val available: Boolean,
        @StringRes val reason: Int? = null
    )

    sealed class Token {
        object Internal : Token()
        object Root : Token()
        object None : Token()
        data class Component(val componentName: ComponentName) : Token()
    }

    sealed class InstallPlan {
        data class Internal(val target: InstallTarget) : InstallPlan()
        data class Root(val target: InstallTarget) : InstallPlan()
        data class External(
            val target: InstallTarget,
            val intent: Intent,
            val sharedFile: File,
            val uri: Uri,
            val expectedPackage: String,
            val installerLabel: String,
            val sourceLabel: String?,
            val token: Token
        ) : InstallPlan()
    }

    enum class InstallTarget(val supportsRoot: Boolean) {
        PATCHER(true),
        SAVED_APP(true),
        MANAGER_UPDATE(false)
    }

    companion object {
        private const val APK_MIME = "application/vnd.android.package-archive"
        internal const val SHARE_DIR = "installer_share"
        private const val AOSP_INSTALLER_PACKAGE = "com.google.android.packageinstaller"
        private const val AOSP_INSTALLER_LABEL = "Package installer"
    }

    fun formatFailureHint(status: Int, extraMessage: String?): String? {
        val normalizedExtra = extraMessage?.takeIf { it.isNotBlank() }
        val base = when (status) {
            PackageInstaller.STATUS_FAILURE -> app.getString(R.string.installer_hint_generic)
            PackageInstaller.STATUS_FAILURE_ABORTED -> app.getString(R.string.installer_hint_aborted)
            PackageInstaller.STATUS_FAILURE_BLOCKED -> app.getString(R.string.installer_hint_blocked)
            PackageInstaller.STATUS_FAILURE_CONFLICT -> app.getString(R.string.installer_hint_conflict)
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> app.getString(R.string.installer_hint_incompatible)
            PackageInstaller.STATUS_FAILURE_INVALID -> app.getString(R.string.installer_hint_invalid)
            PackageInstaller.STATUS_FAILURE_STORAGE -> app.getString(R.string.installer_hint_storage)
            PackageInstaller.STATUS_FAILURE_TIMEOUT -> app.getString(R.string.installer_hint_timeout)
            else -> null
        }

        return when {
            base == null -> normalizedExtra
            normalizedExtra == null -> base
            else -> app.getString(R.string.installer_hint_with_reason, base, normalizedExtra)
        }
    }
}
