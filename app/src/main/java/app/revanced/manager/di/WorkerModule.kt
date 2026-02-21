package app.revanced.manager.di

import app.revanced.manager.patcher.worker.BundleUpdateNotificationWorker
import app.revanced.manager.patcher.worker.ManagerUpdateNotificationWorker
import app.revanced.manager.patcher.worker.PatcherWorker
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.dsl.module

val workerModule = module {
    workerOf(::PatcherWorker)
    workerOf(::BundleUpdateNotificationWorker)
    workerOf(::ManagerUpdateNotificationWorker)
}
