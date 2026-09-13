package app.urv.manager.di

import app.urv.manager.domain.installer.root.RootReconciliationScheduling
import app.urv.manager.patcher.worker.AnnouncementNotificationWorker
import app.urv.manager.patcher.worker.AutoClearCacheWorker
import app.urv.manager.patcher.worker.AutoPatchWorker
import app.urv.manager.patcher.worker.BundleUpdateNotificationWorker
import app.urv.manager.patcher.worker.ManagerUpdateNotificationWorker
import app.urv.manager.patcher.worker.PatcherWorker
import app.urv.manager.worker.RootMountReconcileWorker
import app.urv.manager.worker.RootMountReconciliationScheduler
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.dsl.module

val workerModule = module {
    workerOf(::PatcherWorker)
    workerOf(::AnnouncementNotificationWorker)
    workerOf(::AutoClearCacheWorker)
    workerOf(::AutoPatchWorker)
    workerOf(::BundleUpdateNotificationWorker)
    workerOf(::ManagerUpdateNotificationWorker)
    workerOf(::RootMountReconcileWorker)
    single<RootReconciliationScheduling> { RootMountReconciliationScheduler(get()) }
}
