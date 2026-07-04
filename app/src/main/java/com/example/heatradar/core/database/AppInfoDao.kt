package com.example.heatradar.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppInfoDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(apps: List<AppInfoEntity>)

    @Query("SELECT * FROM app_info ORDER BY appName ASC")
    fun observeAll(): Flow<List<AppInfoEntity>>

    @Query("SELECT * FROM app_info WHERE packageName = :packageName LIMIT 1")
    suspend fun getByPackageName(packageName: String): AppInfoEntity?
}
