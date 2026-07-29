package app.urv.manager.di

import android.content.Context
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import ru.solrudev.ackpine.uninstaller.PackageUninstaller

val ackpineModule = module {
    fun provideUninstaller(context: Context) = PackageUninstaller.getInstance(context)

    single { provideUninstaller(androidContext()) }
}
