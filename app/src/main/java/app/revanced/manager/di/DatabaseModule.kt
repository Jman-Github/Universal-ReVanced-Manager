package app.revanced.manager.di

import android.content.Context
import androidx.room.Room
import app.revanced.manager.data.room.AppDatabase
import app.revanced.manager.data.room.MIGRATION_1_2
import app.revanced.manager.data.room.MIGRATION_2_3
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val databaseModule = module {
    fun provideAppDatabase(context: Context) =
        Room.databaseBuilder(context, AppDatabase::class.java, "manager")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    single {
        provideAppDatabase(androidContext())
    }
}
