package app.urv.manager.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import app.urv.manager.domain.worker.WorkerRepository
import app.urv.manager.patcher.worker.PatcherWorker
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class PatchingTaskMonitorService : Service(), KoinComponent {
    private val workerRepository: WorkerRepository by inject()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        runCatching {
            workerRepository.cancelUniqueWork(PatcherWorker.UNIQUE_WORK_NAME)
            getSystemService(NotificationManager::class.java)?.cancel(PatcherWorker.NOTIFICATION_ID)
        }.onFailure { error ->
            Log.d(TAG, "Failed to cancel patching after task removal", error)
        }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "PatchingTaskMonitor"

        fun start(context: Context) {
            context.startService(Intent(context, PatchingTaskMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PatchingTaskMonitorService::class.java))
        }
    }
}
