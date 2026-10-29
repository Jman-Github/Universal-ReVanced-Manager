package app.universal.patch.manager.di

import app.universal.patch.manager.domain.installer.RootInstaller
import app.universal.patch.manager.domain.installer.root.AndroidPackageStateReader
import app.universal.patch.manager.domain.installer.root.LibsuRootShellGateway
import app.universal.patch.manager.domain.installer.root.MountTableReader
import app.universal.patch.manager.domain.installer.root.PackageStateReader
import app.universal.patch.manager.domain.installer.root.RootModuleStore
import app.universal.patch.manager.domain.installer.root.RootModuleStorage
import app.universal.patch.manager.domain.installer.root.RootMountVerification
import app.universal.patch.manager.domain.installer.root.RootMountTransactionCoordinator
import app.universal.patch.manager.domain.installer.root.RootMountNamespaces
import app.universal.patch.manager.domain.installer.root.RootMountVerifier
import app.universal.patch.manager.domain.installer.root.RootPackageInstaller
import app.universal.patch.manager.domain.installer.root.RootPackageInstallation
import app.universal.patch.manager.domain.installer.root.RootPackageLock
import app.universal.patch.manager.domain.installer.root.RootPackageLocking
import app.universal.patch.manager.domain.installer.root.RootShellGateway
import app.universal.patch.manager.domain.installer.root.RootTransactionStore
import app.universal.patch.manager.domain.installer.root.RootTransactionStorage
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val rootModule = module {
    singleOf(::RootInstaller)
    single<RootShellGateway> { LibsuRootShellGateway(get()) }
    single<PackageStateReader> { AndroidPackageStateReader(get(), get()) }
    single { MountTableReader(get()) }
    single { RootMountNamespaces(get(), android.os.Process.myPid()) }
    single<RootTransactionStorage> { RootTransactionStore(get()) }
    single<RootModuleStorage> { RootModuleStore(get(), get()) }
    single<RootPackageInstallation> { RootPackageInstaller(get()) }
    single<RootPackageLocking> { RootPackageLock(get()) }
    single<RootMountVerification> { RootMountVerifier(get(), get(), get(), get()) }
    singleOf(::RootMountTransactionCoordinator)
}
