package app.urv.manager.di

import app.urv.manager.network.service.HttpService
import app.urv.manager.util.DownloadProgressNotifier
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val serviceModule = module {
    singleOf(::HttpService)
    singleOf(::DownloadProgressNotifier)
}