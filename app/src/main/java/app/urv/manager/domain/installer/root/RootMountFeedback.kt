package app.urv.manager.domain.installer.root

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import app.universal.revanced.manager.R
import app.urv.manager.util.AppForeground

object RootMountFeedback {
    private val mainHandler = Handler(Looper.getMainLooper())
    private data class Outcome(
        val success: Boolean,
        val shownAt: Long,
        val completedAt: Long,
        val reportedAt: Long,
        val pendingAt: Long
    )
    private val processOutcomes = mutableMapOf<String, Outcome>()
    private val activeToasts = mutableMapOf<String, Toast>()

    fun automaticResult(context: Context, packageName: String, result: RootMountResult) {
        val success = automaticRootMountFeedbackSuccess(result) ?: return
        show(
            context,
            packageName,
            success,
            notify = result !is RootMountResult.Success || result.automaticallyRemounted
        )
    }

    fun show(
        context: Context,
        packageName: String,
        success: Boolean,
        completedAt: Long = SystemClock.elapsedRealtime(),
        notify: Boolean = true
    ) {
        val app = context.applicationContext
        mainHandler.post {
            runCatching {
                val bootCount = runCatching {
                    Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
                }.getOrDefault(-1)
                val prefs = app.getSharedPreferences("root-remount-feedback", Context.MODE_PRIVATE)
                val outcome = "$bootCount:$success"
                val now = SystemClock.elapsedRealtime()
                val previous = processOutcomes[packageName]
                val previousOutcome = if (bootCount >= 0) {
                    prefs.getString(packageName, null)
                } else {
                    previous?.let { "$bootCount:${it.success}" }
                }
                val previousShownAt = if (bootCount >= 0) {
                    prefs.getLong("$packageName:shown-at", -1)
                } else {
                    previous?.shownAt ?: -1
                }
                val previousCompletedAt = if (bootCount >= 0) {
                    prefs.getLong("$packageName:completed-at", previousShownAt)
                } else {
                    previous?.completedAt ?: -1
                }
                val sameBoot = previousOutcome?.substringBefore(':') == outcome.substringBefore(':') &&
                    previousCompletedAt in 0..now
                val previousReportedAt = when {
                    !sameBoot -> -1L
                    bootCount >= 0 -> prefs.getLong("$packageName:reported-at", previousCompletedAt)
                    else -> previous?.reportedAt ?: -1L
                }
                if (isOutdatedRootMountFeedback(
                    previousOutcome, outcome, previousCompletedAt, completedAt, now, previousReportedAt, notify
                )) {
                    return@post
                }
                val showToast = notify &&
                    shouldShowRootMountFeedback(
                        previousOutcome, outcome, success, previousShownAt, now, completedAt
                    )
                val previousPendingAt = if (bootCount >= 0) {
                    prefs.getLong("$packageName:pending-at", -1)
                } else {
                    previous?.pendingAt ?: -1
                }
                val pendingAt = pendingRootMountFeedback(
                    previousOutcome, outcome, previousPendingAt, completedAt, showToast
                )
                if (previousOutcome != outcome) activeToasts.remove(packageName)?.cancel()
                // Health checks update freshness without consuming undelivered feedback.
                val shownAt = if (previousOutcome == outcome) previousShownAt else -1L
                val latestCompletedAt = if (sameBoot) maxOf(previousCompletedAt, completedAt) else completedAt
                val reportedAt = if (notify) maxOf(previousReportedAt, completedAt) else previousReportedAt
                processOutcomes[packageName] = Outcome(success, shownAt, latestCompletedAt, reportedAt, pendingAt)
                if (bootCount >= 0) prefs.edit()
                    .putString(packageName, outcome)
                    .putLong("$packageName:shown-at", shownAt)
                    .putLong("$packageName:completed-at", latestCompletedAt)
                    .putLong("$packageName:reported-at", reportedAt)
                    .putLong("$packageName:pending-at", pendingAt)
                    .apply()
                if (showToast) displayPending(app, packageName, outcome, pendingAt, bootCount)
            }.onFailure { Log.w("RootMountFeedback", "Could not show automatic remount result", it) }
        }
    }

    fun showPending(context: Context) {
        val app = context.applicationContext
        mainHandler.post {
            if (!AppForeground.isResumed) return@post
            runCatching {
                val bootCount = runCatching {
                    Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
                }.getOrDefault(-1)
                val prefs = app.getSharedPreferences("root-remount-feedback", Context.MODE_PRIVATE)
                val packages = if (bootCount >= 0) {
                    prefs.all.keys.filter { it.endsWith(":pending-at") }.map { it.removeSuffix(":pending-at") }
                } else {
                    processOutcomes.keys.toList()
                }
                val now = SystemClock.elapsedRealtime()
                packages.forEach { packageName ->
                    val outcome = if (bootCount >= 0) prefs.getString(packageName, null) else {
                        processOutcomes[packageName]?.let { "$bootCount:${it.success}" }
                    }
                    val pendingAt = if (bootCount >= 0) prefs.getLong("$packageName:pending-at", -1) else {
                        processOutcomes[packageName]?.pendingAt ?: -1
                    }
                    if (outcome?.substringBefore(':') == bootCount.toString() && pendingAt in 0..now) {
                        displayPending(app, packageName, outcome, pendingAt, bootCount)
                    }
                }
            }.onFailure { Log.w("RootMountFeedback", "Could not deliver pending remount feedback", it) }
        }
    }

    private fun displayPending(
        app: Context,
        packageName: String,
        outcome: String,
        pendingAt: Long,
        bootCount: Int
    ) {
        if (!AppForeground.isResumed &&
            !app.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        ) {
            Log.d("RootMountFeedback", "Remount feedback for $packageName deferred: notifications disabled")
            return
        }
        val label = runCatching {
            app.packageManager.getApplicationInfo(packageName, 0)
                .loadLabel(app.packageManager).toString()
        }.getOrDefault(packageName)
        val message = app.getString(
            if (outcome.endsWith(":true")) R.string.root_mount_automatic_success else R.string.root_mount_automatic_failure,
            label
        )
        activeToasts.remove(packageName)?.cancel()
        val toast = Toast.makeText(app, message, Toast.LENGTH_LONG)
        activeToasts[packageName] = toast
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            toast.addCallback(object : Toast.Callback() {
                override fun onToastShown() {
                    if (activeToasts[packageName] !== toast) return
                    runCatching {
                        markDisplayed(app, packageName, outcome, pendingAt, bootCount)
                    }.onFailure { Log.w("RootMountFeedback", "Could not record displayed remount feedback", it) }
                }

                override fun onToastHidden() {
                    if (activeToasts[packageName] === toast) activeToasts.remove(packageName)
                }
            })
        }
        toast.show()
        // Consume queued foreground successes across process death, but retain
        // failures until the toast callback confirms they actually reached the user.
        if ((AppForeground.isResumed && outcome.endsWith(":true")) ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R
        ) {
            markDisplayed(app, packageName, outcome, pendingAt, bootCount)
        }
        // Suppressed toasts receive no callback; retain their pending result for the next resume.
        mainHandler.postDelayed({
            if (activeToasts[packageName] === toast) activeToasts.remove(packageName)?.cancel()
        }, 15_000)
    }

    private fun markDisplayed(app: Context, packageName: String, outcome: String, pendingAt: Long, bootCount: Int) {
        val prefs = app.getSharedPreferences("root-remount-feedback", Context.MODE_PRIVATE)
        val previous = processOutcomes[packageName]
        val currentOutcome = if (bootCount >= 0) prefs.getString(packageName, null) else {
            previous?.let { "$bootCount:${it.success}" }
        }
        val currentPendingAt = if (bootCount >= 0) prefs.getLong("$packageName:pending-at", -1) else {
            previous?.pendingAt ?: -1
        }
        if (currentOutcome != outcome || currentPendingAt != pendingAt) return
        val shownAt = SystemClock.elapsedRealtime()
        previous?.let { processOutcomes[packageName] = it.copy(shownAt = shownAt, pendingAt = -1) }
        if (bootCount >= 0) prefs.edit()
            .putLong("$packageName:shown-at", shownAt)
            .putLong("$packageName:pending-at", -1)
            .commit()
        Log.d("RootMountFeedback", "Remount feedback displayed for $packageName")
    }
}

internal fun bootRootMountFeedbackSuccess(status: String?): Boolean? = when (status) {
    "VERIFIED" -> true
    "REPAIR_REQUIRED", "REPATCH_REQUIRED", "VERIFY_FAILED" -> false
    // These checkpoints hand recovery to Manager; they do not establish a failed mount.
    "INCOMPLETE_TRANSACTION", "DEFERRED" -> null
    else -> null
}

internal fun automaticRootMountFeedbackSuccess(result: RootMountResult): Boolean? = when (result) {
    is RootMountResult.Success,
    is RootMountResult.RecoveredToPreviousMount -> true
    is RootMountResult.Failure -> result.recoveryState == RootRecoveryState.PREVIOUS_MOUNT
    is RootMountResult.Busy -> null
    else -> false
}

internal fun shouldShowRootMountFeedback(
    previousOutcome: String?,
    outcome: String,
    success: Boolean,
    previousShownAt: Long,
    now: Long,
    completedAt: Long
): Boolean {
    if (previousOutcome != outcome || previousShownAt < 0) return true
    // Only a remount completed after the last delivery is a new success event.
    // Replaying an older boot result cannot re-announce it after an app restart.
    return success && (now < previousShownAt || completedAt > previousShownAt)
}

internal fun isOutdatedRootMountFeedback(
    previousOutcome: String?,
    outcome: String,
    previousCompletedAt: Long,
    completedAt: Long,
    now: Long,
    previousReportedAt: Long = previousCompletedAt,
    notify: Boolean = true
): Boolean {
    val older = previousOutcome?.substringBefore(':') == outcome.substringBefore(':') &&
        previousCompletedAt in 0..now && completedAt < previousCompletedAt
    // A silent healthy check must not consume an unreported boot success.
    val unreportedSuccess = notify && outcome.endsWith(":true") && previousOutcome == outcome &&
        previousReportedAt < completedAt
    return older && !unreportedSuccess
}

internal fun pendingRootMountFeedback(
    previousOutcome: String?,
    outcome: String,
    previousPendingAt: Long,
    completedAt: Long,
    showToast: Boolean
): Long = when {
    showToast -> completedAt
    previousOutcome == outcome -> previousPendingAt
    else -> -1L
}
