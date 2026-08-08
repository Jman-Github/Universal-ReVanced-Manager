/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.urv.manager.network.downloader

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File
import java.util.zip.ZipFile

// Code adapted from Morphe, see third-party/NOTICE for more information
// https://github.com/MorpheApp/morphe-manager/pull/797
object ApkDownloadHelperContract {
    const val ACTION_DOWNLOAD_ORIGINAL_APK =
        "app.morphe.manager.action.DOWNLOAD_ORIGINAL_APK"
    const val PROTOCOL_VERSION = 1

    const val EXTRA_PROTOCOL_VERSION = "app.morphe.manager.extra.PROTOCOL_VERSION"
    const val EXTRA_CALLER_PACKAGE = "app.morphe.manager.extra.CALLER_PACKAGE"
    const val EXTRA_PACKAGE_NAME = "app.morphe.manager.extra.PACKAGE_NAME"
    const val EXTRA_APP_NAME = "app.morphe.manager.extra.APP_NAME"
    const val EXTRA_VERSION_NAME = "app.morphe.manager.extra.VERSION_NAME"
    const val EXTRA_VERSION_CODES = "app.morphe.manager.extra.VERSION_CODES"
    const val EXTRA_COMPATIBLE_VERSION_NAMES =
        "app.morphe.manager.extra.COMPATIBLE_VERSION_NAMES"
    const val EXTRA_SUPPORTED_ABIS = "app.morphe.manager.extra.SUPPORTED_ABIS"
    const val EXTRA_FILE_TYPE = "app.morphe.manager.extra.FILE_TYPE"
    const val EXTRA_ALLOW_SPLIT_ARCHIVE =
        "app.morphe.manager.extra.ALLOW_SPLIT_ARCHIVE"
    const val EXTRA_STOCK_INSTALL_REQUIRED =
        "app.morphe.manager.extra.STOCK_INSTALL_REQUIRED"
    const val EXTRA_FALLBACK_WEB_URL = "app.morphe.manager.extra.FALLBACK_WEB_URL"

    const val EXTRA_RESULT_USE_INSTALLED_APP =
        "app.morphe.manager.extra.RESULT_USE_INSTALLED_APP"
    const val EXTRA_RESULT_PACKAGE_NAME = "app.morphe.manager.extra.RESULT_PACKAGE_NAME"
    const val EXTRA_RESULT_VERSION_NAME = "app.morphe.manager.extra.RESULT_VERSION_NAME"

    const val FILE_TYPE_APK = "apk"
    const val FILE_TYPE_APKM = "apkm"
    const val FILE_TYPE_APKS = "apks"
    const val FILE_TYPE_XAPK = "xapk"

    data class Helper(
        val componentName: ComponentName,
        val label: String,
        val version: String?
    ) {
        val packageName: String get() = componentName.packageName
        val id: String get() = "apk-helper:${componentName.flattenToString()}"
    }

    fun findHelpers(context: Context): List<Helper> {
        val intent = Intent(ACTION_DOWNLOAD_ORIGINAL_APK)
            .addCategory(Intent.CATEGORY_DEFAULT)
        val packageManager = context.packageManager
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(
                    PackageManager.MATCH_DEFAULT_ONLY.toLong()
                )
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }

        return resolved.mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            val version = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(
                        activity.packageName,
                        PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(activity.packageName, 0)
                }.versionName
            }.getOrNull()
            Helper(
                componentName = ComponentName(activity.packageName, activity.name),
                label = info.loadLabel(packageManager).toString(),
                version = version
            )
        }.distinctBy { it.componentName }
            .sortedBy { it.label.lowercase() }
    }

    fun isHelperArchive(file: File, packageInfo: PackageInfo): Boolean {
        val exportedActivities = packageInfo.activities
            .orEmpty()
            .filter { it.exported }
            .mapTo(hashSetOf()) { it.name }
        if (exportedActivities.isEmpty()) return false

        return runCatching {
            ZipFile(file).use archive@ { zip ->
                val manifestEntry = zip.getEntry("AndroidManifest.xml") ?: return@archive false
                zip.getInputStream(manifestEntry).use manifest@ { input ->
                    val manifestBlock = AndroidManifestBlock.load(input)
                    val root = manifestBlock.manifestElement ?: return@manifest false
                    val elements = root.recursiveElements()
                    while (elements.hasNext()) {
                        val activity = elements.next()
                        if (!activity.equalsName("activity") && !activity.equalsName("activity-alias")) {
                            continue
                        }
                        val rawActivityName = AndroidManifestBlock.getAndroidNameValue(activity)
                            ?: continue
                        val activityName = normalizeActivityName(packageInfo.packageName, rawActivityName)
                        if (activityName !in exportedActivities) continue

                        val filters = activity.getElements("intent-filter")
                        while (filters.hasNext()) {
                            val filter = filters.next()
                            var hasDownloadAction = false
                            val actions = filter.getElements("action")
                            while (actions.hasNext()) {
                                if (AndroidManifestBlock.getAndroidNameValue(actions.next()) ==
                                    ACTION_DOWNLOAD_ORIGINAL_APK
                                ) {
                                    hasDownloadAction = true
                                    break
                                }
                            }
                            if (!hasDownloadAction) continue

                            val categories = filter.getElements("category")
                            while (categories.hasNext()) {
                                if (AndroidManifestBlock.getAndroidNameValue(categories.next()) ==
                                    Intent.CATEGORY_DEFAULT
                                ) {
                                    return@manifest true
                                }
                            }
                        }
                    }
                    false
                }
            }
        }.getOrDefault(false)
    }

    private fun normalizeActivityName(packageName: String, activityName: String): String = when {
        activityName.startsWith('.') -> packageName + activityName
        '.' !in activityName -> "$packageName.$activityName"
        else -> activityName
    }

    fun createRequestIntent(
        helper: Helper,
        callerPackage: String,
        packageName: String,
        appName: String,
        versionName: String?,
        versionCodes: LongArray,
        compatibleVersionNames: List<String>,
        supportedAbis: Array<String>,
        fileType: String?,
        allowSplitArchive: Boolean,
        stockInstallRequired: Boolean,
        fallbackWebUrl: String
    ) = Intent(ACTION_DOWNLOAD_ORIGINAL_APK).apply {
        component = helper.componentName
        addCategory(Intent.CATEGORY_DEFAULT)
        putExtra(EXTRA_PROTOCOL_VERSION, PROTOCOL_VERSION)
        putExtra(EXTRA_CALLER_PACKAGE, callerPackage)
        putExtra(EXTRA_PACKAGE_NAME, packageName)
        putExtra(EXTRA_APP_NAME, appName)
        versionName?.let { putExtra(EXTRA_VERSION_NAME, it) }
        putExtra(EXTRA_VERSION_CODES, versionCodes)
        putStringArrayListExtra(
            EXTRA_COMPATIBLE_VERSION_NAMES,
            ArrayList(compatibleVersionNames)
        )
        putExtra(EXTRA_SUPPORTED_ABIS, supportedAbis)
        fileType?.let { putExtra(EXTRA_FILE_TYPE, it) }
        putExtra(EXTRA_ALLOW_SPLIT_ARCHIVE, allowSplitArchive)
        putExtra(EXTRA_STOCK_INSTALL_REQUIRED, stockInstallRequired)
        putExtra(EXTRA_FALLBACK_WEB_URL, fallbackWebUrl)
    }

    fun usesInstalledApp(intent: Intent?): Boolean =
        intent?.getBooleanExtra(EXTRA_RESULT_USE_INSTALLED_APP, false) == true

    fun resultPackageName(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA_RESULT_PACKAGE_NAME)

    fun resultVersionName(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA_RESULT_VERSION_NAME)

    fun resultUri(intent: Intent?): Uri? =
        intent?.data?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }

    fun grantsReadAccess(intent: Intent?): Boolean =
        intent != null &&
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
}
