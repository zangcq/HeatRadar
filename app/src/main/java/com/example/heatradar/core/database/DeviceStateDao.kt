package com.example.heatradar.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceStateDao {
    @Insert
    suspend fun insert(state: DeviceStateEntity)

    @Query("SELECT * FROM device_state ORDER BY timestamp DESC LIMIT 1")
    fun observeLatest(): Flow<DeviceStateEntity?>

    @Query("SELECT * FROM device_state ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): DeviceStateEntity?

    @Query("DELETE FROM device_state WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM device_state")
    suspend fun deleteAll()
}
