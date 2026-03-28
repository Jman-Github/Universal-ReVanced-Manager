package app.urv.manager.di

import app.urv.manager.domain.installer.RootInstaller
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val rootModule = module {
    singleOf(::RootInstaller)
}