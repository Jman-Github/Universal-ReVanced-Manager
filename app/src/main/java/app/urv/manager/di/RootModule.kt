package app.urv.manager.di

import app.urv.manager.domain.installer.RootInstaller
import app.urv.manager.domain.installer.root.AndroidPackageStateReader
import app.urv.manager.domain.installer.root.LibsuRootShellGateway
import app.urv.manager.domain.installer.root.MountTableReader
import app.urv.manager.domain.installer.root.PackageStateReader
import app.urv.manager.domain.installer.root.RootModuleStore
import app.urv.manager.domain.installer.root.RootModuleStorage
import app.urv.manager.domain.installer.root.RootMountVerification
import app.urv.manager.domain.installer.root.RootMountTransactionCoordinator
import app.urv.manager.domain.installer.root.RootMountNamespaces
import app.urv.manager.domain.installer.root.RootMountVerifier
import app.urv.manager.domain.installer.root.RootPackageInstaller
import app.urv.manager.domain.installer.root.RootPackageInstallation
import app.urv.manager.domain.installer.root.RootPackageLock
import app.urv.manager.domain.installer.root.RootPackageLocking
import app.urv.manager.domain.installer.root.RootShellGateway
import app.urv.manager.domain.installer.root.RootTransactionStore
import app.urv.manager.domain.installer.root.RootTransactionStorage
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val rootModule = module {
    singleOf(::RootInstaller)
    single<RootShellGateway> { LibsuRootShellGateway(get()) }
    single<PackageStateReader> { AndroidPackageStateReader(get(), get()) }
    single { MountTableReader(get()) }
    single { RootMountNamespaces(get()) }
    single<RootTransactionStorage> { RootTransactionStore(get()) }
    single<RootModuleStorage> { RootModuleStore(get(), get()) }
    single<RootPackageInstallation> { RootPackageInstaller(get()) }
    single<RootPackageLocking> { RootPackageLock(get()) }
    single<RootMountVerification> { RootMountVerifier(get(), get(), get(), get()) }
    singleOf(::RootMountTransactionCoordinator)
}
