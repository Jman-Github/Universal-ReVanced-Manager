package app.urv.manager.domain.installer

import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import app.urv.manager.util.InstalledPackageSnapshot
import app.urv.manager.util.PM
import app.urv.manager.util.installedPackageSnapshot
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Code adapted from Morphe, see third-party/NOTICE for more information
// https://github.com/MorpheApp/morphe-manager/blob/b394eb8c4319ff16198193b49e204dfd352d208f/app/src/main/java/app/morphe/manager/domain/installer/SessionInstaller.kt
class SessionInstaller(
    private val app: Application,
    private val pm: PM
) {
    /**
     * Installs [apkFile] directly from app storage using Android's PackageInstaller session API.
     *
     * @throws InstallCancelledException when the user dismisses the system confirmation.
     * @throws SessionDeadException when an OEM kills the session before it can complete.
     */
    suspend fun install(
        apkFile: File,
        expectedPackage: String,
        onUserActionLaunched: () -> Unit = {}
    ): InstallResult {
        val beforeInstall = withContext(Dispatchers.IO) {
            pm.installedPackageSnapshot(expectedPackage, includeHashes = false)
        }
        return try {
            installInternal(apkFile, onUserActionLaunched)
        } catch (_: InstallCancelledException) {
            // Code adapted from Morphe, see third-party/NOTICE for more information
            // https://github.com/MorpheApp/morphe-manager/pull/598
            if (confirmInstallCompleted(apkFile, expectedPackage, beforeInstall)) {
                Log.w(
                    TAG,
                    "Install callback reported cancelled but APK verification succeeded for $expectedPackage"
                )
                InstallResult.Success
            } else {
                throw InstallCancelledException()
            }
        }
    }

    @SuppressLint("RequestInstallPackagesPolicy")
    private suspend fun installInternal(
        apkFile: File,
        onUserActionLaunched: () -> Unit
    ): InstallResult = withContext(Dispatchers.IO) {
        require(apkFile.isFile) { "APK does not exist: ${apkFile.path}" }

        suspendCancellableCoroutine { continuation ->
            val completionClaimed = AtomicBoolean(false)
            val installer = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setInstallReason(PackageManager.INSTALL_REASON_USER)
                setOriginatingUid(Process.myUid())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    setRequestUpdateOwnership(true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
            }

            val sessionId = installer.createSession(params)
            Log.d(TAG, "Created session $sessionId for ${apkFile.name}")
            var registeredReceiver: BroadcastReceiver? = null

            try {
                installer.openSession(sessionId).use { session ->
                    session.openWrite("base.apk", 0, apkFile.length()).use { output ->
                        apkFile.inputStream().use { input -> input.copyTo(output) }
                        session.fsync(output)
                    }

                    val statusIntent = Intent(ACTION_INSTALL_STATUS).apply {
                        `package` = app.packageName
                        putExtra(EXTRA_SESSION_ID, sessionId)
                    }
                    val pendingIntent = PendingIntent.getBroadcast(
                        app,
                        sessionId,
                        statusIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )

                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            if (intent.getIntExtra(EXTRA_SESSION_ID, -1) != sessionId) return

                            val status = intent.getIntExtra(
                                PackageInstaller.EXTRA_STATUS,
                                PackageInstaller.STATUS_FAILURE
                            )
                            val message =
                                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                            Log.d(TAG, "Session $sessionId status=$status message=$message")

                            when (status) {
                                PackageInstaller.STATUS_SUCCESS -> {
                                    unregisterReceiver(this)
                                    continuation.resumeSafely(InstallResult.Success, completionClaimed)
                                }

                                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                                    val confirmationIntent = intent.confirmationIntent()
                                    if (confirmationIntent == null) {
                                        unregisterReceiver(this)
                                        continuation.resumeSafely(
                                            InstallResult.Failure(
                                                PackageInstaller.STATUS_FAILURE,
                                                "Installer confirmation was unavailable"
                                            ),
                                            completionClaimed
                                        )
                                    } else {
                                        confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        runCatching { app.startActivity(confirmationIntent) }
                                            .onSuccess { onUserActionLaunched() }
                                            .onFailure { error ->
                                                unregisterReceiver(this)
                                                continuation.resumeSafely(
                                                    InstallResult.Failure(
                                                        PackageInstaller.STATUS_FAILURE,
                                                        error.message
                                                    ),
                                                    completionClaimed
                                                )
                                            }
                                    }
                                }

                                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                                    unregisterReceiver(this)
                                    if (message.isDeadSessionMessage()) {
                                        continuation.resumeExceptionSafely(
                                            SessionDeadException(message),
                                            completionClaimed
                                        )
                                    } else {
                                        continuation.resumeExceptionSafely(
                                            InstallCancelledException(),
                                            completionClaimed
                                        )
                                    }
                                }

                                PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                                    unregisterReceiver(this)
                                    continuation.resumeSafely(InstallResult.Conflict(message), completionClaimed)
                                }

                                else -> {
                                    unregisterReceiver(this)
                                    if (message.isDeadSessionMessage()) {
                                        continuation.resumeExceptionSafely(
                                            SessionDeadException(message),
                                            completionClaimed
                                        )
                                    } else {
                                        continuation.resumeSafely(
                                            InstallResult.Failure(status, message),
                                            completionClaimed
                                        )
                                    }
                                }
                            }
                        }
                    }

                    registerReceiver(receiver)
                    registeredReceiver = receiver
                    continuation.invokeOnCancellation {
                        completionClaimed.set(true)
                        unregisterReceiver(receiver)
                        runCatching { installer.abandonSession(sessionId) }
                    }
                    session.commit(pendingIntent.intentSender)
                }
            } catch (error: Exception) {
                registeredReceiver?.let(::unregisterReceiver)
                runCatching { installer.abandonSession(sessionId) }
                continuation.resumeExceptionSafely(error, completionClaimed)
            }
        }
    }

    private suspend fun confirmInstallCompleted(
        apkFile: File,
        expectedPackage: String,
        beforeInstall: InstalledPackageSnapshot?
    ): Boolean = withContext(Dispatchers.IO) {
        val afterInstall = pm.installedPackageSnapshot(expectedPackage)
            ?: return@withContext false
        afterInstall.changedSince(beforeInstall) && afterInstall.matches(listOf(apkFile))
    }

    private fun <T> CancellableContinuation<T>.resumeSafely(
        value: T,
        completionClaimed: AtomicBoolean
    ) {
        if (completionClaimed.compareAndSet(false, true)) {
            resume(value)
        }
    }

    private fun <T> CancellableContinuation<T>.resumeExceptionSafely(
        error: Throwable,
        completionClaimed: AtomicBoolean
    ) {
        if (completionClaimed.compareAndSet(false, true)) {
            resumeWithException(error)
        }
    }

    private fun registerReceiver(receiver: BroadcastReceiver) {
        val filter = IntentFilter(ACTION_INSTALL_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
    }

    private fun unregisterReceiver(receiver: BroadcastReceiver) {
        runCatching { app.unregisterReceiver(receiver) }
    }

    @Suppress("DEPRECATION", "UnsafeIntentLaunch")
    private fun Intent.confirmationIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private fun String?.isDeadSessionMessage(): Boolean =
        this?.contains("dead", ignoreCase = true) == true ||
            this?.contains("abandoned", ignoreCase = true) == true

    private companion object {
        const val TAG = "URV SessionInstaller"
        const val ACTION_INSTALL_STATUS = "app.urv.manager.INSTALL_STATUS"
        const val EXTRA_SESSION_ID = "session_id"
    }
}

sealed interface InstallResult {
    data object Success : InstallResult
    data class Conflict(val message: String?) : InstallResult
    data class Failure(val status: Int, val message: String?) : InstallResult
}

class InstallCancelledException : Exception("Installation cancelled")

class SessionDeadException(message: String?) : Exception(message)
