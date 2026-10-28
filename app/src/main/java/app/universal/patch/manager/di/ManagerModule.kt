package app.universal.patch.manager.di

import app.universal.patch.manager.domain.installer.InstallerManager
import app.universal.patch.manager.domain.installer.SessionInstaller
import app.universal.patch.manager.domain.installer.ShizukuInstaller
import app.universal.patch.manager.domain.manager.AndroidApkArchiveMetadataReader
import app.universal.patch.manager.domain.manager.ApkArchiveMetadataReader
import app.universal.patch.manager.domain.manager.KeystoreManager
import app.universal.patch.manager.domain.manager.SignatureMetadataInjectorManager
import app.universal.patch.manager.util.PM
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val managerModule = module {
    singleOf(::KeystoreManager)
    single<ApkArchiveMetadataReader> { AndroidApkArchiveMetadataReader(get()) }
    singleOf(::SignatureMetadataInjectorManager)
    singleOf(::PM)
    singleOf(::ShizukuInstaller)
    singleOf(::SessionInstaller)
    singleOf(::InstallerManager)
}
