package com.example.heatradar.core.database

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "heatradar.db"
        ).build()
    }

    @Provides
    fun provideAppInfoDao(database: AppDatabase): AppInfoDao = database.appInfoDao()

    @Provides
    fun provideResourceSampleDao(database: AppDatabase): ResourceSampleDao =
        database.resourceSampleDao()

    @Provides
    fun provideAnomalyEventDao(database: AppDatabase): AnomalyEventDao = database.anomalyEventDao()

    @Provides
    fun provideDeviceStateDao(database: AppDatabase): DeviceStateDao = database.deviceStateDao()
}
