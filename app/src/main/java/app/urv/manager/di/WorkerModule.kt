package app.urv.manager.di

import app.urv.manager.patcher.worker.AnnouncementNotificationWorker
import app.urv.manager.patcher.worker.AutoClearCacheWorker
import app.urv.manager.patcher.worker.BundleUpdateNotificationWorker
import app.urv.manager.patcher.worker.ManagerUpdateNotificationWorker
import app.urv.manager.patcher.worker.PatcherWorker
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.dsl.module

val workerModule = module {
    workerOf(::PatcherWorker)
    workerOf(::AnnouncementNotificationWorker)
    workerOf(::AutoClearCacheWorker)
    workerOf(::BundleUpdateNotificationWorker)
    workerOf(::ManagerUpdateNotificationWorker)
}
