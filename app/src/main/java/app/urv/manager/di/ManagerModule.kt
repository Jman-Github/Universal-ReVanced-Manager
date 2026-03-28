package app.urv.manager.di

import app.urv.manager.domain.installer.InstallerManager
import app.urv.manager.domain.installer.RootInstaller
import app.urv.manager.domain.installer.ShizukuInstaller
import app.urv.manager.domain.manager.KeystoreManager
import app.urv.manager.util.PM
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val managerModule = module {
    singleOf(::KeystoreManager)
    singleOf(::PM)
    singleOf(::RootInstaller)
    singleOf(::ShizukuInstaller)
    singleOf(::InstallerManager)
}
