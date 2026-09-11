package app.urv.manager.worker

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.urv.manager.domain.installer.root.RootReconciliationScheduling
import app.urv.manager.receiver.PackageChangeReceiver
import java.util.concurrent.TimeUnit

class RootMountReconciliationScheduler(
    private val app: Application
) : RootReconciliationScheduling {
    override fun ensureScheduled(userId: Int, packageName: String) {
        require(userId >= 0) { "Invalid Android user" }
        require(packageName.isNotBlank()) { "Invalid package name" }
        synchronized(REGISTRY_LOCK) {
            val preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val packages = preferences.getStringSet(packagesKey(userId), emptySet())
                .orEmpty()
                .toMutableSet()
                .apply { add(packageName) }
            check(preferences.edit()
                .putStringSet(packagesKey(userId), packages)
                .commit()
            ) { "Could not persist root mount reconciliation state" }
            setReceiverEnabled(app, true)
        }
        val request = PeriodicWorkRequestBuilder<RootMountReconcileWorker>(
            REPEAT_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        ).setInputData(
            Data.Builder()
                .putInt(RootMountReconcileWorker.KEY_USER_ID, userId)
                .build()
        ).build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            "$UNIQUE_WORK_PREFIX$userId",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    override fun stopScheduled(userId: Int, packageName: String) {
        require(userId >= 0) { "Invalid Android user" }
        require(packageName.isNotBlank()) { "Invalid package name" }
        val empty = synchronized(REGISTRY_LOCK) {
            val preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val packages = preferences.getStringSet(packagesKey(userId), emptySet())
                .orEmpty()
                .toMutableSet()
                .apply { remove(packageName) }
            check(preferences.edit()
                .putStringSet(packagesKey(userId), packages)
                .commit()
            ) { "Could not persist root mount reconciliation state" }
            setReceiverEnabled(app, isEnabled(app))
            packages.isEmpty()
        }
        if (empty) {
            WorkManager.getInstance(app).cancelUniqueWork("$UNIQUE_WORK_PREFIX$userId")
        }
    }

    override fun trackedPackages(userId: Int): Set<String> {
        require(userId >= 0) { "Invalid Android user" }
        return synchronized(REGISTRY_LOCK) {
            app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getStringSet(packagesKey(userId), emptySet())
                .orEmpty()
                .toSet()
        }
    }

    companion object {
        private const val UNIQUE_WORK_PREFIX = "root-mount-periodic-reconcile-"
        private const val REPEAT_INTERVAL_MINUTES = 15L
        private const val PREFERENCES = "root_mount_reconciliation"
        private const val PACKAGES_PREFIX = "packages_"
        private val REGISTRY_LOCK = Any()

        private fun packagesKey(userId: Int) = "$PACKAGES_PREFIX$userId"

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .all
                .any { (key, value) ->
                    key.startsWith(PACKAGES_PREFIX) && (value as? Set<*>)?.isNotEmpty() == true
                }

        fun syncReceiverEnabledState(context: Context) {
            synchronized(REGISTRY_LOCK) {
                setReceiverEnabled(context, isEnabled(context))
            }
        }

        private fun setReceiverEnabled(context: Context, enabled: Boolean) {
            val component = ComponentName(context, PackageChangeReceiver::class.java)
            val state = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            if (context.packageManager.getComponentEnabledSetting(component) == state) return
            context.packageManager.setComponentEnabledSetting(
                component,
                state,
                PackageManager.DONT_KILL_APP
            )
        }

        fun isTracked(context: Context, userId: Int, packageName: String): Boolean =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getStringSet(packagesKey(userId), emptySet())
                .orEmpty()
                .contains(packageName)
    }
}
