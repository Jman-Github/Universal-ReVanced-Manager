package app.urv.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.urv.manager.worker.RootMountReconcileWorker
import app.urv.manager.worker.RootMountReconciliationScheduler

class PackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart ?: return
        val action = intent.action ?: return
        if (action !in PACKAGE_ACTIONS) return
        val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        if (replacing && action != Intent.ACTION_PACKAGE_REPLACED) return
        val userId = android.os.Process.myUid() / 100_000
        if (!RootMountReconciliationScheduler.isTracked(context, userId, packageName)) return
        val request = OneTimeWorkRequestBuilder<RootMountReconcileWorker>()
            .setInputData(
                Data.Builder()
                    .putString(RootMountReconcileWorker.KEY_PACKAGE, packageName)
                    .putString(RootMountReconcileWorker.KEY_ACTION, action)
                    .putInt(RootMountReconcileWorker.KEY_USER_ID, userId)
                    .build()
            )
            .build()
        // A full uninstall emits REMOVED and then FULLY_REMOVED. Queue both without
        // cancelling a reconciliation transaction that may already be running.
        WorkManager.getInstance(context).enqueueUniqueWork(
            "root-mount-reconcile-$userId-$packageName",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    private companion object {
        val PACKAGE_ACTIONS = setOf(
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_FULLY_REMOVED
        )
    }
}
