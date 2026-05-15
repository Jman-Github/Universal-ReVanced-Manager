package app.urv.manager.di

import app.urv.manager.data.platform.Filesystem
import app.urv.manager.data.platform.NetworkInfo
import app.urv.manager.domain.repository.*
import app.urv.manager.domain.worker.BundleUpdateWebSocketCoordinator
import app.urv.manager.domain.worker.WorkerRepository
import app.urv.manager.network.api.ExternalBundlesApi
import app.urv.manager.network.api.ReVancedAPI
import org.koin.core.module.dsl.createdAtStart
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val repositoryModule = module {
    singleOf(::ExternalBundlesApi)
    singleOf(::ReVancedAPI)
    singleOf(::Filesystem) {
        createdAtStart()
    }
    singleOf(::NetworkInfo)
    singleOf(::PatchSelectionRepository)
    singleOf(::PatchOptionsRepository)
    singleOf(::PatchProfileRepository)
    singleOf(::PatchBundleRepository) {
        // It is best to load patch bundles ASAP
        createdAtStart()
    }
    singleOf(::BundleUpdateWebSocketCoordinator) {
        createdAtStart()
    }
    singleOf(::AnnouncementRepository)
    singleOf(::DownloaderPluginRepository)
    singleOf(::PatcherRuntimePluginRepository)
    singleOf(::WorkerRepository)
    singleOf(::DownloadedAppRepository)
    singleOf(::InstalledAppRepository)
}
