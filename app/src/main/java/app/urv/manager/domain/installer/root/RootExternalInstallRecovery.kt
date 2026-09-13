package app.urv.manager.domain.installer.root

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import app.urv.manager.domain.installer.RootInstaller
import app.urv.manager.util.PM
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class PendingRootExternalInstall(
    val requestId: String,
    val transactionId: String,
    val packageName: String,
    val userId: Int,
    val stockSha256: String,
    val patchedSha256: String,
    val committedAtEpochMs: Long,
    val baselineVersionCode: Long?,
    val baselineLastUpdateTime: Long?,
    val cleanupFilePath: String? = null,
    val grantedUri: String? = null,
    val restoreMountAfterPackageChange: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    fun stateIdentity() = RootMountStateIdentity(
        transactionId = transactionId,
        packageName = packageName,
        userId = userId,
        stockSha256 = stockSha256,
        patchedSha256 = patchedSha256,
        committedAtEpochMs = committedAtEpochMs
    )
}

internal class RootExternalInstallRecoveryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun write(record: PendingRootExternalInstall) {
        check(
            preferences.edit()
                .putString(record.requestId, json.encodeToString(record))
                .commit()
        ) { "Failed to persist pending root installer state" }
    }

    fun read(requestId: String): PendingRootExternalInstall? =
        preferences.getString(requestId, null)?.let { encoded ->
            runCatching { json.decodeFromString<PendingRootExternalInstall>(encoded) }
                .getOrNull()
        }

    fun readAll(): List<PendingRootExternalInstall> = preferences.all.keys.mapNotNull(::read)

    fun remove(requestId: String) {
        preferences.edit().remove(requestId).commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "root_external_install_recovery"
        val json = Json { ignoreUnknownKeys = true }
    }
}

internal object RootExternalInstallActivityRegistry {
    private val activeRequestIds = ConcurrentHashMap.newKeySet<String>()

    fun register(requestId: String) {
        activeRequestIds += requestId
    }

    fun unregister(requestId: String) {
        activeRequestIds -= requestId
    }

    fun isActive(requestId: String): Boolean = requestId in activeRequestIds
}

internal fun RootMountSuspension.enableProcessDeathRecovery(
    context: Context,
    cleanupFile: File? = null,
    grantedUri: Uri? = null,
    restoreMountAfterPackageChange: Boolean = false
): String {
    val packageInfo = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageName, 0)
    }.getOrElse { error ->
        throw IllegalStateException("Installed package state is unavailable", error)
    }
    val versionCode = packageInfo.let { info ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }
    return enableProcessDeathRecovery(
        context = context,
        baselineVersionCode = versionCode,
        baselineLastUpdateTime = packageInfo.lastUpdateTime,
        cleanupFile = cleanupFile,
        grantedUri = grantedUri,
        restoreMountAfterPackageChange = restoreMountAfterPackageChange
    )
}

internal suspend fun recoverPendingRootExternalInstall(
    context: Context,
    requestId: String,
    rootInstaller: RootInstaller,
    rootMountCoordinator: RootMountTransactionCoordinator,
    pm: PM,
    waitForPackageChange: Boolean = true
): Boolean = withContext(NonCancellable + Dispatchers.IO) {
    val store = RootExternalInstallRecoveryStore(context)
    val record = store.read(requestId) ?: return@withContext false
    if (!rootInstaller.hasRootAccess()) return@withContext false

    var installedChanged = packageChangedSince(record, pm)
    if (waitForPackageChange && !installedChanged) {
        val deadline = System.currentTimeMillis() + EXTERNAL_RECOVERY_GRACE_MS
        do {
            delay(EXTERNAL_RECOVERY_POLL_INTERVAL_MS)
            installedChanged = packageChangedSince(record, pm)
        } while (!installedChanged && System.currentTimeMillis() < deadline)
    }

    val suspension = RootMountSuspension(
        rootMountCoordinator = rootMountCoordinator,
        packageName = record.packageName,
        userId = record.userId,
        stateIdentity = record.stateIdentity(),
        recoveryStore = store,
        recoveryRequestId = record.requestId
    )
    if (record.restoreMountAfterPackageChange || !installedChanged) {
        suspension.restoreMount()
    } else {
        suspension.retireMount()
    }
    cleanupRecoveredInstall(context, record)
    true
}

/**
 * Recovers eligible inactive installs and returns the delay until recovery should run again.
 */
internal suspend fun recoverAbandonedRootExternalInstalls(
    context: Context,
    rootInstaller: RootInstaller,
    rootMountCoordinator: RootMountTransactionCoordinator,
    pm: PM
): Long? {
    val store = RootExternalInstallRecoveryStore(context)
    val now = System.currentTimeMillis()
    val records = store.readAll().filterNot { record ->
        RootExternalInstallActivityRegistry.isActive(record.requestId)
    }
    val retryAfterMs = records.asSequence()
        .map { record ->
            record.createdAtEpochMs + ABANDONED_INSTALL_MIN_AGE_MS - now
        }
        .filter { it > 0L }
        .minOrNull()
    val eligibleRecords = records.filter { record ->
        record.createdAtEpochMs + ABANDONED_INSTALL_MIN_AGE_MS <= now
    }
    if (eligibleRecords.isNotEmpty() && rootInstaller.hasRootAccess()) {
        eligibleRecords.forEach { record ->
            if (RootExternalInstallActivityRegistry.isActive(record.requestId)) return@forEach
            runCatching {
                recoverPendingRootExternalInstall(
                    context,
                    record.requestId,
                    rootInstaller,
                    rootMountCoordinator,
                    pm
                )
            }
        }
    }
    val abandonedRetryAfterMs = ABANDONED_INSTALL_RETRY_INTERVAL_MS.takeIf {
        eligibleRecords.any { record ->
            !RootExternalInstallActivityRegistry.isActive(record.requestId) &&
                store.read(record.requestId) != null
        }
    }
    return sequenceOf(retryAfterMs, abandonedRetryAfterMs)
        .filterNotNull()
        .minOrNull()
}

private fun packageChangedSince(record: PendingRootExternalInstall, pm: PM): Boolean {
    val current = pm.getPackageInfo(record.packageName) ?: return true
    val versionCode = pm.getVersionCode(current)
    return record.baselineVersionCode == null ||
        versionCode != record.baselineVersionCode ||
        current.lastUpdateTime != record.baselineLastUpdateTime
}

private fun cleanupRecoveredInstall(context: Context, record: PendingRootExternalInstall) {
    record.grantedUri?.let { encoded ->
        runCatching {
            context.revokeUriPermission(
                Uri.parse(encoded),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }
    record.cleanupFilePath?.let { path -> runCatching { File(path).delete() } }
}

internal fun newRootExternalInstallRecoveryId(): String = UUID.randomUUID().toString()

private const val EXTERNAL_RECOVERY_GRACE_MS = 10_000L
private const val EXTERNAL_RECOVERY_POLL_INTERVAL_MS = 250L
private const val ABANDONED_INSTALL_MIN_AGE_MS = 10L * 60L * 1_000L
private const val ABANDONED_INSTALL_RETRY_INTERVAL_MS = 30_000L
