package app.revanced.manager.patcher.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.work.WorkerParameters
import app.universal.revanced.manager.R
import app.revanced.manager.MainActivity
import app.revanced.manager.domain.repository.PatchBundleRepository
import app.revanced.manager.domain.worker.Worker
import app.revanced.manager.domain.worker.WorkerRepository
import app.revanced.manager.util.permission.hasNotificationPermission
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class BundleUpdateNotificationWorker(
    context: Context,
    parameters: WorkerParameters
) : Worker<BundleUpdateNotificationWorker.Args>(context, parameters), KoinComponent {
    private val patchBundleRepository: PatchBundleRepository by inject()
    private val workerRepository: WorkerRepository by inject()

    class Args

    private val notificationChannel = NotificationChannel(
        "background-bundle-update-channel",
        "Background Bundle Updates",
        NotificationManager.IMPORTANCE_HIGH
    )

    override suspend fun doWork(): Result {
        if (!applicationContext.hasNotificationPermission()) {
            return Result.success()
        }

        return try {
            patchBundleRepository.fetchUpdatesAndNotify(applicationContext) { bundleName, bundleVersion ->
                val (notification, notificationManager) =
                    workerRepository.createNotification<MainActivity>(
                        applicationContext,
                        notificationChannel,
                        applicationContext.getString(R.string.bundle_update_available),
                        applicationContext.getString(
                            R.string.bundle_update_description_available,
                            bundleName,
                            bundleVersion
                        )
                    )

                if (applicationContext.hasNotificationPermission()) {
                    notificationManager.notify(
                        "$bundleName-$bundleVersion".hashCode(),
                        notification
                    )
                }
            }
            Result.success()
        } catch (e: Exception) {
            Log.d("BundleAutoUpdateWorker", "Error during work: ${e.message}")
            Result.failure()
        }
    }
}
