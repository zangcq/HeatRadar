package com.example.heatradar.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        AppInfoEntity::class,
        ResourceSampleEntity::class,
        AnomalyEventEntity::class,
        DeviceStateEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appInfoDao(): AppInfoDao
    abstract fun resourceSampleDao(): ResourceSampleDao
    abstract fun anomalyEventDao(): AnomalyEventDao
    abstract fun deviceStateDao(): DeviceStateDao
}
